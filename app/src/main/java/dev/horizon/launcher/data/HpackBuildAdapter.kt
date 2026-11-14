package dev.horizon.launcher.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/** Registry-pinned .hpack importer. Installs files into an isolated private profile, not the active game. */
object HpackBuildAdapter {
    private const val MAX_PACK_BYTES = 64L * 1024L * 1024L
    private const val MAX_INDEX_BYTES = 32L * 1024L * 1024L

    fun install(
        context: Context,
        packUri: Uri,
        indexUri: Uri,
        allowUnsigned: Boolean,
        onLogLine: (String) -> Unit = {}
    ): String {
        val state = EmbeddedTermuxPaths.state(context)
        val input = File(state, "hpack-input")
        input.deleteRecursively()
        require(input.isDirectory || input.mkdirs()) { "Cannot create private Hpack input directory." }
        try {
            val pack = copyUri(context, packUri, File(input, "selected.hpack"), MAX_PACK_BYTES, "Hpack")
            val index = copyUri(context, indexUri, File(input, "registry-index.json"), MAX_INDEX_BYTES, "Registry index")
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
