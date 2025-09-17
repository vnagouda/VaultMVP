// app/src/main/java/com/example/vaultmvp/ui/ViewerScreen.kt
package com.example.vaultmvp.ui

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.vaultmvp.util.LOG_TAG
import com.example.vaultmvp.vm.Preview
import com.example.vaultmvp.vm.VaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

private enum class ViewPhase { Loading, SuccessAnim, Content }

/* ───────────────────── Orchestrator screen ───────────────────── */

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    vm: VaultViewModel,
    itemId: String,
    onBack: () -> Unit
) {
    val ui = vm.ui.collectAsStateWithLifecycle().value
    val item = remember(ui.items, itemId) { ui.items.find { it.id == itemId } }

    // kick off decrypt+open
    LaunchedEffect(itemId) { item?.let { vm.openAfterAuth(it) } }

    // ADD THIS LINE:
    val repo = rememberVaultRepo()

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { androidx.compose.material3.Text(item?.displayName ?: "Viewer") },
                navigationIcon = {
                    androidx.compose.material3.TextButton(onClick = { vm.closePreview(); onBack() }) {
                        androidx.compose.material3.Text("Back")
                    }
                }
            )
        }
    ) { pad ->
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxSize()
                .padding(pad),
            contentAlignment = Alignment.Center
        ) {
            when (val p = ui.preview) {
                null -> {
                    EncryptProgressOverlay(
                        title = item?.let { "Opening ${it.displayName}" } ?: "Opening…",
                        progress = ui.openProgress,
                        onCancel = null
                    )
                }
                is Preview.ImageFile -> {
                    ImageFileViewer(p.file)
                }
                is Preview.Pdf -> {
                    PdfViewer(p.file)
                }
                is Preview.Video -> {
                    VideoPlayer(p.file)
                }
                is Preview.OfficeDoc -> {
                    // Use our new router that handles DOCX/XLSX/PPTX with WebView
                    AnyDocumentViewer(item = p.item, repo = repo, modifier = Modifier.fillMaxSize())
                }
                is Preview.TextDoc -> {
                    AnyDocumentViewer(item = p.item, repo = repo, modifier = Modifier.fillMaxSize())
                }
                is Preview.Unsupported -> {
                    androidx.compose.material3.Text(p.reason)
                }
            }

        }

    }
}

@Composable
private fun rememberVaultRepo(): com.example.vaultmvp.data.VaultRepo {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember(ctx) { com.example.vaultmvp.data.VaultRepo(ctx) }
}



/* ───────────────────── Helpers: secure & decoders ───────────────────── */

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// --- Image from content Uri ---
@Composable
private fun ImageUriViewer(uri: android.net.Uri) {
    SecureScreen(true)
    val context = androidx.compose.ui.platform.LocalContext.current
    val resolver = context.contentResolver

    var containerW by remember { mutableStateOf(0) }
    var containerH by remember { mutableStateOf(0) }
    var bmp by remember(uri, containerW, containerH) { mutableStateOf<Bitmap?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uri, containerW, containerH) {
        if (containerW > 0 && containerH > 0) {
            try {
                val decoded = withContext(Dispatchers.IO) {
                    decodeBitmapSafe(resolver, uri, containerW, containerH)
                }
                bmp = decoded
            } catch (t: Throwable) {
                Log.e(LOG_TAG, "ImageUriViewer decode failed: $uri", t)
                err = t.message ?: "Decode failed"
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { s -> containerW = s.width; containerH = s.height },
        contentAlignment = Alignment.Center
    ) {
        when {
            bmp != null -> Image(
                bitmap = bmp!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(8.dp)
            )
            err != null -> Text("Unable to display image: $err", modifier = Modifier.padding(16.dp))
            else -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("Rendering…")
                }
            }
        }
    }
}

