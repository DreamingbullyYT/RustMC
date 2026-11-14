package dev.horizon.launcher.data

import android.app.Activity
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** First-run installer and entry point for Horizon's private Termux-compatible HNMod toolchain. */
object EmbeddedToolchain {
    private const val BOOTSTRAP_SHA256 =
        "5a1429b6c68ee3bdc17b5fd6e47ba42933ec924cd7283fe74992574e1d5badae"
    private const val STATE_SCHEMA = "3"

    data class RuntimeStatus(
        val supportedAbi: Boolean,
        val installed: Boolean,
        val detail: String
    )

    fun status(activity: Activity): RuntimeStatus {
        val supported = Build.VERSION.SDK_INT >= 29 &&
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
        val prefix = EmbeddedTermuxPaths.prefix(activity)
        val marker = EmbeddedTermuxPaths.marker(activity)
        val installed = supported &&
            marker.isFile && marker.readText().contains("schema=$STATE_SCHEMA") &&
            existsOrSymlink(File(prefix, "bin/bash")) &&
            existsOrSymlink(File(prefix, "bin/cargo")) &&
            existsOrSymlink(File(prefix, "bin/rustc")) &&
            existsOrSymlink(File(prefix, "bin/clang")) &&
            EmbeddedTermuxPaths.proot(activity).isFile &&
            EmbeddedTermuxPaths.loader(activity).isFile
        val detail = when {
            !supported -> "HNMod local compilation requires ARM64 and Android 10 or newer."
            installed -> "Private Termux-compatible compiler runtime ready."
            else -> "The compiler will be installed automatically on the first HNMod compilation."
        }
        return RuntimeStatus(supported, installed, detail)
    }

    suspend fun start(
        activity: Activity,
        selected: List<Uri>,
        onProgress: (String) -> Unit = {},
        onLogLine: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        require(selected.isNotEmpty()) { "Select at least one .hnmod file." }
        val invalid = selected.firstOrNull { uri ->
            val name = activity.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            name?.endsWith(".hnmod", ignoreCase = true) != true
        }
        require(invalid == null) { "Only .hnmod archives can be compiled." }
        val initial = status(activity)
        check(initial.supportedAbi) { initial.detail }

        HnmodBuildLog.begin(activity)
        fun logLine(message: String) {
            HnmodBuildLog.append(activity, message)
            onLogLine(message)
        }
        fun progress(message: String) {
            HnmodBuildLog.append(activity, message)
            onProgress(message)
            onLogLine(message)
        }
        // Keeps the process in the foreground while PRoot/Cargo run, even if the user leaves the app.
        HnmodBuildService.begin(activity.applicationContext)
        try {
            if (!initial.installed) installRuntime(activity, ::progress, ::logLine)
            progress("Preparing HNMod build")
            when (val imported = HnmodBuildAdapter.compile(activity, EmbeddedTermuxPaths.prefix(activity), selected, ::logLine)) {
                is Updater.Imported.Ok ->
                    "Build ${imported.name} (${imported.sha.take(12)}) imported and activated. Restart the game to use it."
                is Updater.Imported.Bad -> error(imported.message)
            }
        } catch (error: Exception) {
            logLine("FATAL: ${error.message ?: error::class.java.simpleName}")
            throw error
        } finally {
            HnmodBuildLog.finish(activity)
            HnmodBuildService.end(activity.applicationContext)
        }
    }

    /** Imports a registry-pinned .hpack into a separate private profile directory. */
    suspend fun installHpack(
        activity: Activity,
        pack: Uri,
        registryIndex: Uri? = null,
        allowUnsigned: Boolean = false,
        onProgress: (String) -> Unit = {},
        onLogLine: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val initial = status(activity)
        check(initial.supportedAbi) { initial.detail }
        HnmodBuildLog.begin(activity)
        fun logLine(message: String) {
            HnmodBuildLog.append(activity, message)
            onLogLine(message)
        }
        fun progress(message: String) {
            HnmodBuildLog.append(activity, message)
            onProgress(message)
            onLogLine(message)
        }
        HnmodBuildService.begin(activity.applicationContext)
        try {
            if (!initial.installed) installRuntime(activity, ::progress, ::logLine)
            progress("Checking registry signature tools")
            val securityTools = EmbeddedProcessRunner.runShellCommand(
                activity,
                "DEBIAN_FRONTEND=noninteractive apt install -y openssl minisign",
                "hpack-security-tools", onLine = ::logLine
            )
            check(securityTools.exitCode == 0) {
                "Could not install SDK/modpack signature tools (exit ${securityTools.exitCode}). Log: ${securityTools.logFile.absolutePath}"
            }
            progress("Preparing Hpack installer")
            HnmodBuildAdapter.materializeProject(activity)
            progress("Downloading and verifying pinned Hpack mods")
            HpackBuildAdapter.install(activity, pack, registryIndex, allowUnsigned, ::logLine)
        } catch (error: Exception) {
            logLine("FATAL: ${error.message ?: error::class.java.simpleName}")
            throw error
        } finally {
            HnmodBuildLog.finish(activity)
            HnmodBuildService.end(activity.applicationContext)
        }
    }

