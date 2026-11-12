package dev.horizon.launcher.ui.screens

import androidx.activity.compose.BackHandler
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

private enum class EntryAction { RENAME, DELETE }

@Composable
fun FileManagerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { MinecraftStorageRepository(context) }
    val scope = rememberCoroutineScope()
    var treeSelected by remember { mutableStateOf(repository.selectedTreeUri != null) }
    var history by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    val currentDocumentId = history.lastOrNull()?.first
    val currentFolderName = history.lastOrNull()?.second ?: "Minecraft"
    var entries by remember { mutableStateOf<List<StorageEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var selectedEntry by remember { mutableStateOf<StorageEntry?>(null) }
    var entryAction by remember { mutableStateOf<EntryAction?>(null) }
    var editedName by remember { mutableStateOf("") }

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

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                repository.selectTree(uri)
                val root = repository.rootDocumentId()
                treeSelected = root != null
                history = if (root != null) listOf(root to "Minecraft") else emptyList()
                error = null
            } catch (e: Exception) {
                error = e.message ?: "Could not access this folder"
            }
        }
    }

    BackHandler(enabled = treeSelected && history.size > 1) {
        history = history.dropLast(1)
    }

    LaunchedEffect(treeSelected, currentDocumentId) {
        if (treeSelected && currentDocumentId != null) refresh()
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("File manager", color = androidx.compose.ui.graphics.Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Browse your Minecraft directory using Android's secure folder access.", color = HorizonColors.TextMuted, fontSize = 13.sp)
        Card(
            colors = CardDefaults.cardColors(containerColor = HorizonColors.Surface),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (treeSelected) "Minecraft folder connected" else "No Minecraft folder selected", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { folderPicker.launch(null) }) { Text(if (treeSelected) "Change folder" else "Choose folder") }
                    if (treeSelected) OutlinedButton(onClick = { showCreateDialog = true }) { Text("New folder") }
                }
            }
        }
        if (error != null) Text(error!!, color = HorizonColors.Warning, fontSize = 12.sp)
        if (!treeSelected) {
            Text("Select the Minecraft game directory. Access is granted only to the folder you choose; Android may restrict protected locations such as Android/data.", color = HorizonColors.TextMuted, fontSize = 13.sp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(if (history.size > 1) "‹  $currentFolderName" else currentFolderName, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).clickable(enabled = history.size > 1) { history = history.dropLast(1) })
                TextButton(onClick = { refresh() }) { Text("Refresh") }
            }
            if (loading) {
                Text("Loading…", color = HorizonColors.TextMuted)
            } else if (entries.isEmpty()) {
                Text("This folder is empty.", color = HorizonColors.TextMuted)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(entries, key = { it.documentId }) { entry ->
                        Card(colors = CardDefaults.cardColors(containerColor = HorizonColors.Surface), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).clickable(enabled = entry.isDirectory) { history = history + (entry.documentId to entry.name) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (entry.isDirectory) "📁" else "📄", fontSize = 20.sp)
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(entry.name, color = androidx.compose.ui.graphics.Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        Text(if (entry.isDirectory) "Folder" else formatSize(entry.sizeBytes), color = HorizonColors.TextMuted, fontSize = 11.sp)
                                    }
                                    if (entry.isDirectory) Text("›", color = HorizonColors.TextMuted, fontSize = 22.sp)
                                }
                                TextButton(onClick = { selectedEntry = entry; editedName = entry.name; entryAction = EntryAction.RENAME }) { Text("Rename") }
                                TextButton(onClick = { selectedEntry = entry; entryAction = EntryAction.DELETE }) { Text("Delete", color = HorizonColors.Warning) }
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
            text = { OutlinedTextField(value = newFolderName, onValueChange = { newFolderName = it }, label = { Text("Folder name") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val parent = currentDocumentId
                    val name = newFolderName.trim()
                    if (parent != null && name.isNotEmpty()) scope.launch {
                        try {
                            val created = withContext(Dispatchers.IO) { repository.createDirectory(parent, name) }
                            if (!created) error = "The storage provider could not create this folder"
                            refresh()
                        } catch (e: Exception) { error = e.message ?: "Could not create folder" }
                    }
                    newFolderName = ""
                    showCreateDialog = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") } }
        )
    }

    val actionEntry = selectedEntry
    when (entryAction) {
        EntryAction.RENAME -> if (actionEntry != null) AlertDialog(
            onDismissRequest = { entryAction = null; selectedEntry = null },
            title = { Text("Rename item") },
            text = { OutlinedTextField(value = editedName, onValueChange = { editedName = it }, label = { Text("New name") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val newName = editedName.trim()
                    if (newName.isNotEmpty()) scope.launch {
                        try {
                            val renamed = withContext(Dispatchers.IO) { repository.rename(actionEntry.documentId, newName) }
                            if (!renamed) error = "The storage provider could not rename this item"
                            refresh()
                        } catch (e: Exception) { error = e.message ?: "Could not rename item" }
                    }
                    entryAction = null
                    selectedEntry = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { entryAction = null; selectedEntry = null }) { Text("Cancel") } }
        )
        EntryAction.DELETE -> if (actionEntry != null) AlertDialog(
            onDismissRequest = { entryAction = null; selectedEntry = null },
            title = { Text("Delete ${actionEntry.name}?") },
            text = { Text("This permanently deletes the selected item if the storage provider allows it. This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            val deleted = withContext(Dispatchers.IO) { repository.delete(actionEntry.documentId) }
                            if (!deleted) error = "The storage provider could not delete this item"
                            refresh()
                        } catch (e: Exception) { error = e.message ?: "Could not delete item" }
                    }
                    entryAction = null
                    selectedEntry = null
                }) { Text("Delete", color = HorizonColors.Warning) }
            },
            dismissButton = { TextButton(onClick = { entryAction = null; selectedEntry = null }) { Text("Cancel") } }
        )
        null -> Unit
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}
