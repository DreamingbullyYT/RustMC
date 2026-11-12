package dev.horizon.launcher.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.horizon.launcher.ui.theme.HorizonColors
import dev.horizon.storage.MinecraftStorageRepository
import dev.horizon.storage.StorageEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FileManagerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { MinecraftStorageRepository(context) }
    val scope = rememberCoroutineScope()
    var treeSelected by remember { mutableStateOf(repository.selectedTreeUri != null) }
    var currentDocumentId by remember { mutableStateOf(repository.rootDocumentId()) }
    var entries by remember { mutableStateOf<List<StorageEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                repository.selectTree(uri)
                treeSelected = true
                currentDocumentId = repository.rootDocumentId()
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Could not access this folder"
            }
        }
    }

    fun refresh() {
        val parent = currentDocumentId ?: return
        scope.launch {
            loading = true
            error = null
            try {
                entries = withContext(Dispatchers.IO) { repository.list(parent) }
            } catch (e: Exception) {
                error = e.message ?: "Could not read this folder"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(treeSelected, currentDocumentId) {
        if (treeSelected && currentDocumentId != null) refresh()
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("File manager", color = androidx.compose.ui.graphics.Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text(
            "Browse your Minecraft directory using Android's secure folder access.",
            color = HorizonColors.TextMuted,
            fontSize = 13.sp
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = HorizonColors.Surface),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (treeSelected) "Minecraft folder connected" else "No Minecraft folder selected",
                    color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { folderPicker.launch(null) }) {
                        Text(if (treeSelected) "Change folder" else "Choose folder")
                    }
                    if (treeSelected) {
                        OutlinedButton(onClick = { showCreateDialog = true }) { Text("New folder") }
                    }
                }
            }
        }

        if (error != null) {
            Text(error!!, color = HorizonColors.Warning, fontSize = 12.sp)
        }

        if (!treeSelected) {
            Text(
                "Select the Minecraft game directory (for example, the folder containing games or Minecraft files). Access is granted only to the folder you choose.",
                color = HorizonColors.TextMuted,
                fontSize = 13.sp
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Folder contents", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { refresh() }) { Text("Refresh") }
            }
            if (loading) {
                Text("Loading…", color = HorizonColors.TextMuted)
            } else if (entries.isEmpty()) {
                Text("This folder is empty.", color = HorizonColors.TextMuted)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(entries, key = { it.documentId }) { entry ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = HorizonColors.Surface),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().clickable(enabled = entry.isDirectory) {
                                currentDocumentId = entry.documentId
                            }
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(if (entry.isDirectory) "📁" else "📄", fontSize = 20.sp)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(entry.name, color = androidx.compose.ui.graphics.Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(if (entry.isDirectory) "Folder" else formatSize(entry.sizeBytes), color = HorizonColors.TextMuted, fontSize = 11.sp)
                                }
                                if (entry.isDirectory) Text("›", color = HorizonColors.TextMuted, fontSize = 22.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create folder") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val parent = currentDocumentId
                    val name = newFolderName.trim()
                    if (parent != null && name.isNotEmpty()) {
                        scope.launch {
                            val created = withContext(Dispatchers.IO) { repository.createDirectory(parent, name) }
                            if (!created) error = "Could not create folder"
                            refresh()
                        }
                    }
                    newFolderName = ""
                    showCreateDialog = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
