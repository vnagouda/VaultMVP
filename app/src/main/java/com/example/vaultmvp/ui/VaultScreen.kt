// VaultScreen.kt
package com.example.vaultmvp.ui

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.vaultmvp.data.VaultItem
import com.example.vaultmvp.util.LOG_TAG
import com.example.vaultmvp.vm.VaultViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultScreen(
    vm: VaultViewModel,
    onPickFiles: () -> Unit,
    onRestore: (VaultItem) -> Unit,
    onOpenItem: (VaultItem) -> Unit,
    onOpenCamera: () -> Unit
) {
    // NOTE: No biometric logic here. MainActivity handles it + shows the PrivacyShield.
    val state = vm.ui.collectAsStateWithLifecycle().value

    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(Filter.All) }

    val filtered = remember(state.items, query, filter) {
        state.items.filter { item ->
            val matchesQ = query.isBlank() || item.displayName.contains(query, ignoreCase = true)
            val matchesF = when (filter) {
                Filter.All    -> true
                Filter.Images -> item.mime.startsWith("image/")
                Filter.Videos -> item.mime.startsWith("video/")
                Filter.Docs   -> !(item.mime.startsWith("image/") || item.mime.startsWith("video/"))
            }
            matchesQ && matchesF
        }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("Vault", style = MaterialTheme.typography.headlineMedium)
                        if (!searchOpen) {
                            Text(
                                if (state.unlocked) "Unlocked" else "Locked",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = if (state.unlocked)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                actions = {
                    IconButton(onClick = { searchOpen = !searchOpen }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onPickFiles) {
                        Icon(Icons.Filled.Add, contentDescription = "Menu")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onOpenCamera,
                modifier = Modifier.navigationBarsPadding()
            ) {
                Icon(Icons.Filled.CameraAlt, contentDescription = "Import")
            }
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
        ) {
            // Search
            if (searchOpen) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text("Search files") }
                )
                Spacer(Modifier.height(12.dp))
            }

            // Filters
            FilterChipsRow(
                selected = filter,
                onSelected = { filter = it }
            )

            Spacer(Modifier.height(8.dp))

            if (filtered.isEmpty()) {
                EmptyState(onPickFiles = onPickFiles)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    items(filtered, key = { it.id }) { item ->
                        ItemCard(
                            item = item,
                            onOpen = { onOpenItem(item) },
                            onRestore = { onRestore(item) }
                        )
                    }
                }
            }
        }
    }
}

private enum class Filter { All, Images, Videos, Docs }

@Composable
private fun FilterChipsRow(
    selected: Filter,
    onSelected: (Filter) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(selected == Filter.All, "All") { onSelected(Filter.All) }
        AssistChip(selected == Filter.Images, "Images") { onSelected(Filter.Images) }
        AssistChip(selected == Filter.Videos, "Videos") { onSelected(Filter.Videos) }
        AssistChip(selected == Filter.Docs, "Docs") { onSelected(Filter.Docs) }
    }
}

@Composable
private fun AssistChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Composable
private fun EmptyState(onPickFiles: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            VaultLoading(diameter = 120.dp) // subtle decorative dial
            Spacer(Modifier.height(12.dp))
            Text("Secure your files with one tap.", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Button(onClick = onPickFiles) { Text("Import files") }
        }
    }
}

@Composable
private fun ItemCard(
    item: VaultItem,
    onOpen: () -> Unit,
    onRestore: () -> Unit
) {
    val icon = remember(item.mime) { mimeIcon(item.mime) }
    Card(
        onClick = {
            Log.d(LOG_TAG, "VaultScreen: open ${item.id} ${item.displayName}")
            onOpen()
        }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.size(10.dp))
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = item.mime,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Restore",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .clickable {
                            Log.d(LOG_TAG, "VaultScreen: restore ${item.id} ${item.displayName}")
                            onRestore()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

private fun mimeIcon(mime: String): ImageVector = when {
    mime.startsWith("image/") -> Icons.Filled.Image
    mime.startsWith("video/") -> Icons.Filled.VideoLibrary
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}
