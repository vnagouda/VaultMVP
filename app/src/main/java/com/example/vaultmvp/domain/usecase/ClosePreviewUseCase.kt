package com.example.vaultmvp.domain.usecase

import android.util.Log
import com.example.vaultmvp.util.LOG_TAG

class ClosePreviewUseCase {
   
    fun execute(cleanup: () -> Unit) {
        Log.d(LOG_TAG, "UseCase.ClosePreview: execute")
        cleanup()
    }
}
