package com.example.vaultmvp.data

import android.content.Context
import android.util.Log
import com.example.vaultmvp.util.LOG_TAG
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

class VaultStore(private val context: Context) {
    
    private val json = Json { prettyPrint = true }
    private val indexFile get() = File(context.filesDir, "vault_index.json")

    fun load(): List<VaultItem> {
        val file = indexFile
        if (!file.exists()) {
            Log.d(LOG_TAG, "Store.load: index not found -> empty list (${file.absolutePath})")
            return emptyList()
        }
        return try {
            val text = file.readText()
            val list = json.decodeFromString(ListSerializer(VaultItem.serializer()), text)
            Log.d(LOG_TAG, "Store.load: loaded count=${list.size} from ${file.absolutePath}")
            list
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "Store.load: error reading ${file.absolutePath}", t)
            emptyList()
        }
    }

    fun save(items: List<VaultItem>) {
        val file = indexFile
        try {
            val text = json.encodeToString(ListSerializer(VaultItem.serializer()), items)
            file.writeText(text)
            Log.d(LOG_TAG, "Store.save: wrote count=${items.size} to ${file.absolutePath}")
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "Store.save: error writing ${file.absolutePath}", t)
            throw t
        }
    }
}
