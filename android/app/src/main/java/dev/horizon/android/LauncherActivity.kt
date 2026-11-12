package dev.horizon.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

private enum class LauncherPage(val title: String) {
    Play("Play"), Files("Files"), Settings("Settings")
}

class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HorizonLauncherTheme {
                LauncherRoot(
                    onLaunchGame = {
                        startActivity(Intent(this, HorizonActivity::class.java))
                    }
                )
            }
        }
    }
}

@Composable
private fun HorizonLauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF55D6A5),
            onPrimary = Color(0xFF06251A),
            secondary = Color(0xFF8BDCC0),
            background = Color(0xFF0C1512),
            surface = Color(0xFF14231D),
            surfaceVariant = Color(0xFF1B3028),
            onSurface = Color(0xFFEAF5EF),
            onSurfaceVariant = Color(0xFFAAC0B5)
        ),
        content = content
    )
}

@Composable
private fun LauncherRoot(onLaunchGame: () -> Unit) {
    var page by remember { mutableStateOf(LauncherPage.Play) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                LauncherPage.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = page == destination,
                        onClick = { page = destination },
                        icon = {
                            Icon(
                                when (destination) {
                                    LauncherPage.Play -> Icons.Filled.Gamepad
                                    LauncherPage.Files -> Icons.Filled.Folder
                                    LauncherPage.Settings -> Icons.Filled.Settings
                                },
                                contentDescription = destination.title
                            )
                        },
                        label = { Text(destination.title) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (page) {
                LauncherPage.Play -> PlayPage(onLaunchGame)
                LauncherPage.Files -> FilesPage()
                LauncherPage.Settings -> SettingsPage()
            }
        }
    }
}

@Composable
private fun PlayPage(onLaunchGame: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("HORIZON", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("Ready to play?", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Minecraft", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Android edition",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onLaunchGame, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Gamepad, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Launch game")
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Launcher status", fontWeight = FontWeight.SemiBold)
                Text(
                    "The Android launcher is separate from the game runtime. Native game integration is being ported against the pinned upstream engine.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Text(
            "No update button is shown until a working updater is connected.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private data class FileEntry(val file: File, val isDirectory: Boolean)

@Composable
private fun FilesPage() {
    val context = LocalContext.current
    val root = remember { context.filesDir.canonicalFile }
    var current by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh(directory: File) {
        try {
            val canonical = directory.canonicalFile
            require(canonical.path == root.path || canonical.toPath().startsWith(root.toPath())) {
                "This folder is outside the app's game-data directory."
            }
            current = canonical
            entries = canonical.listFiles()
                ?.map { FileEntry(it, it.isDirectory) }
                ?.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.file.name.lowercase() })
                ?: emptyList()
            error = null
        } catch (e: Exception) {
            error = e.message ?: "Unable to read this folder."
        }
    }

    LaunchedEffect(root) { refresh(root) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Game files", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { refresh(current) }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                Spacer(Modifier.width(4.dp))
                Text("Refresh")
            }
        }
        Text(
            current.relativeTo(root).path.let { if (it == ".") "Internal game data" else it },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        if (current != root) {
            OutlinedButton(onClick = { refresh(current.parentFile ?: root) }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Parent folder")
            }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (entries.isEmpty() && error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This folder is empty.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries, key = { it.file.absolutePath }) { entry ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable(enabled = entry.isDirectory) {
                                refresh(entry.file)
                            }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.file.name, fontWeight = FontWeight.Medium)
                            Text(
                                if (entry.isDirectory) "Folder" else formatFileSize(entry.file.length()),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (entry.isDirectory) {
                            Icon(Icons.Filled.ChevronRight, contentDescription = "Open folder")
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

@Composable
private fun SettingsPage() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE) }
    var keepScreenOn by remember { mutableStateOf(preferences.getBoolean("keep_screen_on", true)) }
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Keep screen on while playing", fontWeight = FontWeight.Medium)
                    Text(
                        "Prevents the display from sleeping during a game session.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = keepScreenOn,
                    onCheckedChange = {
                        keepScreenOn = it
                        preferences.edit().putBoolean("keep_screen_on", it).apply()
                    }
                )
            }
        }
        Divider(color = MaterialTheme.colorScheme.surfaceVariant)
        Text(
            "Renderer, touch-layout and mod settings will be exposed here after their native runtime bridge is connected. They are not presented as working controls yet.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
