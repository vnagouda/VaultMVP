package com.example.vaultmvp.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.vaultmvp.crypto.VaultCrypto
import com.example.vaultmvp.provider.DecryptProvider
import com.example.vaultmvp.util.LOG_TAG
import java.io.*
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val BUFFER_SIZE = 256 * 1024 // 256KB
private const val GCM_TAG_BITS = 128
private const val IV_LEN = 12

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

    fun streamingUriFor(itemId: String): Uri =
        DecryptProvider.contentUriFor(context, itemId)

    fun renameItem(itemId: String, newName: String): List<VaultItem> {
        Log.d(LOG_TAG, "Repo.renameItem: id=$itemId -> \"$newName\"")
        val list = loadItems().map { item ->
            if (item.id == itemId) item.copy(displayName = newName) else item
        }
        saveItems(list)
        return list
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
            context.contentResolver.openInputStream(uri).use { input ->
                FileOutputStream(outFile).use { output ->
                    requireNotNull(input) { "Unable to open input stream for $uri" }
                    // VaultCrypto has buffered streams inside now
                    VaultCrypto.encryptStream(
                        input = input,
                        output = output,
                        key = key,
                        totalBytes = size,
                        onProgress = { p ->
                            if (p.isFinite()) onProgress?.invoke(p)
                            if (p.isFinite() && (p == 1f || p >= 0.9f || p <= 0.1f)) {
                                Log.d(LOG_TAG, "Repo.importAndEncrypt: progress=${"%.0f".format(p * 100)}% id=$id")
                            }
                        },
                        isCancelled = { isCancelled?.invoke() ?: false }
                    )
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

        val cipherFile = File(item.encryptedPath)
        if (!cipherFile.exists()) {
            Log.e(LOG_TAG, "Repo.exportToUriAndRemove: missing encrypted file ${cipherFile.absolutePath}")
            return false
        }

        val totalPlain = item.sizeBytes
        var copied = 0L

        return try {
            FileInputStream(cipherFile).use { fileInRaw ->
                val fileIn = BufferedInputStream(fileInRaw, BUFFER_SIZE)

                val iv = ByteArray(IV_LEN)
                require(fileIn.read(iv) == IV_LEN) { "Invalid header" }

                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                }

                CipherInputStream(fileIn, cipher).use { cis ->
                    context.contentResolver.openOutputStream(dest, "w").use { outRaw ->
                        requireNotNull(outRaw) { "Cannot open output stream for $dest" }
                        BufferedOutputStream(outRaw, BUFFER_SIZE).use { out ->
                            val buf = ByteArray(BUFFER_SIZE)
                            while (true) {
                                if (isCancelled?.invoke() == true) {
                                    Log.w(LOG_TAG, "Repo.exportToUriAndRemove: cancellation requested")
                                    throw InterruptedException("Export cancelled")
                                }
                                val read = cis.read(buf)
                                if (read <= 0) break
                                out.write(buf, 0, read)
                                copied += read
                                if (totalPlain != null && totalPlain > 0) {
                                    onProgress?.invoke((copied.toDouble() / totalPlain.toDouble()).toFloat())
                                } else {
                                    onProgress?.invoke(null) // indeterminate
                                }
                            }
                            out.flush()
                        }
                    }
                }
            }

            // Remove encrypted blob + update index
            cipherFile.apply { if (exists()) delete() }
            saveItems(loadItems().filterNot { it.id == item.id })
            Log.d(LOG_TAG, "Repo.exportToUriAndRemove: success id=${item.id} plainBytes=$copied")
            true
        } catch (e: InterruptedException) {
            Log.w(LOG_TAG, "Repo.exportToUriAndRemove: cancelled id=${item.id} after=$copied bytes", e)
            false
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "Repo.exportToUriAndRemove: error id=${item.id} after=$copied bytes", t)
            false
        }
    }

    /** Decrypt to a temp file emitting progress (buffered). */
    fun decryptToTempFileProgress(
        item: VaultItem,
        onProgress: ((Float?) -> Unit)? = null
    ): File {
        Log.d(LOG_TAG, "Repo.decryptToTempFileProgress: id=${item.id}")
        val enc = File(item.encryptedPath)
        val temp = File.createTempFile(item.id, ".dec", context.cacheDir).apply { deleteOnExit() }

        val totalPlain = item.sizeBytes
        var copied = 0L

        try {
            FileInputStream(enc).use { rawIn ->
                val inBuf = BufferedInputStream(rawIn, BUFFER_SIZE)

                val iv = ByteArray(IV_LEN)
                require(inBuf.read(iv) == IV_LEN) { "Invalid header" }

                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                }

                CipherInputStream(inBuf, cipher).use { cis ->
                    FileOutputStream(temp).use { rawOut ->
                        BufferedOutputStream(rawOut, BUFFER_SIZE).use { out ->
                            val buf = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val read = cis.read(buf)
                                if (read <= 0) break
                                out.write(buf, 0, read)
                                copied += read
                                if (totalPlain != null && totalPlain > 0) {
                                    onProgress?.invoke((copied.toDouble() / totalPlain.toDouble()).toFloat())
                                } else {
                                    onProgress?.invoke(null)
                                }
                            }
                            out.flush()
                        }
                    }
                }
            }
            Log.d(LOG_TAG, "Repo.decryptToTempFileProgress: success id=${item.id} temp=${temp.absolutePath} plainBytes=$copied")
            return temp
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "Repo.decryptToTempFileProgress: error id=${item.id} after=$copied bytes", t)
            try { temp.delete() } catch (_: Exception) {}
            throw t
        }
    }

    fun decryptToTempFile(item: VaultItem): File {
        Log.d(LOG_TAG, "Repo.decryptToTempFile: id=${item.id}")
        val enc = File(item.encryptedPath)
        val temp = File.createTempFile(item.id, ".dec", context.cacheDir).apply { deleteOnExit() }
        FileInputStream(enc).use { inp ->
            FileOutputStream(temp).use { out ->
                // VaultCrypto now uses buffered streams internally
                VaultCrypto.decryptStream(inp, out, key)
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
