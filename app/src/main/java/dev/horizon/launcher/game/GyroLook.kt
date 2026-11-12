package dev.horizon.launcher.game

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface

/** Optional gyroscope camera (pref "gyro_enabled"); extracted from the old quick settings. */
class GyroLook(private val activity: Activity) : SensorEventListener {
    private val prefs = activity.getSharedPreferences("zalith-touch-settings", Context.MODE_PRIVATE)
    private val manager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var lastTimestamp = 0L
    private var running = false

    fun start() {
        if (running || !prefs.getBoolean("gyro_enabled", false)) return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
        lastTimestamp = 0L
        running = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        if (running) GameInput.nativeLookActive(true)
    }

    fun stop() {
        if (!running) return
        manager.unregisterListener(this)
        running = false
        GameInput.nativeLookActive(false)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (lastTimestamp == 0L) { lastTimestamp = event.timestamp; return }
        val dt = ((event.timestamp - lastTimestamp) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastTimestamp = event.timestamp
        val sensitivity = prefs.getFloat("gyro_sensitivity", 1f)
        val invertX = prefs.getBoolean("invert_look_x", false)
        val invertY = prefs.getBoolean("invert_look_y", false)
        @Suppress("DEPRECATION")
        val rotation = activity.windowManager.defaultDisplay.rotation
        val (x, y) = when (rotation) {
            Surface.ROTATION_90 -> event.values[1] to -event.values[0]
            Surface.ROTATION_180 -> -event.values[0] to -event.values[1]
            Surface.ROTATION_270 -> -event.values[1] to event.values[0]
            else -> event.values[0] to event.values[1]
        }
        GameInput.nativeLook(
            y * dt * sensitivity * 900f * if (invertX) -1f else 1f,
            x * dt * sensitivity * 900f * if (invertY) -1f else 1f,
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
