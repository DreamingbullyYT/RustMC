package dev.horizon.launcher.game

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import dev.horizon.launcher.game.controls.LookTuner
import kotlin.math.hypot

/**
 * Plain-Android (no UI toolkit) touch pipeline for the game: swipe-to-look, tap/long-press gestures,
 * direct hotbar taps and menu touches. It was extracted from the old Compose overlay so gameplay keeps
 * working while the launcher UI is rebuilt. Feed it every touch the game window receives.
 *
 * Settings are the same SharedPreferences keys the old quick settings wrote
 * ("zalith-touch-settings"), so existing player choices carry over.
 */
class TouchInputRouter(context: Context, private val view: View) {
    private enum class Kind { LOOK, GESTURE_ONLY, HOTBAR, MENU }

    private class Pointer(val kind: Kind, val startX: Float, val startY: Float) {
        var lastX = startX
        var lastY = startY
        val downAt = SystemClock.elapsedRealtime()
        var moved = false
        var held: String? = null
        var longPress: Runnable? = null
    }

    private val prefs = context.getSharedPreferences("zalith-touch-settings", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val tuner = LookTuner()
    private val pointers = mutableMapOf<Int, Pointer>()

    // Settings, re-read at every new touch so changes apply immediately.
    private var swipeLook = true
    private var lookSensitivity = 1f
    private var lookSensX = 1f
    private var lookSensY = 1f
    private var lookAccel = 0f
    private var lookSmoothing = 0f
    private var areaStart = 0f
    private var invertX = false
    private var invertY = false
    private var hotbarTouch = true
    private var gesturesEnabled = true
    private var tapAction = 0
    private var longPressAction = 0
    private var longPressDelay = 500L

    private var grabbing = true
    private var grabbingCheckedAt = 0L

    private fun readSettings() {
        swipeLook = prefs.getBoolean("swipe_look", true)
        lookSensitivity = prefs.getFloat("look_sensitivity", 1f)
        lookSensX = prefs.getFloat("look_sens_x", 1f)
        lookSensY = prefs.getFloat("look_sens_y", 1f)
        lookAccel = prefs.getFloat("look_accel", 0f)
        lookSmoothing = prefs.getFloat("look_smoothing", 0f)
        areaStart = when (prefs.getInt("look_area", 0)) { 1 -> 0.3f; 2 -> 0.5f; else -> 0f }
        invertX = prefs.getBoolean("invert_look_x", false)
        invertY = prefs.getBoolean("invert_look_y", false)
        hotbarTouch = prefs.getBoolean("hotbar_touch", true)
        gesturesEnabled = prefs.getBoolean("touch_gestures_enabled", true)
        tapAction = prefs.getInt("gesture_tap_action", 0)
        longPressAction = prefs.getInt("gesture_long_action", 0)
        longPressDelay = prefs.getInt("gesture_long_delay", 500).toLong()
    }

    private fun isGrabbing(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - grabbingCheckedAt > 120) {
            grabbing = runCatching { GameInput.nativeCursorGrabbing() }.getOrDefault(true)
            grabbingCheckedAt = now
        }
        return grabbing
    }

    /** Index 0..8 of the hotbar slot under the point, or -1. Geometry comes from the game's HUD. */
    private fun hotbarSlotAt(x: Float, y: Float): Int {
        val r = runCatching { GameInput.nativeHotbarRect() }.getOrNull() ?: return -1
        if (r.size < 4 || r[2] <= 0f) return -1
        val pad = r[3] * 0.25f
        if (y < r[1] - pad || y > r[1] + r[3] + pad) return -1
        val slot = ((x - r[0] - r[2] * 0.05f) / r[2]).toInt()
        return if (x >= r[0] && slot in 0..8) slot else -1
    }

    private fun click(button: String) {
        GameInput.nativeSendControl(button, true)
        GameInput.nativeSendControl(button, false)
    }

    private fun buttonFor(action: Int) = if (action == 1) "GLFW_MOUSE_BUTTON_LEFT" else "GLFW_MOUSE_BUTTON_RIGHT"

