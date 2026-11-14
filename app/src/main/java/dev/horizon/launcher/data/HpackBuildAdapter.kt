package dev.horizon.launcher.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Registry-pinned .hpack importer. Installs files into an isolated private profile, not the active game. */
object HpackBuildAdapter {
    private const val MAX_PACK_BYTES = 64L * 1024L * 1024L
    private const val MAX_INDEX_BYTES = 32L * 1024L * 1024L
    private const val INDEX_URL = "https://horizon-rust.github.io/horizon-mods/index.json"
    private const val INDEX_CACHE_MS = 10L * 60L * 1000L

    fun install(
        context: Context,
        packUri: Uri,
        indexUri: Uri? = null,
        allowUnsigned: Boolean,
        onLogLine: (String) -> Unit = {}
    ): String {
        val state = EmbeddedTermuxPaths.state(context)
        val input = File(state, "hpack-input")
        input.deleteRecursively()
        require(input.isDirectory || input.mkdirs()) { "Cannot create private Hpack input directory." }
        try {
            val pack = copyUri(context, packUri, File(input, "selected.hpack"), MAX_PACK_BYTES, "Hpack")
            val index = if (indexUri != null) {
                copyUri(context, indexUri, File(input, "registry-index.json"), MAX_INDEX_BYTES, "Registry index")
            } else {
                fetchOfficialIndex(context, File(input, "registry-index.json"))
            }
            val project = File(context.filesDir, "horizon-project")
            val script = File(project, "android/termux/install-hpack.sh")
            require(script.isFile) { "The embedded Hpack installer is missing from this launcher build." }
            val profiles = File(EmbeddedTermuxPaths.home(context), "Horizon-profiles")
            val args = mutableListOf(pack.absolutePath, index.absolutePath, profiles.absolutePath)
            if (allowUnsigned) args += "--allow-unsigned"
            onLogLine("Resolving Hpack pins against the registry index")
            val result = EmbeddedProcessRunner.runScript(context, script, args, onLine = onLogLine)
            check(result.exitCode == 0) {
                "Hpack installation failed (exit ${result.exitCode}). Log: ${result.logFile.absolutePath}\n${result.output.takeLast(6000)}"
            }
            val summary = result.output.lineSequence().lastOrNull { it.trim().startsWith("{") }
            val installed = summary?.let { org.json.JSONObject(it) }
            val profile = installed?.optString("profile")?.takeIf { it.isNotBlank() }
                ?: error("Hpack installer finished without returning a profile receipt.")
            val mods = installed?.optInt("mods_installed", 0) ?: 0
            val native = installed?.optInt("native_mods", 0) ?: 0
            val unsigned = installed?.optJSONArray("unsigned_mods")?.length() ?: 0
            "Hpack installed as a private profile: $mods mods ($native native), $unsigned unsigned. Profile: $profile. Select/activate it in the launcher when profile management is connected."
        } finally {
            input.deleteRecursively()
        }
    }

    private fun fetchOfficialIndex(context: Context, destination: File): File {
        val cacheDir = File(EmbeddedTermuxPaths.state(context), "horizon-registry")
        require(cacheDir.isDirectory || cacheDir.mkdirs()) { "Cannot create private registry cache." }
        val cached = File(cacheDir, "index.json")
        val fresh = cached.isFile && System.currentTimeMillis() - cached.lastModified() <= INDEX_CACHE_MS
        if (!fresh) {
            val temporary = File(cacheDir, "index.json.part")
            try {
                val connection = (URL(INDEX_URL).openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 45_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "rustMC-Android-Hpack/1")
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    check(connection.responseCode == HttpsURLConnection.HTTP_OK) {
                        "Horizon registry returned HTTP ${connection.responseCode}."
                    }
                    val finalUrl = URL(connection.url.toString())
                    check(finalUrl.protocol == "https" && finalUrl.host == "horizon-rust.github.io") {
                        "Horizon registry redirected to an untrusted host."
                    }
                    connection.inputStream.use { input ->
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_INDEX_BYTES) { "Horizon registry index exceeds 32 MiB." }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    require(temporary.isFile && temporary.length() > 0L) { "Horizon registry returned an empty index." }
                    val json = org.json.JSONObject(temporary.readText(Charsets.UTF_8))
                    require(json.optInt("schema", -1) == 1 && json.optJSONArray("projects") != null) {
                        "Horizon registry index has an unsupported schema."
                    }
                    if (cached.exists()) cached.delete()
                    check(temporary.renameTo(cached)) { "Could not atomically cache the Horizon registry index." }
                } finally {
                    connection.disconnect()
                }
            } catch (error: Exception) {
                temporary.delete()
                if (!cached.isFile || System.currentTimeMillis() - cached.lastModified() > INDEX_CACHE_MS) {
                    throw IllegalStateException("Could not refresh the Horizon registry index: ${error.message}", error)
                }
                onRegistryCacheFallback()
            }
        }
        cached.copyTo(destination, overwrite = true)
        return destination
    }

    private fun onRegistryCacheFallback() {
        // The caller's build log reports the selected cache file when resolution starts.
    }

    private fun copyUri(
        context: Context,
        uri: Uri,
        destination: File,
        limit: Long,
        label: String
    ): File {
        val source = context.contentResolver.openInputStream(uri)
            ?: error("Could not open selected $label.")
        source.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= limit) { "$label exceeds the ${limit / (1024 * 1024)} MiB import limit." }
                    output.write(buffer, 0, count)
                }
            }
        }
        require(destination.isFile && destination.length() > 0L) { "Selected $label is empty." }
        return destination
    }
}
