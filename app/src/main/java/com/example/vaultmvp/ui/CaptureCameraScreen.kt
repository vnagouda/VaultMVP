package com.example.vaultmvp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.FocusMeteringAction.FLAG_AE
import androidx.camera.core.FocusMeteringAction.FLAG_AF
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.vaultmvp.vm.VaultViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import androidx.camera.core.Camera as XCamera


private enum class CamMode { Photo, Video }

private data class CapturedPhoto(
    val bytes: ByteArray,
    val rotationDegrees: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraCaptureScreen(
    vm: VaultViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val mainExecutor: Executor = remember { ContextCompat.getMainExecutor(context) }

    // Runtime permission
    var hasCamPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    val requestPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCamPermission = granted }
    LaunchedEffect(Unit) { if (!hasCamPermission) requestPerm.launch(Manifest.permission.CAMERA) }
    if (!hasCamPermission) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera permission required")
        }
        return
    }

    // CameraX handles
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cam by remember { mutableStateOf<XCamera?>(null) }   // <— use alias type and different name


    // UI state
    var showGrid by remember { mutableStateOf(true) }
    var flashMode by remember { mutableStateOf(ImageCapture.FLASH_MODE_AUTO) }
    var mode by remember { mutableStateOf(CamMode.Photo) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var captured by remember { mutableStateOf<CapturedPhoto?>(null) } // <-- preview state

    val focusAlpha by animateFloatAsState(
        targetValue = if (focusPoint != null) 1f else 0f,
        animationSpec = tween(160, easing = LinearEasing),
        label = "focusAlpha"
    )

    /* Camera preview */
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val pv = PreviewView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val provider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(90)
                    .build()

                try {
                    provider.unbindAll()
                    val boundCam = provider.bindToLifecycle(
                        lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic
                    )
                    previewView = pv
                    imageCapture = ic
                    cam = boundCam
                } catch (t: Throwable) {
                    Log.e("VaultMVP", "Camera bind failed", t)
                }
                pv
            }
        )

        // Tap-to-focus overlay
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        if (captured != null) return@detectTapGestures // ignore taps while previewing
                        val pv = previewView ?: return@detectTapGestures
                        val c = cam ?: return@detectTapGestures
                        val mp = pv.meteringPointFactory.createPoint(tap.x, tap.y)
                        val action = FocusMeteringAction.Builder(mp, FLAG_AF or FLAG_AE)
                            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        c.cameraControl.startFocusAndMetering(action)

                        scope.launch {
                            delay(900)
                            focusPoint = null
                        }
                    }
                }
        )

        if (showGrid && captured == null) GridOverlay(color = Color.White.copy(alpha = 0.25f), stroke = 1.dp)
        if (focusPoint != null && captured == null) FocusRing(center = focusPoint!!, alpha = focusAlpha)

        // Top bar
        TopAppBar(
            title = { Text("Auto", fontWeight = FontWeight.SemiBold, color = Color.White) },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            },
            actions = {
                IconButton(onClick = {
                    flashMode = when (flashMode) {
                        ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                        ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                        else -> ImageCapture.FLASH_MODE_AUTO
                    }
                    imageCapture?.flashMode = flashMode
                }) {
                    Text(
                        when (flashMode) {
                            ImageCapture.FLASH_MODE_ON -> "On"
                            ImageCapture.FLASH_MODE_OFF -> "Off"
                            else -> "Auto"
                        },
                        color = Color.White
                    )
                }
                IconButton(onClick = { showGrid = !showGrid }) {
                    Text("Grid", color = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(Color.Transparent)
        )

        // Bottom controls
        if (captured == null) {
            // normal capture controls
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    ModeChip("PHOTO", selected = mode == CamMode.Photo) { mode = CamMode.Photo }
                    ModeChip("VIDEO", selected = mode == CamMode.Video) { mode = CamMode.Video }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Spacer(Modifier.size(56.dp))
                    ShutterButton(
                        isVideo = mode == CamMode.Video,
                        onClick = {
                            if (mode == CamMode.Video) {
                                // TODO: integrate CameraX VideoCapture<Recorder>
                            } else {
                                val ic = imageCapture ?: return@ShutterButton
                                ic.flashMode = flashMode
                                ic.takePicture(
                                    mainExecutor,
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            try {
                                                val data = imageProxyToJpeg(image)
                                                val deg = image.imageInfo.rotationDegrees
                                                captured = CapturedPhoto(data, deg) // show preview
                                            } catch (t: Throwable) {
                                                Log.e("VaultMVP", "Capture failed", t)
                                            } finally {
                                                image.close()
                                            }
                                        }
                                        override fun onError(exception: ImageCaptureException) {
                                            Log.e("VaultMVP", "Capture error", exception)
                                        }
                                    }
                                )
                            }
                        }
                    )
                    Spacer(Modifier.size(56.dp))
                }
            }
        } else {
            // PREVIEW OVERLAY (Save / Retake)
            CapturedPreviewOverlay(
                photo = captured!!,
                onRetake = { captured = null }, // just drop bytes and keep camera running
                onSave = {
                    // Save -> encrypt to vault (still no plaintext on disk)
                    vm.importCapturedPhoto(captured!!.bytes)
                    onClose()
                }
            )
        }
    }
}

