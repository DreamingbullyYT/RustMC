package dev.horizon.launcher.game.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movtery.layer_controller.data.ButtonPosition
import com.movtery.layer_controller.data.ButtonSize
import com.movtery.layer_controller.data.JoystickDirection
import com.movtery.layer_controller.data.JoystickTriggerMode
import com.movtery.layer_controller.data.TextAlignment
import com.movtery.layer_controller.data.VisibilityType
import com.movtery.layer_controller.observable.ObservableControlLayer
import com.movtery.layer_controller.observable.ObservableControlLayout
import com.movtery.layer_controller.observable.ObservableJoystickData
import com.movtery.layer_controller.observable.ObservableNormalData
import com.movtery.layer_controller.observable.ObservableTextData
import com.movtery.layer_controller.observable.ObservableWidget

private fun visibilityLabel(v: VisibilityType) = when (v) {
    VisibilityType.ALWAYS -> "Always"
    VisibilityType.IN_GAME -> "In game"
    VisibilityType.IN_MENU -> "In menus"
}

private fun directionLabel(d: JoystickDirection) = d.name.replace(Regex("([a-z])([A-Z])"), "$1 $2")

/** Position sliders + 0.1 % nudge buttons shared by every widget type. */
@Composable
private fun PositionEditor(position: ButtonPosition, onChange: (ButtonPosition) -> Unit) {
    fun pct(v: Int) = "%.1f%%".format(v / 100f)
    LabeledSlider("Horizontal position", position.x.toFloat(), 0f..10000f, { pct(it.toInt()) }) { onChange(position.copy(x = it.toInt())) }
    LabeledSlider("Vertical position", position.y.toFloat(), 0f..10000f, { pct(it.toInt()) }) { onChange(position.copy(y = it.toInt())) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { onChange(position.copy(x = (position.x - 10).coerceAtLeast(0))) }) { Text("←") }
        OutlinedButton(onClick = { onChange(position.copy(x = (position.x + 10).coerceAtMost(10000))) }) { Text("→") }
        OutlinedButton(onClick = { onChange(position.copy(y = (position.y - 10).coerceAtLeast(0))) }) { Text("↑") }
        OutlinedButton(onClick = { onChange(position.copy(y = (position.y + 10).coerceAtMost(10000))) }) { Text("↓") }
        OutlinedButton(onClick = { onChange(ButtonPosition(5000, 5000)) }) { Text("Center") }
    }
}

/** Size editor for percentage / dp sized rectangular widgets (buttons, labels). */
@Composable
private fun SizeEditor(size: ButtonSize, onChange: (ButtonSize) -> Unit) {
    var square by remember(size) { mutableStateOf(size.widthPercentage == size.heightPercentage && size.widthDp == size.heightDp) }
    ChipChoice(ButtonSize.Type.values().toList(), size.type, { when (it) {
        ButtonSize.Type.Dp -> "Fixed (dp)"; ButtonSize.Type.Percentage -> "Screen %"; ButtonSize.Type.WrapContent -> "Fit text" } }) { onChange(size.copy(type = it)) }
    when (size.type) {
        ButtonSize.Type.Percentage -> {
            ChipChoice(ButtonSize.Reference.values().toList(), size.widthReference,
                { if (it == ButtonSize.Reference.ScreenWidth) "Relative to width" else "Relative to height" }) {
                onChange(size.copy(widthReference = it, heightReference = it))
            }
            ToggleRow("Keep square", square) { square = it; if (it) onChange(size.copy(heightPercentage = size.widthPercentage)) }
            LabeledSlider("Width", size.widthPercentage.toFloat(), 100f..10000f, { "%.1f%%".format(it / 100f) }) {
                val v = it.toInt(); onChange(if (square) size.copy(widthPercentage = v, heightPercentage = v) else size.copy(widthPercentage = v))
            }
            if (!square) LabeledSlider("Height", size.heightPercentage.toFloat(), 100f..10000f, { "%.1f%%".format(it / 100f) }) { onChange(size.copy(heightPercentage = it.toInt())) }
        }
        ButtonSize.Type.Dp -> {
            ToggleRow("Keep square", square) { square = it; if (it) onChange(size.copy(heightDp = size.widthDp)) }
            LabeledSlider("Width", size.widthDp, 5f..400f, { "${it.toInt()} dp" }) {
                onChange(if (square) size.copy(widthDp = it, heightDp = it) else size.copy(widthDp = it))
            }
            if (!square) LabeledSlider("Height", size.heightDp, 5f..400f, { "${it.toInt()} dp" }) { onChange(size.copy(heightDp = it)) }
        }
        ButtonSize.Type.WrapContent -> Text("Size follows the label text.", style = MaterialTheme.typography.bodySmall)
    }
}

