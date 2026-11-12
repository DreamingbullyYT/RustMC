package dev.horizon.launcher.core

/**
 * The JNI surface of the Horizon launcher core (Rust). Every method is blocking; the app calls
 * them from Dispatchers.IO. Strings are JSON (or plain text) so the bridge stays small.
 */
/** Path of an updated libhorizon_core.so (set by Updater.prepare before Native is first used). */
object NativeLoader {
    @Volatile var corePath: String? = null
}

object Native {
    var loadError: String? = null
        private set

    init {
        val updated = NativeLoader.corePath
        var loaded = false
        if (updated != null) {
            try {
                System.load(updated)
                loaded = true
            } catch (_: Throwable) {
                // fall back to the library packaged in the APK
            }
        }
        if (!loaded) {
            try {
                System.loadLibrary("horizon_core")
            } catch (t: Throwable) {
                loadError = t.message ?: t.javaClass.simpleName
            }
        }
    }

    external fun nativeInit(home: String): String

    // In-app updates (core/src/update.rs): signed Nightly releases, the PC launcher's scheme
    external fun nativeUpdateList(): String
    external fun nativeUpdateDownload(tag: String, what: String, dest: String): String
    external fun nativeUpdateProgress(): String

    // Mod browser (core/src/browse.rs)
    external fun nativeBrowseCatalog(): String
    external fun nativeBrowseSearch(source: String, kind: String, text: String, offset: Int, everything: Boolean): String
    external fun nativeBrowseDetails(source: String, id: String): String
    external fun nativeBrowseInstall(source: String, id: String): String
    external fun nativeBrowseProgress(): String
    external fun nativeBrowseInstalled(): String
    external fun nativeBrowseRemove(name: String): String
    external fun nativeGameReady(): Boolean
    external fun nativeInstallProgress(): String
    external fun nativeSetImportCache(cache: String): String
    external fun nativeImportCachedFile(name: String, source: String): String
    external fun nativeInstallGame(cache: String): String
    external fun nativeVersion(): String
    external fun nativeDataDir(): String
    external fun nativeReleasesJson(): String
    external fun nativeInstancesJson(): String
    external fun nativePlatformJson(): String

    external fun nativeAccountsJson(): String
    external fun nativeAddOfflineName(name: String): String
    external fun nativePlayOfflineName(name: String): String
    external fun nativeRemoveOfflineName(name: String): String
    external fun nativePlayAsGameName(): String
    external fun nativeSelectAccount(uuid: String): String
    external fun nativeRemoveAccount(uuid: String): String
    external fun nativeSignInInfo(): String

    external fun nativeSettingsJson(): String
    external fun nativeSaveSettings(patch: String): String
    external fun nativeSetNoAi(on: Boolean): String
    external fun nativeTouchControlsJson(): String
    external fun nativeSaveTouchControls(patch: String): String

    external fun nativeCreateInstance(name: String): String
    external fun nativeDeleteInstance(id: String): String
    external fun nativeSelectInstance(id: String): String

    external fun nativeInstalledJson(): String
    external fun nativeInstallVersion(tag: String): String
    external fun nativeRemoveVersion(tag: String): String

    external fun nativeWorldsJson(): String
    external fun nativeServersJson(): String
    external fun nativeAddServer(name: String, ip: String): String
    external fun nativeRemoveServer(index: Int): String
    external fun nativeModsJson(): String
    external fun nativeImportMod(source: String): String
    external fun nativeRemoveMod(id: String): String
    external fun nativeImportShader(source: String): String
    external fun nativeRemoveShader(name: String): String

    external fun nativeLaunch(server: String?): String

    external fun nativeTelemetryJson(): String
    external fun nativeSetTelemetry(on: Boolean): String
}
