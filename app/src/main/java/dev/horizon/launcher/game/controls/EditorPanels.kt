package dev.horizon.launcher.game.controls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.movtery.layer_controller.data.VisibilityType
import com.movtery.layer_controller.layout.ControlLayout
import com.movtery.layer_controller.layout.createNewLayer
import com.movtery.layer_controller.observable.ObservableButtonStyle
import com.movtery.layer_controller.observable.ObservableControlLayer
import com.movtery.layer_controller.observable.ObservableControlLayout
import com.movtery.layer_controller.observable.ObservableJoystickStyle

private fun visibilityLabel(v: VisibilityType) = when (v) {
    VisibilityType.ALWAYS -> "Always"
    VisibilityType.IN_GAME -> "In game"
    VisibilityType.IN_MENU -> "In menus"
}

// -------------------------------------------------------------------------------------------
// Layers
// -------------------------------------------------------------------------------------------

/**
 * Layer manager. New buttons are added to the selected layer. A layer can start hidden and be
 * revealed by a button (e.g. a "Building" layer toggled from a small corner button), and can
 * auto-hide while a mouse or gamepad is in use.
 */
@Composable
fun LayerPanel(
    layout: ObservableControlLayout,
    selected: ObservableControlLayer?,
    onSelect: (ObservableControlLayer?) -> Unit,
    recordHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val layers by layout.layers.collectAsState()
    var renaming by remember { mutableStateOf<ObservableControlLayer?>(null) }
    var deleting by remember { mutableStateOf<ObservableControlLayer?>(null) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Layers", style = MaterialTheme.typography.titleMedium)
        Text("Tap a layer to add new controls to it. The top layer is drawn above the others.", style = MaterialTheme.typography.bodySmall)
        layers.forEachIndexed { index, layer ->
            val isSelected = selected?.uuid == layer.uuid
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().clickable { onSelect(layer) }, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text((if (isSelected) "● " else "○ ") + layer.name.ifBlank { "Layer ${layers.size - index}" },
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text("${layer.normalButtons.collectAsState().value.size + layer.joystickButtons.collectAsState().value.size} controls",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (isSelected) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { renaming = layer }) { Text("Rename") }
                        OutlinedButton(enabled = index > 0, onClick = { recordHistory(); layout.reorder(index, index - 1) }) { Text("↑") }
                        OutlinedButton(enabled = index < layers.size - 1, onClick = { recordHistory(); layout.reorder(index, index + 1) }) { Text("↓") }
                        OutlinedButton(enabled = index < layers.size - 1, onClick = { recordHistory(); layout.mergeDownward(layer) }) { Text("Merge ↓") }
                        OutlinedButton(enabled = layers.size > 1, onClick = { deleting = layer }) { Text("Delete") }
                    }
                    ToggleRow("Hidden at start (show it with a button)", layer.editorHide) { layer.editorHide = it; layer.hide = it }
                    ToggleRow("Hide while using a mouse", layer.hideWhenMouse) { layer.hideWhenMouse = it }
                    ToggleRow("Hide while using a gamepad", layer.hideWhenGamepad) { layer.hideWhenGamepad = it }
                    Text("Layer visible", style = MaterialTheme.typography.bodySmall)
                    ChipChoice(VisibilityType.values().toList(), layer.visibilityType, ::visibilityLabel) { layer.visibilityType = it }
                }
            }
        }
        Button(onClick = {
            recordHistory()
            onSelect(layout.addLayer(createNewLayer("Layer ${layers.size + 1}")))
        }) { Text("+ New layer") }
    }

    renaming?.let { layer ->
        TextEntryDialog("Rename layer", layer.name, "Name", onDone = { layer.name = it; renaming = null }, onDismiss = { renaming = null })
    }
    deleting?.let { layer ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete layer \"${layer.name}\" and its controls?") },
            confirmButton = { TextButton(onClick = {
                recordHistory(); layout.removeLayer(layer.uuid)
                if (selected?.uuid == layer.uuid) onSelect(layout.layers.value.firstOrNull())
                deleting = null
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

// -------------------------------------------------------------------------------------------
// Styles library
// -------------------------------------------------------------------------------------------

@Composable
fun StylesPanel(layout: ObservableControlLayout, recordHistory: () -> Unit, modifier: Modifier = Modifier) {
    val buttonStyles by layout.styles.collectAsState()
    val joystickStyles by layout.joystickStyles.collectAsState()
    var editButton by remember { mutableStateOf<ObservableButtonStyle?>(null) }
    var editJoystick by remember { mutableStateOf<ObservableJoystickStyle?>(null) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Styles", style = MaterialTheme.typography.titleMedium)
        Text("Reusable looks. Change a style once and every control using it updates. Assign styles from a control's panel.", style = MaterialTheme.typography.bodySmall)

        SectionHeader("Button styles")
        if (buttonStyles.isEmpty()) Text("None yet — pick a preset below.", style = MaterialTheme.typography.bodySmall)
        buttonStyles.forEach { style ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(style.name, modifier = Modifier.weight(1f))
                TextButton(onClick = { editButton = style }) { Text("Edit") }
                TextButton(onClick = { recordHistory(); layout.cloneStyle(style) }) { Text("Copy") }
                TextButton(onClick = { recordHistory(); layout.removeStyle(style.uuid) }) { Text("Delete") }
            }
        }
        Text("Add from preset", style = MaterialTheme.typography.bodySmall)
        ChipChoice(StylePresets.buttons.map { it.first }, null, { it }) { name ->
            StylePresets.buttons.firstOrNull { it.first == name }?.second?.let { recordHistory(); layout.addStyle(it()) }
        }

        SectionHeader("Joystick styles")
        if (joystickStyles.isEmpty()) Text("None yet — pick a preset below.", style = MaterialTheme.typography.bodySmall)
        joystickStyles.forEach { style ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(style.name, modifier = Modifier.weight(1f))
                TextButton(onClick = { editJoystick = style }) { Text("Edit") }
                TextButton(onClick = { recordHistory(); layout.cloneJoystickStyle(style) }) { Text("Copy") }
                TextButton(onClick = { recordHistory(); layout.removeJoystickStyle(style.uuid) }) { Text("Delete") }
            }
        }
        Text("Add from preset", style = MaterialTheme.typography.bodySmall)
        ChipChoice(StylePresets.joysticks.map { it.first }, null, { it }) { name ->
            StylePresets.joysticks.firstOrNull { it.first == name }?.second?.let { recordHistory(); layout.addJoystickStyle(it()) }
        }
    }
    editButton?.let { ButtonStyleEditorDialog(it) { editButton = null } }
    editJoystick?.let { JoystickStyleEditorDialog(it) { editJoystick = null } }
}

// -------------------------------------------------------------------------------------------
// Saved layouts (profiles), import / export / share
// -------------------------------------------------------------------------------------------

@Composable
fun ProfilesPanel(
    profiles: LayoutProfiles,
    layout: ObservableControlLayout,
    onLoad: (ControlLayout, String) -> Unit,
    onResetToDefault: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var names by remember { mutableStateOf(profiles.names()) }
    var active by remember { mutableStateOf(profiles.activeName) }
    var saveAs by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    fun refresh() { names = profiles.names(); active = profiles.activeName }

    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Saved layouts", style = MaterialTheme.typography.titleMedium)
        Text("Active: ${active.ifBlank { "unsaved layout" }}", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { saveAs = true }) { Text("Save as…") }
            if (active.isNotBlank() && active in names) OutlinedButton(onClick = {
                runCatching { profiles.save(active, layout.pack()) }.onFailure { onError(it.message ?: "Save failed") }
            }) { Text("Update “$active”") }
        }
        names.forEach { name ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text((if (name == active) "● " else "○ ") + name, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    runCatching { profiles.load(name) }
                        .onSuccess { profiles.activeName = name; active = name; onLoad(it, name) }
                        .onFailure { e -> onError("Could not load $name: ${e.message}") }
                }) { Text("Load") }
                TextButton(onClick = { rename = name }) { Text("Rename") }
                TextButton(onClick = { delete = name }) { Text("Delete") }
            }
        }

        SectionHeader("Share and backup")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onImport) { Text("Import file") }
            OutlinedButton(onClick = onExport) { Text("Export file") }
            OutlinedButton(onClick = onShare) { Text("Share") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onCopy) { Text("Copy to clipboard") }
            OutlinedButton(onClick = onPaste) { Text("Paste from clipboard") }
        }
        Text("Imports accept Horizon and Zalith Launcher 2 layout .json files.", style = MaterialTheme.typography.bodySmall)

        SectionHeader("Layout details")
        var infoName by remember { mutableStateOf(false) }
        var infoAuthor by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Name: ${layout.info.name.default.ifBlank { "—" }}", modifier = Modifier.weight(1f))
            TextButton(onClick = { infoName = true }) { Text("Edit") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Author: ${layout.info.author.default.ifBlank { "—" }}", modifier = Modifier.weight(1f))
            TextButton(onClick = { infoAuthor = true }) { Text("Edit") }
        }
        if (infoName) TextEntryDialog("Layout name", layout.info.name.default, "Name", onDone = { layout.info.name.default = it; infoName = false }, onDismiss = { infoName = false })
        if (infoAuthor) TextEntryDialog("Author", layout.info.author.default, "Author", onDone = { layout.info.author.default = it; infoAuthor = false }, onDismiss = { infoAuthor = false })

        SectionHeader("Reset")
        OutlinedButton(onClick = { confirmReset = true }) { Text("Restore Horizon default layout") }
    }

    if (saveAs) TextEntryDialog("Save layout as", initial = profiles.uniqueName(layout.info.name.default.ifBlank { "My layout" }), label = "Name", confirm = "Save",
        onDone = { name ->
            saveAs = false
            runCatching { profiles.save(profiles.uniqueName(name), layout.pack()) }
                .onSuccess { saved -> profiles.activeName = saved; refresh() }
                .onFailure { onError(it.message ?: "Save failed") }
        }, onDismiss = { saveAs = false })
    rename?.let { old ->
        TextEntryDialog("Rename layout", old, "Name", onDone = { new -> profiles.rename(old, new); rename = null; refresh() }, onDismiss = { rename = null })
    }
    delete?.let { name ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete saved layout “$name”?") },
            text = { Text("The layout you are currently editing is not affected.") },
            confirmButton = { TextButton(onClick = {
                profiles.delete(name); if (profiles.activeName == name) profiles.activeName = ""; delete = null; refresh()
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } })
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("Restore default layout?") },
        text = { Text("Your current layout is replaced. You can undo this while the editor is open.") },
        confirmButton = { TextButton(onClick = { confirmReset = false; onResetToDefault() }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } })
}