/** Style picker shared by buttons, labels and joysticks. */
@Composable
private fun StyleRow(
    names: List<Pair<String, String>>,   // uuid -> display name
    currentId: String?,
    onPick: (String?) -> Unit,
    onEdit: () -> Unit,
    onNewFromPreset: (String) -> Unit,
    presetNames: List<String>
) {
    ChipChoice(listOf<String?>(null) + names.map { it.first }, currentId,
        { id -> if (id == null) "Default" else names.firstOrNull { it.first == id }?.second ?: "Style" }) { onPick(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (currentId != null) Button(onClick = onEdit) { Text("Edit style") }
    }
    Text("New style from preset", style = MaterialTheme.typography.bodySmall)
    ChipChoice(presetNames, null, { it }) { onNewFromPreset(it) }
}

/**
 * Everything editable about one selected widget. Works directly on the observable models so the
 * canvas updates live. [recordHistory] is called before destructive edits (delete/duplicate).
 */
@Composable
fun WidgetInspector(
    widget: ObservableWidget,
    layout: ObservableControlLayout,
    layer: ObservableControlLayer?,
    recordHistory: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val layers by layout.layers.collectAsState()
    val buttonStyles by layout.styles.collectAsState()
    val joystickStyles by layout.joystickStyles.collectAsState()
    var labelDialog by remember(widget) { mutableStateOf(false) }
    var editButtonStyle by remember(widget) { mutableStateOf<String?>(null) }
    var editJoystickStyle by remember(widget) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(widget) { mutableStateOf(false) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(when (widget) {
                is ObservableNormalData -> "Button"
                is ObservableJoystickData -> "Joystick"
                is ObservableTextData -> "Label"
                else -> "Widget"
            }, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose) { Text("Close") }
        }

        when (widget) {
            is ObservableNormalData -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Label: ${widget.text.default.ifBlank { "(empty)" }}", modifier = Modifier.weight(1f))
                    TextButton(onClick = { labelDialog = true }) { Text("Edit") }
                }
                if (labelDialog) TextEntryDialog("Button label", widget.text.default, "Label", onDone = { widget.text.default = it; labelDialog = false }, onDismiss = { labelDialog = false })

                SectionHeader("Bindings (pressed together)")
                EventListEditor(widget.clickEvents, layers) { widget.clickEvents = it }

                SectionHeader("Behaviour")
                ToggleRow("Toggle (tap on / tap off)", widget.isToggleable) { widget.isToggleable = it }
                ToggleRow("Slide into neighbouring buttons", widget.isSwipple) { widget.isSwipple = it }
                ToggleRow("Let touches pass through", widget.isPenetrable) { widget.isPenetrable = it }
                Text("Visible", style = MaterialTheme.typography.bodySmall)
                ChipChoice(VisibilityType.values().toList(), widget.visibilityType, ::visibilityLabel) { widget.visibilityType = it }

                SectionHeader("Size")
                SizeEditor(widget.buttonSize) { widget.buttonSize = it }
                SectionHeader("Position")
                PositionEditor(widget.position) { widget.position = it }

                SectionHeader("Text")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = widget.textBold, onClick = { widget.textBold = !widget.textBold }, label = { Text("Bold") })
                    FilterChip(selected = widget.textItalic, onClick = { widget.textItalic = !widget.textItalic }, label = { Text("Italic") })
                    FilterChip(selected = widget.textUnderline, onClick = { widget.textUnderline = !widget.textUnderline }, label = { Text("Underline") })
                }
                ChipChoice(TextAlignment.values().toList(), widget.textAlignment, { it.name }) { widget.textAlignment = it }

                SectionHeader("Look")
                StyleRow(buttonStyles.map { it.uuid to it.name }, widget.buttonStyle,
                    onPick = { widget.buttonStyle = it }, onEdit = { editButtonStyle = widget.buttonStyle },
                    presetNames = StylePresets.buttons.map { it.first },
                    onNewFromPreset = { preset ->
                        StylePresets.buttons.firstOrNull { it.first == preset }?.second?.invoke()?.let { style ->
                            layout.addStyle(style); widget.buttonStyle = style.uuid
                        }
                    })
            }

            is ObservableJoystickData -> {
                SectionHeader("Size")
                ChipChoice(listOf(ButtonSize.Type.Percentage, ButtonSize.Type.Dp), widget.sizeType,
                    { if (it == ButtonSize.Type.Dp) "Fixed (dp)" else "Screen %" }) { widget.sizeType = it }
                if (widget.sizeType == ButtonSize.Type.Dp) LabeledSlider("Diameter", widget.sizeDp, 20f..500f, { "${it.toInt()} dp" }) { widget.sizeDp = it }
                else LabeledSlider("Diameter", widget.sizePercentage.toFloat(), 2000f..10000f, { "%.0f%%".format(it / 100f) }) { widget.sizePercentage = it.toInt() }
                SectionHeader("Position")
                PositionEditor(widget.position) { widget.position = it }

                SectionHeader("Feel")
                Text("Control", style = MaterialTheme.typography.bodySmall)
                ChipChoice(JoystickTriggerMode.values().toList(), widget.triggerMode,
                    { if (it == JoystickTriggerMode.DRAG) "Drag the stick" else "Touch anywhere on base" }) { widget.triggerMode = it }
                LabeledSlider("Dead zone", widget.deadZoneRatio, 0f..0.9f, { "${(it * 100).toInt()}%" }) { widget.deadZoneRatio = it }
                ToggleRow("Sprint lock (push forward to lock)", widget.canLock) { widget.canLock = it }
                if (widget.canLock) LabeledSlider("Lock distance", widget.lockThreshold, 0f..1f, { "${(it * 100).toInt()}%" }) { widget.lockThreshold = it }
                Text("Visible", style = MaterialTheme.typography.bodySmall)
                ChipChoice(VisibilityType.values().toList(), widget.visibilityType, ::visibilityLabel) { widget.visibilityType = it }

                SectionHeader("Direction bindings")
                var expanded by remember(widget) { mutableStateOf<JoystickDirection?>(null) }
                JoystickDirection.values().filter { it != JoystickDirection.None }.forEach { dir ->
                    val events = widget.directionEvents[dir] ?: emptyList()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${directionLabel(dir)}: ${events.joinToString(" + ") { KeyCatalog.label(it.key) }.ifBlank { "—" }}",
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { expanded = if (expanded == dir) null else dir }) { Text(if (expanded == dir) "Hide" else "Edit") }
                    }
                    if (expanded == dir) EventListEditor(events, layers, allowChat = false, allowLayers = false) {
                        widget.directionEvents = widget.directionEvents + (dir to it)
                    }
                }
                OutlinedButton(onClick = {
                    // Arrow keys instead of WASD, handy for custom keybinds / menus.
                    fun key(id: String) = listOf(com.movtery.layer_controller.event.ClickEvent(com.movtery.layer_controller.event.ClickEvent.Type.Key, id))
                    val up = key("GLFW_KEY_UP"); val down = key("GLFW_KEY_DOWN"); val left = key("GLFW_KEY_LEFT"); val right = key("GLFW_KEY_RIGHT")
                    widget.directionEvents = mapOf(
                        JoystickDirection.North to up, JoystickDirection.South to down, JoystickDirection.West to left, JoystickDirection.East to right,
                        JoystickDirection.NorthEast to up + right, JoystickDirection.NorthWest to up + left,
                        JoystickDirection.SouthEast to down + right, JoystickDirection.SouthWest to down + left)
                }) { Text("Use arrow keys") }

                SectionHeader("Sprint-lock bindings")
                EventListEditor(widget.lockEvents, layers, allowChat = false, allowLayers = false) { widget.lockEvents = it }

                SectionHeader("Look")
                StyleRow(joystickStyles.map { it.uuid to it.name }, widget.joystickStyleId,
                    onPick = { widget.joystickStyleId = it }, onEdit = { editJoystickStyle = widget.joystickStyleId },
                    presetNames = StylePresets.joysticks.map { it.first },
                    onNewFromPreset = { preset ->
                        StylePresets.joysticks.firstOrNull { it.first == preset }?.second?.invoke()?.let { style ->
                            layout.addJoystickStyle(style); widget.joystickStyleId = style.uuid
                        }
                    })
            }

            is ObservableTextData -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Text: ${widget.text.default.ifBlank { "(empty)" }}", modifier = Modifier.weight(1f))
                    TextButton(onClick = { labelDialog = true }) { Text("Edit") }
                }
                if (labelDialog) TextEntryDialog("Label text", widget.text.default, "Text", onDone = { widget.text.default = it; labelDialog = false }, onDismiss = { labelDialog = false })
                Text("Visible", style = MaterialTheme.typography.bodySmall)
                ChipChoice(VisibilityType.values().toList(), widget.visibilityType, ::visibilityLabel) { widget.visibilityType = it }
                SectionHeader("Size")
                SizeEditor(widget.buttonSize) { widget.buttonSize = it }
                SectionHeader("Position")
                PositionEditor(widget.position) { widget.position = it }
                ChipChoice(TextAlignment.values().toList(), widget.textAlignment, { it.name }) { widget.textAlignment = it }
                SectionHeader("Look")
                StyleRow(buttonStyles.map { it.uuid to it.name }, widget.buttonStyle,
                    onPick = { widget.buttonStyle = it }, onEdit = { editButtonStyle = widget.buttonStyle },
                    presetNames = StylePresets.buttons.map { it.first },
                    onNewFromPreset = { preset ->
                        StylePresets.buttons.firstOrNull { it.first == preset }?.second?.invoke()?.let { style ->
                            layout.addStyle(style); widget.buttonStyle = style.uuid
                        }
                    })
            }
            else -> Text("This widget type has no editable properties.")
        }

        SectionHeader("Actions")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (widget is ObservableNormalData || widget is ObservableJoystickData) OutlinedButton(onClick = { recordHistory(); onDuplicate() }) { Text("Duplicate") }
            Button(onClick = { confirmDelete = true }) { Text("Delete") }
        }
        if (confirmDelete) androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false }, title = { Text("Delete this control?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; recordHistory(); onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    }

    editButtonStyle?.let { id -> buttonStyles.firstOrNull { it.uuid == id }?.let { style ->
        ButtonStyleEditorDialog(style, previewLabel = (widget as? ObservableNormalData)?.text?.default?.ifBlank { "Key" } ?: "Key") { editButtonStyle = null }
    } }
    editJoystickStyle?.let { id -> joystickStyles.firstOrNull { it.uuid == id }?.let { style ->
        JoystickStyleEditorDialog(style) { editJoystickStyle = null }
    } }
}
