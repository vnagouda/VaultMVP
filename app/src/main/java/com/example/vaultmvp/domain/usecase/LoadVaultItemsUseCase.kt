package com.example.vaultmvp.domain.usecase

import android.util.Log
import com.example.vaultmvp.data.VaultItem
import com.example.vaultmvp.data.VaultRepo
import com.example.vaultmvp.util.LOG_TAG

class LoadVaultItemsUseCase(private val repo: VaultRepo) {
    
    fun execute(): List<VaultItem> {
        Log.d(LOG_TAG, "UseCase.Load: begin")
        val list = repo.loadItems()
        Log.d(LOG_TAG, "UseCase.Load: loaded count=${list.size}")
        return list
    }
}
