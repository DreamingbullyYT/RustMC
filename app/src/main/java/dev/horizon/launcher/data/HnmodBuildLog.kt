package dev.horizon.launcher.data

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared append-only log shown by Horizon while the embedded HNMod compiler is running. */
object HnmodBuildLog {
    private const val FILE_NAME = "hnmod-live.log"
    private var writer: BufferedWriter? = null

    fun file(context: Context): File =
        File(EmbeddedTermuxPaths.state(context), "logs/$FILE_NAME")

    @Synchronized
    fun begin(context: Context) {
        writer?.close()
        val target = file(context)
        require(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "Cannot create HNMod live log directory."
        }
        writer = target.outputStream().bufferedWriter(Charsets.UTF_8).also {
            it.appendLine("Horizon HNMod compiler log")
            it.flush()
        }
    }

    @Synchronized
    fun append(context: Context, line: String) {
        val target = file(context)
        require(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "Cannot create HNMod live log directory."
        }
        val output = writer ?: target.outputStream().bufferedWriter(Charsets.UTF_8).also { writer = it }
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        output.append("[$stamp] ").append(line).append('\n')
        output.flush()
    }

    @Synchronized
    fun finish(context: Context) {
        append(context, "HNMod compiler session finished")
        writer?.close()
        writer = null
    }
}
