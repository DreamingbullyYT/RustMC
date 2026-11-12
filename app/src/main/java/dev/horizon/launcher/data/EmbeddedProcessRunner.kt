package dev.horizon.launcher.data

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

/** Runs commands in the Gemini-style private Termux/PRoot environment. */
object EmbeddedProcessRunner {
    data class Result(val exitCode: Int, val output: String, val logFile: File)

    fun runScript(context: Context, script: File, args: List<String> = emptyList(), onLine: (String) -> Unit = {}): Result {
        require(script.isFile) { "Build script not found: ${script.name}" }
        val guestScript = guestPath(context, script)
        val guestArgs = args.map { arg ->
            if (arg.startsWith("/")) runCatching { guestPath(context, File(arg)) }.getOrDefault(arg) else arg
        }
        val command = "exec ${EmbeddedTermuxPaths.GUEST_PREFIX}/bin/bash ${shellQuote(guestScript)} " +
            guestArgs.joinToString(" ") { shellQuote(it) }
        return run(context, command, script.name, onLine)
    }

    fun runShellCommand(context: Context, command: String, label: String, onLine: (String) -> Unit = {}): Result =
        run(context, command, label, onLine)

    private fun run(context: Context, shellCommand: String, label: String, onLine: (String) -> Unit): Result {
        val root = EmbeddedTermuxPaths.root(context)
        val rootfs = EmbeddedTermuxPaths.rootfs(context)
        val termuxData = EmbeddedTermuxPaths.termuxData(context)
        val prefix = EmbeddedTermuxPaths.prefix(context)
        val proot = EmbeddedTermuxPaths.proot(context)
        val loader = EmbeddedTermuxPaths.loader(context)
        val state = EmbeddedTermuxPaths.state(context)
        val home = EmbeddedTermuxPaths.home(context)
        val tmp = EmbeddedTermuxPaths.tmp(context)
        val logs = File(state, "logs")
        val prefixLib = File(prefix, "lib")
        listOf(root, rootfs, termuxData, prefixLib, home, tmp, logs).forEach {
            require(it.isDirectory || it.mkdirs()) { "Cannot create embedded Termux runtime directories." }
        }
        require(proot.isFile && loader.isFile) {
            "Embedded PRoot runtime is missing. Reinstall the Horizon app build."
        }
        require(File(prefixLib, "libtalloc.so.2").isFile) {
            "Embedded PRoot dependency libtalloc.so.2 is missing. Reinstall Horizon."
        }

        // Match Gemini-cli-for-Android: root=/, bind only the fake Termux data tree,
        // and keep Android's system paths visible to the guest.
        val guestCommand = mutableListOf(
            proot.absolutePath, "-r", "/", "--link2symlink", "--kill-on-exit",
            "-b", "${termuxData.absolutePath}:/data/data/com.termux",
            "-b", "${context.filesDir.absolutePath}:/horizon-host",
            "-w", EmbeddedTermuxPaths.GUEST_HOME,
            "${EmbeddedTermuxPaths.GUEST_PREFIX}/bin/env", "-u", "LD_LIBRARY_PATH",
            "${EmbeddedTermuxPaths.GUEST_PREFIX}/bin/bash", "-lc", shellCommand
        )
        val hostCommand = mutableListOf(
            "/system/bin/sh", "-c", "exec \"\$@\"",
            "horizon"
        )
        hostCommand.addAll(guestCommand)

        val process = ProcessBuilder(hostCommand)
            .directory(state)
            .redirectErrorStream(true)
        val env = process.environment()
        env["PROOT"] = proot.absolutePath
        env["PROOT_LOADER"] = loader.absolutePath
        env["PROOT_TMP_DIR"] = tmp.absolutePath
        env["PROOT_NO_SECCOMP"] = "1"
        // PRoot is an Android executable: its dependencies must be visible to the host linker.
        // The same prefix/lib directory is used by Gemini CLI's working Termux setup.
        env["LD_LIBRARY_PATH"] = prefixLib.absolutePath
        env["PREFIX"] = EmbeddedTermuxPaths.GUEST_PREFIX
        env["TERMUX__PREFIX"] = EmbeddedTermuxPaths.GUEST_PREFIX
        env["TERMUX__USER_ID"] = Process.myUid().toString()
        env["TERMUX_APP__PACKAGE_NAME"] = "com.termux"
        env["TERMUX_APP__DATA_DIR"] = "/data/data/com.termux"
        env["TERMUX__ROOTFS"] = "/data/data/com.termux/files"
        env["TERMUX__NAME"] = "Horizon"
        env["TERMUX_VERSION"] = "0.118.0"
        env["TERMUX_APP_PID"] = Process.myPid().toString()
        env["TERMUX_MAIN_PACKAGE_FORMAT"] = "debian"
        env["TERMUX_APK_RELEASE"] = "F_DROID"
        env["TERMUX_IS_TERMUX"] = "1"
        env["ANDROID__BUILD_VERSION_SDK"] = Build.VERSION.SDK_INT.toString()
        env["HOME"] = EmbeddedTermuxPaths.GUEST_HOME
        env["TMPDIR"] = "${EmbeddedTermuxPaths.GUEST_PREFIX}/tmp"
        env["PATH"] = "${EmbeddedTermuxPaths.GUEST_PREFIX}/bin:/system/bin:/system/xbin"
        env["SHELL"] = "${EmbeddedTermuxPaths.GUEST_PREFIX}/bin/bash"
        env["LANG"] = "en_US.UTF-8"
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["HORIZON_EMBEDDED"] = "1"

        val logFile = File(logs, "${label.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.log")
        val output = StringBuilder()
        val child = process.start()
        logFile.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
            child.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    writer.appendLine(line)
                    writer.flush() // Make each compiler line visible to the live log immediately.
                    onLine(line)
                    output.appendLine(line)
                    if (output.length > 256 * 1024) output.delete(0, output.length - 192 * 1024)
                }
            }
        }
        val exitCode = child.waitFor()
        return Result(exitCode, output.toString(), logFile)
    }

    private fun guestPath(context: Context, file: File): String {
        val base = context.filesDir.canonicalFile.toPath()
        val path = file.canonicalFile.toPath()
        require(path.startsWith(base)) { "Build input is outside Horizon's private directory." }
        val relative = base.relativize(path).toString().replace(File.separatorChar, '/')
        return "/horizon-host/$relative"
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
