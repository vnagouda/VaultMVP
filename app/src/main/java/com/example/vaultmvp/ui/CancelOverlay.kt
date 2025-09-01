package com.example.vaultmvp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.min

@Composable
fun CancelOverlay(
    show: Boolean,
    onFinished: () -> Unit
) {
    var visible by remember(show) { mutableStateOf(show) }
    val spring = spring<Float>(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow)

    LaunchedEffect(show) {
        if (show) {
            delay(600)
            visible = false
            delay(150)
            onFinished()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(animationSpec = spring),
        exit = fadeOut()
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(140.dp)) {
                val w = size.width
                val h = size.height
                val stroke = w * 0.12f
                val c = Color(0xFFE74C3C)
                drawLine(
                    color = c, start = androidx.compose.ui.geometry.Offset(w*0.2f,h*0.2f),
                    end = androidx.compose.ui.geometry.Offset(w*0.8f,h*0.8f),
                    strokeWidth = stroke, cap = StrokeCap.Round
                )
                drawLine(
                    color = c, start = androidx.compose.ui.geometry.Offset(w*0.8f,h*0.2f),
                    end = androidx.compose.ui.geometry.Offset(w*0.2f,h*0.8f),
                    strokeWidth = stroke, cap = StrokeCap.Round
                )
            }
        }
    }
}
