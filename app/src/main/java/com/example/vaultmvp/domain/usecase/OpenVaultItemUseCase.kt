package com.example.vaultmvp.domain.usecase

import android.util.Log
import com.example.vaultmvp.data.VaultItem
import com.example.vaultmvp.data.VaultRepo
import com.example.vaultmvp.util.LOG_TAG
import java.io.File

sealed interface Preview {
    data class Image(val bytes: ByteArray): Preview
    data class Pdf(val file: File): Preview
    data class Video(val file: File): Preview
    data class Unsupported(val reason: String): Preview
}

class OpenVaultItemUseCase(private val repo: VaultRepo) {
 
    fun execute(item: VaultItem): Preview {
        Log.d(LOG_TAG, "UseCase.Open: id=${item.id} mime=${item.mime}")
        return try {
            when {
                item.mime.startsWith("image/") -> {
                    val bytes = repo.decryptToBytes(item)
                    Log.d(LOG_TAG, "UseCase.Open: image bytes=${bytes.size}")
                    Preview.Image(bytes)
                }
                item.mime == "application/pdf" -> {
                    val f = repo.decryptToTempFile(item)
                    Log.d(LOG_TAG, "UseCase.Open: pdf temp=${f.absolutePath}")
                    Preview.Pdf(f)
                }
                item.mime.startsWith("video/") -> {
                    val f = repo.decryptToTempFile(item)
                    Log.d(LOG_TAG, "UseCase.Open: video temp=${f.absolutePath}")
                    Preview.Video(f)
                }
                else -> {
                    Log.d(LOG_TAG, "UseCase.Open: unsupported mime")
                    Preview.Unsupported("Office preview not supported in MVP")
                }
            }
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "UseCase.Open: error id=${item.id}", t)
            Preview.Unsupported("Failed to open item")
        }
    }
}
