package dev.horizon.launcher.data

import dev.horizon.launcher.core.Native
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class Release(
    val tag: String,
    val name: String,
    val date: String,
    val body: String,
    val assetName: String?,
    val assetSize: Long,
    val prerelease: Boolean,
    val nightly: Boolean,
)

data class InstanceInfo(
    val id: String,
    val name: String,
    val group: String?,
    val version: String?,
    val playedSecs: Long,
    val lastPlayed: Long?,
    val home: String,
)

data class PlatformInfo(
    val os: String,
    val arch: String,
    val asset: String?,
    val launcherAsset: String?,
    val version: String,
    val dataDir: String,
)

data class AccountEntry(val uuid: String, val name: String)

data class PlayerInfo(val kind: String, val name: String?) {
    val label: String
        get() = when (kind) {
            "microsoft" -> name ?: "Microsoft account"
            "offline" -> name ?: "Offline player"
            else -> "The game's own name"
        }
}

data class AccountsInfo(
    val accounts: List<AccountEntry>,
    val selected: String?,
    val offline: List<String>,
    val offlineSelected: String?,
    val player: PlayerInfo,
    val hasOwner: Boolean,
    val keychain: Boolean,
    val signInConfigured: Boolean,
)

data class SignInInfo(
    val configured: Boolean,
    val message: String?,
    val keychain: Boolean,
    val noKeychain: String?,
)

data class SettingsInfo(
    val instance: String?,
    val selected: String?,
    val devOffline: Boolean,
    val installNewVersions: Boolean,
    val pruneOldVersions: Boolean,
    val skipOpening: Boolean,
    val noAi: Boolean,
    val gameArgs: String,
    val proxy: String?,
    val appearance: String,
    val accent: String,
    val language: String?,
)

data class TouchControlsInfo(
    val enabled: Boolean,
    val layout: String,
    val leftHanded: Boolean,
    val opacity: Float,
    val buttonSize: Float,
    val lookSensitivity: Float,
    val autoJump: Boolean,
    val holdToSprint: Boolean,
    val haptics: Boolean,
    val positions: Map<String, Pair<Float, Float>>,
    val bindings: Map<String, String>,
    val customButtons: List<CustomTouchButtonInfo>,
)

data class CustomTouchButtonInfo(
    val id: String,
    val label: String,
    val output: String,
)

data class InstalledVersion(
    val tag: String,
    val version: String,
    val prepared: Boolean,
    val sounds: Boolean,
)

data class WorldInfo(
    val folder: String,
    val name: String,
    val mode: String,
    val version: String?,
    val size: Long,
    val lastPlayed: Long?,
)

data class ServerInfo(val name: String, val ip: String)

data class ModInfo(val name: String, val file: String, val enabled: Boolean, val id: String = "")

data class TelemetryInfo(
    val performance: Boolean,
    val crashes: Boolean,
    val bugs: Boolean,
    val benchmarks: Boolean,
    val asked: Boolean,
) {
    val any: Boolean get() = performance || crashes || bugs || benchmarks
}

data class LaunchResult(
    val started: Boolean,
    val pid: Long?,
    val version: String?,
    val offline: String?,
)

sealed interface ReleasesState {
    data object Loading : ReleasesState
    data class Ready(val items: List<Release>) : ReleasesState
    data class Failed(val message: String) : ReleasesState
}

object Core {
    private fun errorOf(raw: String): String? =
        if (raw.trimStart().startsWith("{")) JSONObject(raw).optString("error").ifEmpty { null } else null

    private fun <T> read(raw: String, parse: (String) -> T): T {
        errorOf(raw)?.let { throw IOException(it) }
        return parse(raw)
    }

    private fun jsonArray(raw: String): JSONArray = read(raw) { JSONArray(it) }

    private fun jsonObject(raw: String): JSONObject = read(raw) { JSONObject(it) }

