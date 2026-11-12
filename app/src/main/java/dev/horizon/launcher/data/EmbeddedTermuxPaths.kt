package dev.horizon.launcher.data

import android.content.Context
import java.io.File

/** Private Termux-compatible filesystem layout, following Gemini-cli-for-Android. */
object EmbeddedTermuxPaths {
    fun root(context: Context) = File(context.filesDir, "termux")
    fun rootfs(context: Context) = File(root(context), "rootfs")
    fun termuxData(context: Context) = File(rootfs(context), "data/data/com.termux")
    fun prefix(context: Context) = File(termuxData(context), "files/usr")
    fun home(context: Context) = File(termuxData(context), "files/home")
    fun tmp(context: Context) = File(root(context), "tmp")
    fun prootDir(context: Context) = File(root(context), "proot-bin")
    fun proot(context: Context) = File(prootDir(context), "proot")
    fun loader(context: Context) = File(prootDir(context), "loader")
    fun state(context: Context) = File(context.filesDir, "horizon-toolchain")
    fun marker(context: Context) = File(state(context), ".horizon-runtime-ready")

    const val GUEST_PREFIX = "/data/data/com.termux/files/usr"
    const val GUEST_HOME = "/data/data/com.termux/files/home"
}
