package com.example.vaultmvp.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile

object SourceRemoval {
    private const val TAG = "SourceRemoval"

    /**
     * Best-effort delete of the original file selected via SAF.
     * Returns true if the provider confirmed deletion, false otherwise.
     */
    fun tryDeleteOriginal(context: Context, uri: Uri): Boolean {
        return try {
            val doc = DocumentFile.fromSingleUri(context, uri)
            if (doc == null) {
                Log.w(TAG, "DocumentFile null for $uri")
                false
            } else if (!doc.isFile) {
                Log.w(TAG, "Not a file: $uri")
                false
            } else if (!doc.canWrite()) {
                Log.w(TAG, "No write permission for $uri")
                false
            } else {
                val ok = doc.delete()
                if (!ok) Log.w(TAG, "Provider returned false for delete: $uri")
                ok
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Delete failed for $uri: ${t.message}")
            false
        }
    }
}
