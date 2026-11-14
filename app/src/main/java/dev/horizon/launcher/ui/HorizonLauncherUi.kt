package dev.horizon.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.horizon.launcher.data.*
import org.json.JSONObject
import java.util.Locale

// Horizon launcher UI, PlayStation-style: always dark, one hero screen, four bottom destinations.
// Only what a normal player needs is shown here. Developer tools (HNMod compiler, build log, engine
// versions, instances, proxy, game arguments...) are intentionally not part of this UI.

private val CanvasBg = Color(0xFF070B18)
private val Ink = Color(0xFFF5F7FF)
private val MutedInk = Color(0xFF9AA3B8)
private val Blue = Color(0xFF4DA3FF)
private val PsBlue = Color(0xFF0070D1)
private val Sky = Color(0xFF7CC4FF)
private val Coral = Color(0xFFFF7A6B)
private val Positive = Color(0xFF3DDB9A)
private val Glass = Color(0xC7121A2E)
private val Hairline = Color(0x99232C45)
private val CardTint = Color(0xFF1A2542)

private val PsScheme = darkColorScheme(
    primary = Blue, onPrimary = Color(0xFF06121F), secondary = Sky,
    background = CanvasBg, onBackground = Ink, surface = Color(0xFF121A2E), onSurface = Ink,
    surfaceVariant = Color(0xFF232C45), onSurfaceVariant = MutedInk, outline = Color(0xFF232C45),
    secondaryContainer = CardTint, onSecondaryContainer = Ink
)

@Composable
private fun psButtonColors() = ButtonDefaults.buttonColors(containerColor = PsBlue, contentColor = Color.White)

