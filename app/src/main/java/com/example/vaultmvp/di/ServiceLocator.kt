package com.example.vaultmvp.di

import android.app.Application
import android.util.Log
import com.example.vaultmvp.data.VaultRepo
import com.example.vaultmvp.domain.usecase.*
import com.example.vaultmvp.util.LOG_TAG

object ServiceLocator {


    fun provideVaultRepo(app: Application) = VaultRepo(app).also {
        Log.d(LOG_TAG, "ServiceLocator: VaultRepo created")
    }

    data class VaultUseCases(
        val load: LoadVaultItemsUseCase,
        val save: SaveVaultItemsUseCase,
        val import: ImportVaultItemsUseCase,
        val open: OpenVaultItemUseCase,
        val closePreview: ClosePreviewUseCase
    )

    fun provideUseCases(app: Application): VaultUseCases {
        Log.d(LOG_TAG, "ServiceLocator: provideUseCases")
        val repo = provideVaultRepo(app)
        return VaultUseCases(
            load = LoadVaultItemsUseCase(repo),
            save = SaveVaultItemsUseCase(repo),
            import = ImportVaultItemsUseCase(repo),
            open = OpenVaultItemUseCase(repo),
            closePreview = ClosePreviewUseCase()
        ).also { Log.d(LOG_TAG, "ServiceLocator: UseCases wired") }
    }
}
