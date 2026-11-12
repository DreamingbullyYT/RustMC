package dev.ferrite.app

import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Material You colours from the wallpaper on Android 12+, a fixed dark/light palette below that. */
@Composable
fun FerriteTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme(); val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFE8803A), secondary = Color(0xFF8C6B55))
        else -> lightColorScheme(primary = Color(0xFFB85A1B), secondary = Color(0xFF7A5A45))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Two slow colour blobs behind the UI. Static when [animated] is false (battery saver, reduced motion). */
@Composable
fun SoftBackground(modifier: Modifier = Modifier, animated: Boolean = true) {
    val c = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "bg")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(8000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(1f, 0f, infiniteRepeatable(tween(11000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val pa = if (animated) a else 0.5f; val pb = if (animated) b else 0.5f
    Canvas(modifier.fillMaxSize().background(c.background)) {
        drawCircle(Brush.radialGradient(listOf(c.primary.copy(alpha = 0.14f), Color.Transparent), Offset(size.width * (0.8f - 0.2f * pa), size.height * (0.2f + 0.1f * pb)), size.minDimension * 0.7f))
        drawCircle(Brush.radialGradient(listOf(c.secondaryContainer.copy(alpha = 0.25f), Color.Transparent), Offset(size.width * (0.2f + 0.15f * pb), size.height * (0.8f - 0.12f * pa)), size.minDimension * 0.6f))
    }
}
