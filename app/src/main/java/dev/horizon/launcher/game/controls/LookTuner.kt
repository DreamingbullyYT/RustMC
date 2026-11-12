package dev.horizon.launcher.game.controls

import kotlin.math.hypot

/** Optional acceleration and smoothing for swipe-to-look. Both at 0 = raw 1:1 touch deltas. */
class LookTuner {
    private var smoothX = 0f
    private var smoothY = 0f
    var outX = 0f; private set
    var outY = 0f; private set

    fun reset() { smoothX = 0f; smoothY = 0f }

    /** [accel] 0..1 boosts fast flicks; [smoothing] 0..0.9 low-pass filters the motion. */
    fun apply(dx: Float, dy: Float, accel: Float, smoothing: Float) {
        val boost = 1f + accel * (hypot(dx, dy) / 12f).coerceAtMost(3f)
        var x = dx * boost
        var y = dy * boost
        if (smoothing > 0f) {
            val a = 1f - smoothing.coerceIn(0f, 0.9f)
            smoothX += (x - smoothX) * a
            smoothY += (y - smoothY) * a
            x = smoothX; y = smoothY
        }
        outX = x; outY = y
    }
}
