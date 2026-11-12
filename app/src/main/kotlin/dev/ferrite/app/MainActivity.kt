package dev.ferrite.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ferrite.core.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Real HTTP for the core's [Http] seam. Always called off the main thread. */
private val http = Http { url ->
    val c = URL(url).openConnection() as HttpURLConnection
    c.setRequestProperty("User-Agent", "ferrite-launcher/0.1 (github.com/DreamingbullyYT)")
    c.connectTimeout = 8000; c.readTimeout = 12000
    c.inputStream.bufferedReader().use { it.readText() }
}

class MainActivity : ComponentActivity() {
    private lateinit var store: InstanceStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = InstanceStore(filesDir)
        setContent { MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { Surface { App() } } }
    }

    /** Hands the instance to the Horizon engine app. Contract: extras `game_dir`, `touch_layout`, `resolution_scale`, `render_distance`. */
    private fun launch(i: Instance): Boolean {
        val intent = packageManager.getLaunchIntentForPackage("dev.horizon.android") ?: return false
        intent.putExtra("game_dir", store.gameDir(i).absolutePath).putExtra("touch_layout", i.touchLayout)
            .putExtra("resolution_scale", i.resolutionScale).putExtra("render_distance", i.renderDistance)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        store.markPlayed(i, System.currentTimeMillis())
        startActivity(intent); return true
    }

    @Composable
    private fun App() {
        var tab by remember { mutableIntStateOf(0) }
        val titles = listOf(R.string.tab_play, R.string.tab_worlds, R.string.tab_servers, R.string.tab_mods, R.string.tab_controls, R.string.tab_logs)
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) { titles.forEachIndexed { n, t -> Tab(selected = tab == n, onClick = { tab = n }, text = { Text(stringResource(t)) }) } }
            Box(Modifier.weight(1f).padding(16.dp)) {
                when (tab) { 0 -> PlayTab(); 1 -> WorldsTab(); 2 -> ServersTab(); 3 -> ModsTab(); 4 -> ControlsTab(); else -> LogsTab() }
            }
        }
    }

    @Composable
    private fun PlayTab() {
        var items by remember { mutableStateOf(store.list()) }
        var message by remember { mutableStateOf<String?>(null) }
        val missing = stringResource(R.string.game_missing)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { store.create("Instance ${items.size + 1}"); items = store.list() }) { Text(stringResource(R.string.new_instance)) }
            message?.let { Text(it) }
            if (items.isEmpty()) Text(stringResource(R.string.no_instances))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { i ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(i.name, style = MaterialTheme.typography.titleMedium)
                            Text("${i.engine} ${i.engineVersion} · x${i.resolutionScale} · ${i.renderDistance} chunks", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { if (!launch(i)) message = missing; items = store.list() }) { Text(stringResource(R.string.play)) }
                                OutlinedButton(onClick = {
                                    thread { val n = Backup.create(store.gameDir(i), File(getExternalFilesDir(null), "${i.id}-backup.zip")); runOnUiThread { message = "Backup: $n files" } }
                                }) { Text(stringResource(R.string.backup)) }
                                TextButton(onClick = { store.delete(i); items = store.list() }) { Text(stringResource(R.string.delete)) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun WorldsTab() {
        val inst = store.list().firstOrNull()
        if (inst == null) { Text(stringResource(R.string.no_instances)); return }
        val wm = remember(inst.id) { WorldManager(store.gameDir(inst)) }
        var worlds by remember { mutableStateOf(wm.list()) }
        var message by remember { mutableStateOf<String?>(null) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(inst.name, style = MaterialTheme.typography.titleSmall)
            message?.let { Text(it) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(worlds, key = { it.folder }) { w ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(w.displayName, style = MaterialTheme.typography.titleMedium)
                            Text("${w.version ?: "?"} · ${w.sizeBytes / 1_000_000} MB", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { thread { val n = wm.backup(w.folder, File(getExternalFilesDir(null), "${w.folder}-world.zip")); runOnUiThread { message = "Backup: $n files" } } }) { Text(stringResource(R.string.backup)) }
                                TextButton(onClick = { wm.delete(w.folder); worlds = wm.list() }) { Text(stringResource(R.string.delete)) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ServersTab() {
        val inst = store.list().firstOrNull()
        if (inst == null) { Text(stringResource(R.string.no_instances)); return }
        val list = remember(inst.id) { ServerList(File(store.gameDir(inst), "ferrite-servers.json")) }
        var servers by remember { mutableStateOf(list.all()) }
        var host by remember { mutableStateOf("") }
        var status by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = host, onValueChange = { host = it }, modifier = Modifier.weight(1f), singleLine = true)
                Button(onClick = { runCatching { list.add(Server(host.trim(), host.trim())) }; servers = list.all(); host = "" }) { Text(stringResource(R.string.add)) }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(servers, key = { it.name }) { s ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${s.name}:${s.port}", style = MaterialTheme.typography.titleSmall)
                            status[s.name]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { thread { val r = runCatching { Ping.query(s.host, s.port).let { "${it.motd} · ${it.online}/${it.max} · ${it.pingMs} ms" } }.getOrElse { "offline" }; runOnUiThread { status = status + (s.name to r) } } }) { Text("Ping") }
                                TextButton(onClick = { list.remove(s.name); servers = list.all() }) { Text(stringResource(R.string.delete)) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ControlsTab() {
        var layout by remember { mutableStateOf(TouchLayout.MCPE) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TouchLayout.PRESETS.forEach { p -> FilterChip(selected = layout.name == p.name, onClick = { layout = p }, label = { Text(p.name) }) }
            }
            Text(stringResource(R.string.overlaps, layout.overlaps().size))
            Text(layout.widgets.joinToString("\n") { "${it.id}: ${it.kind.name.lowercase()} (${"%.2f".format(it.x)}, ${"%.2f".format(it.y)})" }, style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun ModsTab() {
        var query by remember { mutableStateOf("") }
        var hits by remember { mutableStateOf<List<ModHit>>(emptyList()) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { val q = query; thread { val r = Modrinth.search(http, q); runOnUiThread { hits = r } } }) { Text(stringResource(R.string.search)) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(hits, key = { it.slug }) { h -> Column { Text(h.title, style = MaterialTheme.typography.titleSmall); Text(h.summary, style = MaterialTheme.typography.bodySmall) } }
            }
        }
    }

    @Composable
    private fun LogsTab() {
        var log by remember { mutableStateOf("") }
        var result by remember { mutableStateOf<Diagnosis?>(null) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = log, onValueChange = { log = it }, modifier = Modifier.fillMaxWidth().height(200.dp), label = { Text(stringResource(R.string.paste_log)) })
            Button(onClick = { result = CrashAnalyzer.analyze(log) }) { Text(stringResource(R.string.analyze)) }
            result?.let { Text(it.hint); if (it.evidence.isNotEmpty()) Text(it.evidence, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
