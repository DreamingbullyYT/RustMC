package dev.horizon.launcher

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Keeps the installed Minecraft (`files/launcher/.local/horizon-client/<version>`) in a single zip
 * in the public Downloads folder, so uninstalling or clearing the app never forces a re-download.
 * Restore unpacks it back and the native `ready()` check passes without touching the network.
 */
object MinecraftBackup {
    const val FILE_NAME = "Horizon-Minecraft.zip"

    private fun versionsDir(context: Context) = File(File(context.filesDir, "launcher"), "game/.local/horizon-client")

    /** Fully installed versions only (the resource marker is written last by the installer). */
    fun installedVersions(context: Context): List<File> =
        versionsDir(context).listFiles { f -> f.isDirectory && !f.name.endsWith(".partial") && File(f, "horizon-resources.json").isFile }
            ?.sortedBy { it.name } ?: emptyList()

    private fun gameDir(context: Context) = File(File(context.filesDir, "launcher"), "game")
    private val skipTop = setOf(".local", "engine-data.zip", ".engine-data.sha", ".engine-refresh", "logs", "crash-reports", "screenshots", "cache")

    /** Worlds, mods, shader packs, resource packs and settings: everything the player made in the game folder. */
    private fun userFiles(context: Context): List<Pair<File, File>> {
        val root = gameDir(context)
        return root.listFiles().orEmpty().filter { it.name !in skipTop && !it.name.endsWith(".partial") }
            .flatMap { top -> if (top.isFile) listOf(root to top) else top.walkTopDown().filter { it.isFile }.map { root to it }.toList() }
    }

    fun installedBytes(context: Context): Long =
        installedVersions(context).sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    /** Returns a human readable location of the written backup. */
    suspend fun exportToDownloads(context: Context, onProgress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val versions = installedVersions(context)
        require(versions.isNotEmpty()) { "Minecraft is not installed yet" }
        val files = versions.flatMap { dir -> dir.walkTopDown().filter { it.isFile }.map { dir to it }.toList() } + userFiles(context)
        // The engine-data bundle is regenerated from the installed APK, not copied into the
        // backup. Record its hash so restore can detect resource tables prepared by another build.
        val engineDataSha = File(gameDir(context), ".engine-data.sha")
            .takeIf { it.isFile }?.readText()?.trim()
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }

        fun write(out: java.io.OutputStream) {
            ZipOutputStream(BufferedOutputStream(out, 1 shl 16)).use { zip ->
                zip.setLevel(Deflater.BEST_SPEED)   // assets are mostly already compressed
                files.forEachIndexed { index, (root, file) ->
                    if (index % 100 == 0) onProgress("Exporting ${index}/${files.size} files")
                    val prefix = if (root == gameDir(context)) "userdata/" else root.name + "/"
                    zip.putNextEntry(ZipEntry(prefix + file.relativeTo(root).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip, 1 shl 16) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("__horizon_backup__/manifest.json"))
                val manifest = JSONObject()
                    .put("schema", 1)
                    .put("engineDataSha256", engineDataSha ?: JSONObject.NULL)
                    .toString().toByteArray(Charsets.UTF_8)
                zip.write(manifest)
                zip.closeEntry()
            }
        }

        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            // Replace our previous backup instead of piling up "Horizon-Minecraft (1).zip" copies.
            resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf(FILE_NAME, Environment.DIRECTORY_DOWNLOADS + "%")
            )
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not create a file in Downloads")
            try {
                resolver.openOutputStream(uri)?.use { write(it) } ?: error("Could not open the file in Downloads")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
            "Downloads/$FILE_NAME"
        } else {
            // Android 8-9 would need the legacy storage permission; use the app's own Downloads folder.
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("No external storage available")
            dir.mkdirs()
            val target = File(dir, FILE_NAME)
            target.outputStream().use { write(it) }
            target.absolutePath
        }
    }

    /** Unpacks a backup chosen with the system file picker. Returns the number of versions restored. */
    suspend fun restore(context: Context, source: Uri, onProgress: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val versions = versionsDir(context).apply { mkdirs() }
        val staging = File(versions, "restore.partial")
        staging.deleteRecursively()
        staging.mkdirs()
        val stagingRoot = staging.canonicalPath + File.separator
        try {
            var count = 0
            ZipInputStream((context.contentResolver.openInputStream(source) ?: error("Could not open the selected file")).buffered(1 shl 16)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val target = File(staging, entry.name).canonicalFile
                    // Zip-slip guard: never write outside the staging folder.
                    require(target.path.startsWith(stagingRoot)) { "Unsafe path in backup: ${entry.name}" }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it, 1 shl 16) }
                    if (++count % 100 == 0) onProgress("Restoring $count files")
                }
            }
            val restored = staging.listFiles { f -> f.isDirectory && File(f, "horizon-resources.json").isFile }.orEmpty()
            require(restored.isNotEmpty()) { "This is not a Horizon Minecraft backup" }

            // Backups deliberately omit engine-data.zip because it is supplied by the APK.
            // If the backup was made by a different build (or is an older backup without a
            // manifest), its prepared Android tables cannot be trusted with today's engine data.
            // Remove the install-complete marker so GameSetup.restoreBackup runs the native
            // installer before allowing the game to launch.
            val backupEngineSha = runCatching {
                JSONObject(File(staging, "__horizon_backup__/manifest.json").readText())
                    .optString("engineDataSha256")
                    .takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            }.getOrNull()
            val currentEngineSha = runCatching {
                File(gameDir(context), ".engine-data.sha").readText().trim()
                    .takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            }.getOrNull()
            val engineDataMatches = backupEngineSha != null && backupEngineSha.equals(currentEngineSha, ignoreCase = true)

            for (dir in restored) {
                if (!engineDataMatches) File(dir, "android-downloads-v2.json").delete()
                val live = File(versions, dir.name)
                live.deleteRecursively()
                check(dir.renameTo(live)) { "Could not move ${dir.name} into place" }
            }
            // Worlds, mods, shaders and settings go back into the game folder.
            File(staging, "userdata").takeIf { it.isDirectory }?.copyRecursively(gameDir(context).apply { mkdirs() }, overwrite = true)
            restored.size
        } finally {
            staging.deleteRecursively()
        }
    }
}
