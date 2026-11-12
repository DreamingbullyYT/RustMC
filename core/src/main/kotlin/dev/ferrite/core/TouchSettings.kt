package dev.ferrite.core

import java.io.File

/** The 14 on-screen controls, in the engine's order (the order is the file format). Defaults are the engine's own. */
enum class TouchControl(val label: String, val x: Double, val y: Double, val size: Double) {
    MOVE("Movement", 0.13, 0.76, 0.32), SPRINT("Sprint", 0.72, 0.78, 0.105), JUMP("Jump / fly up", 0.92, 0.78, 0.16),
    SNEAK("Sneak / fly down", 0.81, 0.86, 0.115), ATTACK("Attack / mine", 0.80, 0.64, 0.16), USE("Use / place", 0.92, 0.52, 0.16),
    CHAT("Chat", 0.46, 0.055, 0.075), PAUSE("Pause", 0.50, 0.055, 0.075), SETTINGS("Touch settings", 0.54, 0.055, 0.075),
    INVENTORY("Inventory", 0.73, 0.95, 0.075), DEBUG("Debug overlay (F3)", 0.42, 0.055, 0.075), PERSPECTIVE("Camera perspective (F5)", 0.58, 0.055, 0.075),
    OFFHAND("Swap hands", 0.80, 0.96, 0.075), SHIELD("Raise shield", 0.92, 0.36, 0.12)
}

enum class TouchMode(val json: String) { CROSSHAIR("crosshair"), TAP("tap"), DPAD("dpad") }

/** One of the engine's four control schemes (the D-pad has a split variant that aims with a crosshair). */
enum class Scheme(val label: String, val mode: TouchMode, val split: Boolean) {
    JOYSTICK_CROSSHAIR("Joystick & aim crosshair", TouchMode.CROSSHAIR, false),
    JOYSTICK_TAP("Joystick & tap to interact", TouchMode.TAP, false),
    DPAD_TAP("D-Pad & tap to interact", TouchMode.DPAD, false),
    DPAD_CROSSHAIR("D-Pad & aim crosshair", TouchMode.DPAD, true)
}

data class Placement(val x: Double, val y: Double, val size: Double, val opacity: Double = 0.65) {
    /** Same limits the engine applies on load, so what the editor saves is what the game keeps. */
    fun sane() = Placement(x.coerceIn(0.025, 0.975), y.coerceIn(0.025, 0.975), size.coerceIn(0.045, 0.45), opacity.coerceIn(0.0, 1.0))
}

const val FOLLOW_SCHEME = 0
const val SHOWN = 1
const val HIDDEN = 2

/**
 * Reads and writes the engine's `horizon-touch.json` (in the instance's game folder). Keys this class does not know
 * are kept untouched, and a missing key reads as the engine's default, so an older or newer file is never broken.
 * Ranges are the engine's (`Settings::sanitize`). Changes reach the game on its next start.
 */
class TouchSettings(private val file: File) {
    private val data: MutableMap<String, Any?> = runCatching { Json.parse(file.readText()).obj().toMutableMap() }.getOrDefault(mutableMapOf())

    private fun d(k: String, def: Double) = data[k].num(def)
    private fun b(k: String, def: Boolean) = data[k].bool(def)
    private fun put(k: String, v: Any?) { data[k] = v }

