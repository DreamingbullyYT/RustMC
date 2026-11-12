package dev.ferrite.core

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class CrashEntry(val file: File, val whenMs: Long, val diagnosis: Diagnosis)

object Diagnostics {
    fun crashes(dir: File): List<CrashEntry> = (dir.listFiles() ?: emptyArray()).filter { it.isFile && (it.name.endsWith(".txt") || it.name.endsWith(".log")) }
        .sortedByDescending { it.lastModified() }.map { CrashEntry(it, it.lastModified(), CrashAnalyzer.analyze(it.readText().takeLast(200_000))) }

    /** Removes tokens and obvious secrets before a log leaves the phone. */
    fun redact(log: String): String = log
        .replace(Regex("(?i)(access_?token|session|authorization|bearer|password|refresh_?token)([\"'=: ]+)[A-Za-z0-9._\\-+/=]{8,}"), "$1$2[redacted]")
        .replace(Regex("eyJ[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}"), "[redacted-jwt]")

    /** Zip with the redacted log, the diagnosis and system facts, for a support request. */
    fun exportForSupport(log: String, facts: Map<String, String>, out: File) = ZipOutputStream(out.outputStream().buffered()).use { z ->
        val d = CrashAnalyzer.analyze(log)
        z.putNextEntry(ZipEntry("log.txt")); z.write(redact(log).toByteArray()); z.closeEntry()
        z.putNextEntry(ZipEntry("report.json")); z.write(Json.write(linkedMapOf("cause" to d.cause.name, "hint" to d.hint, "evidence" to redact(d.evidence), "system" to facts), true).toByteArray()); z.closeEntry()
    }

    fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").let { md -> f.inputStream().use { s -> val b = ByteArray(65536); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }; md.digest().joinToString("") { "%02x".format(it) } }

    data class Integrity(val missing: List<String>, val corrupt: List<String>) { val ok get() = missing.isEmpty() && corrupt.isEmpty() }

    /** Checks files under [root] against a `relative path -> sha256` manifest. */
    fun verify(root: File, expected: Map<String, String>): Integrity {
        val missing = mutableListOf<String>(); val corrupt = mutableListOf<String>()
        for ((p, h) in expected) { val f = File(root, p); if (!f.isFile) missing += p else if (!sha256(f).equals(h, true)) corrupt += p }
        return Integrity(missing.sorted(), corrupt.sorted())
    }
}
