package dev.horizon.launcher.data

import java.io.File

/**
 * Small adapter for the game Activity copied from Horizon Android Edition.
 * Runtime updates are not exposed yet; the APK-bundled game library is used.
 */
object Updater {
    fun gamePath(filesDir: File): String? {
        val updated = File(File(filesDir, "launcher"), "runtime/libhorizon_android_game.so")
        return updated.takeIf { it.isFile && it.canRead() }?.absolutePath
    }

    fun armGuard(filesDir: File, phase: String) {
        val launcher = File(filesDir, "launcher")
        if (!launcher.exists() && !launcher.mkdirs()) return
        runCatching { File(launcher, "game-session.txt").writeText(phase) }
    }

    fun clearGameSession(filesDir: File, clearStartupGuard: Boolean = false) {
        val launcher = File(filesDir, "launcher")
        runCatching { File(launcher, "game-session.txt").delete() }
        if (clearStartupGuard) runCatching { File(launcher, "game-startup-guard.txt").delete() }
    }
}
