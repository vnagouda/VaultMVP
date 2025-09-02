package com.example.vaultmvp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vaultmvp.vm.ImportPhase
import com.example.vaultmvp.vm.VaultViewModel
import kotlinx.coroutines.delay

@Composable
fun ImportProgressScreen(
    vm: VaultViewModel,
    onBackToHome: () -> Unit
) {
    val state = vm.ui.collectAsStateWithLifecycle().value
    val import = state.import

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (import.phase) {
            ImportPhase.Encrypting -> {
                EncryptProgressOverlay(
                    title = import.fileName?.let { "Encrypting $it" } ?: "Encrypting…",
                    progress = import.progress,
                    onCancel = { vm.requestCancelEncryption() }
                )
            }
            ImportPhase.Finalizing -> {
                EncryptProgressOverlay(
                    title = "Finalizing securely…",
                    progress = null,
                    onCancel = { vm.requestCancelEncryption() }
                )
            }
            ImportPhase.Success -> {
                SuccessOverlay(show = true, onFinished = { /* no-op */ })
                LaunchedEffect(Unit) {
                    delay(900)
                    vm.clearImportUi()
                    onBackToHome()
                }
            }
            ImportPhase.Cancelled -> {
                CancelOverlay(show = true, onFinished = {
                    vm.clearImportUi()
                    onBackToHome()
                })
            }
            ImportPhase.Error -> {
                FailureOverlay(show = true, onFinished = {
                    vm.clearImportUi()
                    onBackToHome()
                })
            }
            ImportPhase.Idle -> {
                LaunchedEffect(Unit) { onBackToHome() }
            }
        }
    }
}