/* ---------- Preview overlay ---------- */

@Composable
private fun CapturedPreviewOverlay(
    photo: CapturedPhoto,
    onRetake: () -> Unit,
    onSave: () -> Unit
) {
    // Decode + rotate bitmap for display only (bytes remain our source of truth)
    val bmp = remember(photo.bytes, photo.rotationDegrees) {
        val raw = BitmapFactory.decodeByteArray(photo.bytes, 0, photo.bytes.size)
        if (photo.rotationDegrees != 0) {
            val m = Matrix().apply { postRotate(photo.rotationDegrees.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        } else raw
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Preview",
            modifier = Modifier.fillMaxSize()
        )

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(onClick = onRetake) { Text("Retake") }
            Button(onClick = onSave) { Text("Save") }
        }
    }
}

/* ---------- UI bits (grid, focus, shutter, chips) ---------- */

@Composable
private fun GridOverlay(color: Color, stroke: Dp) {
    Canvas(modifier = Modifier.fillMaxSize().alpha(0.9f)) {
        val w = size.width; val h = size.height; val sw = stroke.toPx()
        drawLine(color, Offset(w / 3f, 0f), Offset(w / 3f, h), sw)
        drawLine(color, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), sw)
        drawLine(color, Offset(0f, h / 3f), Offset(w, h / 3f), sw)
        drawLine(color, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), sw)
    }
}

@Composable
private fun FocusRing(center: Offset, alpha: Float) {
    Canvas(modifier = Modifier.fillMaxSize().alpha(alpha)) {
        val r = 64f; val sw = 3f
        drawCircle(Color(1f, 1f, 1f, 0.9f), radius = r, center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw))
        val t = 12f
        drawLine(Color.White, center.copy(x = center.x - r), center.copy(x = center.x - r + t), sw)
        drawLine(Color.White, center.copy(x = center.x + r), center.copy(x = center.x + r - t), sw)
        drawLine(Color.White, center.copy(y = center.y - r), center.copy(y = center.y - r + t), sw)
        drawLine(Color.White, center.copy(y = center.y + r), center.copy(y = center.y + r - t), sw)
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (selected) Color.White else Color(1f, 1f, 1f, 0.6f)
        )
    ) {
        Text(text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun ShutterButton(isVideo: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.25f))
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(if (isVideo) Color.Red else Color.White)
                .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
        )
    }
}

/* ---------- ImageProxy -> JPEG bytes ---------- */

private fun imageProxyToJpeg(image: ImageProxy): ByteArray {
    if (image.format == ImageFormat.JPEG && image.planes.size == 1) {
        val buf: ByteBuffer = image.planes[0].buffer
        return ByteArray(buf.remaining()).also { buf.get(it) }
    }
    if (image.format == ImageFormat.YUV_420_888) {
        val w = image.width; val h = image.height
        val ySize = w * h; val nv21 = ByteArray(ySize + (ySize / 2))

        image.planes[0].buffer.apply {
            val rowStride = image.planes[0].rowStride
            val yAll = ByteArray(remaining()).also { get(it) }
            var pos = 0
            for (row in 0 until h) {
                System.arraycopy(yAll, row * rowStride, nv21, pos, w)
                pos += w
            }
        }
        val v = image.planes[2]; val u = image.planes[1]
        val chromaH = h / 2; val chromaW = w / 2
        var outPos = ySize
        for (row in 0 until chromaH) {
            val vRow = row * v.rowStride; val uRow = row * u.rowStride
            for (col in 0 until chromaW) {
                nv21[outPos++] = v.buffer.get(vRow + col * v.pixelStride)
                nv21[outPos++] = u.buffer.get(uRow + col * u.pixelStride)
            }
        }
        val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val baos = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, w, h), 90, baos)
        return baos.toByteArray()
    }
    throw IllegalStateException("Unexpected image format ${image.format}")
}
