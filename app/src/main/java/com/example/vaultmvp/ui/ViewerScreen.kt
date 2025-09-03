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
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    vm: VaultViewModel,
    itemId: String,
    onBack: () -> Unit
) {
    val state = vm.ui.collectAsStateWithLifecycle().value
    val item = remember(state.items, itemId) { state.items.find { it.id == itemId } }

    // Start decrypt/prepare on entry
    LaunchedEffect(itemId) { item?.let { vm.openAfterAuth(it) } }

    var phase by remember { mutableStateOf(ViewPhase.Loading) }

    // Phase transitions based on preview availability
    LaunchedEffect(state.preview) {
        if (state.preview == null) {
            phase = ViewPhase.Loading
        } else if (phase != ViewPhase.Content) {
            phase = ViewPhase.SuccessAnim
        }
    }

    LaunchedEffect(itemId) { Log.d(LOG_TAG, "ViewerScreen: open itemId=$itemId") }

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

            // Opening progress overlay while decrypting/streaming
            if (state.preview == null && state.openProgress != null) {
                EncryptProgressOverlay(
                    title = item?.let { "Opening ${it.displayName}" } ?: "Opening…",
                    progress = state.openProgress,
                    onCancel = null,
                    size = 220.dp
                )
            }

            when (phase) {
                ViewPhase.Loading -> {
                    VaultLoading(
                        modifier = Modifier
                            .fillMaxSize()
                            .align(Alignment.Center)
                    )
                }
                ViewPhase.SuccessAnim -> {
                    SuccessOverlay(show = true, onFinished = { phase = ViewPhase.Content })
                }
                ViewPhase.Content -> {
                    when (val p = state.preview) {
                        is Preview.ImageFile -> ImageFileViewer(p.file)
                        is Preview.ImageUri  -> ImageUriViewer(p.uri)      // NEW
                        is Preview.Pdf       -> PdfViewer(p.file)
                        is Preview.PdfUri    -> PdfViewerUri(p.uri)        // NEW
                        is Preview.Video     -> VideoPlayer(file = p.file)
                        is Preview.VideoUri  -> VideoPlayer(uri = p.uri)
                        is Preview.Unsupported -> Text("Unsupported", Modifier.align(Alignment.Center))
                        null -> {
                            phase = ViewPhase.Loading
                            VaultLoading(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .align(Alignment.Center)
                            )
                        }
                    }

                }

            }
        }
    }
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
private fun PdfViewer(file: File, onDisposeDelete: Boolean = true) {
    SecureScreen(true)
    val pages by produceState(initialValue = emptyList<Bitmap>(), file) {
        val list = mutableListOf<Bitmap>()
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
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
    DisposableEffect(Unit) { onDispose { if (onDisposeDelete) file.delete() } }

    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(pages) { idx, b ->
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = "Page ${idx + 1}",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
        }
    }
}

/* ───────────────────── Video (Media3) ───────────────────── */

@Composable
private fun VideoPlayer(file: File) {
    VideoPlayer(uri = Uri.fromFile(file))  // reuse the Uri variant
    // We do not delete here; VM clears temp files in closePreview().
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
