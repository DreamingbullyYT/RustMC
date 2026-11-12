package dev.ferrite.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
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
        setContent { FerriteTheme { Box { SoftBackground(); App() } } }
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
    private fun SliderRow(label: String, value: Double, range: ClosedFloatingPointRange<Double>, onChange: (Double) -> Unit) {
        Column {
            Text("$label: ${"%.2f".format(value)}", style = MaterialTheme.typography.bodyMedium)
            Slider(value = value.toFloat(), onValueChange = { onChange(it.toDouble()) }, valueRange = range.start.toFloat()..range.endInclusive.toFloat())
        }
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f)); Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    /** Quick settings and the control editor for the first instance; both edit the engine's `horizon-touch.json`. */
    @Composable
    private fun ControlsTab() {
        val inst = store.list().firstOrNull()
        if (inst == null) { Text(stringResource(R.string.no_instances)); return }
        val ts = remember(inst.id) { TouchSettings(File(store.gameDir(inst), TouchSettings.FILE)) }
        var rev by remember { mutableIntStateOf(0) }
        var mode by remember { mutableStateOf(TouchMode.CROSSHAIR) }
        var selected by remember { mutableStateOf(TouchControl.JUMP) }
        fun change(block: () -> Unit) { block(); ts.save(); rev++ }
        @Suppress("UNUSED_EXPRESSION") rev
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(stringResource(R.string.quick_settings), style = MaterialTheme.typography.titleMedium) }
            item {
                Text(stringResource(R.string.resolution, ts.resolutionPercent))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TouchSettings.RESOLUTION_CHIPS.forEach { p -> FilterChip(selected = ts.resolutionPercent == p, onClick = { change { ts.resolutionPercent = p } }, label = { Text("$p%") }) }
                }
                Slider(value = ts.resolutionPercent.toFloat(), onValueChange = { change { ts.resolutionPercent = it.toInt() } }, valueRange = 25f..100f)
            }
            item { SliderRow(stringResource(R.string.sensitivity), ts.sensitivity, 0.1..3.0) { v -> change { ts.sensitivity = v } } }
            item { SwitchRow(stringResource(R.string.virtual_mouse), ts.virtualMouse) { v -> change { ts.virtualMouse = v } } }
            item { SliderRow(stringResource(R.string.mouse_speed), ts.virtualMouseSpeed, 0.25..3.0) { v -> change { ts.virtualMouseSpeed = v } } }
            item { SliderRow(stringResource(R.string.pointer_size), ts.pointerScale, 0.5..2.0) { v -> change { ts.pointerScale = v } } }
            item { SliderRow(stringResource(R.string.button_size), ts.buttonScale, 0.5..2.0) { v -> change { ts.buttonScale = v } } }
            item { SwitchRow(stringResource(R.string.animations), ts.gameAnimations) { v -> change { ts.gameAnimations = v } } }
            item { SwitchRow(stringResource(R.string.vibrate), ts.vibrate) { v -> change { ts.vibrate = v } } }
            item { SwitchRow(stringResource(R.string.invert_y), ts.invertY) { v -> change { ts.invertY = v } } }
            item { SwitchRow(stringResource(R.string.left_handed), ts.leftHanded) { v -> change { ts.leftHanded = v } } }
            item { SwitchRow(stringResource(R.string.sprint_toggle), ts.sprintToggle) { v -> change { ts.sprintToggle = v } } }
            item { SwitchRow(stringResource(R.string.dynamic_joystick), ts.dynamicJoystick) { v -> change { ts.dynamicJoystick = v } } }
            item { SwitchRow(stringResource(R.string.gyroscope), ts.gyroscope) { v -> change { ts.gyroscope = v } } }
            item {
                Text(stringResource(R.string.scheme))
                Scheme.entries.forEach { s -> FilterChip(selected = ts.scheme == s, onClick = { change { ts.scheme = s } }, label = { Text(s.label) }) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Mcpe", "GoyControl").forEach { st -> FilterChip(selected = ts.controlStyle == st, onClick = { change { ts.controlStyle = st } }, label = { Text(st) }) }
                }
            }
            item { Text(stringResource(R.string.control_editor), style = MaterialTheme.typography.titleMedium) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TouchMode.entries.forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.json) }) }
                }
            }
            item {
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
                    TouchControl.entries.forEach { c ->
                        val p = ts.placement(mode, c); val side = (p.size * h)
                        val hidden = ts.visibility(c) == HIDDEN
                        Box(
                            Modifier
                                .offset { IntOffset((p.x * w - side / 2).toInt(), (p.y * h - side / 2).toInt()) }
                                .size(with(LocalDensity.current) { side.toDp() })
                                .background(if (c == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary, RoundedCornerShape(8.dp))
                                .alpha(if (hidden) 0.25f else p.opacity.toFloat().coerceAtLeast(0.3f))
                                .pointerInput(c, mode) {
                                    detectDragGestures(onDragStart = { selected = c }, onDragEnd = { ts.save() }) { change, drag ->
                                        change.consume(); val q = ts.placement(mode, c)
                                        ts.setPlacement(mode, c, q.copy(x = q.x + drag.x / w, y = q.y + drag.y / h)); rev++
                                    }
                                }
                        ) { Text(c.label.take(2), Modifier.align(androidx.compose.ui.Alignment.Center), style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            item {
                val p = ts.placement(mode, selected)
                Text(selected.label, style = MaterialTheme.typography.titleSmall)
                SliderRow(stringResource(R.string.size), p.size, 0.045..0.45) { v -> change { ts.setPlacement(mode, selected, p.copy(size = v)) } }
                SliderRow(stringResource(R.string.opacity), p.opacity, 0.0..1.0) { v -> change { ts.setPlacement(mode, selected, p.copy(opacity = v)) } }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(FOLLOW_SCHEME to R.string.vis_auto, SHOWN to R.string.vis_shown, HIDDEN to R.string.vis_hidden).forEach { (v, label) ->
                        FilterChip(selected = ts.visibility(selected) == v, onClick = { change { ts.setVisibility(selected, v) } }, label = { Text(stringResource(label)) })
                    }
                }
                OutlinedButton(onClick = { change { ts.resetPlacements(mode) } }) { Text(stringResource(R.string.reset_layout)) }
            }
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
