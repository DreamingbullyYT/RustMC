package com.movtery.zalithlauncher.ui.components
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
@Composable fun rememberBoxSize(): IntSize {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    return IntSize(
        with(density) { (configuration.screenWidthDp.dp.toPx()).roundToInt() },
        with(density) { (configuration.screenHeightDp.dp.toPx()).roundToInt() }
    )
}
