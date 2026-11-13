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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * PlayStation-style dashboard backdrop: a deep vertical gradient with two slow, soft glows
 * (blue and violet). Light mode gets the same shapes at a much lower intensity.
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val t = rememberInfiniteTransition(label = "aurora")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(1f, 0f, infiniteRepeatable(tween(12000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val top = scheme.background
    val bottom = if (dark) lerp(scheme.background, Color(0xFF0A1B45), 0.65f) else lerp(scheme.background, Color(0xFFD5E3FA), 0.8f)
    val blueGlow = Color(0xFF0070D1).copy(alpha = if (dark) 0.34f else 0.14f)
    val violetGlow = Color(0xFF6C5CE7).copy(alpha = if (dark) 0.24f else 0.10f)
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(top, bottom)))
        drawCircle(
            Brush.radialGradient(
                listOf(blueGlow, Color.Transparent),
                center = Offset(size.width * (0.80f - 0.20f * a), size.height * (0.18f + 0.10f * b)),
                radius = size.minDimension * 0.85f
            )
        )
        drawCircle(
            Brush.radialGradient(
                listOf(violetGlow, Color.Transparent),
                center = Offset(size.width * (0.20f + 0.18f * b), size.height * (0.82f - 0.12f * a)),
                radius = size.minDimension * 0.80f
            )
        )
    }
}
