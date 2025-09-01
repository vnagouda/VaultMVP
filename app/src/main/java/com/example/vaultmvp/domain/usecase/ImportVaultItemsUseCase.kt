package com.example.vaultmvp.domain.usecase

import android.net.Uri
import android.util.Log
import com.example.vaultmvp.data.VaultItem
import com.example.vaultmvp.data.VaultRepo
import com.example.vaultmvp.util.LOG_TAG

class ImportVaultItemsUseCase(private val repo: VaultRepo) {
   
    fun execute(uris: List<Uri>): List<VaultItem> {
        Log.d(LOG_TAG, "UseCase.Import: urisCount=${uris.size}")
        val items = uris.map { uri ->
            Log.d(LOG_TAG, "UseCase.Import: importing $uri")
            val item = repo.importAndEncrypt(uri)
            Log.d(LOG_TAG, "UseCase.Import: imported id=${item.id}")
            item
        }
        Log.d(LOG_TAG, "UseCase.Import: done count=${items.size}")
        return items
    }
}
