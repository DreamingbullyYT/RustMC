package dev.horizon.launcher.game.controls

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View

/** Light click feedback for on-screen buttons. Works without the VIBRATE permission. */
class Haptics(private val view: View) {
    /** 0 = off, 1 = light, 2 = medium, 3 = strong. */
    var level: Int = 0
    private var last = 0L

    @Suppress("DEPRECATION")
    fun tick() {
        if (level <= 0) return
        val now = SystemClock.uptimeMillis()
        if (now - last < 35) return          // multi-key buttons and joystick diagonals fire several events
        last = now
        val constant = when (level) {
            1 -> HapticFeedbackConstants.KEYBOARD_TAP
            2 -> HapticFeedbackConstants.VIRTUAL_KEY
            else -> HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    companion object {
        val labels = listOf("Off", "Light", "Medium", "Strong")
    }
}
