package dev.horizon.launcher.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/** Copies selected HNMods into private storage, runs the pinned build pipeline in PRoot and imports its ARM64 result. */
object HnmodBuildAdapter {
    private const val MAX_MOD_ARCHIVE_BYTES = 72L * 1024L * 1024L
    private const val MAX_TOTAL_ARCHIVE_BYTES = 288L * 1024L * 1024L

    fun compile(context: Context, prefix: File, selected: List<Uri>, onLogLine: (String) -> Unit = {}): Updater.Imported {
        require(selected.isNotEmpty()) { "Select at least one .hnmod archive." }
        materializeProject(context)

        val state = EmbeddedTermuxPaths.state(context)
        val inputDir = File(state, "input-mods")
        inputDir.deleteRecursively()
        require(inputDir.isDirectory || inputDir.mkdirs()) { "Cannot create private HNMod input directory." }

        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Horizon:HNModBuild")
            .apply { setReferenceCounted(false) }
        try {
            wakeLock.acquire(6L * 60L * 60L * 1000L)
            var totalBytes = 0L
            val archives = selected.mapIndexed { index, uri ->
                val fallback = "mod-$index.hnmod"
                val displayName = context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }?.takeIf { it.isNotBlank() } ?: fallback

                require(displayName.endsWith(".hnmod", ignoreCase = true)) {
                    "Only .hnmod archives can be compiled: $displayName"
                }
                val stem = displayName.substringBeforeLast('.', displayName)
                val safeName = stem.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".hnmod"
                val destination = File(inputDir, "${index.toString().padStart(3, '0')}_$safeName")
                val source = context.contentResolver.openInputStream(uri)
                    ?: error("Could not open selected HNMod archive: $displayName")
                source.use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var fileBytes = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            fileBytes += count
                            totalBytes += count
                            require(fileBytes <= MAX_MOD_ARCHIVE_BYTES) {
                                "$displayName exceeds the 72 MiB compressed archive limit."
                            }
                            require(totalBytes <= MAX_TOTAL_ARCHIVE_BYTES) {
                                "Selected HNMod archives exceed the 288 MiB total limit."
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                require(destination.length() > 0L) { "Selected HNMod archive is empty: $displayName" }
                destination
            }

            val buildName = "mods-${System.currentTimeMillis()}"
            val projectRoot = File(context.filesDir, "horizon-project")
            val script = File(projectRoot, "android/termux/build-with-mods.sh")
            val args = listOf("--name", buildName, "--jobs", "3", "--allow-version-mismatch") + archives.map { it.absolutePath }
            val build = EmbeddedProcessRunner.runScript(context, script, args, onLine = onLogLine)
            onLogLine("HNMod compiler process exited with code ${build.exitCode}")
            val completeLog = HnmodBuildLog.file(context).takeIf { it.isFile } ?: build.logFile
            val logLocation = publishBuildLog(context, completeLog, buildName) ?: completeLog.absolutePath
            check(build.exitCode == 0) {
                "HNMod compilation failed (exit ${build.exitCode}). Full log: $logLocation\n${build.output.takeLast(8000)}"
            }

            val library = File(EmbeddedTermuxPaths.home(context), "Horizon-builds/$buildName/libhorizon_android_game.so")
            require(library.isFile && library.length() > 1_000_000L) {
                "The build finished without producing a valid game library. Log: $logLocation"
            }
            return Updater.importLocal(context, library.inputStream(), File(library.parentFile, "native-data"))
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            inputDir.deleteRecursively()
        }
    }

    /**
     * Publishes the complete compiler output in the user's Downloads folder.
     * MediaStore is permission-free on Android 10+; older Android versions keep
     * a copy in the app-specific Downloads directory rather than failing the build.
     */
    private fun publishBuildLog(context: Context, source: File, buildName: String): String? {
        if (!source.isFile) return null
        val fileName = "Horizon-HNMod-$buildName.log"
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Horizon")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Android could not create the log in Downloads.")
                try {
                    context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    }
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }.also {
                        context.contentResolver.update(uri, it, null, null)
                    }
                    "Downloads/Horizon/$fileName"
                } catch (error: Throwable) {
                    context.contentResolver.delete(uri, null, null)
                    throw error
                }
            } else {
                val directory = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
                    "Horizon"
                )
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create log directory." }
                val destination = File(directory, fileName)
                source.copyTo(destination, overwrite = true)
                destination.absolutePath
            }
        }.getOrNull()
    }

    /** Copies everything bundled under assets/hnmod into the private build workspace, so a new asset needs no list entry. */
    private fun materializeProject(context: Context) {
        val root = File(context.filesDir, "horizon-project")
        require(root.isDirectory || root.mkdirs()) { "Cannot create private Horizon build workspace." }
        // Drop files an older app version shipped; keep hnmod-core/target and the other build caches.
        File(root, "android").deleteRecursively()
        File(root, "hnmod-core/src").deleteRecursively()
        copyAssetTree(context, "hnmod", root)
    }

    private fun copyAssetTree(context: Context, assetPath: String, destination: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            require(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
                "Cannot create build workspace directory for $assetPath"
            }
            context.assets.open(assetPath).use { source ->
                destination.outputStream().use { output -> source.copyTo(output) }
            }
        } else {
            require(destination.isDirectory || destination.mkdirs()) { "Cannot create build workspace directory for $assetPath" }
            children.forEach { copyAssetTree(context, "$assetPath/$it", File(destination, it)) }
        }
    }
}
