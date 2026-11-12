package com.movtery.zalithlauncher.setting

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.movtery.layer_controller.utils.snap.SnapMode

class EditorSetting<T>(private val key: String, initial: T) {
    private val stateHolder = mutableStateOf(initial)
    var state: T
        get() = stateHolder.value
        private set(value) { stateHolder.value = value }
    fun save(value: T) {
        state = value
        prefs?.edit()?.apply {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
                is Enum<*> -> putString(key, value.name)
            }
        }?.apply()
    }
    fun load(context: Context) { prefs = context.getSharedPreferences("zalith-editor", Context.MODE_PRIVATE) }
    companion object { @Volatile private var prefs: android.content.SharedPreferences? = null }
}

object AllSettings {
    val editorEnableWidgetSnap = EditorSetting("snap", true)
    val editorSnapInAllLayers = EditorSetting("snap_all", false)
    val editorWidgetSnapMode = EditorSetting("snap_mode", SnapMode.FullScreen)
    fun initialize(context: Context) {
        editorEnableWidgetSnap.load(context); editorSnapInAllLayers.load(context); editorWidgetSnapMode.load(context)
    }
}