    /** Returns true: the router always consumes the touch so the native window does not see it twice. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                down(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) move(event.getPointerId(i), event.getX(i), event.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                up(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        if (pointers.isEmpty()) readSettings()
        val grab = isGrabbing()
        if (grab) {
            if (hotbarTouch) {
                val slot = hotbarSlotAt(x, y)
                if (slot >= 0) {
                    pointers[id] = Pointer(Kind.HOTBAR, x, y)
                    click("GLFW_KEY_${slot + 1}")
                    return
                }
            }
            val inLookArea = x >= view.width * areaStart
            val p = Pointer(if (inLookArea) Kind.LOOK else Kind.GESTURE_ONLY, x, y)
            pointers[id] = p
            if (inLookArea) {
                tuner.reset()
                if (swipeLook) GameInput.nativeLookActive(true)
            }
            if (gesturesEnabled && longPressAction in 1..2) {
                val fire = Runnable {
                    if (pointers[id] === p && !p.moved) {
                        val button = buttonFor(longPressAction)
                        GameInput.nativeSendControl(button, true)
                        p.held = button
                    }
                }
                p.longPress = fire
                handler.postDelayed(fire, longPressDelay)
            }
        } else {
            pointers[id] = Pointer(Kind.MENU, x, y)
            GameInput.nativeMenuTouch(id.toLong(), x, y, 0)
        }
    }

    private fun move(id: Int, x: Float, y: Float) {
        val p = pointers[id] ?: return
        when (p.kind) {
            Kind.MENU -> GameInput.nativeMenuTouch(id.toLong(), x, y, 1)
            Kind.HOTBAR -> Unit
            Kind.LOOK, Kind.GESTURE_ONLY -> {
                if (!p.moved && hypot(x - p.startX, y - p.startY) > 24f) {
                    p.moved = true
                    p.longPress?.let { handler.removeCallbacks(it) }
                    p.held?.let { GameInput.nativeSendControl(it, false) }
                    p.held = null
                }
                if (p.kind == Kind.LOOK && swipeLook) {
                    val dx = (x - p.lastX) * lookSensitivity * lookSensX * if (invertX) -1f else 1f
                    val dy = (y - p.lastY) * lookSensitivity * lookSensY * if (invertY) -1f else 1f
                    tuner.apply(dx, dy, lookAccel, lookSmoothing)
                    GameInput.nativeLook(tuner.outX, tuner.outY)
                }
                p.lastX = x
                p.lastY = y
            }
        }
    }

    private fun up(id: Int, x: Float, y: Float) {
        val p = pointers.remove(id) ?: return
        when (p.kind) {
            Kind.MENU -> GameInput.nativeMenuTouch(id.toLong(), x, y, 2)
            Kind.HOTBAR -> Unit
            Kind.LOOK, Kind.GESTURE_ONLY -> {
                p.longPress?.let { handler.removeCallbacks(it) }
                if (p.kind == Kind.LOOK && swipeLook) {
                    GameInput.nativeLookActive(pointers.values.any { it.kind == Kind.LOOK })
                }
                val held = p.held
                if (held != null) {
                    GameInput.nativeSendControl(held, false)
                } else if (!p.moved && gesturesEnabled) {
                    val long = SystemClock.elapsedRealtime() - p.downAt >= longPressDelay
                    val action = if (long) longPressAction else tapAction
                    if (action in 1..2) click(buttonFor(action))
                }
            }
        }
    }

    /** Releases everything the router started; call when the window loses focus or pauses. */
    fun cancelAll() {
        for ((id, p) in pointers) {
            p.longPress?.let { handler.removeCallbacks(it) }
            p.held?.let { GameInput.nativeSendControl(it, false) }
            if (p.kind == Kind.MENU) GameInput.nativeMenuTouch(id.toLong(), p.lastX, p.lastY, 2)
        }
        pointers.clear()
        if (swipeLook) GameInput.nativeLookActive(false)
        tuner.reset()
    }
}