    private suspend fun installRuntime(
        context: android.content.Context,
        onProgress: (String) -> Unit,
        onLogLine: (String) -> Unit
    ) {
        val marker = EmbeddedTermuxPaths.marker(context)
        marker.delete()
        val archive = File(context.cacheDir, "termux-bootstrap-arm64.zip")
        try {
            onProgress("Loading bundled official Termux ARM64 bootstrap")
            context.assets.open("bootstrap-aarch64.zip").use { input ->
                FileOutputStream(archive).use { output -> input.copyTo(output) }
            }
            onProgress("Verifying bundled Termux bootstrap")
            require(sha256(archive) == BOOTSTRAP_SHA256) {
                "Termux bootstrap checksum verification failed. Please retry the installation."
            }

            onProgress("Extracting private Termux environment")
            EmbeddedBootstrapInstaller.install(context, archive)
            installProotAssets(context)
            onProgress("Configuring official Termux package repository")
            val setup = EmbeddedProcessRunner.runShellCommand(
                context,
                // Termux apt stores downloads under app data, outside the prefix.
                "if [ -e \"\$PREFIX/etc/profile.d/01-termux-bootstrap-second-stage-fallback.sh\" ]; then " +
                    "echo 'Termux bootstrap second stage failed'; exit 1; fi; " +
                    "mkdir -p /data/data/com.termux/cache/apt/archives/partial " +
                    "/data/data/com.termux/cache/apt/lists/partial " +
                    "\$PREFIX/etc/apt \$PREFIX/var/cache/apt/archives/partial " +
                    "\$PREFIX/var/lib/apt/lists/partial; " +
                    "printf '%s\\n' 'deb https://packages-cf.termux.dev/apt/termux-main stable main' > \$PREFIX/etc/apt/sources.list; " +
                    "apt update -o Acquire::Retries=5 -o Acquire::Queue-Mode=access || " +
                    "apt update -o Acquire::Retries=5 -o Acquire::Queue-Mode=access",
                "termux-apt-update", onLine = onLogLine
            )
            check(setup.exitCode == 0) {
                "Could not configure Termux or update its package index (exit ${setup.exitCode}). Log: ${setup.logFile.absolutePath}\n${setup.output.takeLast(4000)}"
            }

            onProgress("Installing Rust, Clang and HNMod build dependencies")
            val packages = EmbeddedProcessRunner.runShellCommand(
                context,
                "DEBIAN_FRONTEND=noninteractive apt install -y --fix-missing " +
                    "-o Acquire::Retries=5 -o Acquire::Queue-Mode=access " +
                    "-o Dpkg::Options::=--force-confnew " +
                    "rust clang git rsync binutils python cmake make pkg-config coreutils openssl minisign",
                "termux-install-build-packages", onLine = onLogLine
            )
            check(packages.exitCode == 0) {
                "Could not install HNMod compiler packages (exit ${packages.exitCode}). Log: ${packages.logFile.absolutePath}\n${packages.output.takeLast(5000)}"
            }

            onProgress("Checking Rust and native compiler")
            val smoke = EmbeddedProcessRunner.runShellCommand(
                context,
                "set -e; test \"\$(uname -m)\" = aarch64; cargo --version; rustc --version; clang --version; git --version; rsync --version; python --version; cmake --version; make --version; pkg-config --version; readelf --version; openssl version",
                "toolchain-smoke-test", onLine = onLogLine
            )
            check(smoke.exitCode == 0) {
                "Compiler verification failed (exit ${smoke.exitCode}). Log: ${smoke.logFile.absolutePath}\n${smoke.output.takeLast(5000)}"
            }
            marker.parentFile?.let { require(it.isDirectory || it.mkdirs()) }
            marker.writeText("schema=$STATE_SCHEMA\nbootstrap=$BOOTSTRAP_SHA256\nabi=arm64-v8a\n")
            onProgress("HNMod compiler installed successfully")
        } catch (error: Exception) {
            marker.delete()
            throw error
        } finally {
            archive.delete()
        }
    }

    private fun installProotAssets(context: android.content.Context) {
        val dir = EmbeddedTermuxPaths.prootDir(context)
        require(dir.isDirectory || dir.mkdirs()) { "Cannot create private PRoot directory." }
        copyAsset(context, "proot/proot", EmbeddedTermuxPaths.proot(context), executable = true)
        copyAsset(context, "proot/loader", EmbeddedTermuxPaths.loader(context), executable = true)
        // Gemini CLI installs PRoot's SONAME dependencies into the fake Termux prefix/lib.
        val libDir = File(EmbeddedTermuxPaths.prefix(context), "lib")
        require(libDir.isDirectory || libDir.mkdirs()) { "Cannot create Termux library directory." }
        val libraries = context.assets.list("proot/lib") ?: emptyArray()
        require(libraries.any { it == "libtalloc.so.2" }) {
            "The bundled PRoot runtime is missing libtalloc.so.2."
        }
        require(libraries.any { it == "libandroid-shmem.so" }) {
            "The bundled PRoot runtime is missing libandroid-shmem.so."
        }
        libraries.forEach { name ->
            copyAsset(context, "proot/lib/$name", File(libDir, name), executable = false)
        }
    }

    private fun copyAsset(context: android.content.Context, asset: String, destination: File, executable: Boolean) {
        require(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "Cannot create directory for $asset."
        }
        context.assets.open(asset).use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output) }
        }
        if (executable) destination.setExecutable(true, false)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun existsOrSymlink(file: File): Boolean =
        file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath())
}
