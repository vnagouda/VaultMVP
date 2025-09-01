package com.example.vaultmvp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.vaultmvp.util.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Adds FLAG_SECURE while this Composable is in composition. */
@Composable
fun SecureScreen(secure: Boolean) {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
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

/* ---------- Image: FILE-BASED (preferred) ---------- */

@Composable
fun ImageFileViewer(file: File) {
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


/* ---------- Image: BYTES (fallback) ---------- */

// in ViewerScreens.kt
@Composable
fun ImageViewer(bytes: ByteArray) {
    var bmp by remember(bytes) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(bytes) {
        try {
            // Decode bounds first
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight

            // Choose a sane max size for the device
            val maxSide = 2048
            var sample = 1
            while (srcW / sample > maxSide || srcH / sample > maxSide) sample *= 2

            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }

            bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }

            if (bmp == null) throw IllegalStateException("BitmapFactory returned null")
            android.util.Log.d(com.example.vaultmvp.util.LOG_TAG,
                "ImageViewer: decoded ${bmp!!.width}x${bmp!!.height} (sample=$sample)")
        } catch (t: Throwable) {
            android.util.Log.e(com.example.vaultmvp.util.LOG_TAG, "ImageViewer: decode failed", t)
            err = t.message ?: "Decode failed"
        }
    }

    when {
        bmp != null -> {
            androidx.compose.foundation.Image(
                bitmap = bmp!!.asImageBitmap(),
                contentDescription = null,
                modifier = androidx.compose.ui.Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            )
        }
        err != null -> {
            androidx.compose.material3.Text(
                text = "Unable to display image: $err",
                modifier = androidx.compose.ui.Modifier.padding(16.dp)
            )
        }
        else -> {
            androidx.compose.foundation.layout.Box(
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                androidx.compose.material3.CircularProgressIndicator()
                androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
            }
        }
    }
}

/* ---------- PDF ---------- */

@Composable
fun PdfViewer(file: File, onDisposeDelete: Boolean = true) {
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
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            )
        }
    }
}

/* ---------- Video ---------- */

@Composable
fun VideoPlayer(file: File) {
    SecureScreen(true)
    val context = LocalContext.current
    val exo = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(file.toURI().toString()))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            exo.release()
            file.delete()
        }
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

/* ---------- Safe decoders (IO thread) ---------- */

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

private fun decodeBitmapSafe(bytes: ByteArray, reqWpx: Int, reqHpx: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

    var sample = calcInSample(bounds.outWidth, bounds.outHeight, reqWpx, reqHpx)
    while (true) {
        try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (oom: OutOfMemoryError) {
            Log.w(LOG_TAG, "decodeBitmapSafe(bytes): OOM with inSample=$sample, retrying...", oom)
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
