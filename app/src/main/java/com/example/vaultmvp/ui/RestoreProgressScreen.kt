package com.example.vaultmvp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vaultmvp.vm.ExportPhase
import com.example.vaultmvp.vm.VaultViewModel
import kotlinx.coroutines.delay

@Composable
fun RestoreProgressScreen(
    vm: VaultViewModel,
    onBackToHome: () -> Unit
) {
    val state = vm.ui.collectAsStateWithLifecycle().value
    val export = state.export

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (export.phase) {
            ExportPhase.Decrypting -> {
                EncryptProgressOverlay(
                    title = export.fileName?.let { "Restoring $it" } ?: "Restoring…",
                    progress = export.progress,
                    onCancel = { vm.requestCancelExport() }
                )
            }

            ExportPhase.Finalizing -> {
                EncryptProgressOverlay(
                    title = "Finalizing restore…",
                    progress = null,
                    onCancel = { vm.requestCancelExport() }
                )
            }

            ExportPhase.Success -> {
                // Show tick, then POP. Clear state after the screen actually leaves.
                SuccessOverlay(show = true, onFinished = { /* no-op */ })

                LaunchedEffect(export.phase) {
                    kotlinx.coroutines.delay(900)  // small pause for tick
                    onBackToHome()                 // 👈 pop first
                }
                androidx.compose.runtime.DisposableEffect(Unit) {
                    onDispose { vm.clearExportUi() }  // 👈 clear AFTER we've left
                }
            }

            ExportPhase.Cancelled -> {
                CancelOverlay(show = true, onFinished = { /* no-op */ })

                LaunchedEffect(export.phase) {
                    kotlinx.coroutines.delay(400)
                    onBackToHome()                 // 👈 pop first
                }
                androidx.compose.runtime.DisposableEffect(Unit) {
                    onDispose { vm.clearExportUi() }
                }
            }

            ExportPhase.Error -> {
                FailureOverlay(show = true, onFinished = { /* no-op */ })

                LaunchedEffect(export.phase) {
                    kotlinx.coroutines.delay(800)
                    onBackToHome()                 // 👈 pop first
                }
                androidx.compose.runtime.DisposableEffect(Unit) {
                    onDispose { vm.clearExportUi() }
                }
            }

            ExportPhase.Idle -> {
                // Idle has two meanings:
                // 1) BEFORE user picks a dest (pre-pick) -> show a 'waiting' placeholder.
                // 2) AFTER finish (state reset) -> we should not be here; just bail out.
                if (export.fileName == null) {
                    EncryptProgressOverlay(
                        title = "Waiting for destination…",
                        progress = null,
                        onCancel = { /* picker is up; ignore */ }
                    )
                } else {
                    LaunchedEffect(Unit) { onBackToHome() }   // defensive: if Idle shows with a filename, leave
                }
            }
        }
    }
}
