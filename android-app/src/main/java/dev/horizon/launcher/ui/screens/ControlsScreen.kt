package dev.horizon.launcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.horizon.controls.TouchControlProfile
import dev.horizon.controls.TouchControlsRepository
import dev.horizon.launcher.ui.theme.HorizonColors
import java.util.Locale

@Composable
fun ControlsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { TouchControlsRepository(context) }
    var profiles by remember { mutableStateOf(repository.profiles()) }
    var profile by remember { mutableStateOf(repository.activeProfile()) }
    var layoutMenu by remember { mutableStateOf(false) }
    var profileMenu by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    fun persist(updated: TouchControlProfile) {
        profile = updated
        repository.save(updated)
        profiles = repository.profiles()
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Touch controls", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
        Text("Configurează profilul care va fi folosit de overlay-ul Android.", color = HorizonColors.TextMuted)

        Card(colors = CardDefaults.cardColors(containerColor = HorizonColors.Surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Profile", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.name, modifier = Modifier.weight(1f))
                    TextButton(onClick = { profileMenu = true }) { Text("Change") }
                    DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                        profiles.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.name) },
                                onClick = {
                                    repository.select(item.id)
                                    profile = item
                                    profileMenu = false
                                }
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val created = repository.createProfile("Custom ${profiles.size}")
                        profiles = repository.profiles()
                        profile = created
                    }) { Text("Duplicate profile") }
                    TextButton(
                        enabled = profile.id != "default",
                        onClick = {
                            repository.delete(profile.id)
                            profiles = repository.profiles()
                            profile = repository.activeProfile()
                        }
                    ) { Text("Delete") }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = HorizonColors.Surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Movement layout", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.layout.replace('_', ' '), modifier = Modifier.weight(1f))
                    TextButton(onClick = { layoutMenu = true }) { Text("Choose") }
                    DropdownMenu(expanded = layoutMenu, onDismissRequest = { layoutMenu = false }) {
                        TouchControlProfile.layouts.forEach { layout ->
                            DropdownMenuItem(
                                text = { Text(layout.replace('_', ' ')) },
                                onClick = { persist(profile.copy(layout = layout)); layoutMenu = false }
                            )
                        }
                    }
                }
                SettingSlider("Button opacity", profile.opacity, 0.1f..1f, { persist(profile.copy(opacity = it)) })
                SettingSlider("Button size", profile.buttonScale, 0.5f..2f, { persist(profile.copy(buttonScale = it)) })
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = HorizonColors.Surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Camera sensitivity", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                SettingSlider("Horizontal", profile.sensitivityX, 0.1f..5f, { persist(profile.copy(sensitivityX = it)) })
                SettingSlider("Vertical", profile.sensitivityY, 0.1f..5f, { persist(profile.copy(sensitivityY = it)) })
                HorizontalDivider(color = HorizonColors.SurfaceElevated)
                ToggleSetting("Invert X axis", profile.invertX) { persist(profile.copy(invertX = it)) }
                ToggleSetting("Invert Y axis", profile.invertY) { persist(profile.copy(invertY = it)) }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = HorizonColors.Surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Assist options", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                ToggleSetting("Gyroscope look", profile.gyroscope) { persist(profile.copy(gyroscope = it)) }
                ToggleSetting("Auto sprint", profile.autoSprint) { persist(profile.copy(autoSprint = it)) }
            }
        }
        Text(
            "Notă: setările sunt salvate local pe profil. Aplicarea lor în joc necesită conectarea overlay-ului la engine/input bridge.",
            color = HorizonColors.TextMuted,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Text(String.format(Locale.US, "%.2f", value), color = HorizonColors.TextMuted)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ToggleSetting(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
