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

    fun delete(i: Instance) { File(dir, i.id).deleteRecursively() }
    fun markPlayed(i: Instance, nowMs: Long) = save(i.copy(lastPlayedMs = nowMs))
}
