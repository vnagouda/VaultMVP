package com.example.vaultmvp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
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
    LaunchedEffect(itemId) { android.util.Log.d(LOG_TAG, "ViewerScreen: open itemId=$itemId") }

    var phase by remember { mutableStateOf(ViewPhase.Loading) }

    // Transition when preview becomes available
    LaunchedEffect(state.preview) {
        if (state.preview == null) {
            phase = ViewPhase.Loading
        } else if (phase != ViewPhase.Content) {
            phase = ViewPhase.SuccessAnim
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.displayName ?: "Viewer") },
                navigationIcon = {
                    IconButton(onClick = { vm.closePreview(); onBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // placeholder share action (no-op)
                    IconButton(onClick = { /* share later if needed */ }) {
                        Icon(Icons.Default.IosShare, contentDescription = "Share")
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (phase) {
                ViewPhase.Loading -> {
                    // Label-less dial; title handled by app bar
                    VaultLoading(
                        modifier = Modifier.fillMaxSize().align(Alignment.Center)
                    )
                }
                ViewPhase.SuccessAnim -> {
                    SuccessOverlay(show = true, onFinished = { phase = ViewPhase.Content })
                }
                ViewPhase.Content -> {
                    when (val p = state.preview) {
                        is Preview.ImageFile -> ImageFileViewer(p.file)
                        is Preview.Image     -> ImageViewer(p.bytes)
                        is Preview.Pdf       -> PdfViewer(p.file)
                        is Preview.Video     -> VideoPlayer(p.file)
                        is Preview.Unsupported, null -> Text("Unsupported", Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}
