package dev.horizon.launcher.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import dev.horizon.launcher.core.Native
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Updates the whole app (APK). The APK is an asset of the signed Nightly release (listed in its
 * SHA256SUMS like every other file), so the Rust core (core/src/update.rs) checks the Ed25519
 * signature and the SHA-256 before the file reaches Android's PackageInstaller, which asks the
 * player to confirm. Android only installs it over the current app when both are signed with the
 * same key (CI signs every build with the project's fixed key).
 */
object AppUpdater {
    private const val ACTION = "dev.horizon.launcher.APP_UPDATE_RESULT"

    data class Offer(val build: Long, val file: String, val tag: String, val version: String, val upstream: String)

    private fun currentBuild(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun folder(context: Context, tag: String) = File(File(context.cacheDir, "app-update"), tag)

    private fun checked(raw: String): JSONObject {
        val o = JSONObject(raw)
        if (o.has("error") && o.optString("error").isNotEmpty()) error(o.getString("error"))
        return o
    }

    /** Blocking. Returns the newer APK on offer, or null when this app is current (or none is published). */
    fun check(context: Context): Offer? {
        val newest = Updater.newest() ?: return null
        val dir = folder(context, newest.tag).apply { mkdirs() }
        val manifest = checked(Native.nativeUpdateDownload(newest.tag, "manifest", dir.absolutePath))
        val apk = manifest.optString("apk").takeIf { it.isNotEmpty() } ?: return null
        val build = manifest.optLong("build", 0)
        if (build <= currentBuild(context)) return null
        return Offer(build, apk, newest.tag, newest.version, manifest.optString("upstream"))
    }

    /** Blocking: downloads the APK and verifies its signed checksum. [onProgress] gets 0..1. */
    fun download(context: Context, offer: Offer, onProgress: (Float) -> Unit): File {
        val dir = folder(context, offer.tag).apply { mkdirs() }
        var running = true
        val poller = thread(isDaemon = true) {
            while (running) {
                runCatching { onProgress(JSONObject(Native.nativeUpdateProgress()).optDouble("fraction", 0.0).toFloat()) }
                Thread.sleep(300)
            }
        }
        try {
            checked(Native.nativeUpdateDownload(offer.tag, "apk", dir.absolutePath))
        } finally {
            running = false
            poller.interrupt()
        }
        return File(dir, offer.file).takeIf { it.isFile } ?: error("The update was not downloaded")
    }

    /** True when Android lets this app install packages; otherwise the caller opens the settings page. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun settingsIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hands the APK to the system installer; Android shows its own confirmation. */
    fun install(context: Context, apk: File) {
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { c.startActivity(it) }
                    }
                    else -> runCatching { app.unregisterReceiver(this) }
                }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("horizon.apk", 0, apk.length()).use { sink ->
                    input.copyTo(sink)
                    session.fsync(sink)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(app, id, Intent(ACTION).setPackage(app.packageName), flags)
            session.commit(pending.intentSender)
        }
    }
}
