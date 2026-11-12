package dev.horizon.controls

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class TouchControlsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("touch_controls", Context.MODE_PRIVATE)

    fun profiles(): List<TouchControlProfile> {
        val raw = preferences.getString("profiles", null) ?: return listOf(defaultProfile())
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) add(array.getJSONObject(index).toProfile())
            }.ifEmpty { listOf(defaultProfile()) }
        }.getOrElse { listOf(defaultProfile()) }
    }

    fun activeProfileId(): String = preferences.getString("active_profile", "default") ?: "default"

    fun activeProfile(): TouchControlProfile =
        profiles().firstOrNull { it.id == activeProfileId() } ?: profiles().first()

    fun save(profile: TouchControlProfile) {
        val all = profiles().filterNot { it.id == profile.id } + profile
        preferences.edit()
            .putString("profiles", JSONArray().apply { all.forEach { put(it.toJson()) } }.toString())
            .putString("active_profile", profile.id)
            .apply()
    }

    fun select(profileId: String) {
        if (profiles().any { it.id == profileId }) {
            preferences.edit().putString("active_profile", profileId).apply()
        }
    }

    fun createProfile(name: String): TouchControlProfile {
        val cleanName = name.trim().take(32).ifBlank { "Custom" }
        val profile = activeProfile().copy(
            id = java.util.UUID.randomUUID().toString(),
            name = cleanName
        )
        save(profile)
        return profile
    }

    fun delete(profileId: String) {
        if (profileId == "default") return
        val remaining = profiles().filterNot { it.id == profileId }.ifEmpty { listOf(defaultProfile()) }
        preferences.edit()
            .putString("profiles", JSONArray().apply { remaining.forEach { put(it.toJson()) } }.toString())
            .putString("active_profile", remaining.first().id)
            .apply()
    }

    private fun defaultProfile() = TouchControlProfile()

    private fun TouchControlProfile.toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("layout", layout)
        put("sensitivityX", sensitivityX.toDouble()); put("sensitivityY", sensitivityY.toDouble())
        put("invertX", invertX); put("invertY", invertY)
        put("gyroscope", gyroscope); put("autoSprint", autoSprint)
        put("opacity", opacity.toDouble()); put("buttonScale", buttonScale.toDouble())
    }

    private fun JSONObject.toProfile() = TouchControlProfile(
        id = optString("id", java.util.UUID.randomUUID().toString()),
        name = optString("name", "Custom"),
        layout = optString("layout", "JOYSTICK_FIXED").takeIf { it in TouchControlProfile.layouts } ?: "JOYSTICK_FIXED",
        sensitivityX = optDouble("sensitivityX", 1.0).toFloat().coerceIn(0.1f, 5f),
        sensitivityY = optDouble("sensitivityY", 1.0).toFloat().coerceIn(0.1f, 5f),
        invertX = optBoolean("invertX", false),
        invertY = optBoolean("invertY", false),
        gyroscope = optBoolean("gyroscope", false),
        autoSprint = optBoolean("autoSprint", false),
        opacity = optDouble("opacity", 0.82).toFloat().coerceIn(0.1f, 1f),
        buttonScale = optDouble("buttonScale", 1.0).toFloat().coerceIn(0.5f, 2f)
    )
}
