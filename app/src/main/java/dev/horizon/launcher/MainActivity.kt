package dev.horizon.launcher

import android.net.Uri
import android.content.Intent
import android.net.Uri as AndroidUri
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.horizon.launcher.ui.HorizonLauncherUi
import androidx.activity.result.contract.ActivityResultContracts
import dev.horizon.launcher.core.Native
import dev.horizon.launcher.data.Core
import dev.horizon.launcher.data.AccountsInfo
import dev.horizon.launcher.data.Browse
import dev.horizon.launcher.data.BrowseCatalog
import dev.horizon.launcher.data.BrowseItem
import dev.horizon.launcher.data.BrowseEntry
import dev.horizon.launcher.data.BrowseProgress
import dev.horizon.launcher.data.PlatformInfo
import dev.horizon.launcher.data.Release
import dev.horizon.launcher.data.SignInInfo
import dev.horizon.launcher.data.TelemetryInfo
import dev.horizon.launcher.auth.MicrosoftAuth
import dev.horizon.launcher.data.InstanceInfo
import dev.horizon.launcher.data.InstalledVersion
import dev.horizon.launcher.data.ModInfo
import dev.horizon.launcher.data.ServerInfo
import dev.horizon.launcher.data.SettingsInfo
import dev.horizon.launcher.data.WorldInfo
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Temporary launcher shell with no UI toolkit: just enough buttons to install, restore, export and play
 * while the real launcher UI is rebuilt piece by piece. All behaviour lives in [GameSetup].
 */
