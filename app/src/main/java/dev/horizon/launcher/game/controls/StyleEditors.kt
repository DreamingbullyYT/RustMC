package dev.horizon.launcher.game.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.movtery.layer_controller.data.ButtonShape
import com.movtery.layer_controller.data.ButtonStyle
import com.movtery.layer_controller.data.DefaultButtonStyleConfig
import com.movtery.layer_controller.data.DefaultJoystickStyleConfig
import com.movtery.layer_controller.data.JoystickStyle
import com.movtery.layer_controller.data.createNewButtonStyle
import com.movtery.layer_controller.data.createNewJoystickStyle
import com.movtery.layer_controller.data.toAndroidShape
import com.movtery.layer_controller.observable.ObservableButtonStyle
import com.movtery.layer_controller.observable.ObservableJoystickStyle
import com.movtery.layer_controller.observable.ObservableStyleConfig

/** Ready-made looks so a new style doesn't start from flat black. */
object StylePresets {
    private fun button(name: String, cfg: ButtonStyle.StyleConfig, animate: Boolean = true): ButtonStyle =
        createNewButtonStyle(name).copy(animateSwap = animate, commonStyle = true, lightStyle = cfg, darkStyle = cfg)

    val buttons: List<Pair<String, () -> ButtonStyle>> = listOf(
        "Glass" to { button("Glass", DefaultButtonStyleConfig.copy(
            backgroundColor = Color.White.copy(alpha = 0.18f), pressedBackgroundColor = Color.White.copy(alpha = 0.42f),
            borderWidth = 2, pressedBorderWidth = 2, borderColor = Color.White.copy(alpha = 0.55f), pressedBorderColor = Color.White,
            borderRadius = ButtonShape(18f), pressedBorderRadius = ButtonShape(18f))) },
        "Solid" to { button("Solid", DefaultButtonStyleConfig.copy(
            backgroundColor = Color(0xFF263238), pressedBackgroundColor = Color(0xFF42A5F5),
            borderRadius = ButtonShape(12f), pressedBorderRadius = ButtonShape(12f))) },
        "Outline" to { button("Outline", DefaultButtonStyleConfig.copy(
            backgroundColor = Color.Transparent, pressedBackgroundColor = Color.White.copy(alpha = 0.3f),
            borderWidth = 3, pressedBorderWidth = 3, borderColor = Color.White, pressedBorderColor = Color(0xFF66BB6A),
            borderRadius = ButtonShape(50f), pressedBorderRadius = ButtonShape(50f))) },
        "Pixel" to { button("Pixel", DefaultButtonStyleConfig.copy(
            backgroundColor = Color(0xFF6D6D6D), pressedBackgroundColor = Color(0xFF9E9E9E),
            borderWidth = 4, pressedBorderWidth = 4, borderColor = Color.Black, pressedBorderColor = Color.Black,
            borderRadius = ButtonShape(0f), pressedBorderRadius = ButtonShape(0f)), animate = false) },
        "Neon" to { button("Neon", DefaultButtonStyleConfig.copy(
            backgroundColor = Color(0x6600E5FF), pressedBackgroundColor = Color(0xCC00E5FF),
            contentColor = Color(0xFF00E5FF), pressedContentColor = Color.Black,
            borderWidth = 2, pressedBorderWidth = 2, borderColor = Color(0xFF00E5FF), pressedBorderColor = Color(0xFFFFFFFF),
            borderRadius = ButtonShape(10f), pressedBorderRadius = ButtonShape(10f))) },
        "Minimal" to { button("Minimal", DefaultButtonStyleConfig.copy(
            backgroundColor = Color.Black.copy(alpha = 0.25f), pressedBackgroundColor = Color.Black.copy(alpha = 0.6f),
            alpha = 0.8f, borderRadius = ButtonShape(6f), pressedBorderRadius = ButtonShape(6f)))
        }
    )

    private fun stick(name: String, cfg: JoystickStyle.StyleConfig): JoystickStyle =
        createNewJoystickStyle(name).copy(commonStyle = true, lightStyle = cfg, darkStyle = cfg)