    suspend fun releases(): List<Release> = withContext(Dispatchers.IO) {
        val raw = Native.nativeReleasesJson()
        val array = jsonArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val asset = o.optJSONArray("asset")
                add(
                    Release(
                        tag = o.optString("tag"),
                        name = o.optString("name"),
                        date = o.optString("date"),
                        body = o.optString("body"),
                        assetName = asset?.optString(0),
                        assetSize = asset?.optLong(1, 0L) ?: 0L,
                        prerelease = o.optBoolean("prerelease"),
                        nightly = !o.isNull("nightly"),
                    )
                )
            }
        }
    }

    suspend fun instances(): List<InstanceInfo> = withContext(Dispatchers.IO) {
        val array = jsonArray(Native.nativeInstancesJson())
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    InstanceInfo(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        group = if (o.isNull("group")) null else o.optString("group"),
                        version = if (o.isNull("version")) null else o.optString("version"),
                        playedSecs = o.optLong("played_secs"),
                        lastPlayed = if (o.isNull("last_played")) null else o.optLong("last_played"),
                        home = o.optString("home"),
                    )
                )
            }
        }
    }

    suspend fun createInstance(name: String): InstanceInfo = withContext(Dispatchers.IO) {
        val o = jsonObject(Native.nativeCreateInstance(name))
        InstanceInfo(
            id = o.optString("id"),
            name = o.optString("name"),
            group = if (o.isNull("group")) null else o.optString("group"),
            version = if (o.isNull("version")) null else o.optString("version"),
            playedSecs = o.optLong("played_secs"),
            lastPlayed = if (o.isNull("last_played")) null else o.optLong("last_played"),
            home = o.optString("home"),
        )
    }

    suspend fun deleteInstance(id: String): List<InstanceInfo> = withContext(Dispatchers.IO) {
        read(Native.nativeDeleteInstance(id)) { instancesFrom(it) }
    }

    suspend fun selectInstance(id: String): List<InstanceInfo> = withContext(Dispatchers.IO) {
        read(Native.nativeSelectInstance(id)) { instancesFrom(it) }
    }

    private fun instancesFrom(raw: String): List<InstanceInfo> {
        val array = JSONArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    InstanceInfo(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        group = if (o.isNull("group")) null else o.optString("group"),
                        version = if (o.isNull("version")) null else o.optString("version"),
                        playedSecs = o.optLong("played_secs"),
                        lastPlayed = if (o.isNull("last_played")) null else o.optLong("last_played"),
                        home = o.optString("home"),
                    )
                )
            }
        }
    }

    suspend fun platform(): PlatformInfo = withContext(Dispatchers.IO) {
        val o = JSONObject(Native.nativePlatformJson())
        PlatformInfo(
            os = o.optString("os"),
            arch = o.optString("arch"),
            asset = if (o.isNull("asset")) null else o.optString("asset"),
            launcherAsset = if (o.isNull("launcherAsset")) null else o.optString("launcherAsset"),
            version = o.optString("version"),
            dataDir = o.optString("dataDir"),
        )
    }

    // ----- Accounts -------------------------------------------------------------------------------

    private fun accountsFrom(raw: String): AccountsInfo {
        val o = jsonObject(raw)
        val accounts = o.optJSONArray("accounts") ?: JSONArray()
        val list = buildList {
            for (i in 0 until accounts.length()) {
                val a = accounts.getJSONObject(i)
                add(AccountEntry(a.optString("uuid"), a.optString("name")))
            }
        }
        val offline = o.optJSONArray("offline") ?: JSONArray()
        val names = buildList { for (i in 0 until offline.length()) add(offline.getString(i)) }
        val player = o.optJSONObject("player")
        return AccountsInfo(
            accounts = list,
            selected = if (o.isNull("selected")) null else o.optString("selected"),
            offline = names,
            offlineSelected = if (o.isNull("offlineSelected")) null else o.optString("offlineSelected"),
            player = PlayerInfo(
                kind = player?.optString("kind") ?: "game",
                name = player?.let { if (it.isNull("name")) null else it.optString("name") },
            ),
            hasOwner = o.optBoolean("hasOwner"),
            keychain = o.optBoolean("keychain"),
            signInConfigured = o.optBoolean("signInConfigured"),
        )
    }

    suspend fun accounts(): AccountsInfo = withContext(Dispatchers.IO) {
        accountsFrom(Native.nativeAccountsJson())
    }

    suspend fun addOffline(name: String): AccountsInfo = withContext(Dispatchers.IO) {
        accountsFrom(Native.nativeAddOfflineName(name))
    }

    suspend fun playOffline(name: String): AccountsInfo = withContext(Dispatchers.IO) {
        accountsFrom(Native.nativePlayOfflineName(name))
    }

    suspend fun removeOffline(name: String): AccountsInfo = withContext(Dispatchers.IO) {
        accountsFrom(Native.nativeRemoveOfflineName(name))
    }

    suspend fun playAsGameName(): AccountsInfo = withContext(Dispatchers.IO) {
        accountsFrom(Native.nativePlayAsGameName())
    }

    suspend fun selectAccount(uuid: String): AccountsInfo = withContext(Dispatchers.IO) {
        accountsFrom(Native.nativeSelectAccount(uuid))
    }

    suspend fun removeAccount(uuid: String): AccountsInfo = withContext(Dispatchers.IO) {
        accountsFrom(Native.nativeRemoveAccount(uuid))
    }

    suspend fun signInInfo(): SignInInfo = withContext(Dispatchers.IO) {
        val o = jsonObject(Native.nativeSignInInfo())
        SignInInfo(
            configured = o.optBoolean("configured"),
            message = if (o.isNull("message")) null else o.optString("message"),
            keychain = o.optBoolean("keychain"),
            noKeychain = if (o.isNull("noKeychain")) null else o.optString("noKeychain"),
        )
    }

    // ----- Settings -------------------------------------------------------------------------------

    private fun settingsFrom(raw: String): SettingsInfo {
        val o = jsonObject(raw)
        return SettingsInfo(
            instance = if (o.isNull("instance")) null else o.optString("instance"),
            selected = if (o.isNull("selected")) null else o.optString("selected"),
            devOffline = o.optBoolean("dev_offline"),
            installNewVersions = o.optBoolean("install_new_versions", true),
            pruneOldVersions = o.optBoolean("prune_old_versions", true),
            skipOpening = o.optBoolean("skip_opening"),
            noAi = o.optBoolean("noAi"),
            gameArgs = o.optString("game_args"),
            proxy = if (o.isNull("proxy")) null else o.optString("proxy"),
            appearance = o.optString("appearance", "dark"),
            accent = o.optString("accent", "violet"),
            language = if (o.isNull("language")) null else o.optString("language"),
        )
    }

    suspend fun settings(): SettingsInfo = withContext(Dispatchers.IO) {
        settingsFrom(Native.nativeSettingsJson())
    }

    suspend fun saveSettings(patch: JSONObject): SettingsInfo = withContext(Dispatchers.IO) {
        settingsFrom(Native.nativeSaveSettings(patch.toString()))
    }

    suspend fun setNoAi(on: Boolean): SettingsInfo = withContext(Dispatchers.IO) {
        settingsFrom(Native.nativeSetNoAi(on))
    }

    private fun touchControlsFrom(raw: String): TouchControlsInfo {
        val o = jsonObject(raw)
        return TouchControlsInfo(
            enabled = o.optBoolean("enabled", true),
            layout = o.optString("layout", "classic"),
            leftHanded = o.optBoolean("left_handed"),
            opacity = o.optDouble("opacity", 0.72).toFloat(),
            buttonSize = o.optDouble("button_size", 1.0).toFloat(),
            lookSensitivity = o.optDouble("look_sensitivity", 1.0).toFloat(),
            autoJump = o.optBoolean("auto_jump"),
            holdToSprint = o.optBoolean("hold_to_sprint"),
            haptics = o.optBoolean("haptics", true),
            positions = buildMap {
                val controls = o.optJSONObject("positions") ?: return@buildMap
                val keys = controls.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val point = controls.optJSONArray(key) ?: continue
                    if (point.length() >= 2) put(key, point.optDouble(0).toFloat() to point.optDouble(1).toFloat())
                }
            },
            bindings = buildMap {
                val values = o.optJSONObject("bindings") ?: return@buildMap
                val keys = values.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    values.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) }
                }
            },
            customButtons = buildList {
                val controls = o.optJSONArray("custom_buttons") ?: return@buildList
                for (index in 0 until controls.length()) {
                    val item = controls.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (id.isNotBlank()) add(CustomTouchButtonInfo(id, item.optString("label", "Key"), item.optString("output", "E")))
                }
            },
        )
    }

    suspend fun touchControls(): TouchControlsInfo = withContext(Dispatchers.IO) {
        touchControlsFrom(Native.nativeTouchControlsJson())
    }

    suspend fun saveTouchControls(patch: JSONObject): TouchControlsInfo = withContext(Dispatchers.IO) {
        touchControlsFrom(Native.nativeSaveTouchControls(patch.toString()))
    }

    // ----- Versions -------------------------------------------------------------------------------

    suspend fun installed(): List<InstalledVersion> = withContext(Dispatchers.IO) {
        val array = jsonArray(Native.nativeInstalledJson())
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    InstalledVersion(
                        tag = o.optString("tag"),
                        version = o.optString("version"),
                        prepared = o.optBoolean("prepared"),
                        sounds = o.optBoolean("sounds"),
                    )
                )
            }
        }
    }

    suspend fun installVersion(tag: String): String = withContext(Dispatchers.IO) {
        val o = jsonObject(Native.nativeInstallVersion(tag))
        o.optString("installed", tag)
    }

    suspend fun removeVersion(tag: String): List<InstalledVersion> = withContext(Dispatchers.IO) {
        val array = jsonArray(Native.nativeRemoveVersion(tag))
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    InstalledVersion(
                        tag = o.optString("tag"),
                        version = o.optString("version"),
                        prepared = o.optBoolean("prepared"),
                        sounds = o.optBoolean("sounds"),
                    )
                )
            }
        }
    }

    // ----- Worlds, servers, mods ------------------------------------------------------------------

    suspend fun worlds(): List<WorldInfo> = withContext(Dispatchers.IO) {
        val array = jsonArray(Native.nativeWorldsJson())
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    WorldInfo(
                        folder = o.optString("folder"),
                        name = o.optString("name"),
                        mode = o.optString("mode"),
                        version = if (o.isNull("version")) null else o.optString("version"),
                        size = o.optLong("size"),
                        lastPlayed = if (o.isNull("lastPlayed")) null else o.optLong("lastPlayed"),
                    )
                )
            }
        }
    }

    private fun serversFrom(raw: String): List<ServerInfo> {
        val array = jsonArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(ServerInfo(o.optString("name"), o.optString("ip")))
            }
        }
    }

    suspend fun servers(): List<ServerInfo> = withContext(Dispatchers.IO) {
        serversFrom(Native.nativeServersJson())
    }

    suspend fun addServer(name: String, ip: String): List<ServerInfo> = withContext(Dispatchers.IO) {
        serversFrom(Native.nativeAddServer(name, ip))
    }

    suspend fun removeServer(index: Int): List<ServerInfo> = withContext(Dispatchers.IO) {
        serversFrom(Native.nativeRemoveServer(index))
    }

    suspend fun mods(): List<ModInfo> = withContext(Dispatchers.IO) {
        val array = jsonArray(Native.nativeModsJson())
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    ModInfo(
                        name = o.optString("name"),
                        file = o.optString("file"),
                        enabled = o.optBoolean("enabled"),
                        id = o.optString("id"),
                    )
                )
            }
        }
    }

    suspend fun importMod(path: String): String = withContext(Dispatchers.IO) {
        jsonObject(Native.nativeImportMod(path)).optString("name")
    }

    suspend fun removeMod(id: String) = withContext(Dispatchers.IO) {
        jsonObject(Native.nativeRemoveMod(id))
    }

    suspend fun importShader(path: String): String = withContext(Dispatchers.IO) {
        jsonObject(Native.nativeImportShader(path)).optString("name")
    }

    suspend fun removeShader(name: String) = withContext(Dispatchers.IO) {
        jsonObject(Native.nativeRemoveShader(name))
    }

    // ----- Play and telemetry ---------------------------------------------------------------------

    suspend fun launch(server: String? = null): LaunchResult = withContext(Dispatchers.IO) {
        val o = jsonObject(Native.nativeLaunch(server))
        LaunchResult(
            started = o.optBoolean("started"),
            pid = if (o.isNull("pid")) null else o.optLong("pid"),
            version = if (o.isNull("version")) null else o.optString("version"),
            offline = if (o.isNull("offline")) null else o.optString("offline"),
        )
    }

    suspend fun telemetry(): TelemetryInfo = withContext(Dispatchers.IO) {
        val o = jsonObject(Native.nativeTelemetryJson())
        TelemetryInfo(
            performance = o.optBoolean("performance"),
            crashes = o.optBoolean("crashes"),
            bugs = o.optBoolean("bugs"),
            benchmarks = o.optBoolean("benchmarks"),
            asked = o.optBoolean("asked"),
        )
    }

    suspend fun setTelemetry(on: Boolean): TelemetryInfo = withContext(Dispatchers.IO) {
        val o = jsonObject(Native.nativeSetTelemetry(on))
        TelemetryInfo(
            performance = o.optBoolean("performance"),
            crashes = o.optBoolean("crashes"),
            bugs = o.optBoolean("bugs"),
            benchmarks = o.optBoolean("benchmarks"),
            asked = o.optBoolean("asked"),
        )
    }

    fun coreVersion(): String = runCatching { Native.nativeVersion() }.getOrDefault("unavailable")
    fun dataDir(): String = runCatching { Native.nativeDataDir() }.getOrDefault("")
    fun init(home: String): String? = runCatching { Native.nativeInit(home) }.exceptionOrNull()?.message
    val loadError: String? get() = Native.loadError

    suspend fun gameReady(): Boolean = withContext(Dispatchers.IO) { Native.nativeGameReady() }

    suspend fun installGame(cache: String): String = withContext(Dispatchers.IO) {
        read(Native.nativeInstallGame(cache)) { JSONObject(it).optString("ready", "false") }
    }

    suspend fun installProgress(): JSONObject = withContext(Dispatchers.IO) {
        jsonObject(Native.nativeInstallProgress())
    }

    suspend fun setImportCache(cache: String) = withContext(Dispatchers.IO) {
        read(Native.nativeSetImportCache(cache)) { it }
    }

    suspend fun importCachedFile(name: String, source: String) = withContext(Dispatchers.IO) {
        read(Native.nativeImportCachedFile(name, source)) { it }
    }
}
