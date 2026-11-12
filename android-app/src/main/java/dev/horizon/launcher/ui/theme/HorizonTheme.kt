package dev.horizon.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object HorizonColors {
    val Background = Color(0xFF0B0D12)
    val Surface = Color(0xFF151923)
    val SurfaceElevated = Color(0xFF1D2330)
    val TextMuted = Color(0xFF9AA4B5)
    val Accent = Color(0xFF9B8CFF)
    val AccentSecondary = Color(0xFF62D9E8)
    val Success = Color(0xFF67D6A3)
    val Warning = Color(0xFFFFC66D)
}

private val HorizonDarkScheme = darkColorScheme(
    primary = HorizonColors.Accent,
    secondary = HorizonColors.AccentSecondary,
    background = HorizonColors.Background,
    surface = HorizonColors.Surface,
    surfaceVariant = HorizonColors.SurfaceElevated,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = HorizonColors.TextMuted
)

@Composable
fun HorizonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HorizonDarkScheme,
        content = content
    )
}
