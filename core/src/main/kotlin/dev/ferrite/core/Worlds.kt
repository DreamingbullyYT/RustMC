package dev.ferrite.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class WorldInfo(val folder: String, val displayName: String, val version: String?, val lastPlayedMs: Long, val sizeBytes: Long)

class IncompatibleBackup(message: String) : Exception(message)

/** Per-world management for one game folder (`<gameDir>/saves/<folder>`). */
class WorldManager(private val gameDir: File) {
    private val saves get() = File(gameDir, "saves")

    fun list(): List<WorldInfo> = (saves.listFiles() ?: emptyArray()).filter { File(it, "level.dat").isFile }.map(::info).sortedByDescending { it.lastPlayedMs }

    private fun info(d: File): WorldInfo {
        val data = runCatching { Nbt.readGzipFile(File(d, "level.dat"))["Data"].obj() }.getOrDefault(emptyMap())
        return WorldInfo(d.name, data["LevelName"].str(d.name), data["Version"].obj()["Name"] as? String, (data["LastPlayed"] as? Number)?.toLong() ?: d.lastModified(), d.walkTopDown().filter { it.isFile }.sumOf { it.length() })
    }

    private fun dir(folder: String): File {
        require(folder.isNotEmpty() && '/' !in folder && '\\' !in folder && folder != "." && folder != "..") { "bad world name" }
        return File(saves, folder)
    }

    /** Renames the folder (the in-game title is left alone). Refuses to overwrite another world. */
    fun rename(folder: String, to: String) {
        val a = dir(folder); val b = dir(to)
        require(a.isDirectory) { "no such world" }; require(!b.exists()) { "a world named '$to' already exists" }
        check(a.renameTo(b)) { "rename failed" }
    }

    fun delete(folder: String) { val d = dir(folder); require(d.isDirectory) { "no such world" }; d.deleteRecursively() }

    /** Imports a world folder or a zip with one world at its root or one level down. Never overwrites. */
    fun import(src: File, name: String = src.nameWithoutExtension): String {
        val target = dir(name); require(!target.exists()) { "a world named '$name' already exists" }
        if (src.isDirectory) { require(File(src, "level.dat").isFile) { "not a world: no level.dat" }; src.copyRecursively(target); return name }
        val base = target.canonicalFile
        ZipInputStream(src.inputStream().buffered()).use { z ->
            val names = mutableListOf<Pair<ZipEntry, ByteArray>>()
            while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) names += e to z.readBytes() }
            val root = names.map { it.first.name }.firstOrNull { it == "level.dat" || it.endsWith("/level.dat") }?.removeSuffix("level.dat") ?: error("not a world: no level.dat")
            for ((e, bytes) in names) {
                if (!e.name.startsWith(root)) continue
                val f = File(base, e.name.removePrefix(root)).canonicalFile
                require(f.path.startsWith(base.path + File.separator)) { "unsafe path: ${e.name}" }
                f.parentFile.mkdirs(); f.writeBytes(bytes)
            }
        }
        return name
    }

    /** One world -> zip with a small manifest recording the game version it was saved with. */
    fun backup(folder: String, out: File, nowMs: Long = System.currentTimeMillis()): Int {
        val d = dir(folder); require(File(d, "level.dat").isFile) { "no such world" }; var n = 0
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("ferrite-backup.json"))
            z.write(Json.write(linkedMapOf("format" to 1, "kind" to "world", "world" to folder, "game_version" to info(d).version, "created_ms" to nowMs)).toByteArray()); z.closeEntry()
            d.walkTopDown().filter { it.isFile }.forEach { f -> z.putNextEntry(ZipEntry("world/" + f.relativeTo(d).invariantSeparatorsPath)); f.inputStream().use { it.copyTo(z) }; z.closeEntry(); n++ }
        }
        return n
    }

    /**
     * Restores a world backup under [asName]. Never overwrites an existing world. A backup made with another
     * game version is refused unless [acceptVersionMismatch] is set.
     */
    fun restore(zip: File, currentVersion: String, asName: String? = null, acceptVersionMismatch: Boolean = false): String {
        val meta = java.util.zip.ZipFile(zip).use { zf ->
            Json.parse(zf.getEntry("ferrite-backup.json")?.let { zf.getInputStream(it).readBytes().decodeToString() } ?: throw IncompatibleBackup("not a Ferrite world backup")).obj()
        }
        require(meta["kind"].str() == "world" && meta["format"].int() == 1) { "unsupported backup" }
        val was = meta["game_version"] as? String
        if (was != null && was != currentVersion && !acceptVersionMismatch) throw IncompatibleBackup("backup is from Minecraft $was, the instance runs $currentVersion")
        val name = asName ?: meta["world"].str(); val target = dir(name)
        require(!target.exists()) { "a world named '$name' already exists" }
        val base = target.canonicalFile; var n = 0
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory || !e.name.startsWith("world/")) continue
                val f = File(base, e.name.removePrefix("world/")).canonicalFile
                require(f.path.startsWith(base.path + File.separator)) { "unsafe path: ${e.name}" }
                f.parentFile.mkdirs(); f.outputStream().use { z.copyTo(it) }; n++
            }
        }
        return name
    }
}

/** Full instance backup: worlds, mods, configs, resource packs and shaders. */
object FullBackup {
    private val parts = listOf("saves", "mods", "config", "resourcepacks", "shaderpacks")

    fun create(gameDir: File, out: File, engineVersion: String): Int {
        var n = 0
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("ferrite-backup.json")); z.write(Json.write(linkedMapOf("format" to 1, "kind" to "full", "game_version" to engineVersion)).toByteArray()); z.closeEntry()
            for (p in parts) File(gameDir, p).walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(ZipEntry("game/" + f.relativeTo(gameDir).invariantSeparatorsPath)); f.inputStream().use { it.copyTo(z) }; z.closeEntry(); n++
            }
            File(gameDir, "options.txt").takeIf { it.isFile }?.let { z.putNextEntry(ZipEntry("game/options.txt")); z.write(it.readBytes()); z.closeEntry(); n++ }
        }
        return n
    }

    /** Restores into an empty-or-missing game folder only; refuses a different game version unless accepted. */
    fun restore(zip: File, gameDir: File, currentVersion: String, acceptVersionMismatch: Boolean = false): Int {
        val meta = java.util.zip.ZipFile(zip).use { zf -> Json.parse(zf.getEntry("ferrite-backup.json")?.let { zf.getInputStream(it).readBytes().decodeToString() } ?: throw IncompatibleBackup("not a Ferrite backup")).obj() }
        require(meta["kind"].str() == "full") { "not a full backup" }
        val was = meta["game_version"] as? String
        if (was != null && was != currentVersion && !acceptVersionMismatch) throw IncompatibleBackup("backup is from Minecraft $was, the instance runs $currentVersion")
        require(parts.all { (File(gameDir, it).list()?.isEmpty() ?: true) }) { "the instance already has data; restore into a new instance" }
        val base = gameDir.canonicalFile; var n = 0
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory || !e.name.startsWith("game/")) continue
                val f = File(base, e.name.removePrefix("game/")).canonicalFile
                require(f.path.startsWith(base.path + File.separator)) { "unsafe path: ${e.name}" }
                f.parentFile.mkdirs(); f.outputStream().use { z.copyTo(it) }; n++
            }
        }
        return n
    }
}
