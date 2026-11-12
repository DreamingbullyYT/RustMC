package dev.horizon.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.horizon.launcher.data.*
import org.json.JSONObject
import java.util.Locale

// Horizon World Atlas: a warm, map-like launcher identity, deliberately unlike the previous
// dark terminal / lime field-console direction.
private data class AtlasPalette(
    val canvasPaper: Color,
    val ink: Color,
    val mutedInk: Color,
    val blue: Color,
    val blueDeep: Color,
    val sky: Color,
    val coral: Color,
    val surface: Color,
    val border: Color,
    val mint: Color,
    val cardTint: Color,
    val emptyTint: Color,
    val warningTint: Color
)

private val LightAtlasPalette = AtlasPalette(
    canvasPaper = Color(0xFFF2F0E9), ink = Color(0xFF171B2A), mutedInk = Color(0xFF777D8C),
    blue = Color(0xFF5266E8), blueDeep = Color(0xFF27378F), sky = Color(0xFF8CD7E7),
    coral = Color(0xFFFF987D), surface = Color(0xFFFFFEFA), border = Color(0xFFE2E0D8),
    mint = Color(0xFFB9E6D0), cardTint = Color(0xFFE5E9FF), emptyTint = Color(0xFFEAE8E0),
    warningTint = Color(0xFFFFE0D7)
)

private val DarkAtlasPalette = AtlasPalette(
    canvasPaper = Color(0xFF0E121B), ink = Color(0xFFF1F3FA), mutedInk = Color(0xFFA7B0C2),
    blue = Color(0xFF9AA8FF), blueDeep = Color(0xFF697BE0), sky = Color(0xFF75D7E7),
    coral = Color(0xFFFFA18B), surface = Color(0xFF1A202D), border = Color(0xFF30394A),
    mint = Color(0xFF214B3B), cardTint = Color(0xFF242D42), emptyTint = Color(0xFF202735),
    warningTint = Color(0xFF4B2D2A)
)

private val LocalAtlasPalette = staticCompositionLocalOf { LightAtlasPalette }
private val CanvasPaper: Color
    @Composable get() = LocalAtlasPalette.current.canvasPaper
private val Ink: Color
    @Composable get() = LocalAtlasPalette.current.ink
private val MutedInk: Color
    @Composable get() = LocalAtlasPalette.current.mutedInk
private val Blue: Color
    @Composable get() = LocalAtlasPalette.current.blue
private val BlueDeep: Color
    @Composable get() = LocalAtlasPalette.current.blueDeep
private val Sky: Color
    @Composable get() = LocalAtlasPalette.current.sky
private val Coral: Color
    @Composable get() = LocalAtlasPalette.current.coral
private val White: Color
    @Composable get() = LocalAtlasPalette.current.surface
private val Border: Color
    @Composable get() = LocalAtlasPalette.current.border
private val Mint: Color
    @Composable get() = LocalAtlasPalette.current.mint
private val CardTint: Color
    @Composable get() = LocalAtlasPalette.current.cardTint
private val EmptyTint: Color
    @Composable get() = LocalAtlasPalette.current.emptyTint
private val WarningTint: Color
    @Composable get() = LocalAtlasPalette.current.warningTint

private enum class AtlasPage(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    HOME("Home", Icons.Default.Explore),
    INSTANCES("Instances", Icons.Default.Inventory2),
    MODS("Mods", Icons.Default.Extension),
    DISCOVER("Discover", Icons.Default.Search),
    SERVERS("Servers", Icons.Default.Public),
    ACCOUNTS("Accounts", Icons.Default.Person),
    CONTROLS("Controls", Icons.Default.SportsEsports),
    SYSTEM("System", Icons.Default.Settings)
}

