package com.example.vaultmvp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vaultmvp.data.VaultItem
import com.example.vaultmvp.vm.ImportPhase
import com.example.vaultmvp.vm.ImportUi
import com.example.vaultmvp.vm.VaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    vm: VaultViewModel,
    onPickFiles: () -> Unit,
    onRestore: (VaultItem) -> Unit,
    onOpenItem: (VaultItem) -> Unit   // <— new: navigate to viewer route
) {
    val activity = LocalContext.current as FragmentActivity
    val state by vm.ui.collectAsStateWithLifecycle()

    // Require auth only when entering the vault (or after idle lock)
    LaunchedEffect(state.unlocked) {
        if (!state.unlocked) {
            promptBiometric(
                activity = activity,
                title = "Unlock Vault",
                onSuccess = { vm.setUnlocked(true) },
                onError = { /* optionally show snackbar */ }
            )
        }
    }

    if (!state.unlocked) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Vault locked")
        }
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("VaultMvp") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onPickFiles) { Text("+") }
        }
    ) { pad ->
        // Use a Box so overlays can sit on top of the list
        Box(
            Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            // Main content (list + rename dialog)
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                var renamingFor by remember { mutableStateOf<VaultItem?>(null) }
                var newName by remember { mutableStateOf("") }

                LazyColumn(Modifier.weight(1f)) {
                    items(state.items) { item ->
                        VaultRow(
                            item = item,
                            onOpen = {
                                promptBiometric(
                                    activity,
                                    "Open ${item.displayName}",
                                    onSuccess = { onOpenItem(item) },
                                    onError = { /* no-op */ }
                                )
                            },
                            onRename = { current ->
                                renamingFor = item
                                newName = current
                            },
                            onRestore = { onRestore(item) }
                        )
                    }
                }

                if (renamingFor != null) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { renamingFor = null },
                        title = { Text("Rename") },
                        text = {
                            androidx.compose.material3.OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it },
                                singleLine = true
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                val trimmed = newName.trim()
                                if (trimmed.isNotEmpty()) vm.rename(renamingFor!!.id, trimmed)
                                renamingFor = null
                            }) { Text("Save") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { renamingFor = null }) { Text("Cancel") }
                        }
                    )
                }
            }

            // ---- OVERLAYS (drawn on top) ----
            when (state.import.phase) {
                ImportPhase.Encrypting -> {
                    ImportOverlay(
                        import = state.import,
                        onCancel = { vm.requestCancelEncryption() },
                        onDismissAfterResult = { /* VM will reset to Idle; nothing needed */ }
                    )
                }
                ImportPhase.Finalizing -> {
                    ImportOverlay(
                        import = state.import,
                        onCancel = { vm.requestCancelEncryption() },
                        onDismissAfterResult = { /* VM will reset to Idle; nothing needed */ }
                    )
                }
                ImportPhase.Success -> {
                    SuccessOverlay(show = true, onFinished = { /* VM resets; nothing needed */ })
                }
                ImportPhase.Cancelled -> {
                    FailureOverlay(show = true, onFinished = { /* VM resets; nothing needed */ })
                }
                ImportPhase.Error,
                ImportPhase.Idle -> Unit
            }
        }
    }

}

@Composable
private fun VaultRow(
    item: VaultItem,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onRestore: () -> Unit
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onOpen() }
                .padding(vertical = 10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                Text("${item.mime} • ${item.sizeBytes ?: 0} bytes", style = MaterialTheme.typography.bodySmall)
            }
            Row {
                Text(
                    "Rename",
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .clickable { onRename(item.displayName) },
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Restore",
                    modifier = Modifier.clickable { onRestore() },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ImportOverlay(
    import: ImportUi,
    onCancel: () -> Unit,
    onDismissAfterResult: () -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (import.phase) {
            ImportPhase.Encrypting -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // your existing vault dial animation
                    VaultLoading(Modifier.size(180.dp))

                    Spacer(Modifier.height(16.dp))
                    // Nice circular progress around it
                    val p = (import.progress ?: 0f).coerceIn(0f, 1f)
                    androidx.compose.material3.CircularProgressIndicator(
                        progress = { p },
                        strokeWidth = 6.dp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(import.fileName ?: "Encrypting")
                    if (import.progress != null) {
                        Text("${(p * 100).toInt()}%")
                    }
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.material3.TextButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                }
            }
            ImportPhase.Finalizing -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // same dial animation you use for Encrypting
                    VaultLoading(Modifier.size(180.dp))

                    Spacer(Modifier.height(16.dp))

                    // Indeterminate spinner during finalize
                    androidx.compose.material3.CircularProgressIndicator()

                    Spacer(Modifier.height(8.dp))
                    Text("Finalizing securely…", style = MaterialTheme.typography.titleMedium)

                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.Button(onClick = onCancel) {
                        Text("Cancel")
                    }
                }
            }


            ImportPhase.Success -> {
                SuccessOverlay(
                    show = true,
                    onFinished = onDismissAfterResult
                )
            }

            ImportPhase.Cancelled -> {
                FailureOverlay(
                    show = true,
                    onFinished = onDismissAfterResult
                )
            }

            ImportPhase.Error -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Import failed", color = androidx.compose.ui.graphics.Color.Red)
                    if (!import.error.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(import.error!!)
                    }
                }
                // auto dismiss after a short delay
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(900)
                    onDismissAfterResult()
                }
            }

            ImportPhase.Idle -> Unit
        }
    }
}