class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statusText by mutableStateOf("")
    private var darkModeState by mutableStateOf(false)
    private var renderApiState by mutableStateOf("auto")
    private var minecraftReadyState by mutableStateOf(false)
    private var toolchainReadyState by mutableStateOf(false)
    private var instancesState by mutableStateOf<List<InstanceInfo>>(emptyList())
    private var selectedInstanceState by mutableStateOf<InstanceInfo?>(null)
    private var worldsState by mutableStateOf<List<WorldInfo>>(emptyList())
    private var serversState by mutableStateOf<List<ServerInfo>>(emptyList())
    private var modsState by mutableStateOf<List<ModInfo>>(emptyList())
    private var versionsState by mutableStateOf<List<InstalledVersion>>(emptyList())
    private var settingsState by mutableStateOf<SettingsInfo?>(null)
    private var playerNameState by mutableStateOf("Local profile")
    private var accountsState by mutableStateOf<AccountsInfo?>(null)
    private var signInInfoState by mutableStateOf<SignInInfo?>(null)
    private var platformState by mutableStateOf<PlatformInfo?>(null)
    private var releasesState by mutableStateOf<List<Release>>(emptyList())
    private var telemetryState by mutableStateOf<TelemetryInfo?>(null)
    private var browseCatalogState by mutableStateOf<BrowseCatalog?>(null)
    private var browseItemsState by mutableStateOf<List<BrowseItem>>(emptyList())
    private var browseInstalledState by mutableStateOf<List<BrowseEntry>>(emptyList())
    private var browseProgressState by mutableStateOf<BrowseProgress?>(null)
    private val buttons = mutableListOf<Button>()
    private var busy = false
    private var initError: String? = null

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) run("Importing Minecraft folder") {
            val count = GameSetup.importFolder(this, uri, this::show)
            refreshDashboardState()
            "Imported $count files."
        }
    }
    private val backupPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) run("Restoring backup") {
            val count = GameSetup.restoreBackup(this, uri, this::show)
            refreshDashboardState()
            "Restored $count Minecraft resource set(s)."
        }
    }
    private val hnmodPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) confirmAndCompileHnmods(uris)
    }
    private val compiledBuildPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) confirmAndImportBuild(uri)
    }
    private val modPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importNativeFile(uri, shader = false)
    }
    private val shaderPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importNativeFile(uri, shader = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkModeState = getSharedPreferences("horizon-ui", MODE_PRIVATE).getBoolean("dark_mode", false)
        renderApiState = getSharedPreferences("horizon-renderer", MODE_PRIVATE).getString("api", "auto")?.takeIf { it in setOf("auto", "vulkan", "opengl") } ?: "auto"
        dev.horizon.launcher.data.Updater.prepare(filesDir, this)

        val home = File(filesDir, "launcher").apply { mkdirs() }
        runCatching { File(home, "render-api.txt").readText().trim() }.getOrNull()
            ?.takeIf { it in setOf("auto", "vulkan", "opengl") }?.let { renderApiState = it }
        val gameHome = File(home, "game").apply { mkdirs() }
        val resourceError = runCatching { dev.horizon.launcher.data.Updater.syncEngineData(this, gameHome) }.exceptionOrNull()?.message
        initError = Native.loadError ?: runCatching { Native.nativeInit(home.absolutePath) }.exceptionOrNull()?.message ?: resourceError
        dev.horizon.launcher.data.Updater.armGuard(filesDir, "launcher")

        setContent {
            HorizonLauncherUi(
                status = statusText,
                darkMode = darkModeState,
                onSetDarkMode = { enabled ->
                    darkModeState = enabled
                    getSharedPreferences("horizon-ui", MODE_PRIVATE).edit().putBoolean("dark_mode", enabled).apply()
                },
                renderApi = renderApiState,
                onSetRenderApi = { api ->
                    if (api in setOf("auto", "vulkan", "opengl")) {
                        renderApiState = api
                        getSharedPreferences("horizon-renderer", MODE_PRIVATE).edit().putString("api", api).apply()
                        runCatching { File(File(filesDir, "launcher").apply { mkdirs() }, "render-api.txt").writeText(api) }
                        show("Rendering API set to ${api.uppercase()}. Restart Minecraft to apply.")
                    }
                },
                minecraftReady = minecraftReadyState,
                toolchainReady = toolchainReadyState,
                instances = instancesState,
                selectedInstance = selectedInstanceState,
                worlds = worldsState,
                servers = serversState,
                mods = modsState,
                versions = versionsState,
                settings = settingsState,
                playerName = playerNameState,
                accounts = accountsState,
                signInInfo = signInInfoState,
                platform = platformState,
                releases = releasesState,
                telemetry = telemetryState,
                browseCatalog = browseCatalogState,
                browseItems = browseItemsState,
                browseInstalled = browseInstalledState,
                browseProgress = browseProgressState,
                onPlay = { GameSetup.play(this).onFailure { show("Could not open the game: " + it.message) } },
                onInstall = {
                    run("Installing Minecraft") {
                        GameSetup.install(this, this::show)
                        refreshDashboardState()
                        "Minecraft is ready."
                    }
                },
                onImportFolder = { folderPicker.launch(null) },
                onRestoreBackup = { backupPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                onCompileHnmod = { hnmodPicker.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
                onImportBuild = { compiledBuildPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                onExportBackup = {
                    run("Exporting backup") {
                        val destination = GameSetup.exportBackup(this, this::show)
                        refreshDashboardState()
                        "Saved to $destination"
                    }
                },
                onShowBuildLog = { showLiveHnmodLog() },
                onShowGameLog = { showGameLog("Last game log", includeLogcat = true) },
                onRefresh = { refreshDashboard() },
                onCreateInstance = { name ->
                    run("Creating instance") {
                        val created = Core.createInstance(name)
                        refreshDashboardState()
                        "Created ${created.name}."
                    }
                },
                onSelectInstance = { id ->
                    run("Selecting instance") {
                        val selected = Core.selectInstance(id).firstOrNull { it.id == id }
                        refreshDashboardState()
                        "Selected ${selected?.name ?: "instance"}."
                    }
                },
                onDeleteInstance = { id ->
                    run("Deleting instance") {
                        Core.deleteInstance(id)
                        refreshDashboardState()
                        "Instance deleted."
                    }
                },
                onAddServer = { name, address ->
                    run("Saving server") {
                        Core.addServer(name, address)
                        refreshDashboardState()
                        "Saved $name."
                    }
                },
                onRemoveServer = { index ->
                    run("Removing server") {
                        Core.removeServer(index)
                        refreshDashboardState()
                        "Server removed."
                    }
                },
                onImportMod = { modPicker.launch(arrayOf("application/java-archive", "application/octet-stream", "*/*")) },
                onImportShader = { shaderPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                onRemoveMod = { id ->
                    run("Removing mod") {
                        Core.removeMod(id)
                        refreshDashboardState()
                        "Mod removed."
                    }
                },
                onRemoveShader = { name ->
                    run("Removing shader pack") {
                        Core.removeShader(name)
                        refreshDashboardState()
                        "Shader pack removed."
                    }
                },
                onInstallVersion = { tag ->
                    run("Installing engine version") {
                        val installed = Core.installVersion(tag)
                        refreshDashboardState()
                        "Installed $installed."
                    }
                },
                onRemoveVersion = { tag ->
                    run("Removing engine version") {
                        Core.removeVersion(tag)
                        refreshDashboardState()
                        "Removed $tag."
                    }
                },
                onSelectAccount = { id ->
                    run("Selecting player identity") {
                        if (id.startsWith("offline:")) Core.playOffline(id.removePrefix("offline:"))
                        else Core.selectAccount(id)
                        refreshDashboardState()
                        "Player identity updated."
                    }
                },
                onAddOffline = { name ->
                    run("Adding offline profile") {
                        Core.addOffline(name)
                        refreshDashboardState()
                        "Offline profile added."
                    }
                },
                onRemoveOffline = { name ->
                    run("Removing offline profile") {
                        Core.removeOffline(name)
                        refreshDashboardState()
                        "Offline profile removed."
                    }
                },
                onRemoveAccount = { uuid ->
                    run("Removing Microsoft account") {
                        Core.removeAccount(uuid)
                        refreshDashboardState()
                        "Microsoft account removed."
                    }
                },
                onUseGameName = {
                    run("Selecting Minecraft player name") {
                        Core.playAsGameName()
                        refreshDashboardState()
                        "Using the game's own player name."
                    }
                },
                onSignIn = { beginMicrosoftSignIn() },
                onSaveSettings = { patch ->
                    run("Saving core settings") {
                        Core.saveSettings(patch)
                        refreshDashboardState()
                        "Core settings saved."
                    }
                },
                onSetTelemetry = { enabled ->
                    run("Updating diagnostics preference") {
                        Core.setTelemetry(enabled)
                        refreshDashboardState()
                        "Diagnostics preference updated."
                    }
                },
                onSearchBrowse = { source, kind, query ->
                    run("Searching add-on catalog") {
                        val result = Browse.search(source, kind, query, 0)
                        browseItemsState = result.first
                        browseProgressState = Browse.progress()
                        "Found ${result.first.size} results."
                    }
                },
                onInstallBrowse = { source, id ->
                    run("Installing catalog item") {
                        val installed = Browse.install(source, id)
                        refreshDashboardState()
                        "Installed ${installed.joinToString()}."
                    }
                },
                onRemoveBrowse = { project ->
                    run("Removing catalog item") {
                        val removed = Browse.remove(project)
                        refreshDashboardState()
                        "Removed $removed."
                    }
                },
                onSetNoAi = { enabled ->
                    run("Saving preference") {
                        Core.setNoAi(enabled)
                        refreshDashboardState()
                        "Preference saved."
                    }
                }
            )
        }
        refreshDashboard()
        startUpdateMonitor()
    }

    override fun onResume() {
        super.onResume()
        reportFailedGameStart()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Back from the game: if it stopped within seconds (or its process died), show why. The game writes
     * launcher/game-log.txt (engine errors, panics, Java crashes); a native crash leaves only logcat.
     */
    private fun reportFailedGameStart() {
        val home = File(filesDir, "launcher")
        val session = File(home, "game-session.txt")
        if (!session.isFile) return
        val text = runCatching { session.readText().trim() }.getOrDefault("")
        val lived = text.removePrefix("end ").toLongOrNull()
        if (lived == null) {
            // Still "start": either the game is running right now or its process died.
            val alive = (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager)
                .runningAppProcesses?.any { it.processName.endsWith(":game") } == true
            if (alive) return
        } else if (lived > 15_000) {
            session.delete()
            return
        }
        session.delete()
        showGameLog("Minecraft stopped ${if (lived == null) "(the game process crashed)" else "after ${lived / 1000}s"}", includeLogcat = lived == null)
    }

    private fun showGameLog(title: String, includeLogcat: Boolean = false) {
        scope.launch {
            val body = withContext(Dispatchers.IO) {
                val file = File(File(filesDir, "launcher"), "game-log.txt")
                val log = if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
                val system = if (includeLogcat) runCatching {
                    val proc = ProcessBuilder("logcat", "-d", "-t", "300", "*:W").redirectErrorStream(true).start()
                    proc.inputStream.bufferedReader().readText().also { proc.destroy() }
                }.getOrDefault("") else ""
                buildString {
                    append(if (log.isBlank()) "(no game log yet)" else log.takeLast(6000))
                    if (system.isNotBlank()) append("\n\n--- system log ---\n").append(system.takeLast(6000))
                }
            }
            val view = TextView(this@MainActivity).apply {
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(20, 16, 20, 16)
                setTextIsSelectable(true)
                text = body
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(title)
                .setView(ScrollView(this@MainActivity).apply { addView(view) })
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy") { _, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Horizon game log", body))
                }
                .show()
        }
    }

    private fun show(text: String) { statusText = text }

    private fun confirmAndCompileHnmods(uris: List<Uri>) {
        AlertDialog.Builder(this)
            .setTitle("Compile native mod source?")
            .setMessage(
                "HNMod files contain Rust source. Their build scripts and procedural macros can execute code " +
                    "with Horizon's app permissions during compilation. The local compiler is not sandboxed. " +
                    "Only continue if you trust every selected mod. Like the PC launcher, Horizon will select the newest pinned engine revision; if selected mods target different revisions, this confirmation also authorizes trying that mixed set (which may fail to apply or compile). " +
                    "Horizon manages its own private compiler runtime; no separate Termux app, external command permission, or shared-storage setup is needed."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                run("Starting HNMod compiler") {
                    dev.horizon.launcher.data.EmbeddedToolchain.start(this, uris, onProgress = { message ->
                        runOnUiThread { show(message) }
                    }, onLogLine = { message ->
                        // The complete line is persisted by HnmodBuildLog; status stays concise.
                        if (message.startsWith("==") || message.startsWith("Compiling") || message.startsWith("ERROR:")) {
                            runOnUiThread { show(message) }
                        }
                    })
                    refreshDashboardState()
                    "HNMod build finished."
                }
            }
            .show()
    }

    private fun confirmAndImportBuild(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Trust this native build?")
            .setMessage(
                "A native mod is compiled into the game library and runs with the game's full access. " +
                    "Only import builds made from mods you trust. The library becomes active on the next game launch."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Import and activate") { _, _ ->
                run("Importing native build") {
                    val result = withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use {
                            dev.horizon.launcher.data.Updater.importLocal(this@MainActivity, it)
                        } ?: error("Could not open the selected library.")
                    }
                    refreshDashboardState()
                    when (result) {
                        is dev.horizon.launcher.data.Updater.Imported.Ok ->
                            "Build ${result.name} imported and activated. Restart the game to use it."
                        is dev.horizon.launcher.data.Updater.Imported.Bad -> error(result.message)
                    }
                }
            }
            .show()
    }


    /** Opens a live, scrollable view of the complete HNMod build log. */
    private fun showLiveHnmodLog() {
        val logFile = dev.horizon.launcher.data.HnmodBuildLog.file(this)
        val logView = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(20, 16, 20, 16)
            text = if (logFile.isFile) logFile.readText() else "No HNMod build log yet. Start a compilation first."
        }
        val scroll = ScrollView(this).apply {
            addView(logView)
            setPadding(0, 0, 0, 0)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("HNMod — live build log")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .create()
        dialog.setOnShowListener {
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            scope.launch {
                val content = StringBuilder()
                var offset = 0L
                while (dialog.isShowing) {
                    if (logFile.isFile) {
                        val length = logFile.length()
                        if (length < offset) {
                            offset = 0L
                            content.setLength(0)
                        }
                        if (length > offset) {
                            val count = (length - offset).coerceAtMost(1024L * 1024L).toInt()
                            val bytes = ByteArray(count)
                            val read = runCatching {
                                java.io.RandomAccessFile(logFile, "r").use { input ->
                                    input.seek(offset)
                                    input.read(bytes)
                                }
                            }.getOrDefault(-1)
                            if (read > 0) {
                                val wasAtBottom = scroll.getChildAt(0).let { child ->
                                    child.bottom - (scroll.scrollY + scroll.height) <= 48
                                }
                                content.append(String(bytes, 0, read, Charsets.UTF_8))
                                offset += read
                                logView.text = content.toString()
                                if (wasAtBottom) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                            }
                        }
                    }
                    delay(500)
                }
            }
        }
        dialog.show()
    }

    private fun beginMicrosoftSignIn() {
        if (!MicrosoftAuth.available) {
            show("Microsoft sign-in is not available in this build.")
            return
        }
        scope.launch {
            runCatching { MicrosoftAuth.start() }
                .onSuccess { code ->
                    runOnUiThread {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Microsoft sign-in")
                            .setMessage("Open ${code.verificationUri} and enter this code:\n\n${code.userCode}\n\nAfter authorizing, return here and tap Continue.")
                            .setNegativeButton("Cancel") { _, _ -> MicrosoftAuth.cancel() }
                            .setNeutralButton("Open website") { _, _ ->
                                startActivity(Intent(Intent.ACTION_VIEW, AndroidUri.parse(code.verificationUri)))
                            }
                            .setPositiveButton("Continue") { _, _ ->
                                run("Completing Microsoft sign-in") {
                                    val name = MicrosoftAuth.complete()
                                    refreshDashboardState()
                                    "Signed in as $name."
                                }
                            }
                            .show()
                    }
                }
                .onFailure { show("Microsoft sign-in failed: ${it.message}") }
        }
    }

    private fun refreshDashboard() {
        scope.launch { refreshDashboardState() }
    }

    private suspend fun refreshDashboardState() {
        val ready = runCatching { GameSetup.ready() }.getOrDefault(false)
        val toolchain = runCatching {
            dev.horizon.launcher.data.EmbeddedToolchain.status(this@MainActivity)
        }.getOrNull()
        val instances = runCatching { Core.instances() }.getOrDefault(emptyList())
        val settings = runCatching { Core.settings() }.getOrNull()
        val worlds = runCatching { Core.worlds() }.getOrDefault(emptyList())
        val servers = runCatching { Core.servers() }.getOrDefault(emptyList())
        val mods = runCatching { Core.mods() }.getOrDefault(emptyList())
        val versions = runCatching { Core.installed() }.getOrDefault(emptyList())
        val accounts = runCatching { Core.accounts() }.getOrNull()
        val signIn = runCatching { Core.signInInfo() }.getOrNull()
        val platform = runCatching { Core.platform() }.getOrNull()
        val releases = runCatching { Core.releases() }.getOrDefault(emptyList())
        val telemetry = runCatching { Core.telemetry() }.getOrNull()
        val browseCatalog = runCatching { Browse.catalog() }.getOrNull()
        val browseInstalled = runCatching { Browse.installed() }.getOrDefault(emptyList())
        val browseProgress = runCatching { Browse.progress() }.getOrNull()

        minecraftReadyState = ready
        toolchainReadyState = toolchain?.installed == true
        instancesState = instances
        settingsState = settings
        selectedInstanceState = instances.firstOrNull {
            it.id == settings?.instance || it.name == settings?.instance
        } ?: instances.firstOrNull()
        worldsState = worlds
        serversState = servers
        modsState = mods
        versionsState = versions
        playerNameState = accounts?.player?.label ?: "Local profile"
        accountsState = accounts
        signInInfoState = signIn
        platformState = platform
        releasesState = releases
        telemetryState = telemetry
        browseCatalogState = browseCatalog
        browseInstalledState = browseInstalled
        browseProgressState = browseProgress

        val problem = initError?.let { " · core problem: $it" } ?: ""
        statusText = "CORE ${Core.coreVersion()}  /  MINECRAFT " +
            (if (ready) "READY" else "NOT INSTALLED") +
            "  /  HNMOD " + (if (toolchainReadyState) "READY" else "NOT INSTALLED") + problem
    }

    private fun importNativeFile(uri: Uri, shader: Boolean) {
        val label = if (shader) "Importing shader pack" else "Importing HMod package"
        run(label) {
            val file = withContext(Dispatchers.IO) {
                val folder = File(cacheDir, "launcher-imports").apply { mkdirs() }
                val extension = if (shader) "zip" else "jar"
                val target = File(folder, "horizon-import-${System.currentTimeMillis()}.$extension")
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Could not open the selected file.")
                target
            }
            try {
                val imported = if (shader) Core.importShader(file.absolutePath)
                    else Core.importMod(file.absolutePath)
                refreshDashboardState()
                "Imported $imported."
            } finally {
                file.delete()
            }
        }
    }

    /**
     * Keep the signed Android Nightly updater alive independently of the UI implementation.
     * The previous Compose LaunchedEffect did this at startup and every 30 minutes; moving the
     * launcher shell must not silently remove that lifecycle hook.
     */
    private fun startUpdateMonitor() {
        if (initError != null) return
        scope.launch {
            var announcedAppTag = ""
            while (true) {
                if (dev.horizon.launcher.data.Updater.autoEnabled(applicationContext)) {
                    val result = withContext(Dispatchers.IO) {
                        dev.horizon.launcher.data.Updater.checkAndInstall(applicationContext)
                    }
                    when (result) {
                        is dev.horizon.launcher.data.Updater.Result.Installed ->
                            show("A new Horizon Nightly runtime was downloaded. It will be used on the next game launch.")
                        is dev.horizon.launcher.data.Updater.Result.Failed ->
                            show("Nightly check failed: ${result.message}")
                        is dev.horizon.launcher.data.Updater.Result.UpToDate -> Unit
                    }

                    val appOffer = runCatching {
                        withContext(Dispatchers.IO) {
                            dev.horizon.launcher.data.AppUpdater.check(applicationContext)
                        }
                    }.getOrNull()
                    if (appOffer != null && announcedAppTag != appOffer.tag) {
                        announcedAppTag = appOffer.tag
                        show("A new Horizon app build is available: ${appOffer.tag}.")
                    }
                }
                delay(30 * 60 * 1000L)
            }
        }
    }

    /** Runs one setup action with the buttons locked and the result (or the error) shown as status. */
    private fun run(label: String, action: suspend () -> String) {
        if (busy) return
        busy = true
        buttons.forEach { it.isEnabled = false }
        show("$label…")
        scope.launch {
            val message = try { action() } catch (e: Exception) { e.message ?: "$label failed" }
            busy = false
            buttons.forEach { it.isEnabled = true }
            // Keep the final result visible. refreshStatus() launched asynchronously here could
            // overwrite a compiler/download error immediately after the dialog closes.
            statusText = "$label\n$message"
        }
    }
}
