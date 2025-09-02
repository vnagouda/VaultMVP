package com.example.vaultmvp.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Label-less animated vault dial. Screens show their own titles
 * (Encrypting…, Restoring…, Opening…) outside this composable.
 */
@Composable
fun VaultLoading(
    modifier: Modifier = Modifier,
    diameter: Dp = 200.dp,
    brand: Color = MaterialTheme.colorScheme.primary
) {
    val infinite = rememberInfiniteTransition(label = "vaultInfinite")

    val dialRotation by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "dialRotation"
    )
    val glow by infinite.animateFloat(
        initialValue = 0.6f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "glow"
    )
    val sweep by infinite.animateFloat(
        initialValue = 20f, targetValue = 320f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep"
    )

    val scheme = MaterialTheme.colorScheme
    val ringBg = scheme.surfaceVariant.copy(alpha = 0.7f)
    val accent = scheme.onSurface.copy(alpha = 0.2f)
    val glowColor = brand.copy(alpha = 0.35f)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val w = this.size.width
            val h = this.size.height
            val m = min(w, h)
            val center = Offset(w / 2f, h / 2f)

            // Outer ring
            drawCircle(
                color = ringBg,
                radius = m * 0.45f,
                center = center,
                style = Stroke(width = m * 0.03f, cap = StrokeCap.Round)
            )

            // Animated sweep arc
            drawArc(
                brush = Brush.linearGradient(listOf(brand.copy(alpha = 0.1f), brand)),
                startAngle = dialRotation,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(center.x - m * 0.45f, center.y - m * 0.45f),
                size = Size(m * 0.9f, m * 0.9f),
                style = Stroke(width = m * 0.035f, cap = StrokeCap.Round)
            )

            // Dial base
            drawCircle(
                color = accent,
                radius = m * 0.22f,
                center = center
            )

            // Tick marks
            repeat(12) { i ->
                val ang = Math.toRadians((i * 30).toDouble()).toFloat()
                val r1 = m * 0.27f
                val r2 = m * 0.35f
                val p1 = Offset(center.x + r1 * cos(ang), center.y + r1 * sin(ang))
                val p2 = Offset(center.x + r2 * cos(ang), center.y + r2 * sin(ang))
                drawLine(
                    color = accent.copy(alpha = 0.5f),
                    start = p1,
                    end = p2,
                    strokeWidth = m * 0.01f,
                    cap = StrokeCap.Round
                )
            }

            // Rotating needle
            val na = Math.toRadians(dialRotation.toDouble()).toFloat()
            val nr1 = m * 0.08f
            val nr2 = m * 0.30f
            val np1 = Offset(center.x + nr1 * cos(na), center.y + nr1 * sin(na))
            val np2 = Offset(center.x + nr2 * cos(na), center.y + nr2 * sin(na))
            drawLine(
                color = brand,
                start = np1,
                end = np2,
                strokeWidth = m * 0.02f,
                cap = StrokeCap.Round
            )

            // Keyhole + glow
            val r = m * 0.06f
            val keyhole = Path().apply {
                addOval(Rect(center - Offset(r, r), Size(2 * r, 2 * r)))
                moveTo(center.x, center.y + r)
                lineTo(center.x - r * 0.35f, center.y + r * 2.4f)
                lineTo(center.x + r * 0.35f, center.y + r * 2.4f)
                close()
            }
            drawPath(path = keyhole, color = Color.Black.copy(alpha = 0.6f))
            drawCircle(
                color = glowColor,
                radius = m * (0.20f * glow),
                center = center,
                style = Stroke(width = m * 0.02f)
            )
        }
    }
}
