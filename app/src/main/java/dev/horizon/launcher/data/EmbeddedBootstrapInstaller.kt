package dev.horizon.launcher.data

import android.content.Context
import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Installs the official Termux bootstrap into a private fake /data/data/com.termux tree.
 * PRoot exposes that tree at Termux's expected absolute paths without installing Termux itself.
 */
object EmbeddedBootstrapInstaller {
    private const val MAX_ENTRIES = 500_000
    private const val MAX_UNPACKED_BYTES = 12L * 1024L * 1024L * 1024L
    private const val SYMLINK_SEPARATOR = '←'

    fun install(context: Context, archive: File): File {
        require(archive.isFile && archive.length() > 0L) { "Termux bootstrap archive is missing or empty." }
        val root = EmbeddedTermuxPaths.root(context)
        val staging = File(root, "rootfs-staging")
        val backup = File(root, "rootfs-backup")
        val stagingPrefix = File(staging, "data/data/com.termux/files/usr")
        staging.deleteRecursively()
        backup.deleteRecursively()
        require(stagingPrefix.mkdirs()) { "Cannot create private Termux prefix." }

        var totalBytes = 0L
        var entries = 0
        var sawSymlinkTable = false
        val paths = HashSet<String>()
        val symlinks = ArrayList<Pair<String, String>>()

        try {
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    require(entries <= MAX_ENTRIES) { "Bootstrap contains too many entries." }
                    if (entry.name == "SYMLINKS.txt" || entry.name == "./SYMLINKS.txt") {
                        require(!sawSymlinkTable) { "Bootstrap contains duplicate symlink tables." }
                        sawSymlinkTable = true
                        val bytes = zip.readBytesBounded(8 * 1024 * 1024)
                        bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.forEach { line ->
                            val split = line.indexOf(SYMLINK_SEPARATOR)
                            require(split > 0 && line.indexOf(SYMLINK_SEPARATOR, split + 1) < 0) {
                                "Malformed bootstrap symlink record."
                            }
                            val target = line.substring(0, split)
                            val link = safeRelative(line.substring(split + 1))
                            validateTarget(stagingPrefix, link, target)
                            require(paths.add(link)) { "Duplicate bootstrap path: $link" }
                            symlinks += target to link
                        }
                        zip.closeEntry()
                        continue
                    }

                    val relative = safeRelative(entry.name.trimEnd('/').removePrefix("./"))
                    if (relative.isEmpty()) {
                        zip.closeEntry()
                        continue
                    }
                    require(paths.add(relative)) { "Duplicate bootstrap path: $relative" }
                    val output = File(stagingPrefix, relative)
                    ensureInside(stagingPrefix, output)
                    if (entry.isDirectory || entry.name.endsWith("/")) {
                        require(output.isDirectory || output.mkdirs()) { "Cannot create $relative." }
                    } else {
                        require(output.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
                            "Cannot create parent directory for $relative."
                        }
                        FileOutputStream(output).use { stream ->
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                totalBytes += count
                                require(totalBytes <= MAX_UNPACKED_BYTES) { "Bootstrap exceeds the unpacked size limit." }
                                stream.write(buffer, 0, count)
                            }
                        }
                        if (isExecutablePath(relative)) runCatching { Os.chmod(output.absolutePath, 0x1C0) }
                    }
                    zip.closeEntry()
                }
            }

            require(sawSymlinkTable && symlinks.isNotEmpty()) { "Official bootstrap has no usable SYMLINKS.txt." }
            require(listOf("bin/bash", "bin/env", "bin/apt").all { it in paths }) {
                "Bootstrap is missing required Termux runtime files."
            }

            symlinks.forEach { (target, relativeLink) ->
                val link = File(stagingPrefix, relativeLink)
                ensureInside(stagingPrefix, link)
                require(!link.exists() && !java.nio.file.Files.isSymbolicLink(link.toPath())) {
                    "Symlink destination already exists: $relativeLink"
                }
                link.parentFile?.let { require(it.isDirectory || it.mkdirs()) }
                Os.symlink(target, link.absolutePath)
            }

            require(!rootfsExists(root) || File(root, "rootfs").renameTo(backup)) {
                "Cannot move the previous Termux runtime aside."
            }
            val finalRootfs = File(root, "rootfs")
            if (!staging.renameTo(finalRootfs)) {
                if (backup.exists()) backup.renameTo(finalRootfs)
                error("Cannot activate the Termux bootstrap.")
            }
            backup.deleteRecursively()
            return EmbeddedTermuxPaths.prefix(context)
        } catch (error: Exception) {
            staging.deleteRecursively()
            if (backup.exists()) {
                File(root, "rootfs").deleteRecursively()
                backup.renameTo(File(root, "rootfs"))
            }
            throw error
        }
    }

    private fun rootfsExists(root: File) = File(root, "rootfs").exists()

    private fun validateTarget(prefix: File, link: String, target: String) {
        require(target.isNotBlank() && !target.contains('\u0000') && !target.contains('\\')) {
            "Malformed bootstrap symlink target."
        }
        if (target.startsWith("/")) {
            require(target.startsWith("/data/data/com.termux/files/usr/") ||
                target == "/data/data/com.termux/files/usr" ||
                target.startsWith("/system/") || target == "/system" ||
                target.startsWith("/apex/") || target == "/apex") {
                "Bootstrap symlink points outside the private Termux root: $link"
            }
        } else {
            val resolved = File(File(prefix, link).parentFile, target).canonicalFile.toPath()
            require(resolved.startsWith(prefix.canonicalFile.toPath())) {
                "Bootstrap symlink escapes the private Termux prefix: $link"
            }
        }
    }

    private fun safeRelative(raw: String): String {
        if (raw.isEmpty()) return ""
        require(!raw.startsWith("/") && !raw.contains('\\') && !raw.contains('\u0000')) {
            "Unsafe absolute or malformed bootstrap path."
        }
        val normalized = raw.removePrefix("./")
        val parts = normalized.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) {
            "Path traversal detected in bootstrap."
        }
        return parts.joinToString("/")
    }

    private fun ensureInside(root: File, candidate: File) {
        require(candidate.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
            "Bootstrap path escapes the extraction directory."
        }
    }

    private fun isExecutablePath(path: String) =
        path.startsWith("bin/") || path.startsWith("libexec/") ||
            path.startsWith("lib/apt/") || path.startsWith("etc/termux/")

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Bootstrap symlink table is too large." }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