// --- PDF from content Uri ---
@Composable
private fun PdfViewerUri(uri: android.net.Uri) {
    SecureScreen(true)
    val context = androidx.compose.ui.platform.LocalContext.current
    val pages by produceState(initialValue = emptyList<Bitmap>(), uri) {
        val list = mutableListOf<Bitmap>()
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")!!
        val pdf = PdfRenderer(pfd)
        repeat(pdf.pageCount) { i ->
            pdf.openPage(i).use { page ->
                val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.RGB_565)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                list.add(bmp)
            }
        }
        pdf.close(); pfd.close()
        value = list
    }

    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(pages) { idx, b ->
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = "Page ${idx + 1}",
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            )
        }
    }
}


/** Adds FLAG_SECURE while this Composable is in composition. */
@Composable
private fun SecureScreen(secure: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(secure, activity) {
        val w = activity?.window
        if (secure && w != null) {
            Log.d(LOG_TAG, "SecureScreen: enabling FLAG_SECURE")
            w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (secure && w != null) {
                Log.d(LOG_TAG, "SecureScreen: clearing FLAG_SECURE")
                w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

/* ───────────────────── Image: file-based (preferred) ───────────────────── */

@Composable
private fun ImageFileViewer(file: File) {
    SecureScreen(true)

    var containerW by remember { mutableStateOf(0) }
    var containerH by remember { mutableStateOf(0) }
    var bmp by remember(file.path, containerW, containerH) { mutableStateOf<Bitmap?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(file.path, containerW, containerH) {
        if (containerW > 0 && containerH > 0) {
            try {
                val decoded: Bitmap = withContext(Dispatchers.IO) {
                    decodeBitmapSafe(file, containerW, containerH)
                }
                bmp = decoded
                Log.d(LOG_TAG, "Viewer decode file ok: ${decoded.width}x${decoded.height} name=${file.name}")
            } catch (t: Throwable) {
                Log.e(LOG_TAG, "Viewer decode file failed: ${file.absolutePath}", t)
                err = t.message ?: "Decode failed"
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                containerW = size.width
                containerH = size.height
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            bmp != null -> Image(
                bitmap = bmp!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            )
            err != null -> Text("Unable to display image: $err", modifier = Modifier.padding(16.dp))
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Rendering…")
            }
        }
    }
}

/* (Optional) If you still need bytes-based fallback, keep this:
@Composable
private fun ImageViewer(bytes: ByteArray) { ... }
*/

/* ───────────────────── PDF ───────────────────── */

@Composable
fun PdfViewer(tempPdfFile: java.io.File, modifier: Modifier = Modifier) {
    // Open once, close when leaving the screen
    val doc = remember(tempPdfFile) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            com.example.vaultmvp.ui.pdf.PdfDoc.open(tempPdfFile)
        } else null
    }
    DisposableEffect(doc) {
        onDispose { doc?.close() }
    }

    if (doc == null) {
        // Fallback for very old APIs (unlikely for your minSdk)
        androidx.compose.material3.Text("PDF preview not supported on this device", modifier)
        return
    }

    val pageCount = doc.pageCount

    val density = androidx.compose.ui.platform.LocalDensity.current
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidthPx = with(density) { config.screenWidthDp.dp.roundToPx() }

    // Simple lazy list of pages with light prefetch
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
    ) {
        items(
            count = pageCount,
            key = { it }
        ) { pageIndex ->
            // Produce each page bitmap off the main thread
            val imageState = produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, pageIndex, screenWidthPx) {
                value = null
                val bmp = doc.renderPageToBitmap(pageIndex, screenWidthPx)
                // Convert to Compose type
                value = bmp.asImageBitmap()
                // You can recycle the underlying Bitmap later if you keep it; here we let ImageBitmap own it.
            }

            // Optional prefetch next page to keep scrolling smooth
            LaunchedEffect(pageIndex) {
                if (pageIndex + 1 < pageCount) {
                    // fire and forget; cache handled by OS
                    doc.renderPageToBitmap(pageIndex + 1, screenWidthPx)
                }
            }

            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                val img = imageState.value
                if (img == null) {
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                    )
                } else {
                    androidx.compose.foundation.Image(
                        bitmap = img,
                        contentDescription = "PDF page ${pageIndex + 1}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
            }
        }
    }
}

/* ───────────────────── Video (Media3) ───────────────────── */

// REPLACE ONLY THIS SECTION in ViewerScreen.kt

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(file: java.io.File) {
    SecureScreen(true)
    val context = LocalContext.current
    val isChunked = remember(file) { detectChunked(file) }

    val exo = remember {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
    }
    DisposableEffect(Unit) { onDispose { exo.release() } }

    LaunchedEffect(file, isChunked) {
        if (isChunked) {
            // Play directly from the encrypted blob
            val dsFactory = androidx.media3.datasource.DataSource.Factory {
                // Repo holds the SecretKey; expose a small accessor if needed.
                // Here we re-use the same key your repo uses via a provider.
                com.example.vaultmvp.data.media.EncryptedFileDataSource(
                    file = file,
                    unwrapKey = {
                        // Retrieve the same SecretKey used in VaultRepo.
                        // Fast path: call into a small singleton that returns it.
                        com.example.vaultmvp.crypto.VaultCrypto.ensureKey()
                    }
                )
            }
            val mediaSource = androidx.media3.exoplayer.source.ProgressiveMediaSource
                .Factory(dsFactory)
                .createMediaSource(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
            exo.setMediaSource(mediaSource)
        } else {
            // Legacy: normal file playback
            exo.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
        }
        exo.prepare()
        exo.playWhenReady = true
    }

    AndroidView(
        factory = {
            androidx.media3.ui.PlayerView(it).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
                player = exo
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun detectChunked(file: java.io.File): Boolean {
    return try {
        java.io.FileInputStream(file).use { fin ->
            val b = ByteArray(4)
            if (fin.read(b) != 4) return false
            java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).int == 0x50564D56
        }
    } catch (_: Throwable) { false }
}


@Composable
private fun VideoPlayer(uri: Uri) {
    SecureScreen(true)
    val context = LocalContext.current
    val exo = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(Unit) {
        onDispose { exo.release() }
    }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
                player = exo
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

/* ───────────────────── Safe decoders ───────────────────── */

private fun decodeBitmapSafe(
    resolver: ContentResolver,
    uri: Uri,
    reqWpx: Int,
    reqHpx: Int
): Bitmap {
    // Pass 1: bounds only (to get source width/height)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }

    var sample = calcInSample(bounds.outWidth, bounds.outHeight, reqWpx, reqHpx)
    if (sample < 1) sample = 1

    // Pass 2: decode with retry on OOM by increasing sample size
    while (true) {
        try {
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565 // lower memory footprint
            }
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Null InputStream for $uri" }
                return BitmapFactory.decodeStream(input, null, opts)
                    ?: throw IllegalStateException("Decode returned null for $uri")
            }
        } catch (oom: OutOfMemoryError) {
            Log.w(LOG_TAG, "decodeBitmapSafe(uri): OOM at inSample=$sample, retrying…", oom)
            sample *= 2
            if (sample > 64) throw oom
        }
    }
}

private fun decodeBitmapSafe(file: File, reqWpx: Int, reqHpx: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)

    var sample = calcInSample(bounds.outWidth, bounds.outHeight, reqWpx, reqHpx)
    while (true) {
        try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.RGB_565 // lower memory footprint
            }
            return BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (oom: OutOfMemoryError) {
            Log.w(LOG_TAG, "decodeBitmapSafe(file): OOM with inSample=$sample, retrying...", oom)
            if (sample >= 64) throw oom
            sample *= 2
        }
    }
}


private fun calcInSample(srcW: Int, srcH: Int, reqWpx: Int, reqHpx: Int): Int {
    var inSample = 1
    if (srcH > reqHpx || srcW > reqWpx) {
        var halfH = srcH / 2
        var halfW = srcW / 2
        while ((halfH / inSample) >= reqHpx && (halfW / inSample) >= reqWpx) {
            inSample *= 2
        }
    }
    return inSample.coerceAtLeast(1)
}