private enum class HomeTab(val label: String, val icon: ImageVector) {
    PLAY("Play", Icons.Default.PlayArrow),
    MODS("Mods", Icons.Default.Extension),
    COMPILE("Compile", Icons.Default.Build),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Suppress("UNUSED_PARAMETER")
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
    onInstallHpack: () -> Unit,
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
    MaterialTheme(colorScheme = PsScheme) {
        var tab by remember { mutableStateOf(HomeTab.PLAY) }
        var accountsOpen by remember { mutableStateOf(false) }
        BackHandler(enabled = accountsOpen) { accountsOpen = false }
        Box(Modifier.fillMaxSize().background(CanvasBg)) {
            AuroraBackground()
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (accountsOpen) {
                        AccountsPage(
                            accounts, signInInfo, playerName, onSelectAccount, onAddOffline,
                            onRemoveOffline, onRemoveAccount, onSignIn
                        ) { accountsOpen = false }
                    } else {
                        Crossfade(targetState = tab, animationSpec = tween(200), label = "tab", modifier = Modifier.fillMaxSize()) { current ->
                            when (current) {
                                HomeTab.PLAY -> PlayPage(
                                    ready = minecraftReady, version = selectedInstance?.version, playerName = playerName,
                                    onPlay = onPlay, onInstall = onInstall, onImportFolder = onImportFolder,
                                    onRefresh = onRefresh, onOpenAccounts = { accountsOpen = true },
                                    onOpenControlsEditor = onOpenControlsEditor
                                )
                                HomeTab.MODS -> ModsPage(
                                    browseCatalog, browseItems, browseProgress, mods, browseInstalled,
                                    onSearchBrowse, onInstallBrowse, onImportMod, onImportShader,
                                    onRemoveMod, onRemoveShader, onRemoveBrowse
                                )
                                HomeTab.COMPILE -> BuildPage(
                                    toolchainReady = toolchainReady,
                                    onCompile = onCompileHnmod,
                                    onInstallHpack = onInstallHpack,
                                    onImportBuild = onImportBuild,
                                    onShowBuildLog = onShowBuildLog
                                )
                                HomeTab.SETTINGS -> SettingsPage(
                                    playerName, accounts, renderApi, onSetRenderApi, telemetry, onSetTelemetry,
                                    platform, onExportBackup, onRestoreBackup, onShowGameLog
                                ) { accountsOpen = true }
                            }
                        }
                    }
                }
                PsNavBar(tab) { tab = it; accountsOpen = false }
            }
            if (status.isNotBlank()) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 88.dp),
                    shape = RoundedCornerShape(16.dp), color = Color(0xFF16203A),
                    border = BorderStroke(1.dp, Hairline)
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Sync, null, tint = Blue, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(status, color = Ink, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun PsNavBar(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    Surface(
        modifier = Modifier.navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp).fillMaxWidth(),
        shape = RoundedCornerShape(30.dp), color = Color(0xEE121A2E), border = BorderStroke(1.dp, Hairline)
    ) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            HomeTab.values().forEach { item ->
                val on = item == selected
                val bg by animateColorAsState(if (on) PsBlue else Color.Transparent, tween(180), label = "navBg")
                Row(
                    Modifier.clip(CircleShape).background(bg).clickable { onSelect(item) }
                        .padding(horizontal = if (on) 16.dp else 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(item.icon, item.label, tint = if (on) Color.White else MutedInk, modifier = Modifier.size(22.dp))
                    AnimatedVisibility(visible = on) {
                        Row {
                            Spacer(Modifier.width(8.dp))
                            Text(item.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Play

@Composable
private fun PlayPage(
    ready: Boolean, version: String?, playerName: String, onPlay: () -> Unit, onInstall: () -> Unit,
    onImportFolder: () -> Unit, onRefresh: () -> Unit, onOpenAccounts: () -> Unit,
    onOpenControlsEditor: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(PsBlue), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                    drawCircle(Color.White, radius = size.minDimension * .43f, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    drawLine(Color.White, Offset(size.width * .12f, size.height * .65f), Offset(size.width * .78f, size.height * .25f), 2.dp.toPx())
                }
            }
            Spacer(Modifier.width(10.dp))
            Text("HORIZON", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh", tint = MutedInk) }
            Surface(onClick = onOpenAccounts, shape = CircleShape, color = Glass, border = BorderStroke(1.dp, Hairline)) {
                Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(PsBlue), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        playerName.ifBlank { "Player" }, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f).heightIn(max = 520.dp).clip(RoundedCornerShape(32.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF0B3F8F), Color(0xFF3A2C9E), Color(0xFF1B1F5E))))
        ) {
            CoverArt(Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB005081A)))))
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(18.dp), shape = CircleShape,
                color = Color(0x55000000)
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (ready) Positive else Coral))
                    Spacer(Modifier.width(7.dp))
                    Text(if (ready) "Ready to play" else "Setup needed", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                Text("Minecraft", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black, lineHeight = 38.sp)
                if (!version.isNullOrBlank()) Text("Version $version", color = Color(0xFFDCE6FF), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = if (ready) onPlay else onInstall,
                modifier = Modifier.weight(1.35f).height(58.dp), shape = CircleShape, colors = psButtonColors()
            ) {
                Icon(if (ready) Icons.Default.PlayArrow else Icons.Default.Download, null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (ready) "Play" else "Install", fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
            OutlinedButton(
                onClick = onOpenControlsEditor,
                modifier = Modifier.weight(1f).height(58.dp),
                shape = CircleShape,
                border = BorderStroke(1.dp, Hairline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
            ) {
                Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Controls", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
            }
        }
        if (!ready) {
            TextButton(onClick = onImportFolder, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("I already have the game files", color = Blue, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun CoverArt(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawCircle(Color(0x44FFE9B0), radius = w * .26f, center = Offset(w * .74f, h * .24f))
        val back = Path().apply {
            moveTo(0f, h * .70f); lineTo(w * .26f, h * .44f); lineTo(w * .42f, h * .60f); lineTo(w * .62f, h * .36f)
            lineTo(w * .84f, h * .62f); lineTo(w, h * .50f); lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(back, Color(0x553F7BFF))
        val front = Path().apply {
            moveTo(0f, h * .82f); lineTo(w * .22f, h * .62f); lineTo(w * .40f, h * .78f); lineTo(w * .66f, h * .52f)
            lineTo(w, h * .80f); lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(front, Color(0x66101A52))
    }
}

// ---------------------------------------------------------------- Mods

@Composable
private fun ModsPage(
    catalog: BrowseCatalog?, results: List<BrowseItem>, progress: BrowseProgress?,
    mods: List<ModInfo>, installed: List<BrowseEntry>,
    onSearch: (String, String, String) -> Unit, onInstall: (String, String) -> Unit,
    onImportMod: () -> Unit, onImportShader: () -> Unit,
    onRemoveMod: (String) -> Unit, onRemoveShader: (String) -> Unit, onRemoveBrowse: (String) -> Unit
) {
    var section by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var source by remember(catalog) { mutableStateOf(catalog?.sources?.firstOrNull()?.id ?: "Modrinth") }
    var kind by remember(catalog) { mutableStateOf(catalog?.kinds?.firstOrNull()?.id ?: "Mod") }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        PageTitle("Mods", "Find mods, shaders and resource packs.")
        Segmented(listOf("Browse", "Installed"), section) { section = it }
        Spacer(Modifier.height(12.dp))
        if (progress?.running == true) {
            LinearProgressIndicator(progress = { progress.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = PsBlue)
            Text(progress.line, color = MutedInk, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        } else if (!progress?.error.isNullOrBlank()) {
            Text(progress?.error ?: "", color = Coral, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (section == 0) {
                item {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search mods, shaders, packs") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) }, shape = CircleShape,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch(source, kind, query) })
                    )
                    if (catalog != null && catalog.sources.size > 1) {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            catalog.sources.forEach { option -> FilterChip(selected = source == option.id, onClick = { source = option.id }, label = { Text(option.label) }) }
                        }
                    }
                    if (catalog != null && catalog.kinds.size > 1) {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            catalog.kinds.forEach { option -> FilterChip(selected = kind == option.id, onClick = { kind = option.id }, label = { Text(option.label) }) }
                        }
                    }
                    Button(
                        onClick = { onSearch(source, kind, query) },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp).height(48.dp),
                        shape = CircleShape, colors = psButtonColors()
                    ) { Text("Search", fontWeight = FontWeight.Bold) }
                }
                if (results.isEmpty()) item { EmptyHint("Nothing to show yet", "Search above to find something to install.") }
                items(results) { item ->
                    GlassCard(Modifier.padding(bottom = 10.dp)) {
                        Text(item.title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("by ${item.author} · ${item.downloads} downloads", color = MutedInk, fontSize = 11.sp)
                        Text(item.summary, color = MutedInk, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        if (!item.reason.isNullOrBlank()) Text(item.reason, color = Coral, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                        Button(
                            onClick = { onInstall(item.source, item.id) },
                            enabled = item.installable && !item.installed,
                            modifier = Modifier.align(Alignment.End).padding(top = 10.dp),
                            colors = psButtonColors(), shape = CircleShape
                        ) { Text(if (item.installed) "Installed" else if (item.installable) "Install" else "Not compatible") }
                    }
                }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                        ActionPill("Add mod file", Icons.Default.Add, onImportMod, Modifier.weight(1f))
                        ActionPill("Add shader", Icons.Default.WbSunny, onImportShader, Modifier.weight(1f))
                    }
                }
                if (mods.isEmpty() && installed.isEmpty()) item { EmptyHint("Nothing installed", "Mods you install will show up here.") }
                items(mods) { mod ->
                    DataRow(
                        mod.name, if (mod.enabled) "Enabled" else "Disabled",
                        trailing = { RemoveButton { onRemoveMod(mod.id.ifBlank { mod.name }) } }
                    )
                }
                items(installed) { item ->
                    val shader = item.kind.contains("shader", ignoreCase = true)
                    DataRow(
                        item.title, "${item.kind} · ${item.source}",
                        trailing = { RemoveButton { if (shader) onRemoveShader(item.title) else onRemoveBrowse(item.project) } }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Compile

@Composable
private fun BuildPage(
    toolchainReady: Boolean,
    onCompile: () -> Unit,
    onInstallHpack: () -> Unit,
    onImportBuild: () -> Unit,
    onShowBuildLog: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        PageTitle("Compile", "Build a native Android game version from HNMod source.")
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(CardTint),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Terminal, null, tint = Blue, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Embedded HNMod compiler", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (toolchainReady) "Private compiler runtime ready"
                        else "Installed automatically when you compile for the first time",
                        color = MutedInk, fontSize = 11.sp
                    )
                }
                Box(
                    Modifier.clip(CircleShape).background(if (toolchainReady) Color(0x223DDB9A) else CardTint)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        if (toolchainReady) "READY" else "ON DEMAND",
                        color = if (toolchainReady) Positive else Sky,
                        fontSize = 10.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        GlassCard {
            Text("Compile native mods", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Black)
            Text(
                "Choose one or more .hnmod files. Horizon checks their engine and mixins compatibility, " +
                    "applies the mods, compiles the Android game library and activates the result as a separate build.",
                color = MutedInk, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp)
            )
            Button(
                onClick = onCompile,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp),
                shape = CircleShape, colors = psButtonColors()
            ) {
                Icon(Icons.Default.Build, null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text("Select HNMods and compile", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))
        GlassCard {
            Text("Horizon modpacks", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "Install a .hpack from the official registry into an isolated profile. Profile activation is not available yet.",
                color = MutedInk, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
            )
            ActionPill("Install .hpack modpack", Icons.Default.Inventory2, onInstallHpack, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        GlassCard {
            Text("Other build actions", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            ActionPill("Import compiled game library", Icons.Default.FileDownload, onImportBuild, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            ActionPill("View live build log", Icons.Default.Description, onShowBuildLog, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storage, null, tint = Sky, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Before the first build", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Requires Android 10+ on an ARM64 device and about 10 GB of free storage. " +
                    "The first build installs the private compiler and downloads/builds its dependencies; " +
                    "later builds can reuse the cache and are usually faster.",
                color = MutedInk, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, null, tint = Coral, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Only compile mods you trust", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Native mod build scripts and procedural macros run with Horizon's app permissions. " +
                    "The compiler is not sandboxed.",
                color = MutedInk, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ---------------------------------------------------------------- Settings

@Composable
private fun SettingsPage(
    playerName: String, accounts: AccountsInfo?, renderApi: String, onSetRenderApi: (String) -> Unit,
    telemetry: TelemetryInfo?, onSetTelemetry: (Boolean) -> Unit, platform: PlatformInfo?,
    onExportBackup: () -> Unit, onRestoreBackup: () -> Unit, onShowGameLog: () -> Unit, onOpenAccounts: () -> Unit
) {
    val apiKeys = listOf("auto", "vulkan", "opengl")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        PageTitle("Settings")
        GlassCard(onClick = onOpenAccounts) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(46)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(playerName.ifBlank { "Player" }, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(accountKindLabel(accounts), color = MutedInk, fontSize = 12.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = MutedInk)
            }
        }
        SectionLabel("Graphics")
        Segmented(listOf("Auto", "Vulkan", "OpenGL"), apiKeys.indexOf(renderApi).coerceAtLeast(0)) { onSetRenderApi(apiKeys[it]) }
        Text("Auto works on most phones. Changes apply the next time the game starts.", color = MutedInk, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        SectionLabel("Backup")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionPill("Export", Icons.Default.Archive, onExportBackup, Modifier.weight(1f))
            ActionPill("Restore", Icons.Default.Restore, onRestoreBackup, Modifier.weight(1f))
        }
        SectionLabel("Privacy")
        ToggleRow("Share anonymous diagnostics", telemetry?.any ?: false, onSetTelemetry)
        SectionLabel("Help")
        ActionPill("View last game log", Icons.Default.Description, onShowGameLog, Modifier.fillMaxWidth())
        if (platform != null) Text("Horizon ${platform.version}", color = MutedInk, fontSize = 11.sp, modifier = Modifier.padding(top = 18.dp, bottom = 20.dp))
    }
}

// ---------------------------------------------------------------- Account

@Composable
private fun AccountsPage(
    accounts: AccountsInfo?, signInInfo: SignInInfo?, playerName: String,
    onSelect: (String) -> Unit, onAddOffline: (String) -> Unit, onRemoveOffline: (String) -> Unit,
    onRemoveAccount: (String) -> Unit, onSignIn: () -> Unit, onBack: () -> Unit
) {
    var add by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink) }
            Text("Account", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(8.dp))
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(54)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(playerName.ifBlank { "Player" }, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Text(accountKindLabel(accounts), color = MutedInk, fontSize = 12.sp)
                }
            }
        }
        accounts?.takeIf { it.player.kind == "microsoft" }?.let { microsoft ->
            microsoft.accounts.firstOrNull { it.uuid == microsoft.selected }?.let { selected ->
                AccountSkinPreview(uuid = selected.uuid, playerName = selected.name)
            }
        }
        SectionLabel("Microsoft")
        if (signInInfo?.configured == true) {
            Button(
                onClick = onSignIn, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).height(50.dp),
                shape = CircleShape, colors = psButtonColors()
            ) {
                Icon(Icons.Default.Login, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sign in with Microsoft", fontWeight = FontWeight.Bold)
            }
        } else if (accounts?.accounts.isNullOrEmpty()) {
            EmptyHint("Microsoft sign-in isn't available", "Use an offline profile instead.")
        }
        accounts?.accounts?.forEach { account ->
            DataRow(
                account.name, "Microsoft account", active = accounts.selected == account.uuid,
                onClick = { onSelect(account.uuid) },
                trailing = { RemoveButton { onRemoveAccount(account.uuid) } }
            )
        }
        SectionLabel("Offline profiles")
        accounts?.offline?.forEach { name ->
            DataRow(
                name, "Offline profile", active = accounts.offlineSelected == name,
                onClick = { onSelect("offline:$name") },
                trailing = { RemoveButton { onRemoveOffline(name) } }
            )
        }
        ActionPill("Add offline profile", Icons.Default.Add, { add = true }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
    }
    if (add) NameDialog("Add offline profile", "3–16 letters, numbers or _", { add = false }, { add = false; onAddOffline(it) })
}

private fun accountKindLabel(accounts: AccountsInfo?): String = when (accounts?.player?.kind) {
    "microsoft" -> "Microsoft account"
    "offline" -> "Offline profile"
    else -> "Local profile"
}

// ---------------------------------------------------------------- Shared pieces

@Composable
private fun Avatar(sizeDp: Int) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(PsBlue), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size((sizeDp * .55f).dp))
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String? = null) {
    Text(title, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = (-.5).sp, modifier = Modifier.padding(top = 8.dp))
    Box(Modifier.padding(top = 6.dp).width(44.dp).height(3.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(Sky, PsBlue))))
    if (subtitle != null) Text(subtitle, color = MutedInk, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SectionLabel(title: String) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(18.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Sky, PsBlue))))
        Spacer(Modifier.width(10.dp))
        Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.clip(RoundedCornerShape(22.dp)).clickable { onClick() } else Modifier),
        color = Glass, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Hairline)
    ) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
private fun DataRow(
    title: String, detail: String, active: Boolean = false, onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(20.dp)).clickable { onClick() } else Modifier),
        color = if (active) CardTint else Glass, shape = RoundedCornerShape(20.dp),
        border = BorderStroke(if (active) 1.5.dp else 1.dp, if (active) Blue else Hairline)
    ) {
        Row(Modifier.padding(start = 15.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, color = MutedInk, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            if (active) {
                Surface(color = Blue.copy(alpha = .18f), shape = CircleShape, modifier = Modifier.padding(horizontal = 6.dp)) {
                    Text("Active", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun RemoveButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Default.DeleteOutline, "Remove", tint = Coral) }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(Glass).border(1.dp, Hairline, CircleShape).padding(4.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(CircleShape).background(if (on) PsBlue else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) { Text(label, color = if (on) Color.White else MutedInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun ActionPill(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier, color = CardTint, shape = CircleShape, border = BorderStroke(1.dp, Hairline)) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = Blue, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun EmptyHint(title: String, detail: String) {
    Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp), color = Color(0xFF0F1626), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Hairline)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, null, tint = MutedInk, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(detail, color = MutedInk, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 3.dp), color = Glass, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Hairline)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = PsBlue, checkedThumbColor = Color.White))
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, valueText: String) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 3.dp), color = Glass, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Hairline)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(valueText, color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Slider(value, onChange, valueRange = range, colors = SliderDefaults.colors(thumbColor = Blue, activeTrackColor = PsBlue))
        }
    }
}

@Composable
private fun NameDialog(title: String, hint: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121A2E), titleContentColor = Ink, textContentColor = Ink,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text(hint) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