@Composable
fun HorizonLauncherUi(
    status: String,
    darkMode: Boolean,
    onSetDarkMode: (Boolean) -> Unit,
    renderApi: String,
    onSetRenderApi: (String) -> Unit,
    minecraftReady: Boolean,
    toolchainReady: Boolean,
    instances: List<InstanceInfo>,
    selectedInstance: InstanceInfo?,
    worlds: List<WorldInfo>,
    servers: List<ServerInfo>,
    mods: List<ModInfo>,
    versions: List<InstalledVersion>,
    settings: SettingsInfo?,
    playerName: String,
    accounts: AccountsInfo?,
    signInInfo: SignInInfo?,
    platform: PlatformInfo?,
    releases: List<Release>,
    touchControls: TouchControlsInfo?,
    telemetry: TelemetryInfo?,
    browseCatalog: BrowseCatalog?,
    browseItems: List<BrowseItem>,
    browseInstalled: List<BrowseEntry>,
    browseProgress: BrowseProgress?,
    onPlay: () -> Unit,
    onInstall: () -> Unit,
    onImportFolder: () -> Unit,
    onRestoreBackup: () -> Unit,
    onCompileHnmod: () -> Unit,
    onImportBuild: () -> Unit,
    onExportBackup: () -> Unit,
    onShowBuildLog: () -> Unit,
    onShowGameLog: () -> Unit,
    onRefresh: () -> Unit,
    onCreateInstance: (String) -> Unit,
    onSelectInstance: (String) -> Unit,
    onDeleteInstance: (String) -> Unit,
    onAddServer: (String, String) -> Unit,
    onRemoveServer: (Int) -> Unit,
    onImportMod: () -> Unit,
    onImportShader: () -> Unit,
    onRemoveMod: (String) -> Unit,
    onRemoveShader: (String) -> Unit,
    onInstallVersion: (String) -> Unit,
    onRemoveVersion: (String) -> Unit,
    onSelectAccount: (String) -> Unit,
    onAddOffline: (String) -> Unit,
    onRemoveOffline: (String) -> Unit,
    onRemoveAccount: (String) -> Unit,
    onUseGameName: () -> Unit,
    onSignIn: () -> Unit,
    onSaveSettings: (JSONObject) -> Unit,
    onSetNoAi: (Boolean) -> Unit,
    onSetTelemetry: (Boolean) -> Unit,
    onSaveTouchControls: (JSONObject) -> Unit,
    onOpenControlsEditor: () -> Unit,
    onSearchBrowse: (String, String, String) -> Unit,
    onInstallBrowse: (String, String) -> Unit,
    onRemoveBrowse: (String) -> Unit
) {
    CompositionLocalProvider(LocalAtlasPalette provides if (darkMode) DarkAtlasPalette else LightAtlasPalette) {
        val scheme = if (darkMode) darkColorScheme(
            primary = Blue, onPrimary = Color(0xFF111827), secondary = Sky,
            background = CanvasPaper, onBackground = Ink, surface = White, onSurface = Ink,
            surfaceVariant = Border, onSurfaceVariant = MutedInk, outline = Border
        ) else lightColorScheme(
            primary = Blue, onPrimary = Color.White, secondary = BlueDeep,
            background = CanvasPaper, onBackground = Ink, surface = White, onSurface = Ink,
            surfaceVariant = Border, onSurfaceVariant = MutedInk, outline = Border
        )
        MaterialTheme(colorScheme = scheme) {
        var page by remember { mutableStateOf(AtlasPage.HOME) }
        Box(Modifier.fillMaxSize().background(CanvasPaper).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
        AtlasHeader(playerName = playerName, ready = minecraftReady, onRefresh = onRefresh)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            AtlasPage.values().forEach { item ->
                val selected = page == item
                Surface(
                    modifier = Modifier.clickable { page = item },
                    shape = RoundedCornerShape(18.dp),
                    color = if (selected) Ink else White,
                    border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, Border)
                ) {
                    Row(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(item.icon, null, tint = if (selected) White else MutedInk, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(item.label, color = if (selected) White else Ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        when (page) {
            AtlasPage.HOME -> HomeAtlas(
                ready = minecraftReady, toolchainReady = toolchainReady, status = status,
                selected = selectedInstance, instances = instances, worlds = worlds,
                mods = mods, servers = servers, playerName = playerName,
                onPlay = onPlay, onInstall = onInstall, onRefresh = onRefresh,
                onSelectInstance = onSelectInstance, onCreateInstance = onCreateInstance,
                onNavigate = { page = it }
            )
            AtlasPage.INSTANCES -> InstancesAtlas(
                ready = minecraftReady, instances = instances, selected = selectedInstance,
                worlds = worlds, versions = versions, onInstall = onInstall,
                onImport = onImportFolder, onRestore = onRestoreBackup, onExport = onExportBackup,
                onCreate = onCreateInstance, onSelect = onSelectInstance, onDelete = onDeleteInstance,
                onInstallVersion = onInstallVersion, onRemoveVersion = onRemoveVersion
            )
            AtlasPage.MODS -> ModsAtlas(
                mods = mods, installed = browseInstalled, toolchainReady = toolchainReady,
                progress = browseProgress, onCompile = onCompileHnmod, onImportBuild = onImportBuild,
                onBuildLog = onShowBuildLog, onImportMod = onImportMod, onImportShader = onImportShader,
                onRemoveMod = onRemoveMod, onRemoveShader = onRemoveShader, onRemoveBrowse = onRemoveBrowse
            )
            AtlasPage.DISCOVER -> DiscoverAtlas(
                catalog = browseCatalog, results = browseItems, progress = browseProgress,
                onSearch = onSearchBrowse, onInstall = onInstallBrowse
            )
            AtlasPage.SERVERS -> ServersAtlas(servers, onAddServer, onRemoveServer)
            AtlasPage.ACCOUNTS -> AccountsAtlas(accounts, signInInfo, playerName, onSelectAccount, onAddOffline, onRemoveOffline, onRemoveAccount, onUseGameName, onSignIn)
            AtlasPage.CONTROLS -> ControlsAtlas(touchControls, onSaveTouchControls, onOpenControlsEditor)
            AtlasPage.SYSTEM -> SystemAtlas(
                status, settings, platform, releases, telemetry, toolchainReady, darkMode, onSetDarkMode, renderApi, onSetRenderApi,
                onSaveSettings, onSetNoAi, onSetTelemetry, onRefresh, onShowBuildLog, onShowGameLog, onInstallVersion
            )
        }
        }
        if (status.isNotBlank()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 12.dp),
                shape = RoundedCornerShape(16.dp), color = White, tonalElevation = 7.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, Border)
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Sync, null, tint = Blue, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(status, color = Ink, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        }
        }
    }
}

@Composable
private fun AtlasHeader(playerName: String, ready: Boolean, onRefresh: () -> Unit) {
    val palette = LocalAtlasPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(15.dp))
                .background(Brush.linearGradient(listOf(Blue, BlueDeep))),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize().padding(9.dp)) {
                drawCircle(palette.sky, radius = size.minDimension * .43f, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                drawLine(palette.surface, Offset(size.width * .12f, size.height * .65f), Offset(size.width * .78f, size.height * .25f), 2.dp.toPx())
            }
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("HORIZON", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text("WORLD ATLAS  /  ANDROID", color = MutedInk, fontSize = 8.sp, letterSpacing = 1.1.sp)
        }
        Surface(shape = CircleShape, color = if (ready) Mint else WarningTint) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (ready) Color(0xFF398C69) else Color(0xFFD36B55)))
                Spacer(Modifier.width(6.dp))
                Text(if (ready) "READY" else "SETUP", color = Ink, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh", tint = Ink) }
    }
}

