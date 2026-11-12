package dev.ferrite.core

enum class Cause { OUT_OF_MEMORY, GPU_DRIVER, MISSING_RESOURCES, MOD_PANIC, NETWORK, DISK_FULL, UNKNOWN }

data class Diagnosis(val cause: Cause, val hint: String, val evidence: String)

/** Maps a game log to a likely cause and a short fix, so players do not have to read a backtrace. */
object CrashAnalyzer {
    private val rules: List<Triple<Regex, Cause, String>> = listOf(
        Triple(Regex("(?i)out of memory|oom-kill|lowmemorykiller|memory allocation of \\d+ bytes failed"), Cause.OUT_OF_MEMORY, "The phone ran out of RAM. Lower the render distance or the resolution scale, and close other apps."),
        Triple(Regex("(?i)no space left on device|ENOSPC|disk full"), Cause.DISK_FULL, "Storage is full. Free some space or move the instance."),
        Triple(Regex("(?i)vk_error_device_lost|vkcreate\\w+ failed|failed to create (vulkan|egl)|no suitable (adapter|gpu)|requestadapter"), Cause.GPU_DRIVER, "The GPU driver failed. Try the OpenGL ES renderer or a lower resolution scale."),
        Triple(Regex("(?i)horizon-resources\\.json|asset index|missing (asset|resource)|prepared (resources|tables)"), Cause.MISSING_RESOURCES, "Game resources are missing. Import your own Minecraft files again from the instance page."),
        Triple(Regex("(?i)panicked at .*(mods?|hnmod)|native mod .* (failed|panicked)"), Cause.MOD_PANIC, "A native mod crashed. Disable the last mod you installed."),
        Triple(Regex("(?i)connection (refused|reset|timed out)|dns|unknown host|handshake failed"), Cause.NETWORK, "Network problem. Check the server address and your connection."),
    )

    fun analyze(log: String): Diagnosis {
        val lines = log.lineSequence().toList().takeLast(4000)
        for ((re, cause, hint) in rules) lines.lastOrNull { re.containsMatchIn(it) }?.let { return Diagnosis(cause, hint, it.trim().take(200)) }
        val panic = lines.lastOrNull { "panicked at" in it }
        return Diagnosis(Cause.UNKNOWN, "Unknown crash. Share the log when reporting it.", panic?.trim()?.take(200) ?: lines.lastOrNull { it.isNotBlank() }?.trim()?.take(200) ?: "")
    }
}
