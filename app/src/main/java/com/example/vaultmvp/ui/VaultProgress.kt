package com.example.vaultmvp.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

@Composable
fun EncryptProgressOverlay(
    title: String,
    progress: Float?,          // null = indeterminate
    onCancel: (() -> Unit)? = null,
    size: Dp = 220.dp
) {
    // Grab colors in composable scope (can't read MaterialTheme inside Canvas)
    val primary = MaterialTheme.colorScheme.primary
    val ringBase = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

    // For indeterminate mode, animate a sweep angle start value (also in composable scope)
    val indeterminateStart: Float? = if (progress == null) {
        val transition = rememberInfiniteTransition(label = "indeterminate")
        val start by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
            label = "indeterminateStart"
        )
        start
    } else null

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Center dial
        VaultLoading(
            modifier = Modifier.size(size - 24.dp),
            diameter = size - 24.dp,
            brand = primary
        )

        // Progress ring
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = this.size.width
                val h = this.size.height
                val m = kotlin.math.min(w, h)
                val stroke = m * 0.06f
                val pad = stroke / 2.2f
                val topLeft = Offset(pad, pad)
                val edge = m - pad * 2

                val sweepBrush = Brush.sweepGradient(
                    colors = listOf(primary.copy(alpha = 0.1f), primary)
                )

                if (progress == null) {
                    // indeterminate: use animated start angle computed above
                    val start = indeterminateStart ?: 0f
                    drawArc(
                        brush = sweepBrush,
                        startAngle = start,
                        sweepAngle = 110f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = Size(edge, edge),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                } else {
                    // determinate: base ring + progress arc
                    drawArc(
                        color = ringBase,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = Size(edge, edge),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                    drawArc(
                        brush = sweepBrush,
                        startAngle = -90f,
                        sweepAngle = (progress.coerceIn(0f, 1f) * 360f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = Size(edge, edge),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }
        }

        // Title + percent + cancel
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (progress != null) {
                Text(text = "${(progress * 100f).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
            if (onCancel != null) {
                Button(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
    }
}
