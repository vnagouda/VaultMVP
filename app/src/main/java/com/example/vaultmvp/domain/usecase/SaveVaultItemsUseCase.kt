package com.example.vaultmvp.domain.usecase

import android.util.Log
import com.example.vaultmvp.data.VaultItem
import com.example.vaultmvp.data.VaultRepo
import com.example.vaultmvp.util.LOG_TAG

class SaveVaultItemsUseCase(private val repo: VaultRepo) {
    
    fun execute(items: List<VaultItem>) {
        Log.d(LOG_TAG, "UseCase.Save: saving count=${items.size}")
        repo.saveItems(items)
        Log.d(LOG_TAG, "UseCase.Save: saved OK")
    }
}
