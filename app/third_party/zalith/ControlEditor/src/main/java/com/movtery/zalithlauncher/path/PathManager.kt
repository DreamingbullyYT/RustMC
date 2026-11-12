package com.movtery.zalithlauncher.path

import java.io.File

/** App-private storage root supplied by Horizon before the editor is opened. */
object PathManager {
    @Volatile var DIR_CONTROL_LAYOUTS: File = File(System.getProperty("java.io.tmpdir") ?: ".", "horizon-control-layouts")
        private set
    fun initialize(filesDir: File) {
        DIR_CONTROL_LAYOUTS = File(filesDir, "launcher/control-layouts").apply { mkdirs() }
    }
}
