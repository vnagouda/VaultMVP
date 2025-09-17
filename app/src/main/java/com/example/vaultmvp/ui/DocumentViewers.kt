package com.example.vaultmvp.ui

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vaultmvp.data.VaultItem
import com.example.vaultmvp.data.VaultRepo
import com.example.vaultmvp.ui.web.SecureDocWebView

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun AnyDocumentViewer(
    item: VaultItem,
    repo: VaultRepo,
    modifier: Modifier = Modifier
) {
    val name = item.displayName.lowercase()
    val mime = item.mime.lowercase()

    when {
        mime == "application/pdf" || name.endsWith(".pdf") -> {
            val temp = remember(item.id) { repo.decryptToTempFileProgress(item, null) }
            PdfViewer(tempPdfFile = temp, modifier = modifier.fillMaxSize())
        }

        // Word
        mime.contains("officedocument.wordprocessingml") || name.endsWith(".docx") -> {
            OfficeWebViewViewer(item, repo, "viewers/docx/index.html", modifier)
        }
        name.endsWith(".doc") -> {
            UnsupportedViewer(modifier)
        }

        // Excel
        mime.contains("officedocument.spreadsheetml") || name.endsWith(".xlsx") -> {
            OfficeWebViewViewer(item, repo, "viewers/xlsx/index.html", modifier)
        }
        name.endsWith(".xls") -> {
            UnsupportedViewer(modifier)
        }

        // PowerPoint
        mime.contains("officedocument.presentationml") || name.endsWith(".pptx") -> {
            OfficeWebViewViewer(item, repo, "viewers/pptx/index.html", modifier)
        }
        name.endsWith(".ppt") -> {
            UnsupportedViewer(modifier)
        }

        // Simple text-like
        name.endsWith(".txt") || name.endsWith(".csv") || name.endsWith(".json") ||
                name.endsWith(".md") || name.endsWith(".xml") -> {
            PlainTextViewer(item, repo, modifier)
        }

        else -> UnsupportedViewer(modifier)
    }
}

@Composable
private fun OfficeWebViewViewer(
    item: VaultItem,
    repo: VaultRepo,
    assetHtmlPath: String,
    modifier: Modifier = Modifier
) {
    // Decrypt once and hand raw bytes to the web viewer
    val bytes = remember(item.id) { repo.decryptToBytes(item) }

    // Ensure the container gives the WebView all available space
    Box(modifier = modifier.fillMaxSize()) {
        SecureDocWebView(
            htmlAssetPath = assetHtmlPath,
            bytes = bytes,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun PlainTextViewer(
    item: VaultItem,
    repo: VaultRepo,
    modifier: Modifier = Modifier
) {
    val text by remember(item.id) { mutableStateOf(String(repo.decryptToBytes(item))) }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        item {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun UnsupportedViewer(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text("Preview not supported for this format.", style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
        Text("Use Export to open and edit in another app.")
        androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(Modifier.fillMaxSize().height(2.dp))
    }
}
