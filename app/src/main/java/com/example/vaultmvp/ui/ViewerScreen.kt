package com.example.vaultmvp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vaultmvp.util.LOG_TAG
import com.example.vaultmvp.vm.Preview
import com.example.vaultmvp.vm.VaultViewModel

private enum class ViewPhase { Loading, SuccessAnim, Content }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    vm: VaultViewModel,
    itemId: String,
    onBack: () -> Unit
) {
    val state = vm.ui.collectAsStateWithLifecycle().value
    val item = remember(state.items, itemId) { state.items.find { it.id == itemId } }

    // Start decrypt on entry
    LaunchedEffect(itemId) { item?.let { vm.openAfterAuth(it) } }

    var phase by remember { mutableStateOf(ViewPhase.Loading) }

    // Transition when preview becomes available
    LaunchedEffect(state.preview) {
        if (state.preview == null) {
            phase = ViewPhase.Loading
        } else if (phase != ViewPhase.Content) {
            phase = ViewPhase.SuccessAnim
        }
    }

    LaunchedEffect(itemId) {
        android.util.Log.d(LOG_TAG, "ViewerScreen: open itemId=$itemId")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.displayName ?: "Viewer") },
                navigationIcon = {
                    TextButton(onClick = { vm.closePreview(); onBack() }) { Text("Back") }
                }
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (phase) {
                ViewPhase.Loading -> {
                    // Show labeled, percent-based progress while decrypting for preview
                    EncryptProgressOverlay(
                        title = item?.displayName?.let { "Opening $it" } ?: "Opening…",
                        progress = state.openProgress,
                        onCancel = null
                    )
                }
                ViewPhase.SuccessAnim -> {
                    SuccessOverlay(show = true, onFinished = { phase = ViewPhase.Content })
                }
                ViewPhase.Content -> {
                    when (val p = state.preview) {
                        is Preview.ImageFile -> ImageFileViewer(p.file)
                        is Preview.Image     -> ImageViewer(p.bytes) // fallback path
                        is Preview.Pdf       -> PdfViewer(p.file)
                        is Preview.Video     -> VideoPlayer(p.file)
                        is Preview.Unsupported -> Text("Unsupported", Modifier.align(Alignment.Center))
                        null -> {
                            phase = ViewPhase.Loading
                            EncryptProgressOverlay(
                                title = item?.displayName?.let { "Opening $it" } ?: "Opening…",
                                progress = state.openProgress,
                                onCancel = null
                            )
                        }
                    }
                }
            }
        }
    }
}
