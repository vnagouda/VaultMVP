package com.example.vaultmvp.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.UriMatcher.NO_MATCH
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.vaultmvp.crypto.VaultCrypto
import com.example.vaultmvp.data.VaultItem
import com.example.vaultmvp.data.VaultStore
import com.example.vaultmvp.util.LOG_TAG
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.GCMParameterSpec
import androidx.core.net.toUri

class DecryptProvider : ContentProvider() {

    companion object {
        private const val PATH_ITEM = "item/*"
        private const val CODE_ITEM = 1

        /** Build the authority using the caller's context. */
        fun authority(ctx: android.content.Context): String = "${ctx.packageName}.decrypt"

        /** Helper to build a content Uri for a given item id (requires a Context). */
        fun contentUriFor(ctx: android.content.Context, id: String): Uri =
            "content://${authority(ctx)}/item/$id".toUri()
    }

    private val matcher = UriMatcher(NO_MATCH)

    override fun onCreate(): Boolean {
        // Register matcher now that context is available
        val auth = authority(requireNotNull(context))
        matcher.addURI(auth, PATH_ITEM, CODE_ITEM)
        return true
    }

    override fun getType(uri: Uri): String? {
        val (item) = resolveItem(uri) ?: return null
        return item.mime
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ) = null

    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun update(uri: Uri, values: ContentValues?, where: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, where: String?, args: Array<out String>?) = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val (item) = resolveItem(uri) ?: throw FileNotFoundException("Not found: $uri")

        // Create a pipe; we’ll stream plaintext into the write end on a background thread.
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]

        val out = ParcelFileDescriptor.AutoCloseOutputStream(writeFd)

        Thread(
            {
                streamDecryptTo(item, out)
            },
            "DecryptPipe-${item.id.take(6)}"
        ).start()

        // Return the read end to the caller (do NOT dup the ParcelFileDescriptor itself).
        return readFd
        // Alternatively, if you needed dup: return ParcelFileDescriptor.dup(readFd.fileDescriptor)
    }

    private fun resolveItem(uri: Uri): Pair<VaultItem, File>? {
        if (matcher.match(uri) != CODE_ITEM) return null
        val id = uri.lastPathSegment ?: return null

        // Load from the store directly (no ViewModel inside a provider)
        val store = VaultStore(requireNotNull(context))
        val item = store.load().find { it.id == id } ?: run {
            Log.e(LOG_TAG, "DecryptProvider: missing index for $id")
            return null
        }
        val enc = File(item.encryptedPath)
        if (!enc.exists()) {
            Log.e(LOG_TAG, "DecryptProvider: encrypted file missing for $id at ${item.encryptedPath}")
            return null
        }
        return item to enc
    }

    private fun streamDecryptTo(item: VaultItem, sink: OutputStream) {
        try {
            val enc = File(item.encryptedPath)
            FileInputStream(enc).use { rawIn ->
                val inBuf = BufferedInputStream(rawIn, 256 * 1024)

                val iv = ByteArray(12)
                require(inBuf.read(iv) == 12) { "Invalid header" }

                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, VaultCrypto.ensureKey(), GCMParameterSpec(128, iv))
                }

                CipherInputStream(inBuf, cipher).use { cis ->
                    BufferedOutputStream(sink, 256 * 1024).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val read = cis.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                        }
                        out.flush()
                    }
                }
            }
            Log.d(LOG_TAG, "DecryptProvider: streamed ${item.displayName}")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "DecryptProvider: stream error for ${item.id}", e)
            try { sink.close() } catch (_: Exception) {}
        }
    }
}
