package dev.horizon.launcher.game.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.movtery.layer_controller.event.ClickEvent
import com.movtery.layer_controller.observable.ObservableControlLayer

@Composable
fun SectionHeader(title: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String = { "%.2f".format(it) },
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text("$label · ${format(value)}", style = MaterialTheme.typography.bodySmall)
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

/** Single-choice chips in a horizontally scrolling row (no experimental FlowRow needed). */
@Composable
fun <T> ChipChoice(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

/** Small "−  value  +" nudge row used for pixel-precise position and size tweaks. */
// ---------------------------------------------------------------------------------------------
// Colour picker
// ---------------------------------------------------------------------------------------------

private val SWATCHES = listOf(
    Color.White, Color(0xFFBDBDBD), Color(0xFF424242), Color.Black,
    Color(0xFFEF5350), Color(0xFFFF9800), Color(0xFFFFEB3B), Color(0xFF66BB6A),
    Color(0xFF26C6DA), Color(0xFF42A5F5), Color(0xFF7E57C2), Color(0xFFEC407A)
)

fun Color.toHex(): String = "#%08X".format(toArgb())

fun parseColorOrNull(text: String): Color? = runCatching {
    val clean = text.trim().let { if (it.startsWith("#")) it else "#$it" }
    Color(android.graphics.Color.parseColor(clean))
}.getOrNull()

/** Colour field: swatch + hex entry + RGBA sliders + quick swatches. Alpha is part of the colour. */
@Composable
fun ColorField(label: String, color: Color, onChange: (Color) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Swatch drawn above a mid-grey so transparency stays visible.
        Box(Modifier.size(28.dp)) {
            Box(Modifier.matchParentSize().background(Color(0xFF808080), RoundedCornerShape(6.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)))
            Box(Modifier.matchParentSize().background(color, RoundedCornerShape(6.dp)))
        }
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(color.toHex(), style = MaterialTheme.typography.bodySmall)
    }
    if (open) {
        Column(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp)) {
            // The control overlay window is not focusable, so typed input must happen in a dialog.
            var hexDialog by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { hexDialog = true }) { Text("Enter hex code") }
            if (hexDialog) TextEntryDialog("Colour (#AARRGGBB or #RRGGBB)", initial = color.toHex(), label = "Hex",
                onDone = { text -> parseColorOrNull(text)?.let(onChange); hexDialog = false },
                onDismiss = { hexDialog = false })
            @Composable fun channel(name: String, value: Float, rebuild: (Float) -> Color) =
                LabeledSlider(name, value * 255f, 0f..255f, { it.toInt().toString() }) { onChange(rebuild(it / 255f)) }
            channel("Red", color.red) { Color(it, color.green, color.blue, color.alpha) }
            channel("Green", color.green) { Color(color.red, it, color.blue, color.alpha) }
            channel("Blue", color.blue) { Color(color.red, color.green, it, color.alpha) }
            channel("Opacity", color.alpha) { Color(color.red, color.green, color.blue, it) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SWATCHES.forEach { swatch ->
                    Box(Modifier.size(30.dp).background(swatch.copy(alpha = color.alpha), RoundedCornerShape(6.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                        .clickable { onChange(swatch.copy(alpha = color.alpha)) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Key picker + event list editor
// ---------------------------------------------------------------------------------------------

/** Searchable picker over [KeyCatalog]. Returns the GLFW id string. */
@Composable
fun KeyPickerDialog(title: String = "Choose key or mouse button", onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Search") }, modifier = Modifier.fillMaxWidth())
                ChipChoice(listOf<String?>(null) + KeyCatalog.groups, group, { it ?: "All" }) { group = it }
                Column(Modifier.height(260.dp).verticalScroll(rememberScrollState())) {
                    KeyCatalog.all
                        .filter { group == null || it.group == group }
                        .filter { query.isBlank() || it.label.contains(query, true) || it.id.contains(query, true) }
                        .forEach { option ->
                            Text(option.label, modifier = Modifier.fillMaxWidth().clickable { onPick(option.id) }.padding(vertical = 10.dp),
                                style = MaterialTheme.typography.bodyLarge)
                        }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun TextEntryDialog(title: String, initial: String = "", label: String, confirm: String = "OK", onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onDone(text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun describe(event: ClickEvent, layers: List<ObservableControlLayer>): String = when (event.type) {
    ClickEvent.Type.Key -> KeyCatalog.label(event.key)
    ClickEvent.Type.SendText -> "Chat: ${event.key.take(18)}"
    ClickEvent.Type.SwitchLayer -> "Toggle layer ${layers.find { it.uuid == event.key }?.name ?: "?"}"
    ClickEvent.Type.ShowLayer -> "Show layer ${layers.find { it.uuid == event.key }?.name ?: "?"}"
    ClickEvent.Type.HideLayer -> "Hide layer ${layers.find { it.uuid == event.key }?.name ?: "?"}"
    ClickEvent.Type.LauncherEvent -> "Launcher: ${event.key}"
}

/**
 * Editable list of bindings: tap a chip's × to remove it; add keys, chat messages and layer
 * show/hide/toggle actions. Used for buttons, every joystick direction and joystick lock.
 */
@Composable
fun EventListEditor(
    events: List<ClickEvent>,
    layers: List<ObservableControlLayer>,
    allowChat: Boolean = false, // Horizon's Android bridge cannot type text into the game yet
    allowLayers: Boolean = true,
    onChange: (List<ClickEvent>) -> Unit
) {
    var pickKey by remember { mutableStateOf(false) }
    var chat by remember { mutableStateOf(false) }
    var layerAction by remember { mutableStateOf<ClickEvent.Type?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (events.isEmpty()) Text("No bindings", style = MaterialTheme.typography.bodySmall)
        events.forEach { event ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(describe(event, layers), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onChange(events - event) }) { Text("✕") }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { pickKey = true }) { Text("+ Key") }
            if (allowChat) OutlinedButton(onClick = { chat = true }) { Text("+ Chat") }
            if (allowLayers) {
                OutlinedButton(onClick = { layerAction = ClickEvent.Type.SwitchLayer }) { Text("+ Toggle layer") }
                OutlinedButton(onClick = { layerAction = ClickEvent.Type.ShowLayer }) { Text("+ Show layer") }
                OutlinedButton(onClick = { layerAction = ClickEvent.Type.HideLayer }) { Text("+ Hide layer") }
            }
        }
    }
    if (pickKey) KeyPickerDialog(onPick = { id ->
        pickKey = false
        if (events.none { it.type == ClickEvent.Type.Key && it.key == id }) onChange(events + ClickEvent(ClickEvent.Type.Key, id))
    }, onDismiss = { pickKey = false })
    if (chat) TextEntryDialog("Send chat message or command", label = "Text (e.g. /home)", confirm = "Add",
        onDone = { text ->
            chat = false
            // LayerController keeps only one valid SendText event per widget.
            onChange(events.filterNot { it.type == ClickEvent.Type.SendText } + ClickEvent(ClickEvent.Type.SendText, text))
        }, onDismiss = { chat = false })
    layerAction?.let { type ->
        AlertDialog(
            onDismissRequest = { layerAction = null },
            title = { Text("Choose layer") },
            text = {
                Column(Modifier.height(220.dp).verticalScroll(rememberScrollState())) {
                    if (layers.isEmpty()) Text("No layers")
                    layers.forEach { layer ->
                        Text(layer.name.ifBlank { "(unnamed layer)" }, modifier = Modifier.fillMaxWidth().clickable {
                            layerAction = null
                            onChange(events + ClickEvent(type, layer.uuid))
                        }.padding(vertical = 10.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { layerAction = null }) { Text("Cancel") } }
        )
    }
}
