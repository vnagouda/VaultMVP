package com.example.vaultmvp.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.vaultmvp.crypto.VaultCrypto
import com.example.vaultmvp.util.LOG_TAG
import java.io.*
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class VaultRepo(private val context: Context) {

    private val key: SecretKey by lazy {
        Log.d(LOG_TAG, "Repo: ensureKey lazy init")
        VaultCrypto.ensureKey()
    }
    private val store = VaultStore(context)

    fun loadItems(): List<VaultItem> {
        Log.d(LOG_TAG, "Repo.loadItems: start")
        return try {
            val list = store.load()
            Log.d(LOG_TAG, "Repo.loadItems: loaded count=${list.size}")
            list
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "Repo.loadItems: error", t)
            emptyList()
        }
    }

    fun saveItems(items: List<VaultItem>) {
        Log.d(LOG_TAG, "Repo.saveItems: saving count=${items.size}")
        try {
            store.save(items)
            Log.d(LOG_TAG, "Repo.saveItems: saved OK")
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "Repo.saveItems: error", t)
            throw t
        }
    }

    // Counts bytes read from the encrypted source so we can report progress,
// even if the cipher hasn’t produced plaintext yet.
    private class CountingInputStream(
        private val upstream: InputStream,
        private val onCountChanged: (Long) -> Unit
    ) : InputStream() {
        var count: Long = 0
            private set

        override fun read(): Int {
            val r = upstream.read()
            if (r >= 0) {
                count += 1
                onCountChanged(count)
            }
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = upstream.read(b, off, len)
            if (n > 0) {
                count += n
                onCountChanged(count)
            }
            return n
        }

        override fun skip(n: Long): Long {
            val s = upstream.skip(n)
            if (s > 0) {
                count += s
                onCountChanged(count)
            }
            return s
        }

        override fun available(): Int = upstream.available()
        override fun close() = upstream.close()
        override fun mark(readlimit: Int) = upstream.mark(readlimit)
        override fun reset() = upstream.reset()
        override fun markSupported(): Boolean = upstream.markSupported()
    }


    fun renameItem(itemId: String, newName: String): List<VaultItem> {
        Log.d(LOG_TAG, "Repo.renameItem: id=$itemId -> \"$newName\"")
        val list = loadItems().map { if (it.id == itemId) it.copy(displayName = newName) else it }
        saveItems(list)
        return list
    }

    fun peekDisplayName(uri: Uri): String? {
        Log.d(LOG_TAG, "Repo.peekDisplayName: uri=$uri")
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return context.contentResolver.query(uri, projection, null, null, null)?.use { c: Cursor ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun deleteOriginal(uri: Uri) {
        Log.d(LOG_TAG, "Repo.deleteOriginal: uri=$uri")
        com.example.vaultmvp.util.SourceRemoval.tryDeleteOriginal(context, uri)
    }

    fun importAndEncrypt(
        uri: Uri,
        onProgress: ((Float) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): VaultItem {
        Log.d(LOG_TAG, "Repo.importAndEncrypt: begin uri=$uri")
        val (name, size) = readMeta(uri)
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val id = UUID.randomUUID().toString()
        val outFile = File(context.filesDir, "$id.bin")
        Log.d(LOG_TAG, "Repo.importAndEncrypt: id=$id name=$name size=$size mime=$mime dest=${outFile.absolutePath}")

        try {
            // inside importAndEncrypt(...)
            context.contentResolver.openInputStream(uri).use { input ->
                FileOutputStream(outFile).use { fileOut ->
                    requireNotNull(input)
                    val inBuf = BufferedInputStream(input, 512 * 1024)
                    val outBuf = BufferedOutputStream(fileOut, 512 * 1024)
                    VaultCrypto.encryptStream(
                        input = inBuf,
                        output = outBuf,
                        key = key,
                        totalBytes = size,
                        onProgress = { p -> /* unchanged */ },
                        isCancelled = { isCancelled?.invoke() ?: false }
                    )
                    outBuf.flush()
                }
            }

        } catch (e: InterruptedException) {
            Log.w(LOG_TAG, "Repo.importAndEncrypt: cancelled id=$id, cleaning partial file", e)
            if (outFile.exists()) outFile.delete()
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Repo.importAndEncrypt: error id=$id, deleting partial file", e)
            if (outFile.exists()) outFile.delete()
            throw e
        }

        val item = VaultItem(
            id = id,
            displayName = name ?: "file",
            mime = mime,
            sizeBytes = size,
            encryptedPath = outFile.absolutePath,
            createdAt = System.currentTimeMillis(),
            originalFileName = name,
            originalParentUri = uri.toString()
        )
        Log.d(LOG_TAG, "Repo.importAndEncrypt: success id=$id")
        return item
    }

    /**
     * Export (decrypt) to [dest] with progress and cancel.
     * Returns true on success.
     */
    fun exportToUriAndRemove(
        item: VaultItem,
        dest: Uri,
        onProgress: ((Float?) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null
    ): Boolean {
        Log.d(LOG_TAG, "Repo.exportToUriAndRemove: begin id=${item.id} dest=$dest")

        val encFile = File(item.encryptedPath)
        if (!encFile.exists()) {
            Log.e(LOG_TAG, "Repo.exportToUriAndRemove: missing encrypted file ${encFile.absolutePath}")
            return false
        }

        val totalCipher = (encFile.length() - 12L).coerceAtLeast(0L)
        var consumedCipher = 0L
        var lastPct = -1
        var copiedPlain = 0L

        return try {
            FileInputStream(encFile).use { rawIn ->
                val inBuf = BufferedInputStream(rawIn, 512 * 1024)

                val iv = ByteArray(12)
                require(inBuf.read(iv) == 12) { "Invalid header" }

                val countingIn = CountingInputStream(inBuf) { consumed ->
                    consumedCipher = consumed
                    if (totalCipher > 0 && onProgress != null) {
                        val pct = ((consumedCipher.toDouble() / totalCipher.toDouble()) * 100).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress((pct / 100f).coerceIn(0f, 1f))
                        }
                    } else {
                        onProgress?.invoke(null)
                    }
                }

                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                }

                CipherInputStream(countingIn, cipher).use { cis ->
                    context.contentResolver.openOutputStream(dest, "w").use { outRaw ->
                        requireNotNull(outRaw) { "Cannot open output stream for $dest" }
                        BufferedOutputStream(outRaw, 512 * 1024).use { out ->
                            val buf = ByteArray(512 * 1024)
                            while (true) {
                                if (isCancelled?.invoke() == true) throw InterruptedException("Export cancelled")
                                val read = cis.read(buf)
                                if (read <= 0) break
                                out.write(buf, 0, read)
                                copiedPlain += read
                            }
                            out.flush()
                        }
                    }
                }
            }

            if (totalCipher > 0) onProgress?.invoke(1f)

            // Remove encrypted blob + update index
            encFile.apply { if (exists()) delete() }
            saveItems(loadItems().filterNot { it.id == item.id })
            Log.d(LOG_TAG, "Repo.exportToUriAndRemove: success id=${item.id} plainBytes=$copiedPlain")
            true
        } catch (e: InterruptedException) {
            Log.w(LOG_TAG, "Repo.exportToUriAndRemove: cancelled id=${item.id} after=$copiedPlain bytes", e)
            false
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "Repo.exportToUriAndRemove: error id=${item.id} after=$copiedPlain bytes", t)
            false
        }
    }


    /** Decrypt to a temp file emitting progress; returns the completed temp file path. */
    // --- in VaultRepo.kt ---
    /** Decrypt to a temp file emitting progress; returns the completed temp file path. */
    // VaultRepo.kt — drop-in replacement

    /** Decrypt to a temp file emitting progress (works with Android Keystore GCM). */
    /** Decrypt to a temp file emitting progress based on ciphertext bytes consumed. */
    fun decryptToTempFileProgress(
        item: VaultItem,
        onProgress: ((Float?) -> Unit)? = null
    ): File {
        Log.d(LOG_TAG, "Repo.decryptToTempFileProgress: id=${item.id}")
        val encFile = File(item.encryptedPath)
        val totalCipher = (encFile.length() - 12L).coerceAtLeast(0L) // encrypted bytes excluding IV
        val temp = File.createTempFile(item.id, ".dec", context.cacheDir).apply { deleteOnExit() }

        var copiedPlain = 0L
        var consumedCipher = 0L
        var lastPct = -1

        try {
            FileInputStream(encFile).use { rawIn ->
                val inBuf = BufferedInputStream(rawIn, 512 * 1024)

                // Read 12-byte IV header
                val iv = ByteArray(12)
                require(inBuf.read(iv) == 12) { "Invalid header" }

                // Wrap *after* IV so count starts at 0 for ciphertext only
                val countingIn = CountingInputStream(inBuf) { consumed ->
                    consumedCipher = consumed
                    if (totalCipher > 0 && onProgress != null) {
                        val pct = ((consumedCipher.toDouble() / totalCipher.toDouble()) * 100).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress((pct / 100f).coerceIn(0f, 1f))
                        }
                    } else {
                        onProgress?.invoke(null) // indeterminate if size unknown
                    }
                }

                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                }

                CipherInputStream(countingIn, cipher).use { cis ->
                    FileOutputStream(temp).use { rawOut ->
                        val out = BufferedOutputStream(rawOut, 512 * 1024)
                        val buf = ByteArray(512 * 1024)

                        while (true) {
                            val read = cis.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            copiedPlain += read
                        }
                        out.flush()
                    }
                }
            }
            Log.d(LOG_TAG, "Repo.decryptToTempFileProgress: success id=${item.id} temp=${temp.absolutePath} plainBytes=$copiedPlain")
            // Ensure we end at 100% if we had a determinate total
            if (totalCipher > 0) onProgress?.invoke(1f)
            return temp
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "Repo.decryptToTempFileProgress: error id=${item.id} after=$copiedPlain bytes", t)
            try { temp.delete() } catch (_: Exception) {}
            throw t
        }
    }

    /** Non-progress helper (images/smaller files). */
    fun decryptToTempFile(item: VaultItem): File {
        Log.d(LOG_TAG, "Repo.decryptToTempFile: id=${item.id}")
        val encFile = File(item.encryptedPath)
        val temp = File.createTempFile(item.id, ".dec", context.cacheDir).apply { deleteOnExit() }

        FileInputStream(encFile).use { rawIn ->
            val inBuf = BufferedInputStream(rawIn, 512 * 1024)

            val iv = ByteArray(12)
            require(inBuf.read(iv) == 12) { "Invalid header" }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }

            CipherInputStream(inBuf, cipher).use { cis ->
                FileOutputStream(temp).use { rawOut ->
                    val out = BufferedOutputStream(rawOut, 512 * 1024)
                    val buf = ByteArray(512 * 1024)
                    while (true) {
                        val read = cis.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                    }
                    out.flush()
                }
            }
        }
        Log.d(LOG_TAG, "Repo.decryptToTempFile: success id=${item.id} temp=${temp.absolutePath}")
        return temp
    }



    fun decryptToBytes(item: VaultItem): ByteArray {
        Log.d(LOG_TAG, "Repo.decryptToBytes: id=${item.id}")
        val out = ByteArrayOutputStream()
        FileInputStream(File(item.encryptedPath)).use { inp ->
            VaultCrypto.decryptStream(inp, out, key)
        }
        Log.d(LOG_TAG, "Repo.decryptToBytes: success id=${item.id} size=${out.size()}")
        return out.toByteArray()
    }

    private fun readMeta(uri: Uri): Pair<String?, Long?> {
        Log.d(LOG_TAG, "Repo.readMeta: uri=$uri")
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)
                val size = if (!c.isNull(1)) c.getLong(1) else null
                Log.d(LOG_TAG, "Repo.readMeta: name=$name size=$size")
                return name to size
            }
        }
        Log.d(LOG_TAG, "Repo.readMeta: no meta found")
        return null to null
    }
}
