package dev.ferrite.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** World backups: a plain zip of `saves/`, so players can open it anywhere. */
object Backup {
    fun create(gameDir: File, out: File): Int {
        val saves = File(gameDir, "saves"); var n = 0
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            saves.walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(ZipEntry("saves/" + f.relativeTo(saves).invariantSeparatorsPath)); f.inputStream().use { it.copyTo(z) }; z.closeEntry(); n++
            }
        }
        return n
    }

    /** Restores into `gameDir`; rejects entries that would escape it (zip-slip) and never overwrites existing worlds. */
    fun restore(zip: File, gameDir: File): Int {
        val base = gameDir.canonicalFile; var n = 0
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory || !e.name.startsWith("saves/")) continue
                val target = File(base, e.name).canonicalFile
                require(target.path.startsWith(base.path + File.separator)) { "unsafe path in backup: ${e.name}" }
                if (target.exists()) continue
                target.parentFile.mkdirs(); target.outputStream().use { z.copyTo(it) }; n++
            }
        }
        return n
    }
}