@Composable
private fun HomeAtlas(
    ready: Boolean, toolchainReady: Boolean, status: String, selected: InstanceInfo?,
    instances: List<InstanceInfo>, worlds: List<WorldInfo>, mods: List<ModInfo>,
    servers: List<ServerInfo>, playerName: String, onPlay: () -> Unit, onInstall: () -> Unit,
    onRefresh: () -> Unit, onSelectInstance: (String) -> Unit, onCreateInstance: (String) -> Unit,
    onNavigate: (AtlasPage) -> Unit
) {
    var create by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp)) {
        Text("YOUR NEXT ADVENTURE", color = MutedInk, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().height(236.dp).clip(RoundedCornerShape(30.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF6479F2), Color(0xFF27378F), Color(0xFF18234D))))
        ) {
            AtlasLandscape(Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.CenterStart).padding(start = 22.dp, end = 18.dp).widthIn(max = 250.dp)) {
                Surface(color = Color(0x33FFFFFF), shape = RoundedCornerShape(12.dp)) {
                    Text(if (ready) "WORLD LINK ESTABLISHED" else "YOUR WORLD IS WAITING", color = White, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                }
                Spacer(Modifier.height(13.dp))
                Text(selected?.name ?: "Find your\nHorizon.", color = White, fontSize = 29.sp, lineHeight = 31.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(4.dp))
                Text(selected?.version ?: if (ready) "Default Minecraft profile" else "Install or import Minecraft to begin", color = Color(0xFFDCE3FF), fontSize = 10.sp, maxLines = 2)
                Spacer(Modifier.height(15.dp))
                Button(
                    onClick = if (ready) onPlay else onInstall,
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = White, contentColor = BlueDeep),
                    contentPadding = PaddingValues(horizontal = 17.dp, vertical = 10.dp)
                ) {
                    Icon(if (ready) Icons.Default.PlayArrow else Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (ready) "PLAY NOW" else "INSTALL GAME", fontWeight = FontWeight.Black, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(17.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            AtlasMetric("WORLDS", worlds.size.toString(), Color(0xFFE2E8FF), Modifier.weight(1f))
            AtlasMetric("MODS", mods.size.toString(), Color(0xFFFFE6DD), Modifier.weight(1f))
            AtlasMetric("SERVERS", servers.size.toString(), Color(0xFFDDF2E7), Modifier.weight(1f))
        }
        Spacer(Modifier.height(22.dp))
        SectionHeading("Continue where you left off", "Your worlds", onClick = { onNavigate(AtlasPage.INSTANCES) })
        if (worlds.isEmpty()) EmptyState("No saved worlds yet", "Worlds found by the native core will appear here.")
        else worlds.take(4).forEach { world ->
            Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), color = White, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFFE2E8FF)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Landscape, null, tint = Blue)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(world.name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(listOfNotNull(world.version, world.mode.takeIf { it.isNotBlank() }).joinToString(" · "), color = MutedInk, fontSize = 9.sp)
                    }
                    Text(formatBytesAtlas(world.size), color = MutedInk, fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.height(13.dp))
        SectionHeading("Your setup", "Active instance", onClick = { onNavigate(AtlasPage.INSTANCES) })
        Surface(Modifier.fillMaxWidth(), color = White, shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
            Column(Modifier.padding(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(selected?.name ?: "Default profile", color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(selected?.version ?: "No version selected", color = MutedInk, fontSize = 10.sp)
                    }
                    TextButton(onClick = onRefresh) { Text("SYNC", color = Blue, fontWeight = FontWeight.Bold) }
                }
                if (instances.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        instances.forEach { instance ->
                            FilterChip(
                                selected = instance.id == selected?.id,
                                onClick = { onSelectInstance(instance.id) },
                                label = { Text(instance.name, maxLines = 1) }
                            )
                        }
                    }
                }
                TextButton(onClick = { create = true }) { Icon(Icons.Default.Add, null); Text("Create instance") }
            }
        }
        Spacer(Modifier.height(17.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeatureTile("Mod studio", "Compile HNMods and manage add-ons", Icons.Default.Extension, Modifier.weight(1f)) { onNavigate(AtlasPage.MODS) }
            FeatureTile("Touch lab", "Edit your full control layout", Icons.Default.Gamepad, Modifier.weight(1f)) { onNavigate(AtlasPage.CONTROLS) }
        }
        Spacer(Modifier.height(12.dp))
        Text("PLAYER  ·  ${playerName.ifBlank { "Local profile" }}", color = MutedInk, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        Text("HNMod toolchain: ${if (toolchainReady) "ready" else "not installed"}", color = MutedInk, fontSize = 9.sp)
        if (status.isNotBlank()) Text(status, color = MutedInk, fontSize = 9.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(28.dp))
    }
    if (create) NameDialog("Create instance", "Instance name", { create = false }, { create = false; onCreateInstance(it) })
}