    val joysticks: List<Pair<String, () -> JoystickStyle>> = listOf(
        "Classic" to { stick("Classic", DefaultJoystickStyleConfig) },
        "Ring" to { stick("Ring", DefaultJoystickStyleConfig.copy(
            backgroundColor = Color.Transparent, borderWidthRatio = 4, borderColor = Color.White.copy(alpha = 0.7f),
            joystickColor = Color.White.copy(alpha = 0.7f), joystickSize = 0.38f)) },
        "Square" to { stick("Square", DefaultJoystickStyleConfig.copy(
            backgroundShape = 12, joystickShape = 12, backgroundColor = Color.Black.copy(alpha = 0.4f),
            joystickColor = Color(0xFF42A5F5).copy(alpha = 0.8f), joystickSize = 0.45f)) },
        "Subtle" to { stick("Subtle", DefaultJoystickStyleConfig.copy(
            alpha = 0.55f, backgroundColor = Color.White.copy(alpha = 0.1f), joystickColor = Color.White.copy(alpha = 0.6f), joystickSize = 0.35f)) }
    )
}

@Composable
private fun StyleDialogFrame(title: String, onClose: () -> Unit, preview: @Composable () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f), shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Button(onClick = onClose) { Text("Done") }
                }
                Box(Modifier.fillMaxWidth().height(96.dp).background(Color(0xFF3B6B3B), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center) { preview() }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Button styles
// -------------------------------------------------------------------------------------------

@Composable
private fun ButtonPreview(cfg: ObservableStyleConfig, pressed: Boolean, label: String) {
    val shape = (if (pressed) cfg.pressedBorderRadius else cfg.borderRadius).toAndroidShape()
    val border = if (pressed) cfg.pressedBorderWidth else cfg.borderWidth
    val size = (if (pressed) cfg.pressedFontSize else cfg.fontSize) ?: 14
    Box(
        Modifier.width(78.dp).height(56.dp)
            .alpha(if (pressed) cfg.pressedAlpha else cfg.alpha)
            .background(if (pressed) cfg.pressedBackgroundColor else cfg.backgroundColor, shape)
            .border(border.dp, if (pressed) cfg.pressedBorderColor else cfg.borderColor, shape),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (pressed) cfg.pressedContentColor else cfg.contentColor, fontSize = size.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ShapeEditor(label: String, shape: ButtonShape, onChange: (ButtonShape) -> Unit) {
    var corners by remember { mutableStateOf(false) }
    LabeledSlider("$label corner radius", shape.topStart, 0f..100f, { it.toInt().toString() }) { onChange(ButtonShape(it)) }
    ToggleRow("Different corners", corners) { corners = it }
    if (corners) {
        LabeledSlider("Top-left", shape.topStart, 0f..100f, { it.toInt().toString() }) { onChange(shape.copy(topStart = it)) }
        LabeledSlider("Top-right", shape.topEnd, 0f..100f, { it.toInt().toString() }) { onChange(shape.copy(topEnd = it)) }
        LabeledSlider("Bottom-right", shape.bottomEnd, 0f..100f, { it.toInt().toString() }) { onChange(shape.copy(bottomEnd = it)) }
        LabeledSlider("Bottom-left", shape.bottomStart, 0f..100f, { it.toInt().toString() }) { onChange(shape.copy(bottomStart = it)) }
    }
}

/** Full editor for a reusable button style (idle + pressed state). */
@Composable
fun ButtonStyleEditorDialog(style: ObservableButtonStyle, previewLabel: String = "Jump", onClose: () -> Unit) {
    // The game always renders with isDark = true: the light style applies to both themes when
    // commonStyle is set, otherwise the dark style is the one actually shown.
    val c = if (style.commonStyle) style.lightStyle else style.darkStyle
    StyleDialogFrame("Edit button style", onClose, preview = {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { ButtonPreview(c, false, previewLabel); Text("Idle", style = MaterialTheme.typography.labelSmall, color = Color.White) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { ButtonPreview(c, true, previewLabel); Text("Pressed", style = MaterialTheme.typography.labelSmall, color = Color.White) }
        }
    }) {
        var nameDialog by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Name: ${style.name}", modifier = Modifier.weight(1f))
            TextButton(onClick = { nameDialog = true }) { Text("Rename") }
        }
        if (nameDialog) TextEntryDialog("Style name", initial = style.name, label = "Name", onDone = { style.name = it; nameDialog = false }, onDismiss = { nameDialog = false })
        ToggleRow("Animate between idle and pressed", style.animateSwap) { style.animateSwap = it }

        SectionHeader("Idle")
        ColorField("Background", c.backgroundColor) { c.backgroundColor = it }
        ColorField("Text / icon", c.contentColor) { c.contentColor = it }
        ColorField("Border", c.borderColor) { c.borderColor = it }
        LabeledSlider("Border width", c.borderWidth.toFloat(), 0f..50f, { it.toInt().toString() }) { c.borderWidth = it.toInt() }
        ShapeEditor("Idle", c.borderRadius) { c.borderRadius = it }
        LabeledSlider("Opacity", c.alpha, 0f..1f, { "${(it * 100).toInt()}%" }) { c.alpha = it }
        ToggleRow("Automatic text size", c.fontSize == null) { c.fontSize = if (it) null else 14 }
        c.fontSize?.let { size -> LabeledSlider("Text size", size.toFloat(), 2f..30f, { it.toInt().toString() }) { c.fontSize = it.toInt() } }

        SectionHeader("Pressed")
        ColorField("Background", c.pressedBackgroundColor) { c.pressedBackgroundColor = it }
        ColorField("Text / icon", c.pressedContentColor) { c.pressedContentColor = it }
        ColorField("Border", c.pressedBorderColor) { c.pressedBorderColor = it }
        LabeledSlider("Border width", c.pressedBorderWidth.toFloat(), 0f..50f, { it.toInt().toString() }) { c.pressedBorderWidth = it.toInt() }
        ShapeEditor("Pressed", c.pressedBorderRadius) { c.pressedBorderRadius = it }
        LabeledSlider("Opacity", c.pressedAlpha, 0f..1f, { "${(it * 100).toInt()}%" }) { c.pressedAlpha = it }
        ToggleRow("Automatic text size", c.pressedFontSize == null) { c.pressedFontSize = if (it) null else 14 }
        c.pressedFontSize?.let { size -> LabeledSlider("Text size", size.toFloat(), 2f..30f, { it.toInt().toString() }) { c.pressedFontSize = it.toInt() } }
    }
}

// -------------------------------------------------------------------------------------------
// Joystick styles
// -------------------------------------------------------------------------------------------

@Composable
fun JoystickStyleEditorDialog(style: ObservableJoystickStyle, onClose: () -> Unit) {
    val c = if (style.commonStyle) style.lightStyle else style.darkStyle
    StyleDialogFrame("Edit joystick style", onClose, preview = {
        val bgShape = RoundedCornerShape(c.backgroundShape.coerceIn(0, 50))
        val knobShape = RoundedCornerShape(c.joystickShape.coerceIn(0, 50))
        Box(Modifier.height(80.dp).width(80.dp).alpha(c.alpha).background(c.backgroundColor, bgShape)
            .border((c.borderWidthRatio / 4).dp, c.borderColor, bgShape), contentAlignment = Alignment.Center) {
            Box(Modifier.height((80 * c.joystickSize).dp).width((80 * c.joystickSize).dp).background(c.joystickColor, knobShape))
        }
    }) {
        var nameDialog by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Name: ${style.name}", modifier = Modifier.weight(1f))
            TextButton(onClick = { nameDialog = true }) { Text("Rename") }
        }
        if (nameDialog) TextEntryDialog("Style name", initial = style.name, label = "Name", onDone = { style.name = it; nameDialog = false }, onDismiss = { nameDialog = false })
        ColorField("Base", c.backgroundColor) { c.backgroundColor = it }
        ColorField("Stick", c.joystickColor) { c.joystickColor = it }
        ColorField("Stick (can lock)", c.joystickCanLockColor) { c.joystickCanLockColor = it }
        ColorField("Stick (locked)", c.joystickLockedColor) { c.joystickLockedColor = it }
        ColorField("Lock mark", c.lockMarkColor) { c.lockMarkColor = it }
        ColorField("Border", c.borderColor) { c.borderColor = it }
        LabeledSlider("Border thickness", c.borderWidthRatio.toFloat(), 0f..50f, { it.toInt().toString() }) { c.borderWidthRatio = it.toInt() }
        LabeledSlider("Base shape (0 square – 50 circle)", c.backgroundShape.toFloat(), 0f..50f, { it.toInt().toString() }) { c.backgroundShape = it.toInt() }
        LabeledSlider("Stick shape (0 square – 50 circle)", c.joystickShape.toFloat(), 0f..50f, { it.toInt().toString() }) { c.joystickShape = it.toInt() }
        LabeledSlider("Stick size", c.joystickSize, 0.1f..1f, { "${(it * 100).toInt()}%" }) { c.joystickSize = it }
        LabeledSlider("Opacity", c.alpha, 0f..1f, { "${(it * 100).toInt()}%" }) { c.alpha = it }
    }
}