    var sensitivity: Double get() = d("sensitivity", 1.0).coerceIn(0.1, 3.0); set(v) = put("sensitivity", v.coerceIn(0.1, 3.0))
    var sensitivityHorizontal: Double get() = d("sensitivity_horizontal", 1.0).coerceIn(0.1, 3.0); set(v) = put("sensitivity_horizontal", v.coerceIn(0.1, 3.0))
    var sensitivityVertical: Double get() = d("sensitivity_vertical", 1.0).coerceIn(0.1, 3.0); set(v) = put("sensitivity_vertical", v.coerceIn(0.1, 3.0))
    var buttonScale: Double get() = d("button_scale", 1.0).coerceIn(0.5, 2.0); set(v) = put("button_scale", v.coerceIn(0.5, 2.0))
    var virtualMouse: Boolean get() = b("virtual_mouse_enabled", true); set(v) = put("virtual_mouse_enabled", v)
    var virtualMouseSpeed: Double get() = d("virtual_mouse_sensitivity", 1.0).coerceIn(0.25, 3.0); set(v) = put("virtual_mouse_sensitivity", v.coerceIn(0.25, 3.0))
    var pointerScale: Double get() = d("virtual_mouse_pointer_scale", 1.0).coerceIn(0.5, 2.0); set(v) = put("virtual_mouse_pointer_scale", v.coerceIn(0.5, 2.0))
    var gyroscope: Boolean get() = b("gyroscope", false); set(v) = put("gyroscope", v)
    var gyroSensitivity: Double get() = d("gyro_sensitivity", 1.0).coerceIn(0.1, 3.0); set(v) = put("gyro_sensitivity", v.coerceIn(0.1, 3.0))
    var vibrate: Boolean get() = b("vibrate", true); set(v) = put("vibrate", v)
    var invertY: Boolean get() = b("invert_y", false); set(v) = put("invert_y", v)
    var resolutionPercent: Int get() = data["resolution_scale_percent"].int(100).coerceIn(25, 100); set(v) = put("resolution_scale_percent", v.coerceIn(25, 100))
    var gameAnimations: Boolean get() = !b("disable_game_animations", false); set(v) = put("disable_game_animations", !v)
    var leftHanded: Boolean get() = b("left_handed", false); set(v) = put("left_handed", v)
    var sprintToggle: Boolean get() = b("sprint_toggle", false); set(v) = put("sprint_toggle", v)
    var dynamicJoystick: Boolean get() = b("dynamic_joystick", false); set(v) = put("dynamic_joystick", v)
    var zeroLatencyTouch: Boolean get() = b("zero_latency_touch", false); set(v) = put("zero_latency_touch", v)
    /** `Mcpe` or `GoyControl`: which family of presets the game offers. */
    var controlStyle: String get() = data["control_style"].str("Mcpe").takeIf { it in setOf("Mcpe", "GoyControl") } ?: "Mcpe"; set(v) { require(v in setOf("Mcpe", "GoyControl")) { "unknown control style" }; put("control_style", v) }

    var scheme: Scheme
        get() { val m = TouchMode.entries.firstOrNull { it.json == data["mode"].str("crosshair") } ?: TouchMode.CROSSHAIR
            return Scheme.entries.first { it.mode == m && it.split == (m == TouchMode.DPAD && b("split_controls", false)) } }
        set(v) { put("mode", v.mode.json); put("split_controls", v.split) }

    fun visibility(c: TouchControl): Int = (data["button_visibility"].arr().getOrNull(c.ordinal).int(FOLLOW_SCHEME)).let { if (it in FOLLOW_SCHEME..HIDDEN) it else FOLLOW_SCHEME }
    fun setVisibility(c: TouchControl, v: Int) {
        require(v in FOLLOW_SCHEME..HIDDEN) { "bad visibility" }
        put("button_visibility", TouchControl.entries.map { if (it == c) v else visibility(it) })
    }

    private fun defaultPlacement(m: TouchMode, c: TouchControl): Placement = when {
        m == TouchMode.DPAD && c == TouchControl.SNEAK -> Placement(0.13, 0.76, 0.10)
        m == TouchMode.DPAD && c == TouchControl.JUMP -> Placement(0.90, 0.78, 0.14)
        else -> Placement(c.x, c.y, c.size)
    }.sane()

    fun placement(m: TouchMode, c: TouchControl): Placement {
        val p = data["layouts"].arr().getOrNull(m.ordinal).arr().getOrNull(c.ordinal).obj()
        if (p.isEmpty()) return defaultPlacement(m, c)
        val center = p["center"].arr()
        return Placement(center.getOrNull(0).num(0.5), center.getOrNull(1).num(0.5), p["size"].num(0.15), p["opacity"].num(0.65)).sane()
    }

    /** The engine reads `layouts` as exactly 3 modes x 14 controls, so a change always writes the whole table. */
    fun setPlacement(m: TouchMode, c: TouchControl, p: Placement) {
        put("layouts", TouchMode.entries.map { mm -> TouchControl.entries.map { cc ->
            val q = if (mm == m && cc == c) p.sane() else placement(mm, cc)
            linkedMapOf("center" to listOf(q.x, q.y), "size" to q.size, "opacity" to q.opacity)
        } })
    }

    fun resetPlacements(m: TouchMode) { TouchControl.entries.forEach { setPlacement(m, it, defaultPlacement(m, it)) } }

    fun save() { file.parentFile?.mkdirs(); val tmp = File(file.path + ".tmp"); tmp.writeText(Json.write(data, pretty = true)); check(tmp.renameTo(file)) { "could not save ${file.name}" } }

    companion object { const val FILE = "horizon-touch.json"; val RESOLUTION_CHIPS = listOf(50, 65, 75, 85, 100) }
}
