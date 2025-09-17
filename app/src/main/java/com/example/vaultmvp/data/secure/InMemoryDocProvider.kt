package com.example.vaultmvp.data.secure

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.util.concurrent.ConcurrentHashMap
import androidx.core.net.toUri

/**
 * Serves decrypted bytes via a one-time token. No plaintext on disk.
 * Authority is "${applicationId}.inmem" (declared in Manifest).
 */
class InMemoryDocProvider : ContentProvider() {

    companion object {
        private const val PATH = "doc/*"        // content://<auth>/doc/<token>
        private const val MATCH_DOC = 1

        // token -> bytes (one-shot)
        private val blobs = ConcurrentHashMap<String, ByteArray>()

        /** Register bytes with a token and get a content:// Uri. */
        fun put(context: android.content.Context, token: String, bytes: ByteArray): Uri {
            blobs[token] = bytes
            val auth = context.packageName + ".inmem" // must match Manifest
            return "content://$auth/doc/$token".toUri()
        }

        /** Explicit revoke (also auto-removed after read). */
        fun revoke(token: String) {
            blobs.remove(token)
        }
    }

    private lateinit var matcher: UriMatcher

    override fun onCreate(): Boolean {
        // Build matcher dynamically using the real authority this provider was registered with
        val auth = requireNotNull(context).packageName + ".inmem"
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(auth, PATH, MATCH_DOC)
        }
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (matcher.match(uri) != MATCH_DOC) return null
        val token = uri.lastPathSegment ?: return null
        val data = blobs[token] ?: return null

        // Stream bytes through a pipe; revoke token after write.
        val (readFd, writeFd) = ParcelFileDescriptor.createPipe()
        val out = ParcelFileDescriptor.AutoCloseOutputStream(writeFd)
        Thread {
            try { out.use { it.write(data) } }
            finally { blobs.remove(token) }
        }.start()
        return readFd
    }

    // Unused
    override fun getType(uri: Uri): String? = "*/*"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
