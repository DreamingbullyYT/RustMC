package dev.ferrite.core

import java.io.File

/** One isolated game profile: its own game folder, saves, options and engine settings. */
data class Instance(
    val id: String,
    val name: String,
    val engine: String = "horizon",
    val engineVersion: String = "26.3",
    val resolutionScale: Double = 1.0,
    val renderDistance: Int = 8,
    val touchLayout: String = "mcpe",
    val nativeMods: List<String> = emptyList(),
    val lastPlayedMs: Long = 0,
) {
    init { require(id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "bad instance id: $id" } }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "name" to name, "engine" to engine, "engine_version" to engineVersion,
        "resolution_scale" to resolutionScale, "render_distance" to renderDistance,
        "touch_layout" to touchLayout, "native_mods" to nativeMods, "last_played_ms" to lastPlayedMs,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>) = Instance(
            id = m["id"].str(), name = m["name"].str(m["id"].str()), engine = m["engine"].str("horizon"),
            engineVersion = m["engine_version"].str("26.3"), resolutionScale = m["resolution_scale"].num(1.0).coerceIn(0.25, 2.0),
            renderDistance = m["render_distance"].int(8).coerceIn(2, 64), touchLayout = m["touch_layout"].str("mcpe"),
            nativeMods = m["native_mods"].arr().map { it.str() }, lastPlayedMs = (m["last_played_ms"] as? Number)?.toLong() ?: 0,
        )

        /** `My World!` -> `my-world`; empty names fall back to `instance`. */
        fun slug(name: String): String =
            name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48).ifEmpty { "instance" }
    }
}

/** Instances live in `<root>/instances/<id>/instance.json`; the game folder is `<root>/instances/<id>/game`. */
class InstanceStore(private val root: File) {
    private val dir get() = File(root, "instances")

    fun gameDir(i: Instance) = File(dir, i.id + "/game")

    fun list(): List<Instance> = (dir.listFiles() ?: emptyArray()).sortedBy { it.name }.mapNotNull { d ->
        runCatching { Instance.fromMap(Json.parse(File(d, "instance.json").readText()).obj()) }.getOrNull()
    }.sortedByDescending { it.lastPlayedMs }

    fun create(name: String, from: Instance? = null): Instance {
        val taken = list().map { it.id }.toSet()
        val base = Instance.slug(name)
        var id = base; var n = 2
        while (id in taken) id = "$base-${n++}"
        val inst = (from ?: Instance(id, name)).copy(id = id, name = name, lastPlayedMs = 0)
        save(inst); gameDir(inst).mkdirs()
        return inst
    }

    fun save(i: Instance) {
        val f = File(dir, i.id + "/instance.json"); f.parentFile.mkdirs()
        val tmp = File(f.parentFile, "instance.json.tmp")
        tmp.writeText(Json.write(i.toMap(), pretty = true)); tmp.renameTo(f)
    }

    /** Copies the instance with its game folder (worlds, mods, packs). */
    fun clone(i: Instance, newName: String, withWorlds: Boolean = true): Instance {
        val c = create(newName, i)
        gameDir(i).takeIf { it.isDirectory }?.copyRecursively(gameDir(c), overwrite = true)
        if (!withWorlds) File(gameDir(c), "saves").deleteRecursively()
        return c
    }

    /** Zip with `instance.json` and `game/`. */
    fun export(i: Instance, out: File) = java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { z ->
        z.putNextEntry(java.util.zip.ZipEntry("instance.json")); z.write(File(dir, i.id + "/instance.json").readBytes()); z.closeEntry()
        val g = gameDir(i); g.walkTopDown().filter { it.isFile }.forEach { f -> z.putNextEntry(java.util.zip.ZipEntry("game/" + f.relativeTo(g).invariantSeparatorsPath)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
    }

    /** Imports an exported zip as a new instance (never replaces an existing one); the id is regenerated if taken. */
    fun import(zip: File): Instance {
        val meta = java.util.zip.ZipFile(zip).use { zf -> Instance.fromMap(Json.parse(zf.getEntry("instance.json")?.let { zf.getInputStream(it).readBytes().decodeToString() } ?: error("not an instance export")).obj()) }
        val inst = create(meta.name, meta); val base = gameDir(inst).canonicalFile
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory || !e.name.startsWith("game/")) continue
                val f = File(base, e.name.removePrefix("game/")).canonicalFile
                require(f.path.startsWith(base.path + File.separator)) { "unsafe path: ${e.name}" }
                f.parentFile.mkdirs(); f.outputStream().use { z.copyTo(it) }
            }
        }
        return inst
    }

    fun delete(i: Instance) { File(dir, i.id).deleteRecursively() }
    fun markPlayed(i: Instance, nowMs: Long) = save(i.copy(lastPlayedMs = nowMs))
}