@Composable
private fun AtlasLandscape(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawCircle(Color(0x66F9E7B4), radius = h * .31f, center = Offset(w * .78f, h * .30f))
        val back = Path().apply { moveTo(w*.42f,h*.75f); lineTo(w*.60f,h*.39f); lineTo(w*.73f,h*.60f); lineTo(w*.86f,h*.35f); lineTo(w,h*.68f); lineTo(w,h); lineTo(w*.42f,h); close() }
        drawPath(back, Color(0xFF8299ED))
        val front = Path().apply { moveTo(w*.35f,h*.83f); lineTo(w*.54f,h*.58f); lineTo(w*.67f,h*.76f); lineTo(w*.82f,h*.53f); lineTo(w,h*.78f); lineTo(w,h); lineTo(w*.35f,h); close() }
        drawPath(front, Color(0xFF4259B6))
        for (i in 0..5) {
            val x = w * (.53f + i * .075f)
            drawRect(Color(0xFFB9C8FF).copy(alpha=.20f), Offset(x,h*.76f), androidx.compose.ui.geometry.Size(w*.035f,h*.11f))
        }
        drawCircle(Color(0x557CE6E4), radius=h*.46f, center=Offset(w*.86f,h*.90f), style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
    }
}

@Composable
private fun InstancesAtlas(
    ready: Boolean, instances: List<InstanceInfo>, selected: InstanceInfo?, worlds: List<WorldInfo>,
    versions: List<InstalledVersion>, onInstall: () -> Unit, onImport: () -> Unit,
    onRestore: () -> Unit, onExport: () -> Unit, onCreate: (String) -> Unit,
    onSelect: (String) -> Unit, onDelete: (String) -> Unit,
    onInstallVersion: (String) -> Unit, onRemoveVersion: (String) -> Unit
) {
    var create by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        PageHeading("Your library", "Profiles, worlds and engine versions in one place.")
        Surface(color = White, shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
            Column(Modifier.padding(14.dp)) {
                Text(if (ready) "MINECRAFT INSTALLED" else "MINECRAFT NOT INSTALLED", color = if (ready) Color(0xFF388563) else Coral, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Text("Manage game files", color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(top = 12.dp)) {
                    ActionPill("Install", Icons.Default.Download, onInstall, Modifier.weight(1f))
                    ActionPill("Import folder", Icons.Default.FolderOpen, onImport, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(top = 7.dp)) {
                    ActionPill("Restore", Icons.Default.Restore, onRestore, Modifier.weight(1f))
                    ActionPill("Export backup", Icons.Default.Archive, onExport, Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        SectionHeading("Instances", "${instances.size} profiles", onClick = { create = true })
        if (instances.isEmpty()) EmptyState("No instances registered", "Create one to start separating game setups.")
        instances.forEach { instance ->
            DataSurface(
                title = instance.name,
                detail = "${instance.version ?: "Version not assigned"} · ${formatPlayTime(instance.playedSecs)}",
                tag = if (instance.id == selected?.id) "ACTIVE" else "SELECT",
                onClick = { onSelect(instance.id) },
                trailing = { IconButton(onClick = { onDelete(instance.id) }) { Icon(Icons.Default.DeleteOutline, "Delete instance", tint = Coral) } }
            )
        }
        OutlinedButton(onClick = { create = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text("New instance") }
        Spacer(Modifier.height(18.dp))
        SectionHeading("Saved worlds", "${worlds.size} worlds")
        if (worlds.isEmpty()) EmptyState("Nothing to show yet", "The world list comes directly from Horizon core.")
        worlds.forEach { DataSurface(it.name, "${it.version ?: "Unknown version"} · ${it.mode} · ${formatBytesAtlas(it.size)}", null) }
        Spacer(Modifier.height(18.dp))
        SectionHeading("Installed engine versions", "${versions.size} installed")
        if (versions.isEmpty()) EmptyState("No versions installed", "Available versions are listed below when core provides them.")
        versions.forEach { version ->
            DataSurface(version.version, "Tag ${version.tag} · prepared ${version.prepared} · sounds ${version.sounds}", "REMOVE", onClick = { onRemoveVersion(version.tag) })
        }
        Spacer(Modifier.height(25.dp))
    }
    if (create) NameDialog("Create instance", "A name for this profile", { create = false }, { create = false; onCreate(it) })
}

@Composable
private fun ModsAtlas(
    mods: List<ModInfo>, installed: List<BrowseEntry>, toolchainReady: Boolean,
    progress: BrowseProgress?, onCompile: () -> Unit, onImportBuild: () -> Unit,
    onBuildLog: () -> Unit, onImportMod: () -> Unit, onImportShader: () -> Unit,
    onRemoveMod: (String) -> Unit, onRemoveShader: (String) -> Unit, onRemoveBrowse: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        PageHeading("Mod atelier", "Native mods, shaders and HNMod builds. Your game folder stays under core's control.")
        Surface(color = CardTint, shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("HNMOD COMPILER", color = BlueDeep, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                        Text(if (toolchainReady) "Toolchain ready" else "First build installs toolchain", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    Icon(Icons.Default.Terminal, null, tint = Blue, modifier = Modifier.size(32.dp))
                }
                Button(onClick = onCompile, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Build, null); Spacer(Modifier.width(7.dp)); Text("COMPILE HNMOD SOURCE")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onImportBuild) { Text("Import compiled build") }
                    TextButton(onClick = onBuildLog) { Text("Live build log") }
                }
                if (progress?.running == true) {
                    LinearProgressIndicator(progress = { progress.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(progress.line, color = Ink, fontSize = 10.sp)
                } else if (!progress?.error.isNullOrBlank()) Text(progress?.error ?: "", color = Coral, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(17.dp))
        SectionHeading("HMod packages", "${mods.size} installed")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionPill("Import HMod", Icons.Default.Add, onImportMod, Modifier.weight(1f))
            ActionPill("Import shader", Icons.Default.WbSunny, onImportShader, Modifier.weight(1f))
        }
        if (mods.isEmpty()) EmptyState("No HMods found", "Imported packages returned by the native core appear here.")
        mods.forEach { mod ->
            DataSurface(mod.name, "${mod.file} · ${if (mod.enabled) "enabled" else "disabled"}", "REMOVE", onClick = { onRemoveMod(mod.id.ifBlank { mod.name }) })
        }
        Spacer(Modifier.height(17.dp))
        SectionHeading("Core-managed add-ons", "${installed.size} installed")
        if (installed.isEmpty()) EmptyState("No browser add-ons installed", "Items installed through Discover will appear here.")
        installed.forEach { item ->
            val shader = item.kind.contains("shader", ignoreCase = true)
            DataSurface(
                item.title,
                "${item.kind} · ${item.source} · ${item.number}",
                "REMOVE",
                onClick = { if (shader) onRemoveShader(item.title) else onRemoveBrowse(item.project) }
            )
        }
    }
}

@Composable
private fun DiscoverAtlas(
    catalog: BrowseCatalog?, results: List<BrowseItem>, progress: BrowseProgress?,
    onSearch: (String, String, String) -> Unit, onInstall: (String, String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var source by remember(catalog) { mutableStateOf(catalog?.sources?.firstOrNull()?.id ?: "Modrinth") }
    var kind by remember(catalog) { mutableStateOf(catalog?.kinds?.firstOrNull()?.id ?: "Mod") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        PageHeading("Discover", "Search the catalog exposed by Horizon core. Compatibility comes from the engine.")
        Surface(color = White, shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
            Column(Modifier.padding(14.dp)) {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search mods, resource packs…") }, singleLine = true)
                if (catalog != null) {
                    Text("SOURCE", color = MutedInk, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 9.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        catalog.sources.forEach { option -> FilterChip(selected = source == option.id, onClick = { source = option.id }, label = { Text(option.label) }) }
                    }
                    Text("TYPE", color = MutedInk, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        catalog.kinds.forEach { option -> FilterChip(selected = kind == option.id, onClick = { kind = option.id }, label = { Text(option.label) }) }
                    }
                }
                Button(onClick = { onSearch(source, kind, query) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue), shape = RoundedCornerShape(13.dp)) {
                    Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)); Text("SEARCH CATALOG")
                }
            }
        }
        if (progress?.running == true) {
            LinearProgressIndicator(progress = { progress.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            Text(progress.line, color = MutedInk, fontSize = 9.sp)
        }
        Spacer(Modifier.height(12.dp))
        if (results.isEmpty()) EmptyState("No results loaded", "Search a catalog to see real projects and compatibility information.")
        results.forEach { item ->
            Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), color = White, shape = RoundedCornerShape(19.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("by ${item.author} · ${item.downloads} downloads", color = MutedInk, fontSize = 9.sp)
                        }
                        Text(item.support.uppercase(), color = if (item.installable) Color(0xFF388563) else Coral, fontSize = 8.sp, fontWeight = FontWeight.Black)
                    }
                    Text(item.summary, color = MutedInk, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                    if (!item.reason.isNullOrBlank()) Text(item.reason, color = Coral, fontSize = 9.sp, modifier = Modifier.padding(top = 5.dp))
                    Button(
                        onClick = { onInstall(item.source, item.id) },
                        enabled = item.installable && !item.installed,
                        modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(if (item.installed) "INSTALLED" else if (item.installable) "INSTALL" else "UNAVAILABLE") }
                }
            }
        }
    }
}

@Composable
private fun ServersAtlas(servers: List<ServerInfo>, onAdd: (String, String) -> Unit, onRemove: (Int) -> Unit) {
    var add by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        PageHeading("Multiplayer", "Your saved destinations, read and written by the native server list.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${servers.size} SAVED SERVERS", color = MutedInk, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = { add = true }, colors = ButtonDefaults.buttonColors(containerColor = Blue), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Add, null); Text("ADD SERVER") }
        }
        if (servers.isEmpty()) EmptyState("No servers saved", "Add an address to keep it in your Minecraft server list.")
        servers.forEachIndexed { index, server ->
            DataSurface(server.name, server.ip, "REMOVE", onClick = { onRemove(index) })
        }
    }
    if (add) ServerDialog({ add = false }) { name, address -> add = false; onAdd(name, address) }
}

@Composable
private fun AccountsAtlas(
    accounts: AccountsInfo?, signInInfo: SignInInfo?, playerName: String,
    onSelect: (String) -> Unit, onAddOffline: (String) -> Unit, onRemoveOffline: (String) -> Unit,
    onRemoveAccount: (String) -> Unit, onUseGameName: () -> Unit, onSignIn: () -> Unit
) {
    var add by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        PageHeading("Player identity", "Choose the identity the core will use when preparing your game session.")
        Surface(color = CardTint, shape = RoundedCornerShape(23.dp)) {
            Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(54.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, null, tint = White, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(playerName, color = Ink, fontWeight = FontWeight.Black, fontSize = 17.sp)
                    Text(accounts?.player?.kind?.uppercase() ?: "LOCAL PROFILE", color = BlueDeep, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        accounts?.takeIf { it.player.kind == "microsoft" }?.let { microsoftAccounts ->
            microsoftAccounts.accounts.firstOrNull { it.uuid == microsoftAccounts.selected }?.let { selectedAccount ->
                AccountSkinPreview(uuid = selectedAccount.uuid, playerName = selectedAccount.name)
            }
        }
        Spacer(Modifier.height(15.dp))
        SectionHeading("Microsoft accounts", "${accounts?.accounts?.size ?: 0} linked")
        if (signInInfo?.configured == true) {
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Login, null); Spacer(Modifier.width(8.dp)); Text("SIGN IN WITH MICROSOFT")
            }
        } else {
            EmptyState("Microsoft sign-in is not configured in this build", signInInfo?.message ?: "Use an offline profile, or configure the supported Microsoft auth client.")
        }
        accounts?.accounts?.forEach { account ->
            DataSurface(
                account.name, account.uuid, if (accounts.selected == account.uuid) "ACTIVE" else "SELECT",
                onClick = { onSelect(account.uuid) },
                trailing = { IconButton(onClick = { onRemoveAccount(account.uuid) }) { Icon(Icons.Default.DeleteOutline, "Remove Microsoft account", tint = Coral) } }
            )
        }
        Spacer(Modifier.height(16.dp))
        SectionHeading("Offline profiles", "${accounts?.offline?.size ?: 0} saved")
        accounts?.offline?.forEach { name ->
            DataSurface(name, "Offline identity", if (accounts.offlineSelected == name) "ACTIVE" else "SELECT", onClick = { onSelect("offline:$name") }, trailing = {
                IconButton(onClick = { onRemoveOffline(name) }) { Icon(Icons.Default.DeleteOutline, "Remove offline profile", tint = Coral) }
            })
        }
        OutlinedButton(onClick = { add = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text("Add offline profile") }
        OutlinedButton(onClick = onUseGameName, modifier = Modifier.fillMaxWidth().padding(top = 7.dp)) { Icon(Icons.Default.PersonOutline, null); Text("Use Minecraft's own player name") }
        if (accounts != null) Text("Owner gate: ${if (accounts.hasOwner) "open" else "closed"} · keychain: ${if (accounts.keychain) "available" else "not available"}", color = MutedInk, fontSize = 9.sp, modifier = Modifier.padding(top = 12.dp))
    }
    if (add) NameDialog("Add offline profile", "3–16 letters, numbers or _", { add = false }, { add = false; onAddOffline(it) })
}

@Composable
private fun ControlsAtlas(
    controls: TouchControlsInfo?, onSave: (JSONObject) -> Unit, onOpenEditor: () -> Unit
) {
    var enabled by remember(controls) { mutableStateOf(controls?.enabled ?: true) }
    var leftHanded by remember(controls) { mutableStateOf(controls?.leftHanded ?: false) }
    var autoJump by remember(controls) { mutableStateOf(controls?.autoJump ?: false) }
    var holdSprint by remember(controls) { mutableStateOf(controls?.holdToSprint ?: false) }
    var haptics by remember(controls) { mutableStateOf(controls?.haptics ?: true) }
    var opacity by remember(controls) { mutableFloatStateOf(controls?.opacity ?: .72f) }
    var size by remember(controls) { mutableFloatStateOf(controls?.buttonSize ?: 1f) }
    var sensitivity by remember(controls) { mutableFloatStateOf(controls?.lookSensitivity ?: 1f) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        PageHeading("Touch laboratory", "The full Zalith editor is integrated separately from these engine-level preferences.")
        Surface(color = Brush.linearGradient(listOf(Blue, BlueDeep)).let { brush -> White }, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Gamepad, null, tint = Blue, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("ZALITH CONTROL EDITOR", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Black)
                        Text("Drag · resize · layers · styles · joystick", color = MutedInk, fontSize = 9.sp)
                    }
                }
                Button(onClick = onOpenEditor, modifier = Modifier.fillMaxWidth().padding(top = 14.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.OpenInFull, null); Spacer(Modifier.width(7.dp)); Text("OPEN FULL CONTROL EDITOR")
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        SectionHeading("Engine touch preferences", "Core-backed")
        SettingSwitch("Touch controls enabled", enabled) { enabled = it }
        SettingSwitch("Left-handed mode", leftHanded) { leftHanded = it }
        SettingSwitch("Auto jump", autoJump) { autoJump = it }
        SettingSwitch("Hold to sprint", holdSprint) { holdSprint = it }
        SettingSwitch("Haptic feedback", haptics) { haptics = it }
        SliderSetting("Control opacity", opacity, .3f..1f, { opacity = it }, "${(opacity * 100).toInt()}%")
        SliderSetting("Button scale", size, .7f..1.5f, { size = it }, String.format(Locale.US, "%.2fx", size))
        SliderSetting("Look sensitivity", sensitivity, .25f..2f, { sensitivity = it }, String.format(Locale.US, "%.2fx", sensitivity))
        Button(
            onClick = {
                onSave(JSONObject().put("enabled", enabled).put("left_handed", leftHanded)
                    .put("auto_jump", autoJump).put("hold_to_sprint", holdSprint).put("haptics", haptics)
                    .put("opacity", opacity.toDouble()).put("button_size", size.toDouble()).put("look_sensitivity", sensitivity.toDouble()))
            },
            modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Ink), shape = RoundedCornerShape(14.dp)
        ) { Text("SAVE ENGINE PREFERENCES") }
    }
}

@Composable
private fun SystemAtlas(
    status: String, settings: SettingsInfo?, platform: PlatformInfo?, releases: List<Release>,
    telemetry: TelemetryInfo?, toolchainReady: Boolean, darkMode: Boolean, onSetDarkMode: (Boolean) -> Unit,
    renderApi: String, onSetRenderApi: (String) -> Unit,
    onSave: (JSONObject) -> Unit, onSetNoAi: (Boolean) -> Unit, onSetTelemetry: (Boolean) -> Unit,
    onRefresh: () -> Unit, onBuildLog: () -> Unit, onGameLog: () -> Unit, onInstallVersion: (String) -> Unit
) {
    var installNew by remember(settings) { mutableStateOf(settings?.installNewVersions ?: true) }
    var prune by remember(settings) { mutableStateOf(settings?.pruneOldVersions ?: true) }
    var skip by remember(settings) { mutableStateOf(settings?.skipOpening ?: false) }
    var noAi by remember(settings) { mutableStateOf(settings?.noAi ?: false) }
    var gameArgs by remember(settings) { mutableStateOf(settings?.gameArgs ?: "") }
    var proxy by remember(settings) { mutableStateOf(settings?.proxy ?: "") }
    var appearance by remember(settings) { mutableStateOf(settings?.appearance ?: "light") }
    var accent by remember(settings) { mutableStateOf(settings?.accent ?: "violet") }
    var language by remember(settings) { mutableStateOf(settings?.language ?: "") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        PageHeading("System & engine", "Runtime details and the settings the native core currently exposes.")
        Surface(color = White, shape = RoundedCornerShape(21.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
            Column(Modifier.padding(15.dp)) {
                Text("RUNTIME", color = Blue, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.3.sp)
                Text("Horizon Core ${platform?.version ?: "—"}", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Text("${platform?.os ?: "Android"} · ${platform?.arch ?: "device architecture"}", color = MutedInk, fontSize = 10.sp)
                Text("Data: ${platform?.dataDir ?: "unavailable"}", color = MutedInk, fontSize = 9.sp, maxLines = 2)
                Text("HNMod toolchain: ${if (toolchainReady) "ready" else "not installed"}", color = if (toolchainReady) Color(0xFF388563) else Coral, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Text(status, color = MutedInk, fontSize = 9.sp, modifier = Modifier.padding(top = 7.dp))
                TextButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, null); Text("Refresh runtime") }
                TextButton(onClick = onBuildLog) { Icon(Icons.Default.Terminal, null); Text("Open HNMod build log") }
                TextButton(onClick = onGameLog) { Icon(Icons.Default.Terminal, null); Text("View last game log") }
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionHeading("Rendering API", "Applied when Minecraft next starts")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            listOf("auto" to "Auto", "vulkan" to "Vulkan", "opengl" to "OpenGL").forEach { (key, label) ->
                if (renderApi == key) {
                    Button(
                        onClick = { onSetRenderApi(key) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 9.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(label, fontSize = 10.sp, maxLines = 1) }
                } else {
                    OutlinedButton(
                        onClick = { onSetRenderApi(key) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 9.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(label, fontSize = 10.sp, maxLines = 1) }
                }
            }
        }
        Text(
            "OpenGL uses the GLES 3.0+ backend (not OpenGL ES 2.0). Vulkan requires a compatible Android GPU driver. Auto lets Horizon choose.",
            color = MutedInk, fontSize = 9.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(Modifier.height(14.dp))
        SectionHeading("Launcher policy", "Saved by core")
        SettingSwitch("Install new engine versions automatically", installNew) { installNew = it }
        SettingSwitch("Prune old versions", prune) { prune = it }
        SettingSwitch("Skip opening screen", skip) { skip = it }
        SettingSwitch("Disable AI features", noAi) { noAi = it; onSetNoAi(it) }
        OutlinedTextField(gameArgs, { gameArgs = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Game arguments") })
        OutlinedTextField(proxy, { proxy = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Proxy") })
        SettingSwitch("Dark mode", darkMode) { enabled ->
            appearance = if (enabled) "dark" else "light"
            onSetDarkMode(enabled)
        }
        OutlinedTextField(accent, { accent = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Accent preference") })
        OutlinedTextField(language, { language = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Language") })
        Button(
            onClick = { onSave(JSONObject().put("install_new_versions", installNew).put("prune_old_versions", prune)
                .put("skip_opening", skip).put("noAi", noAi).put("game_args", gameArgs)
                .put("proxy", proxy.takeIf { it.isNotBlank() }).put("appearance", appearance).put("accent", accent)
                .put("language", language.takeIf { it.isNotBlank() })) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue), shape = RoundedCornerShape(14.dp)
        ) { Text("SAVE CORE SETTINGS") }
        Spacer(Modifier.height(18.dp))
        SectionHeading("Diagnostics", "Privacy controls")
        SettingSwitch("Share anonymous diagnostics", telemetry?.any ?: false, onSetTelemetry)
        Text("Telemetry preferences are sent to the native core; nothing is enabled by this screen without your action.", color = MutedInk, fontSize = 9.sp)
        Spacer(Modifier.height(18.dp))
        SectionHeading("Available engine releases", "${releases.size} returned")
        if (releases.isEmpty()) EmptyState("No release metadata", "The native core did not return a release list.")
        releases.take(15).forEach { release ->
            DataSurface(
                release.name.ifBlank { release.tag },
                "${release.tag} · ${release.date} · ${if (release.nightly) "Nightly" else if (release.prerelease) "Pre-release" else "Release"}",
                "INSTALL",
                onClick = { onInstallVersion(release.tag) }
            )
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Black)
            Text(subtitle, color = MutedInk, fontSize = 9.sp)
        }
        if (onClick != null) TextButton(onClick = onClick) { Text("VIEW ALL", color = Blue, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Text(title, color = Ink, fontSize = 27.sp, fontWeight = FontWeight.Black, letterSpacing = (-.6).sp)
    Text(subtitle, color = MutedInk, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
}

@Composable
private fun AtlasMetric(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(modifier, color = tint, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(13.dp)) {
            Text(value, color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Black)
            Text(label, color = MutedInk, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun FeatureTile(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), color = White, shape = RoundedCornerShape(19.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(13.dp)) {
            Icon(icon, null, tint = Blue, modifier = Modifier.size(22.dp))
            Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(top = 9.dp))
            Text(description, color = MutedInk, fontSize = 9.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun DataSurface(
    title: String, detail: String, tag: String? = null, onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        color = White, shape = RoundedCornerShape(17.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Border)
    ) {
        Row(Modifier.padding(start = 13.dp, top = 12.dp, bottom = 12.dp, end = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, color = MutedInk, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            if (tag != null) Text(tag, color = Blue, fontSize = 8.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 7.dp))
            trailing?.invoke()
        }
    }
}

@Composable
private fun ActionPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 9.dp)) {
        Icon(icon, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text(label, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun EmptyState(title: String, detail: String) {
    Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), color = EmptyTint, shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.padding(15.dp)) {
            Text(title, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = MutedInk, fontSize = 9.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun SliderSetting(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, valueText: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Ink, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = Blue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Slider(value, onChange, valueRange = range)
    }
}

@Composable
private fun NameDialog(title: String, hint: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text(hint) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ServerDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add multiplayer server") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Server name") }, singleLine = true)
                OutlinedTextField(address, { address = it }, label = { Text("Address / host:port") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank() && address.isNotBlank()) onConfirm(name.trim(), address.trim()) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun formatBytesAtlas(bytes: Long): String = when {
    bytes < 1024 -> "${bytes} B"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
private fun formatPlayTime(seconds: Long): String = if (seconds < 3600) "${seconds / 60} min" else "${seconds / 3600} h ${(seconds % 3600) / 60} min"
