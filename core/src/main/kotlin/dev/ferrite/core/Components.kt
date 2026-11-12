package dev.ferrite.core

import java.io.File

data class Component(val id: String, val version: String, val url: String, val sha256: String)

/** Engine and launcher components published as a GitHub release manifest: `{"components":[{id,version,url,sha256}]}`. */
object Components {
    fun parse(text: String): List<Component> = Json.parse(text).obj()["components"].arr().map { val m = it.obj(); Component(m["id"].str(), m["version"].str(), m["url"].str(), m["sha256"].str()) }
        .also { l -> require(l.all { it.id.matches(Regex("[a-z0-9._-]+")) && it.url.startsWith("https://") && it.sha256.length == 64 }) { "bad component entry" } }

    /** Components that are missing or older than the manifest says. */
    fun plan(installed: Map<String, String>, manifest: List<Component>): List<Component> = manifest.filter { c -> installed[c.id]?.let { Updates.isNewer(it, c.version) } ?: true }

    /** Moves a downloaded file into place only if its hash matches; otherwise leaves the installed copy untouched. */
    fun install(c: Component, downloaded: File, dir: File): File {
        require(Diagnostics.sha256(downloaded).equals(c.sha256, true)) { "checksum mismatch for ${c.id}" }
        val target = File(dir, "${c.id}-${c.version}"); dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp"); downloaded.copyTo(tmp, overwrite = true); check(tmp.renameTo(target)) { "install failed" }
        return target
    }
}
