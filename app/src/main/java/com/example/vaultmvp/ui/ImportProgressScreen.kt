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

                // 👇 run once when we enter Success
                LaunchedEffect(import.phase) {
                    kotlinx.coroutines.delay(900)
                    onBackToHome()           // pop first
                }
                // 👇 clear AFTER we've left this screen
                androidx.compose.runtime.DisposableEffect(Unit) {
                    onDispose { vm.clearImportUi() }
                }
            }

            ImportPhase.Cancelled -> {
                CancelOverlay(show = true, onFinished = { /* no-op */ })
                LaunchedEffect(import.phase) {
                    kotlinx.coroutines.delay(400)
                    onBackToHome()
                }
                androidx.compose.runtime.DisposableEffect(Unit) {
                    onDispose { vm.clearImportUi() }
                }
            }

            ImportPhase.Error -> {
                FailureOverlay(show = true, onFinished = { /* no-op */ })
                LaunchedEffect(import.phase) {
                    kotlinx.coroutines.delay(800)
                    onBackToHome()
                }
                androidx.compose.runtime.DisposableEffect(Unit) {
                    onDispose { vm.clearImportUi() }
                }
            }

            ImportPhase.Idle -> {
                // If we navigated to ImportProgress before the picker result,
                // show a gentle placeholder. If we’re here post-finish, just exit.
                if (import.fileName == null) {
                    EncryptProgressOverlay(
                        title = "Waiting for selection…",
                        progress = null,
                        onCancel = { /* picker is up; ignore */ }
                    )
                } else {
                    LaunchedEffect(Unit) { onBackToHome() }
                }
            }
        }

    }
}
