package dev.horizon.launcher.game

import android.app.NativeActivity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import java.io.File

/** Native game surface; touch input comes from the engine. */
class HorizonGameActivity : NativeActivity(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry by lazy { LifecycleRegistry(this) }
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private val savedStateController by lazy { SavedStateRegistryController.create(this) }
    private val activePadAxes = mutableSetOf<String>()
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry


    private val launcherDir: File get() = File(filesDir, "launcher")
    private var startedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        // So the launcher can tell why the game stopped: "start" with no "end" means the process died.
        startedAt = android.os.SystemClock.elapsedRealtime()
        runCatching {
            launcherDir.mkdirs()
            File(launcherDir, "game-session.txt").writeText("start")
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching {
                    File(launcherDir, "game-log.txt").appendText(
                        "\nJAVA CRASH in ${thread.name}:\n${android.util.Log.getStackTraceString(error)}\n"
                    )
                }
                previous?.uncaughtException(thread, error)
            }
        }
        // Choose the wgpu backend (Vulkan or GLES) before NativeActivity starts loading the engine.
        // Launcher settings write both render-api.txt and the "horizon-renderer" preference; either is accepted.
        val renderChoices = setOf("auto", "vulkan", "opengl")
        val renderApi = runCatching { File(File(filesDir, "launcher"), "render-api.txt").readText().trim() }
            .getOrNull()?.takeIf { it in renderChoices }
            ?: getSharedPreferences("horizon-renderer", MODE_PRIVATE).getString("api", "auto")?.takeIf { it in renderChoices }
            ?: "auto"
        runCatching { android.system.Os.setenv("HORIZON_GRAPHICS_API", renderApi, true) }
        // NativeActivity loads the game inside super.onCreate: tell the boot stub which one.
        dev.horizon.launcher.data.Updater.gamePath(filesDir)?.let {
            runCatching { android.system.Os.setenv("HORIZON_GAME_LIB", it, true) }
        }
        dev.horizon.launcher.data.Updater.armGuard(filesDir, "game")
        super.onCreate(savedInstanceState)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7414)
            startForegroundService(Intent(this, GameKeepAliveService::class.java))
        }
        savedStateController.performAttach()
        savedStateController.performRestore(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    returnToLauncherCallback
                )
            }.onFailure {
                android.util.Log.w("HorizonSession", "Could not register predictive-back handler", it)
            }
        }
        // Supply owners on the decor tree before Compose creates its WindowRecomposer.
        window.decorView.setViewTreeLifecycleOwner(this)
        window.decorView.setViewTreeSavedStateRegistryOwner(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Sustained-performance mode keeps clocks steady instead of boosting then throttling (MediaTek/Mali SoCs overheat fast).
        runCatching {
            val power = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (android.os.Build.VERSION.SDK_INT >= 24 && power.isSustainedPerformanceModeSupported) window.setSustainedPerformanceMode(true)
        }
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    private val returnToLauncherCallback = android.window.OnBackInvokedCallback {
        openLauncherWithoutClosingGame()
    }

    private fun openLauncherWithoutClosingGame() {
        android.util.Log.i("HorizonSession", "Back requested: showing launcher above the live game Activity")
        startActivity(
            Intent(this, dev.horizon.launcher.MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    }

    override fun onStart() { super.onStart(); lifecycleRegistry.currentState = Lifecycle.State.STARTED }
    override fun onResume() {
        super.onResume()
        android.util.Log.i("HorizonSession", "Game Activity resumed; reusing existing native session")
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }
    override fun onPause() { lifecycleRegistry.currentState = Lifecycle.State.STARTED; super.onPause() }
    override fun onStop() { lifecycleRegistry.currentState = Lifecycle.State.CREATED; super.onStop() }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Fallback for Android versions that do not dispatch the predictive-back callback.
        openLauncherWithoutClosingGame()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        android.util.Log.i("HorizonSession", "Existing game Activity received a return intent; no new game launch")
    }

    override fun onDestroy() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(returnToLauncherCallback) }
        }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        val lived = android.os.SystemClock.elapsedRealtime() - startedAt
        runCatching {
            File(launcherDir, "game-session.txt").writeText("end $lived")
            // The player closed a game that had been running: the Vulkan start worked, do not blame it.
            if (lived > 8_000) File(launcherDir, "vulkan-pending").delete()
        }
        // Once NativeActivity is destroyed, its winit loop and JNI globals belong to a dead
        // session. Do not leave :game alive only because its foreground service is still running:
        // a second NativeActivity in that same process would reuse stale native app state.
        dev.horizon.launcher.data.Updater.clearGameSession(filesDir)
        val terminateGameProcess = !isChangingConfigurations
        runCatching { stopService(Intent(this, GameKeepAliveService::class.java)) }
        super.onDestroy()
        if (terminateGameProcess) {
            // A later Play must start a clean :game process with fresh NativeActivity/JNI state.
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /** Explicit player action: close the native game session and its keep-alive notification. */
    fun closeGame() {
        dev.horizon.launcher.data.Updater.clearGameSession(filesDir, clearStartupGuard = true)
        stopService(Intent(this, GameKeepAliveService::class.java))
        finish()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        savedStateController.performSave(outState)
        super.onSaveInstanceState(outState)
    }

    // ---------------------------------------------------------------------------------------
    // Hardware gamepad
    // ---------------------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isPad = event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            event.source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
        if (!isPad) return super.dispatchKeyEvent(event)
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> "button_a"
            KeyEvent.KEYCODE_BUTTON_B -> "button_b"
            KeyEvent.KEYCODE_BUTTON_X -> "button_x"
            KeyEvent.KEYCODE_BUTTON_Y -> "button_y"
            KeyEvent.KEYCODE_BUTTON_L1 -> "left_bumper"
            KeyEvent.KEYCODE_BUTTON_R1 -> "right_bumper"
            KeyEvent.KEYCODE_BUTTON_L2 -> "left_trigger"
            KeyEvent.KEYCODE_BUTTON_R2 -> "right_trigger"
            KeyEvent.KEYCODE_BUTTON_THUMBL -> "left_stick"
            KeyEvent.KEYCODE_BUTTON_THUMBR -> "right_stick"
            KeyEvent.KEYCODE_BUTTON_START -> "button_start"
            KeyEvent.KEYCODE_BUTTON_SELECT -> "button_select"
            KeyEvent.KEYCODE_DPAD_UP -> "dpad_up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "dpad_down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "dpad_left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "dpad_right"
            else -> return super.dispatchKeyEvent(event)
        }
        val binding = getSharedPreferences("zalith-touch-settings", MODE_PRIVATE).getString("pad_$key", PAD_DEFAULTS[key] ?: "")
        if (!binding.isNullOrBlank()) GameInput.nativeSendControl(binding, event.action != KeyEvent.ACTION_UP)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val joystick = event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (!joystick || event.action != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(event)
        val prefs = getSharedPreferences("zalith-touch-settings", MODE_PRIVATE)
        val deadzone = prefs.getFloat("pad_deadzone", 0.22f)
        val triggerThreshold = prefs.getFloat("pad_trigger_threshold", 0.22f)
        val sensitivity = prefs.getFloat("pad_camera_sensitivity", 1.4f)
        val invertY = prefs.getBoolean("pad_invert_y", false)
        val swapSticks = prefs.getBoolean("pad_swap_sticks", false)
        fun axis(axis: Int): Float = event.getAxisValue(axis)
        val leftX = axis(MotionEvent.AXIS_X); val leftY = axis(MotionEvent.AXIS_Y)
        val rightX = axis(MotionEvent.AXIS_Z).takeIf { it != 0f } ?: axis(MotionEvent.AXIS_RX)
        val rightY = axis(MotionEvent.AXIS_RZ).takeIf { it != 0f } ?: axis(MotionEvent.AXIS_RY)
        // "Swap sticks" gives left-handed / "legacy" layouts: right stick moves, left stick looks.
        val lx = if (swapSticks) rightX else leftX
        val ly = if (swapSticks) rightY else leftY
        val rx = if (swapSticks) leftX else rightX
        val ry = (if (swapSticks) leftY else rightY) * if (invertY) -1f else 1f
        setPadAxis("GLFW_KEY_A", lx < -deadzone); setPadAxis("GLFW_KEY_D", lx > deadzone)
        setPadAxis("GLFW_KEY_W", ly < -deadzone); setPadAxis("GLFW_KEY_S", ly > deadzone)
        val lt = axis(MotionEvent.AXIS_LTRIGGER).takeIf { it > 0f } ?: axis(MotionEvent.AXIS_BRAKE)
        val rt = axis(MotionEvent.AXIS_RTRIGGER).takeIf { it > 0f } ?: axis(MotionEvent.AXIS_GAS)
        setPadAxis(prefs.getString("pad_left_trigger", "GLFW_MOUSE_BUTTON_LEFT") ?: "", lt > triggerThreshold)
        setPadAxis(prefs.getString("pad_right_trigger", "GLFW_MOUSE_BUTTON_RIGHT") ?: "", rt > triggerThreshold)
        if (kotlin.math.abs(rx) > deadzone || kotlin.math.abs(ry) > deadzone) {
            GameInput.nativeLook(rx * sensitivity * 24f, ry * sensitivity * 24f)
        }
        return true
    }

    private fun setPadAxis(key: String, pressed: Boolean) {
        if (key.isBlank()) return
        val wasPressed = key in activePadAxes
        if (pressed != wasPressed) {
            if (pressed) activePadAxes += key else activePadAxes -= key
            GameInput.nativeSendControl(key, pressed)
        }
    }

    companion object {

        /** Default hardware-gamepad bindings; every slot can be rebound in the quick panel. */
        val PAD_DEFAULTS = mapOf(
            "button_a" to "GLFW_KEY_SPACE", "button_b" to "GLFW_KEY_LEFT_SHIFT",
            "button_x" to "GLFW_KEY_E", "button_y" to "GLFW_KEY_Q",
            "left_bumper" to "GLFW_KEY_LEFT_CONTROL", "right_bumper" to "GLFW_MOUSE_BUTTON_RIGHT",
            "left_trigger" to "GLFW_MOUSE_BUTTON_LEFT", "right_trigger" to "GLFW_MOUSE_BUTTON_RIGHT",
            "left_stick" to "GLFW_KEY_LEFT_SHIFT", "right_stick" to "GLFW_MOUSE_BUTTON_MIDDLE",
            "button_start" to "GLFW_KEY_ESCAPE", "button_select" to "GLFW_KEY_TAB",
            "dpad_up" to "GLFW_KEY_W", "dpad_down" to "GLFW_KEY_S",
            "dpad_left" to "GLFW_KEY_A", "dpad_right" to "GLFW_KEY_D"
        )
    }
}
