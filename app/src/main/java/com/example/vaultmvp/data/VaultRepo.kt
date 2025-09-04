package com.example.vaultmvp.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.vaultmvp.crypto.VaultCrypto
import com.example.vaultmvp.util.LOG_TAG
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.min

class VaultRepo(private val context: Context) {

    // === Key: same as your original ===
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

    // Counts bytes read from an upstream so we can emit progress no matter the cipher buffering.
    private class CountingInputStream(
        private val upstream: InputStream,
        private val onCountChanged: (Long) -> Unit
    ) : InputStream() {
        var count: Long = 0
            private set
        override fun read(): Int {
            val r = upstream.read()
            if (r >= 0) { count += 1; onCountChanged(count) }
            return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = upstream.read(b, off, len)
            if (n > 0) { count += n; onCountChanged(count) }
            return n
        }
        override fun skip(n: Long): Long {
            val s = upstream.skip(n)
            if (s > 0) { count += s; onCountChanged(count) }
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

    /**
     * Import + encrypt.
     * - For videos (mime starts with "video/"): use CHUNKED AES-GCM (fast seeks, scalable).
     * - For everything else: keep your existing single-IV whole-file AES-GCM.
     * Progress is emitted in both paths.
     */
    @RequiresApi(Build.VERSION_CODES.O)
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
        Log.d(
            LOG_TAG,
            "Repo.importAndEncrypt: id=$id name=$name size=$size mime=$mime dest=${outFile.absolutePath}"
        )

        try {
            if (mime.startsWith("video/")) {
                // NEW: Chunked AES-GCM for videos (header: 'VMVP',1,chunkSize,totalPlain)
                encryptChunkedFromUri(
                    uri = uri,
                    outFile = outFile,
                    totalBytes = size,
                    isCancelled = isCancelled,
                    onProgress = onProgress
                )
            } else {
                // === Your original single-IV whole-file path === :contentReference[oaicite:2]{index=2}
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
                            onProgress = { p -> onProgress?.invoke(p) },
                            isCancelled = { isCancelled?.invoke() ?: false }
                        )
                        outBuf.flush()
                    }
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
     * Export (decrypt) to [dest] with progress and cancel, then remove.
     * Detects chunked vs legacy format automatically.
     */
    @RequiresApi(Build.VERSION_CODES.O)
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

        return try {
            if (isChunked(encFile)) {
                // Decrypt chunked → stream to dest with progress
                decryptChunkedToUri(encFile, dest, onProgress, isCancelled)
            } else {
                // === Your original whole-file GCM path === :contentReference[oaicite:3]{index=3}
                val totalCipher = (encFile.length() - 12L).coerceAtLeast(0L)
                var consumedCipher = 0L
                var lastPct = -1
                var copiedPlain = 0L

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
                true
            }.also { ok ->
                if (ok) {
                    encFile.apply { if (exists()) delete() }
                    saveItems(loadItems().filterNot { it.id == item.id })
                }
            }
        } catch (e: InterruptedException) {
            Log.w(LOG_TAG, "Repo.exportToUriAndRemove: cancelled id=${item.id}", e)
            false
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "Repo.exportToUriAndRemove: error id=${item.id}", t)
            false
        }
    }

