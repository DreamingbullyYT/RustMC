package dev.horizon.updater

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String?
)

data class ReleaseInfo(
    val tag: String,
    val title: String,
    val changelog: String,
    val pageUrl: String,
    val publishedAt: String?,
    val apk: ReleaseAsset,
    val isPrerelease: Boolean
)

data class DownloadProgress(
    val status: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val reason: Int
) {
    val percent: Int
        get() = if (totalBytes > 0) ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
}

data class ChecksumResult(
    val file: File,
    val actualSha256: String,
    val verified: Boolean,
    val expectedSha256: String?
)

class HorizonUpdater(context: Context) {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)

    fun checkLatestRelease(): ReleaseInfo {
        val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Horizon-Android-Updater")
        }
        try {
            val code = connection.responseCode
            if (code == 404) throw IllegalStateException("Nu există încă un release publicat pentru rustMC.")
            if (code !in 200..299) throw IllegalStateException("GitHub a răspuns cu HTTP $code.")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("draft", false)) throw IllegalStateException("Cel mai recent release este încă draft.")
            val assetsJson = json.optJSONArray("assets") ?: throw IllegalStateException("Release-ul nu conține fișiere.")
            var apk: ReleaseAsset? = null
            val checksumAssets = mutableListOf<Pair<String, String>>()
            for (index in 0 until assetsJson.length()) {
                val item = assetsJson.getJSONObject(index)
                val name = item.optString("name")
                val url = item.optString("browser_download_url")
                if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://")) {
                    val digest = item.optString("digest").takeIf { it.startsWith("sha256:", true) }?.substringAfter(':')
                    apk = ReleaseAsset(name, url, item.optLong("size", 0L), digest)
                } else if ((name.endsWith(".sha256", true) || name.equals("SHA256SUMS", true)) && url.startsWith("https://")) {
                    checksumAssets += name to url
                }
            }
            val selectedApk = apk ?: throw IllegalStateException("Release-ul nu conține un APK.")
            var checksum = selectedApk.sha256
            if (checksum == null) {
                val companion = checksumAssets.firstOrNull { it.first.equals("${selectedApk.name}.sha256", true) }
                    ?: checksumAssets.firstOrNull()
                if (companion != null) checksum = fetchChecksum(companion.second, selectedApk.name)
            }
            return ReleaseInfo(
                tag = json.optString("tag_name", "unknown"),
                title = json.optString("name").ifBlank { json.optString("tag_name", "Horizon update") },
                changelog = json.optString("body").ifBlank { "Nu a fost furnizat un changelog." },
                pageUrl = json.optString("html_url"),
                publishedAt = json.optString("published_at").takeIf { it.isNotBlank() && it != "null" },
                apk = selectedApk.copy(sha256 = checksum),
                isPrerelease = json.optBoolean("prerelease", false)
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchChecksum(url: String, apkName: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "Horizon-Android-Updater")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val line = text.lineSequence().firstOrNull { it.contains(apkName) } ?: text.lineSequence().firstOrNull()
            line?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }?.lowercase(Locale.ROOT)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    fun enqueueApkDownload(asset: ReleaseAsset): Long {
        val safeName = asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val request = DownloadManager.Request(Uri.parse(asset.downloadUrl))
            .setTitle("Horizon $safeName")
            .setDescription("Downloading launcher update")
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, safeName)
        return downloadManager.enqueue(request)
    }

    fun queryDownload(id: Long): DownloadProgress? {
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return DownloadProgress(
                status = it.getIntColumn(DownloadManager.COLUMN_STATUS),
                downloadedBytes = it.getLongColumn(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                totalBytes = it.getLongColumn(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                reason = it.getIntColumn(DownloadManager.COLUMN_REASON)
            )
        }
    }

    fun verifyDownloadedApk(asset: ReleaseAsset): ChecksumResult {
        val safeName = asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("Spațiul privat pentru descărcări nu este disponibil.")
        val file = File(directory, safeName)
        if (!file.isFile) throw IllegalStateException("APK-ul descărcat nu a fost găsit.")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        val expected = asset.sha256?.lowercase(Locale.ROOT)
        return ChecksumResult(file, actual, expected != null && actual.equals(expected, true), expected)
    }

    fun cancelDownload(id: Long): Int = downloadManager.remove(id)

    private fun Cursor.getIntColumn(name: String): Int = getInt(getColumnIndexOrThrow(name))
    private fun Cursor.getLongColumn(name: String): Long = getLong(getColumnIndexOrThrow(name))

    companion object {
        private const val RELEASES_API = "https://api.github.com/repos/DreamingbullyYT/rustMC/releases/latest"
        const val STATUS_PENDING = DownloadManager.STATUS_PENDING
        const val STATUS_RUNNING = DownloadManager.STATUS_RUNNING
        const val STATUS_PAUSED = DownloadManager.STATUS_PAUSED
        const val STATUS_SUCCESSFUL = DownloadManager.STATUS_SUCCESSFUL
        const val STATUS_FAILED = DownloadManager.STATUS_FAILED
    }
}
