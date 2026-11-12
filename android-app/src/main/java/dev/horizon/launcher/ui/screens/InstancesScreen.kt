package dev.horizon.launcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.horizon.instances.InstanceProfile
import dev.horizon.instances.InstanceRepository
import dev.horizon.launcher.ui.theme.HorizonColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class InstanceDialog { CREATE, RENAME, DELETE }

@Composable
fun InstancesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { InstanceRepository(context) }
    val scope = rememberCoroutineScope()
    val profiles = remember { mutableStateListOf<InstanceProfile>() }
    var dialog by remember { mutableStateOf<InstanceDialog?>(null) }
    var target by remember { mutableStateOf<InstanceProfile?>(null) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val result = withContext(Dispatchers.IO) { repository.listInstances() }
        profiles.clear()
        profiles.addAll(result)
    }

    LaunchedEffect(repository) { refresh() }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Instances", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Independent Minecraft profiles and game folders", color = HorizonColors.TextMuted, fontSize = 12.sp)
            }
            Button(onClick = {
                target = null
                name = ""
                error = null
                dialog = InstanceDialog.CREATE
            }) { Text("＋ Create") }
        }

        if (profiles.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = HorizonColors.Surface),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("No instances yet", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text("Create a profile to get its own game, mods, config, saves and resource-pack folders.", color = HorizonColors.TextMuted, fontSize = 13.sp)
                    Button(onClick = {
                        target = null
                        name = "Default"
                        error = null
                        dialog = InstanceDialog.CREATE
                    }) { Text("Create first instance") }
                }
            }
        } else {
            Text("${profiles.size} PROFILE${if (profiles.size == 1) "" else "S"}", color = HorizonColors.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(profiles, key = { it.id }) { profile ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = HorizonColors.Surface),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(profile.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                    Text(if (profile.isActive) "ACTIVE PROFILE" else "SEPARATE PROFILE", color = if (profile.isActive) HorizonColors.Success else HorizonColors.TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                }
                                if (profile.isActive) Text("✓", color = HorizonColors.Success, fontSize = 20.sp)
                            }
                            Text("Private storage · game / mods / config / saves", color = HorizonColors.TextMuted, fontSize = 11.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!profile.isActive) {
                                    Button(onClick = {
                                        busy = true
                                        error = null
                                        scope.launch {
                                            runCatching { withContext(Dispatchers.IO) { repository.selectInstance(profile.id) } }
                                                .onFailure { error = it.message ?: "Could not select instance." }
                                            refresh()
                                            busy = false
                                        }
                                    }, enabled = !busy) { Text("Select") }
                                }
                                OutlinedButton(onClick = {
                                    target = profile
                                    name = profile.name
                                    error = null
                                    dialog = InstanceDialog.RENAME
                                }) { Text("Rename") }
                                TextButton(onClick = {
                                    target = profile
                                    error = null
                                    dialog = InstanceDialog.DELETE
                                }) { Text("Delete", color = Color(0xFFFF8D8D)) }
                            }
                        }
                    }
                }
            }
        }
        error?.let { Text(it, color = Color(0xFFFF8D8D), fontSize = 12.sp) }
    }

    if (dialog == InstanceDialog.CREATE || dialog == InstanceDialog.RENAME) {
        val isCreate = dialog == InstanceDialog.CREATE
        AlertDialog(
            onDismissRequest = { if (!busy) dialog = null },
            title = { Text(if (isCreate) "Create instance" else "Rename instance") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it; error = null }, label = { Text("Instance name") }, singleLine = true)
                    error?.let { Text(it, color = Color(0xFFFF8D8D), fontSize = 12.sp) }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && !busy, onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                if (isCreate) repository.createInstance(name) else repository.renameInstance(target!!.id, name)
                            }
                        }
                        result.onFailure { error = it.message ?: "Operation failed." }
                        refresh()
                        busy = false
                        if (result.isSuccess) dialog = null
                    }
                }) { Text(if (isCreate) "Create" else "Save") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }, enabled = !busy) { Text("Cancel") } }
        )
    }

    if (dialog == InstanceDialog.DELETE && target != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) dialog = null },
            title = { Text("Delete ${target!!.name}?") },
            text = { Text("This permanently removes this profile and all files stored inside its private instance folder.") },
            confirmButton = {
                TextButton(onClick = {
                    val deleting = target!!
                    busy = true
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) { repository.deleteInstance(deleting.id) } }
                        result.onFailure { error = it.message ?: "Could not delete instance." }
                        refresh()
                        busy = false
                        if (result.isSuccess) dialog = null
                    }
                }, enabled = !busy) { Text("Delete", color = Color(0xFFFF8D8D)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }, enabled = !busy) { Text("Cancel") } }
        )
    }
}
