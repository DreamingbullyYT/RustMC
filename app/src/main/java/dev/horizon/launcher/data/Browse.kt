package dev.horizon.launcher.data

import dev.horizon.launcher.core.Native
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

data class BrowseOption(val id: String, val label: String)
data class BrowseCatalog(val sources: List<BrowseOption>, val kinds: List<BrowseOption>, val game: String)

data class BrowseItem(
    val source: String,
    val id: String,
    val title: String,
    val summary: String,
    val author: String,
    val downloads: Long,
    val kind: String,
    val support: String,
    val installable: Boolean,
    val reason: String?,
    val works: Boolean,
    val installed: Boolean,
)

data class BrowseProgress(val running: Boolean, val line: String, val fraction: Float, val error: String?)

data class BrowseEntry(val project: String, val title: String, val kind: String, val number: String, val source: String)

/** The mod browser: Kotlin side of core/src/browse.rs (search, install, remove). */
object Browse {
    suspend fun catalog(): BrowseCatalog = withContext(Dispatchers.IO) {
        val o = obj(Native.nativeBrowseCatalog())
        fun options(key: String): List<BrowseOption> {
            val array = o.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                BrowseOption(item.optString("id"), item.optString("label"))
            }
        }
        BrowseCatalog(options("sources"), options("kinds"), o.optString("game"))
    }

    private fun obj(raw: String): JSONObject {
        val o = JSONObject(raw)
        if (o.has("error") && o.optString("error").isNotEmpty()) throw IOException(o.optString("error"))
        return o
    }

    private fun JSONObject.str(key: String): String? = if (isNull(key) || !has(key)) null else getString(key)

    suspend fun search(source: String, kind: String, text: String, offset: Int): Pair<List<BrowseItem>, Int> =
        withContext(Dispatchers.IO) {
            val o = obj(Native.nativeBrowseSearch(source, kind, text, offset, false))
            val arr = o.getJSONArray("results")
            val items = (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                BrowseItem(
                    source = p.optString("source"),
                    id = p.optString("id"),
                    title = p.optString("title"),
                    summary = p.optString("summary"),
                    author = p.optString("author"),
                    downloads = p.optLong("downloads"),
                    kind = p.optString("kind"),
                    support = p.optString("support"),
                    installable = p.optBoolean("installable"),
                    reason = p.str("reason"),
                    works = p.optBoolean("works"),
                    installed = p.optBoolean("installed"),
                )
            }
            items to o.optInt("next", offset + items.size)
        }

    suspend fun install(source: String, id: String): List<String> = withContext(Dispatchers.IO) {
        val o = obj(Native.nativeBrowseInstall(source, id))
        val arr = o.optJSONArray("installed")
        if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }
    }

    suspend fun progress(): BrowseProgress = withContext(Dispatchers.IO) {
        val o = obj(Native.nativeBrowseProgress())
        BrowseProgress(o.optBoolean("running"), o.optString("line"), o.optDouble("fraction", 0.0).toFloat(), o.str("error"))
    }

    suspend fun installed(): List<BrowseEntry> = withContext(Dispatchers.IO) {
        val arr = obj(Native.nativeBrowseInstalled()).getJSONArray("entries")
        (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            BrowseEntry(e.optString("project"), e.optString("title"), e.optString("kind"), e.optString("number"), e.optString("source"))
        }
    }

    suspend fun remove(project: String): String = withContext(Dispatchers.IO) {
        obj(Native.nativeBrowseRemove(project)).optString("removed")
    }
}
