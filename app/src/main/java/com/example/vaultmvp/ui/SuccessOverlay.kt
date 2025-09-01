package com.example.vaultmvp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.min

@Composable
fun SuccessOverlay(
    show: Boolean,
    onFinished: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    var visible by remember(show) { mutableStateOf(show) }

    // Bouncey scale
    val scaleSpring = spring<Float>(
        dampingRatio = 0.45f,
        stiffness = Spring.StiffnessLow
    )
    val targetScale = if (show) 1f else 0f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = scaleSpring,
        label = "successScale"
    )

    // Quick radial splash
    val infinite = rememberInfiniteTransition(label = "splash")
    val splashRadius by infinite.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing)),
        label = "splashRadius"
    )
    val splashAlpha by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing)),
        label = "splashAlpha"
    )

    LaunchedEffect(show) {
        if (show) {
            delay(700)      // let it play
            visible = false // trigger fadeOut
            delay(200)
            onFinished()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(animationSpec = scaleSpring),
        exit = fadeOut(animationSpec = tween(200))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale
                ),
            contentAlignment = Alignment.Center
        ) {
            // Splash behind the check
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val m = min(w, h)
                val r = m * 0.18f * splashRadius
                drawCircle(
                    color = tint.copy(alpha = splashAlpha),
                    radius = r,
                    center = Offset(w / 2f, h / 2f),
                    style = Stroke(width = r * 0.12f, cap = StrokeCap.Round)
                )
            }

            // Checkmark ✓
            Canvas(Modifier.size(140.dp)) {
                val w = size.width
                val h = size.height
                val stroke = w * 0.12f
                val path = Path().apply {
                    moveTo(w * 0.20f, h * 0.55f)
                    lineTo(w * 0.42f, h * 0.75f)
                    lineTo(w * 0.82f, h * 0.30f)
                }
                drawPath(
                    path = path,
                    color = Color(0xFF2ECC71),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
    }
}
