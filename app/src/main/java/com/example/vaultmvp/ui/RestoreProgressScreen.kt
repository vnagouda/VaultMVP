package com.example.vaultmvp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
                SuccessOverlay(show = true, onFinished = { /* no-op */ })
                LaunchedEffect(export.phase) {
                    delay(900)
                    onBackToHome()
                }
                DisposableEffect(Unit) {
                    onDispose { vm.clearExportUi() }
                }
            }
            ExportPhase.Cancelled -> {
                CancelOverlay(show = true, onFinished = { /* no-op */ })
                LaunchedEffect(export.phase) { delay(400); onBackToHome() }
                DisposableEffect(Unit) {
                    onDispose { vm.clearExportUi() }
                }
            }
            ExportPhase.Error -> {
                FailureOverlay(show = true, onFinished = { /* no-op */ })
                LaunchedEffect(export.phase) { delay(800); onBackToHome() }
                DisposableEffect(Unit) {
                    onDispose { vm.clearExportUi() }
                }
            }
            ExportPhase.Idle -> {
                // If we reached here pre-pick, show waiting; if post-finish, bounce home.
                if (export.fileName == null) {
                    EncryptProgressOverlay(
                        title = "Waiting for destination…",
                        progress = null,
                        onCancel = { /* ignore while picker active */ }
                    )
                } else {
                    LaunchedEffect(Unit) { onBackToHome() }
                }
            }
        }
    }
}
