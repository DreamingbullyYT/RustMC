package dev.horizon.launcher.game.controls

import android.content.Context
import android.net.Uri
import com.movtery.layer_controller.layout.ControlLayout
import java.io.File

/**
 * Named control layouts stored in `filesDir/launcher/layouts/<name>.json`.
 * `touch-layout.json` stays the layout the game loads on start; a profile is a saved copy you
 * can switch to, export, share or delete.
 */
class LayoutProfiles(private val context: Context) {
    private val dir: File get() = File(File(context.filesDir, "launcher"), "layouts").apply { mkdirs() }
    private val prefs get() = context.getSharedPreferences("zalith-touch-settings", Context.MODE_PRIVATE)

    var activeName: String
        get() = prefs.getString("active_layout_profile", "") ?: ""
        set(value) { prefs.edit().putString("active_layout_profile", value).apply() }

    fun names(): List<String> =
        dir.listFiles { f -> f.isFile && f.extension == "json" }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9 _.\\-]"), "_").take(40).ifBlank { "Layout" }

    fun save(name: String, layout: ControlLayout): String {
        val clean = sanitize(name)
        File(dir, "$clean.json").writeText(ControlJson.encode(layout))
        return clean
    }

    fun load(name: String): ControlLayout = ControlJson.decode(File(dir, "${sanitize(name)}.json").readText())

    fun delete(name: String) { File(dir, "${sanitize(name)}.json").delete() }

    fun rename(old: String, new: String): String {
        val clean = sanitize(new)
        if (clean != old) File(dir, "${sanitize(old)}.json").renameTo(File(dir, "$clean.json"))
        if (activeName == old) activeName = clean
        return clean
    }

    fun uniqueName(base: String): String {
        val existing = names().toSet()
        val clean = sanitize(base)
        if (clean !in existing) return clean
        var i = 2
        while ("$clean $i" in existing) i++
        return "$clean $i"
    }

    /** Writes [layout] as JSON to a document the user picked with ACTION_CREATE_DOCUMENT. */
    fun exportTo(uri: Uri, layout: ControlLayout) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(ControlJson.encode(layout).toByteArray()) }
            ?: error("Could not open the destination file")
    }
}
