package dev.horizon.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.horizon.launcher.data.Core
import dev.horizon.launcher.game.HorizonGameActivity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Everything the launcher does to get Minecraft installed and the game started, with no UI in it.
 * Any screen (or none) can call these; [onStatus] receives short progress text on the caller's thread.
 * Extracted from the old Compose `HorizonApp`, behaviour unchanged.
 */
object GameSetup {
    suspend fun ready(): Boolean = runCatching { Core.gameReady() }.getOrDefault(false)

    /** Runs [block] while polling the native installer's progress into [onStatus]. */
    private suspend fun <T> withProgress(onStatus: (String) -> Unit, block: suspend () -> T): T = coroutineScope {
        val poll = launch {
            while (true) {
                runCatching { Core.installProgress() }.onSuccess { p ->
                    val total = p.optLong("total")
                    val done = p.optLong("done")
                    val stage = p.optString("stage", "Installing Minecraft")
                    onStatus(if (total > 0) "$stage · ${done * 100 / total}%" else stage)
                }
                delay(650)
            }
        }
        try { block() } finally { poll.cancel() }
    }

    /** Downloads (verified) and installs Minecraft. Throws with a readable message on failure. */
    suspend fun install(context: Context, onStatus: (String) -> Unit) {
        onStatus("Checking Minecraft version")
        withProgress(onStatus) {
            Core.setImportCache(context.cacheDir.absolutePath)
            Core.installGame(context.cacheDir.absolutePath)
        }
    }

    /** Imports an existing Mojang launcher folder (SAF tree) instead of downloading. Returns files copied. */
    suspend fun importFolder(context: Context, tree: Uri, onStatus: (String) -> Unit): Int {
        onStatus("Reading Minecraft folder")
        Core.setImportCache(context.cacheDir.absolutePath)
        val count = importMinecraftFolder(context, tree)
        onStatus("Preparing Minecraft")
        withProgress(onStatus) { Core.installGame(context.cacheDir.absolutePath) }
        return count
    }

    /** Restores a Horizon-Minecraft.zip backup; finishes the install if the backup was incomplete. */
    suspend fun restoreBackup(context: Context, zip: Uri, onStatus: (String) -> Unit): Int {
        onStatus("Restoring Minecraft backup")
        val count = MinecraftBackup.restore(context, zip) { onStatus(it) }
        if (!ready()) {
            onStatus("Finishing Minecraft setup")
            Core.setImportCache(context.cacheDir.absolutePath)
            withProgress(onStatus) { Core.installGame(context.cacheDir.absolutePath) }
        }
        return count
    }

    /** Copies the installed game to Downloads/Horizon-Minecraft.zip; returns where it went. */
    suspend fun exportBackup(context: Context, onStatus: (String) -> Unit): String =
        MinecraftBackup.exportToDownloads(context, onStatus)

    /** Starts the game process. */
    fun play(context: Context): Result<Unit> = runCatching {
        context.startActivity(
            Intent(context, HorizonGameActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    }
}
