package dev.ferrite.core

/** The only network seam: the app plugs in a real HTTP client, tests plug in canned answers. */
fun interface Http { fun get(url: String): String }

data class Release(val tag: String, val apkUrl: String?, val notes: String)

object Updates {
    private fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }

    fun isNewer(current: String, candidate: String): Boolean {
        val a = parts(current); val b = parts(candidate)
        for (i in 0 until maxOf(a.size, b.size)) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return y > x }
        return false
    }

    fun latest(http: Http, repo: String): Release? = runCatching {
        val o = Json.parse(http.get("https://api.github.com/repos/$repo/releases/latest")).obj()
        val apk = o["assets"].arr().map { it.obj() }.firstOrNull { it["name"].str().endsWith(".apk") }?.get("browser_download_url") as? String
        Release(o["tag_name"].str(), apk, o["body"].str())
    }.getOrNull()
}

data class ModHit(val slug: String, val title: String, val summary: String, val downloads: Long, val iconUrl: String?)

object Modrinth {
    private const val API = "https://api.modrinth.com/v2"
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    fun searchUrl(query: String, loader: String? = null, limit: Int = 20): String {
        val facets = buildList { if (loader != null) add("[\"categories:${loader}\"]") }
        val f = if (facets.isEmpty()) "" else "&facets=" + enc(facets.joinToString(",", "[", "]"))
        return "$API/search?query=${enc(query)}&limit=${limit.coerceIn(1, 50)}$f"
    }

    fun search(http: Http, query: String, loader: String? = null): List<ModHit> = runCatching {
        Json.parse(http.get(searchUrl(query, loader))).obj()["hits"].arr().map {
            val m = it.obj(); ModHit(m["slug"].str(), m["title"].str(), m["description"].str(), (m["downloads"] as? Number)?.toLong() ?: 0, m["icon_url"] as? String)
        }
    }.getOrDefault(emptyList())
}
