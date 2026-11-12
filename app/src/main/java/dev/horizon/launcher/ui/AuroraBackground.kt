package dev.horizon.launcher.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Slow drifting colour glows behind the launcher (idea from GoyDevv/Horizon-Android, MIT). */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "aurora")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(7200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(1f, 0f, infiniteRepeatable(tween(9600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    Canvas(modifier.fillMaxSize()) {
        drawCircle(
            Brush.radialGradient(
                listOf(scheme.primary.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * (0.75f - 0.18f * a), size.height * (0.22f + 0.12f * b)),
                radius = size.minDimension * 0.65f
            )
        )
        drawCircle(
            Brush.radialGradient(
                listOf(scheme.secondary.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(size.width * (0.24f + 0.16f * b), size.height * (0.78f - 0.14f * a)),
                radius = size.minDimension * 0.60f
            )
        )
    }
}
