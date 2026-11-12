package dev.horizon.launcher.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import dev.horizon.mods.HShaderRepository
import dev.horizon.mods.ShaderPack
import dev.horizon.launcher.ui.theme.HorizonColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ModPage { HNMOD, HMOD, HSHADER }

@Composable
fun ModsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { HShaderRepository(context) }
    val scope = rememberCoroutineScope()
    val packs = remember { mutableStateListOf<ShaderPack>() }
    var page by remember { mutableStateOf(ModPage.HSHADER) }
    var pendingDelete by remember { mutableStateOf<ShaderPack?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val items = withContext(Dispatchers.IO) { repository.list() }
        packs.clear()
        packs.addAll(items)
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            busy = true
            error = null
            scope.launch {
                val result = runCatching { withContext(Dispatchers.IO) { repository.importZip(uri) } }
                result.onFailure { error = it.message ?: "Could not import shader pack." }
                refresh()
                busy = false
            }
        }
    }

    LaunchedEffect(repository, page) { if (page == ModPage.HSHADER) refresh() }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Mods & shaders", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Three independent systems, one management area.", color = HorizonColors.TextMuted, fontSize = 12.sp)
        ScrollableTabRow(selectedTabIndex = page.ordinal, containerColor = Color.Transparent, contentColor = HorizonColors.Accent) {
            ModPage.values().forEach { item ->
                Tab(selected = page == item, onClick = { page = item; error = null }, text = { Text(item.name) })
            }
        }

        when (page) {
            ModPage.HNMOD -> SystemInfoCard(
                title = "HNMod Builder",
                detail = "Native Android modification builds, ARM64 toolchain, dependency checks, engine commit and hooks API validation.",
                status = "BUILDER NOT CONNECTED",
                note = "The existing Android Edition toolchain must be audited and reused before enabling builds. No compatibility is guessed here."
            )
            ModPage.HMOD -> SystemInfoCard(
                title = "HMod packages",
                detail = "Native packages supported by Horizon, managed separately from HNMod source projects and shader packs.",
                status = "RUNTIME LOADER NOT CONNECTED",
                note = "Package import and activation stay disabled until the actual HMod package format and runtime loader are verified."
            )
            ModPage.HSHADER -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Shader packs", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text("Selected instance · shaderpacks/", color = HorizonColors.TextMuted, fontSize = 11.sp)
                    }
                    Button(onClick = { importPicker.launch(arrayOf("application/zip", "application/x-zip-compressed")) }, enabled = !busy) {
                        Text("＋ Import ZIP")
                    }
                }
                if (packs.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = HorizonColors.Surface), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("No shader packs", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Import a .zip shader pack. It will be stored inside the active instance, not globally.", color = HorizonColors.TextMuted, fontSize = 12.sp)
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(packs, key = { it.fileName }) { pack ->
                            Card(colors = CardDefaults.cardColors(containerColor = HorizonColors.Surface), shape = RoundedCornerShape(16.dp)) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(pack.displayName, color = Color.White, fontWeight = FontWeight.SemiBold)
                                        Text("${pack.fileName} · ${pack.sizeBytes / 1024} KB", color = HorizonColors.TextMuted, fontSize = 10.sp)
                                    }
                                    TextButton(onClick = { pendingDelete = pack }) { Text("Remove", color = Color(0xFFFF8D8D)) }
                                }
                            }
                        }
                    }
                }
                Text("Imported files are not yet activated by the Horizon runtime. ZIP validation confirms the archive signature only, not shader compatibility.", color = HorizonColors.Warning, fontSize = 11.sp)
            }
        }

        error?.let { Text(it, color = Color(0xFFFF8D8D), fontSize = 12.sp) }
    }

    pendingDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${pack.displayName}?") },
            text = { Text("This deletes the shader-pack archive from the active instance.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    busy = true
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) { repository.delete(pack.fileName) } }
                        result.onFailure { error = it.message ?: "Could not remove shader pack." }
                        refresh()
                        busy = false
                    }
                }, enabled = !busy) { Text("Remove", color = Color(0xFFFF8D8D)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SystemInfoCard(title: String, detail: String, status: String, note: String) {
    Card(colors = CardDefaults.cardColors(containerColor = HorizonColors.Surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = HorizonColors.TextMuted, fontSize = 13.sp)
            HorizontalDivider(color = HorizonColors.SurfaceElevated)
            Text(status, color = HorizonColors.Warning, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(note, color = HorizonColors.TextMuted, fontSize = 11.sp)
        }
    }
}
