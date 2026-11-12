package dev.ferrite.core

import java.io.File
import java.util.zip.ZipFile

enum class ModKind(val ext: String, val folder: String) { HMOD("hmod", "mods"), HNMOD("hnmod", "mods"), HSHADER("hshader", "shaderpacks") }

data class ModEntry(val file: File, val kind: ModKind, val enabled: Boolean, val id: String, val game: String?, val depends: List<String>, val conflicts: List<String>)
data class ModProblem(val modId: String, val message: String)

/**
 * Mods keep their files: disabling renames `x.hnmod` to `x.hnmod.disabled`. Metadata comes from
 * `horizon-native.json` inside an `.hnmod` (schema 1: id, game, optional `depends` / `conflicts` lists).
 * `.hmod` and `.hshader` carry no readable manifest here yet, so they list with their file name as id.
 */
class ModManager(private val gameDir: File) {
    private fun dirOf(k: ModKind) = File(gameDir, k.folder)

    fun list(): List<ModEntry> = ModKind.entries.flatMap { k ->
        (dirOf(k).listFiles() ?: emptyArray()).mapNotNull { f ->
            val enabled = f.name.endsWith("." + k.ext); val disabled = f.name.endsWith("." + k.ext + ".disabled")
            if (!enabled && !disabled) null else describe(f, k, enabled)
        }
    }.distinctBy { it.file }.sortedBy { it.id }

    private fun describe(f: File, k: ModKind, enabled: Boolean): ModEntry {
        val fallback = f.name.removeSuffix(".disabled").removeSuffix("." + k.ext)
        if (k != ModKind.HNMOD) return ModEntry(f, k, enabled, fallback, null, emptyList(), emptyList())
        val m = runCatching { ZipFile(f).use { z -> Json.parse(z.getEntry("horizon-native.json")?.let { z.getInputStream(it).readBytes().decodeToString() } ?: "{}").obj() } }.getOrDefault(emptyMap())
        return ModEntry(f, k, enabled, m["id"].str(fallback), m["game"] as? String, m["depends"].arr().map { it.str() }, m["conflicts"].arr().map { it.str() })
    }

    fun setEnabled(e: ModEntry, on: Boolean): ModEntry {
        if (e.enabled == on) return e
        val target = if (on) File(e.file.parentFile, e.file.name.removeSuffix(".disabled")) else File(e.file.parentFile, e.file.name + ".disabled")
        require(!target.exists()) { "${target.name} already exists" }
        check(e.file.renameTo(target)) { "rename failed" }
        return e.copy(file = target, enabled = on)
    }

    fun install(src: File): ModEntry {
        val k = ModKind.entries.firstOrNull { src.name.endsWith("." + it.ext) } ?: error("unknown mod type: ${src.name}")
        val target = File(dirOf(k), src.name); require(!target.exists() && !File(target.path + ".disabled").exists()) { "${src.name} is already installed" }
        target.parentFile.mkdirs(); src.copyTo(target); return describe(target, k, true)
    }

    fun uninstall(e: ModEntry) { e.file.delete() }

    /** Compatibility, missing dependencies and conflicts among the enabled mods. */
    fun problems(engineVersion: String): List<ModProblem> {
        val on = list().filter { it.enabled }; val ids = on.map { it.id }.toSet(); val out = mutableListOf<ModProblem>()
        for (m in on) {
            if (m.game != null && m.game != engineVersion) out += ModProblem(m.id, "built for ${m.game}, the instance runs $engineVersion")
            m.depends.filter { it !in ids }.forEach { out += ModProblem(m.id, "needs $it, which is not enabled") }
            m.conflicts.filter { it in ids }.forEach { out += ModProblem(m.id, "conflicts with $it") }
        }
        return out
    }
}