    /** Decrypt to a temp file with progress (supports both formats). */
    @RequiresApi(Build.VERSION_CODES.O)
    fun decryptToTempFileProgress(
        item: VaultItem,
        onProgress: ((Float?) -> Unit)? = null
    ): File {
        Log.d(LOG_TAG, "Repo.decryptToTempFileProgress: id=${item.id}")
        val encFile = File(item.encryptedPath)
        val temp = File.createTempFile(item.id, ".dec", context.cacheDir).apply { deleteOnExit() }

        return try {
            if (isChunked(encFile)) {
                decryptChunkedToFile(encFile, temp, onProgress)
                temp
            } else {
                // === Your original whole-file GCM path (progress by ciphertext consumed) === :contentReference[oaicite:4]{index=4}
                val totalCipher = (encFile.length() - 12L).coerceAtLeast(0L)
                var consumedCipher = 0L
                var lastPct = -1
                var copiedPlain = 0L

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
                if (totalCipher > 0) onProgress?.invoke(1f)
                temp
            }
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "Repo.decryptToTempFileProgress: error id=${item.id}", t)
            try { temp.delete() } catch (_: Exception) {}
            throw t
        }
    }

    /** Non-progress helper (kept for images/small files, both formats supported). */
    @RequiresApi(Build.VERSION_CODES.O)
    fun decryptToTempFile(item: VaultItem): File {
        Log.d(LOG_TAG, "Repo.decryptToTempFile: id=${item.id}")
        val encFile = File(item.encryptedPath)
        val temp = File.createTempFile(item.id, ".dec", context.cacheDir).apply { deleteOnExit() }

        return if (isChunked(encFile)) {
            decryptChunkedToFile(encFile, temp, onProgress = null)
            temp
        } else {
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
            temp
        }
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

    // -------------------- Helpers & new chunked path --------------------

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

    /** Detect our chunked header 'VMVP' (0x50564D56 LE). */
    private fun isChunked(file: File): Boolean {
        return try {
            FileInputStream(file).use { fin ->
                val bb = ByteArray(4)
                if (fin.read(bb) != 4) return false
                ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == 0x50564D56
            }
        } catch (_: Throwable) { false }
    }

    /**
     * Encrypt from a Uri into our chunked format:
     * Header (17 bytes LE): magic('VMVP') [4] + version[1]=1 + chunkSize[4] + totalPlain[8]
     * For each chunk: iv[12] + ctLen[4] + ct[ctLen] + tag[16]
     * Uses the same SecretKey as your legacy path (no wrapped key in header).
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun encryptChunkedFromUri(
        uri: Uri,
        outFile: File,
        totalBytes: Long?,
        isCancelled: (() -> Boolean)?,
        onProgress: ((Float) -> Unit)?
    ) {
        val chunkSize = 1 * 1024 * 1024 // 1 MiB; tune 1–4 MiB if you like
        val tagLen = 16

        context.contentResolver.openInputStream(uri).use { input ->
            FileChannel.open(
                outFile.toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
            ).use { outCh ->
                // header: magic + ver + chunkSize + totalPlain
                val hdr = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
                hdr.putInt(0x50564D56) // 'VMVP'
                hdr.put(1)             // version
                hdr.putInt(chunkSize)
                hdr.putLong(totalBytes ?: -1L)
                hdr.flip()
                while (hdr.hasRemaining()) outCh.write(hdr)

                val inBuf = ByteArray(chunkSize)
                var copied = 0L

                while (true) {
                    if (isCancelled?.invoke() == true) throw InterruptedException("Import cancelled")
                    val n = input!!.read(inBuf)
                    if (n <= 0) break
                    copied += n

                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    // IMPORTANT: let Keystore/StrongBox generate a random IV
                    cipher.init(Cipher.ENCRYPT_MODE, key)
                    val iv = cipher.iv  // 12 bytes generated by provider

                    val out = cipher.doFinal(inBuf, 0, n) // ct || tag
                    val ctLen = out.size - tagLen

                    // write record: iv(12) + ctLen(4, LE) + ct + tag(16)
                    outCh.write(ByteBuffer.wrap(iv))

                    val lenBB = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    lenBB.putInt(ctLen)
                    lenBB.flip()
                    outCh.write(lenBB)

                    outCh.write(ByteBuffer.wrap(out, 0, ctLen))
                    outCh.write(ByteBuffer.wrap(out, ctLen, tagLen))

                    if (totalBytes != null && totalBytes > 0) {
                        val pct = (copied.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                        onProgress?.invoke(pct)
                    }
                }
                if (totalBytes != null && totalBytes > 0) onProgress?.invoke(1f)
            }
        }
    }


    /** Decrypt a chunked blob to a temp/plain file, reporting progress by produced plaintext. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun decryptChunkedToFile(
        enc: File,
        out: File,
        onProgress: ((Float?) -> Unit)?
    ) {
        FileChannel.open(enc.toPath(), StandardOpenOption.READ).use { ch ->
            // Read fixed header: 17 bytes ('VMVP',1,chunkSize,totalPlain)

            val fixed = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
            readFully(ch, fixed.also { it.clear() })
            fixed.flip()
            require(fixed.int == 0x50564D56) { "Bad magic" }
            require(fixed.get() == 1.toByte()) { "Bad version" }
            val chunk = fixed.int
            val totalPlain = fixed.long

            FileOutputStream(out).use { fout ->
                val outBuf = BufferedOutputStream(fout, 512 * 1024)
                val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                val iv = ByteArray(12)
                var produced = 0L

                while (true) {
                    // Try to read next record; if EOF -> done
                    if (!readFullyOrEof(ch, ByteBuffer.wrap(iv))) break
                    lenBuf.clear(); readFully(ch, lenBuf.also { it.clear(); it.limit(4) })
                    val ctLen = lenBuf.getInt(0)
                    val ct = ByteArray(ctLen); readFully(ch, ByteBuffer.wrap(ct))
                    val tag = ByteArray(16); readFully(ch, ByteBuffer.wrap(tag))

                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                    val all = ByteArray(ctLen + 16)
                    System.arraycopy(ct, 0, all, 0, ctLen)
                    System.arraycopy(tag, 0, all, ctLen, 16)
                    val plain = cipher.doFinal(all)

                    outBuf.write(plain)
                    produced += plain.size
                    if (totalPlain > 0) {
                        onProgress?.invoke((produced.toDouble() / totalPlain.toDouble()).toFloat())
                    } else {
                        onProgress?.invoke(null)
                    }
                }
                outBuf.flush()
                if (totalPlain > 0) onProgress?.invoke(1f)
            }
        }
    }

    /** Decrypt a chunked blob and stream directly to a SAF URI with progress/cancel. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun decryptChunkedToUri(
        enc: File,
        dest: Uri,
        onProgress: ((Float?) -> Unit)?,
        isCancelled: (() -> Boolean)?
    ): Boolean {
        FileChannel.open(enc.toPath(), StandardOpenOption.READ).use { ch ->
            val fixed = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
            readFully(ch, fixed.also { it.clear() }); fixed.flip()
            require(fixed.int == 0x50564D56) { "Bad magic" }
            require(fixed.get() == 1.toByte()) { "Bad version" }
            val chunk = fixed.int
            val totalPlain = fixed.long

            context.contentResolver.openOutputStream(dest, "w").use { outRaw ->
                requireNotNull(outRaw) { "Cannot open output stream for $dest" }
                BufferedOutputStream(outRaw, 512 * 1024).use { out ->
                    val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    val iv = ByteArray(12)
                    var produced = 0L

                    while (true) {
                        if (isCancelled?.invoke() == true) throw InterruptedException("Export cancelled")
                        if (!readFullyOrEof(ch, ByteBuffer.wrap(iv))) break
                        lenBuf.clear(); readFully(ch, lenBuf.also { it.clear(); it.limit(4) })
                        val ctLen = lenBuf.getInt(0)
                        val ct = ByteArray(ctLen); readFully(ch, ByteBuffer.wrap(ct))
                        val tag = ByteArray(16); readFully(ch, ByteBuffer.wrap(tag))

                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                        val all = ByteArray(ctLen + 16)
                        System.arraycopy(ct, 0, all, 0, ctLen)
                        System.arraycopy(tag, 0, all, ctLen, 16)
                        val plain = cipher.doFinal(all)

                        out.write(plain)
                        produced += plain.size
                        if (totalPlain > 0) onProgress?.invoke((produced.toDouble() / totalPlain.toDouble()).toFloat())
                        else onProgress?.invoke(null)
                    }
                    out.flush()
                    if (totalPlain > 0) onProgress?.invoke(1f)
                }
            }
        }
        return true
    }

    private fun readFully(ch: FileChannel, bb: ByteBuffer) {
        while (bb.hasRemaining()) {
            val n = ch.read(bb)
            if (n < 0) error("EOF")
        }
    }

    private fun readFullyOrEof(ch: FileChannel, bb: ByteBuffer): Boolean {
        var total = 0
        while (bb.hasRemaining()) {
            val n = ch.read(bb)
            if (n < 0) return total > 0
            total += n
        }
        return true
    }
}
