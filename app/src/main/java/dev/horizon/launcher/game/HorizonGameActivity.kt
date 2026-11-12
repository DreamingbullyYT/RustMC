package dev.horizon.launcher.game

import android.app.Activity
import android.app.Dialog
import android.app.NativeActivity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.compose.ui.graphics.Color
import com.movtery.layer_controller.data.ButtonShape
import com.movtery.layer_controller.data.DefaultButtonStyleConfig
import com.movtery.layer_controller.data.DefaultJoystickStyleConfig
import com.movtery.layer_controller.data.TextAlignment
import com.movtery.layer_controller.data.createNewButtonStyle
import com.movtery.layer_controller.data.createNewJoystickStyle
import com.movtery.layer_controller.data.ButtonPosition
import com.movtery.layer_controller.data.DefaultSize
import com.movtery.layer_controller.data.JoystickData
import com.movtery.layer_controller.data.NormalData
import com.movtery.layer_controller.data.VisibilityType
import com.movtery.layer_controller.data.lang.createTranslatable
import com.movtery.layer_controller.event.ClickEvent
import com.movtery.layer_controller.layout.ControlLayout
import com.movtery.layer_controller.layout.createNewLayer
import com.movtery.layer_controller.layout.loadLayoutFromFile
import com.movtery.layer_controller.observable.ObservableControlLayer
import com.movtery.layer_controller.observable.ObservableControlLayout
import dev.horizon.launcher.game.controls.ControlJson
import dev.horizon.launcher.game.controls.LayoutProfiles
import java.io.File
import java.util.UUID

/** Native game surface with Zalith's open-source control canvas above it. */
class HorizonGameActivity : NativeActivity(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry by lazy { LifecycleRegistry(this) }
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    private val savedStateController by lazy { SavedStateRegistryController.create(this) }
    private val activePadAxes = mutableSetOf<String>()
    private var layoutImportCallback: ((ControlLayout) -> Unit)? = null
    private var pendingExport: ControlLayout? = null
    private var zalithOverlay: ComposeView? = null
    private var overlayDialog: Dialog? = null
    private val overlayLayoutState = mutableStateOf<ObservableControlLayout?>(null)
    private var overlayLayoutSnapshot: String? = null
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val currentLayoutFile: File get() = File(File(filesDir, "launcher"), "touch-layout.json")

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
        // Disable the legacy Rust HUD; Zalith owns the touch layer in this Activity.
        runCatching { android.system.Os.setenv("HORIZON_ANDROID_ZALITH_CONTROLS", "1", true) }
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

        val initialLayout = loadLayout()
        overlayLayoutState.value = initialLayout
        overlayLayoutSnapshot = runCatching { currentLayoutFile.takeIf { it.isFile }?.readText() }.getOrNull()
        val profiles = LayoutProfiles(this)
        val overlay = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@HorizonGameActivity)
            Class.forName("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
                .getMethod("set", View::class.java, SavedStateRegistryOwner::class.java)
                .invoke(null, this, this@HorizonGameActivity)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                HorizonGameControls(
                    layout = overlayLayoutState.value ?: initialLayout,
                    profiles = profiles,
                    defaultLayout = { defaultLayout() },
                    setKeepAwake = { enabled ->
                        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    },
                    setMaxRefresh = { enabled ->
                        val attributes = window.attributes
                        attributes.preferredRefreshRate = if (enabled) windowManager.defaultDisplay.refreshRate else 0f
                        window.attributes = attributes
                    },
                    save = { packed -> saveCurrentLayout(packed) }
                )
            }
        }
        // NativeActivity's game surface can cover views attached with addContentView().
        // Host the controls in a transparent application dialog instead, and install the
        // Activity owners on the dialog DecorView BEFORE Compose attaches its WindowRecomposer.
        zalithOverlay = overlay
        runCatching {
            val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
            val dialogWindow = requireNotNull(dialog.window) { "Control overlay window is unavailable" }
            dialogWindow.decorView.setViewTreeLifecycleOwner(this)
            dialogWindow.decorView.setViewTreeSavedStateRegistryOwner(this)
            dialog.setContentView(overlay)
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            dialogWindow.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialogWindow.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            )
            dialogWindow.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            dialogWindow.decorView.systemUiVisibility = window.decorView.systemUiVisibility
            dialogWindow.attributes = dialogWindow.attributes.apply {
                gravity = Gravity.TOP or Gravity.START
                dimAmount = 0f
            }
            dialog.show()
            dialog.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            overlayDialog = dialog
            android.util.Log.i("HorizonZalith", "Touch overlay attached above NativeActivity surface")
        }.onFailure {
            android.util.Log.e("HorizonZalith", "Could not attach Zalith overlay window", it)
        }
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
        // The standalone Zalith editor writes the canonical Horizon layout file. Reload only
        // when its contents changed so normal pause/resume does not recreate the live HUD.
        val snapshot = runCatching { currentLayoutFile.takeIf { it.isFile }?.readText() }.getOrNull()
        if (snapshot != null && snapshot != overlayLayoutSnapshot) {
            runCatching { loadLayout() }
                .onSuccess { overlayLayoutState.value = it; overlayLayoutSnapshot = snapshot }
                .onFailure { android.util.Log.e("HorizonZalith", "Could not reload edited control layout", it) }
        }
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
        runCatching { overlayDialog?.dismiss() }
        overlayDialog = null
        zalithOverlay = null
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
    // Layout files: current layout, import, export, share, paste
    // ---------------------------------------------------------------------------------------

    private fun saveCurrentLayout(packed: ControlLayout) {
        runCatching {
            val file = currentLayoutFile
            file.parentFile?.mkdirs()
            // Write to a temp file first so a crash mid-save cannot corrupt the layout.
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(ControlJson.encode(packed))
            if (!temp.renameTo(file)) { temp.copyTo(file, overwrite = true); temp.delete() }
            overlayLayoutSnapshot = file.readText()
        }.onFailure { toast("Could not save control layout: ${it.message}") }
    }

    @Suppress("DEPRECATION")
    fun chooseTouchLayout(onImported: (ControlLayout) -> Unit) {
        layoutImportCallback = onImported
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, REQUEST_IMPORT_LAYOUT)
    }

    @Suppress("DEPRECATION")
    fun exportTouchLayout(layout: ControlLayout) {
        pendingExport = layout
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "horizon-layout.json")
        }
        startActivityForResult(intent, REQUEST_EXPORT_LAYOUT)
    }

    /** Shares the layout JSON as text (no FileProvider needed); the receiver can paste it back in. */
    fun shareTouchLayout(layout: ControlLayout) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Horizon control layout")
            putExtra(Intent.EXTRA_TEXT, ControlJson.encode(layout))
        }
        startActivity(Intent.createChooser(send, "Share control layout"))
    }

    fun copyTouchLayout(layout: ControlLayout) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Horizon control layout", ControlJson.encode(layout)))
        toast("Layout copied to clipboard")
    }

    fun pasteTouchLayout(onImported: (ControlLayout) -> Unit) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) { toast("Clipboard is empty"); return }
        runCatching { ControlJson.decode(text) }
            .onSuccess(onImported)
            .onFailure { toast("Clipboard does not contain a control layout: ${it.message}") }
    }

    fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    @Suppress("DEPRECATION")
    @Deprecated("NativeActivity uses the platform activity-result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        when (requestCode) {
            REQUEST_IMPORT_LAYOUT -> {
                if (resultCode != Activity.RESULT_OK || uri == null) { layoutImportCallback = null; return }
                runCatching {
                    val target = File(File(filesDir, "launcher"), "imported-touch-layout.json")
                    target.parentFile?.mkdirs()
                    contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Could not open selected layout" }.use { source -> target.outputStream().use(source::copyTo) }
                    }
                    loadLayoutFromFile(target)
                }.onSuccess { imported -> layoutImportCallback?.invoke(imported) }
                    .onFailure { toast("Could not import control layout: ${it.message}") }
                layoutImportCallback = null
            }
            REQUEST_EXPORT_LAYOUT -> {
                val layout = pendingExport
                pendingExport = null
                if (resultCode != Activity.RESULT_OK || uri == null || layout == null) return
                runCatching { LayoutProfiles(this).exportTo(uri, layout) }
                    .onSuccess { toast("Layout exported") }
                    .onFailure { toast("Could not export control layout: ${it.message}") }
            }
        }
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

    // ---------------------------------------------------------------------------------------
    // Default / saved layout
    // ---------------------------------------------------------------------------------------

    private fun loadLayout(): ObservableControlLayout {
        val file = currentLayoutFile
        val prefs = getSharedPreferences("zalith-touch-settings", MODE_PRIVATE)
        if (!prefs.getBoolean("default_layout_v4", false)) {
            prefs.edit().putBoolean("default_layout_v4", true).apply()
            // Keep whatever the player had as a saved layout ("Previous layout") before switching defaults.
            if (file.isFile) runCatching { LayoutProfiles(this).save("Previous layout", loadLayoutFromFile(file)) }
            val fresh = defaultLayout()
            saveCurrentLayout(fresh)
            LayoutProfiles(this).activeName = ""
            return ObservableControlLayout(fresh)
        }
        return runCatching { if (file.isFile) ObservableControlLayout(loadLayoutFromFile(file)) else ObservableControlLayout(defaultLayout()) }
            .getOrElse { ObservableControlLayout(defaultLayout()) }
    }

    /**
     * Default touch layout modelled on the Horizon reference screenshot: square glass joystick on the
     * left, sword / use / jump / sprint / sneak on the right, a row of small icon buttons at the top
     * centre, and a "…" button that reveals extra keys. A separate menu layer keeps Back / Inventory /
     * Shift / Drop on screen whenever a GUI (inventory, pause, chat) has the cursor.
     */
    internal fun defaultLayout(): ControlLayout {
        val glass = createNewButtonStyle("Glass").copy(commonStyle = true).let { base ->
            val cfg = DefaultButtonStyleConfig.copy(
                backgroundColor = Color(0x40FFFFFF), pressedBackgroundColor = Color(0x80FFFFFF),
                contentColor = Color.White, pressedContentColor = Color.White,
                borderWidth = 2, pressedBorderWidth = 2,
                borderColor = Color(0xBFFFFFFF), pressedBorderColor = Color.White,
                borderRadius = ButtonShape(10f), pressedBorderRadius = ButtonShape(10f), alpha = 1f, pressedAlpha = 1f)
            base.copy(lightStyle = cfg, darkStyle = cfg)
        }
        val squareStick = createNewJoystickStyle("Glass square").copy(commonStyle = true).let { base ->
            val cfg = DefaultJoystickStyleConfig.copy(
                backgroundShape = 10, joystickShape = 10,
                backgroundColor = Color(0x4DFFFFFF), borderWidthRatio = 4, borderColor = Color(0xBFFFFFFF),
                joystickColor = Color(0x99FFFFFF), joystickSize = 0.4f)
            base.copy(lightStyle = cfg, darkStyle = cfg)
        }

        // Positions in LayerController are a fraction of the free space, so convert from "centre of the
        // button at (cx, cy) of the screen" assuming a ~20:9 landscape screen.
        fun pos(cx: Float, cy: Float, size: Int): ButtonPosition {
            val h = size / 10000f
            val w = h / 2.2f
            val x = ((cx - w / 2f) / (1f - w)).coerceIn(0f, 1f)
            val y = ((cy - h / 2f) / (1f - h)).coerceIn(0f, 1f)
            return ButtonPosition((x * 10000).toInt(), (y * 10000).toInt())
        }
        fun button(label: String, events: List<ClickEvent>, cx: Float, cy: Float, size: Int,
                   visibility: VisibilityType, toggle: Boolean = false) = NormalData(
            text = createTranslatable(label), uuid = UUID.randomUUID().toString(),
            position = pos(cx, cy, size),
            buttonSize = DefaultSize.copy(widthPercentage = size, heightPercentage = size),
            buttonStyle = glass.uuid, textAlignment = TextAlignment.Center, textBold = true,
            visibilityType = visibility, _clickEvents = events,
            isSwipple = false, isPenetrable = false, isToggleable = toggle
        )
        fun key(id: String) = listOf(ClickEvent(ClickEvent.Type.Key, id))

        // Hidden-at-start layer opened by the "…" button.
        val more = ObservableControlLayer(createNewLayer("More").copy(visibilityType = VisibilityType.IN_GAME))
        more.editorHide = true
        more.hide = true
        more.addNormalButton(button("Drop", key("GLFW_KEY_Q"), 0.36f, 0.60f, 900, VisibilityType.IN_GAME))
        more.addNormalButton(button("Swap", key("GLFW_KEY_F"), 0.42f, 0.60f, 900, VisibilityType.IN_GAME))
        more.addNormalButton(button("Pick", listOf(ClickEvent(ClickEvent.Type.Key, "GLFW_MOUSE_BUTTON_MIDDLE")), 0.48f, 0.60f, 900, VisibilityType.IN_GAME))
        more.addNormalButton(button("HUD", key("GLFW_KEY_F1"), 0.54f, 0.60f, 900, VisibilityType.IN_GAME))
        more.addNormalButton(button("Run", key("GLFW_KEY_LEFT_CONTROL"), 0.60f, 0.60f, 900, VisibilityType.IN_GAME, toggle = true))

        val gameplay = ObservableControlLayer(createNewLayer("Gameplay").copy(visibilityType = VisibilityType.IN_GAME))
        gameplay.apply {
            addJoystickButton(JoystickData(
                uuid = UUID.randomUUID().toString(), position = pos(0.12f, 0.70f, 2800),
                sizePercentage = 2800, visibilityType = VisibilityType.IN_GAME, joystickStyleId = squareStick.uuid
            ))
            addNormalButton(button("@sword", key("GLFW_MOUSE_BUTTON_LEFT"), 0.83f, 0.64f, 1400, VisibilityType.IN_GAME))
            addNormalButton(button("@use", key("GLFW_MOUSE_BUTTON_RIGHT"), 0.94f, 0.52f, 1300, VisibilityType.IN_GAME))
            addNormalButton(button("@jump", key("GLFW_KEY_SPACE"), 0.955f, 0.78f, 1400, VisibilityType.IN_GAME))
            addNormalButton(button("@sprint", key("GLFW_KEY_LEFT_CONTROL"), 0.74f, 0.77f, 1100, VisibilityType.IN_GAME, toggle = true))
            addNormalButton(button("@sneak", key("GLFW_KEY_LEFT_SHIFT"), 0.85f, 0.87f, 1100, VisibilityType.IN_GAME))
            addNormalButton(button("@dots", listOf(ClickEvent(ClickEvent.Type.SwitchLayer, more.uuid)), 0.75f, 0.92f, 800, VisibilityType.IN_GAME))
            // Icon row, top centre
            addNormalButton(button("@bag", key("GLFW_KEY_E"), 0.430f, 0.08f, 850, VisibilityType.IN_GAME))
            addNormalButton(button("@chat", key("GLFW_KEY_T"), 0.475f, 0.08f, 850, VisibilityType.IN_GAME))
            addNormalButton(button("@pause", key("GLFW_KEY_ESCAPE"), 0.520f, 0.08f, 850, VisibilityType.IN_GAME))
            addNormalButton(button("@camera", key("GLFW_KEY_F5"), 0.565f, 0.08f, 850, VisibilityType.IN_GAME))
            addNormalButton(button("@list", key("GLFW_KEY_TAB"), 0.610f, 0.08f, 850, VisibilityType.IN_GAME))
        }

        // Shown whenever a GUI has the cursor (inventory, pause screen, chat, crafting, ...).
        val menu = ObservableControlLayer(createNewLayer("Menu").copy(visibilityType = VisibilityType.IN_MENU))
        menu.apply {
            addNormalButton(button("Back", key("GLFW_KEY_ESCAPE"), 0.07f, 0.12f, 1100, VisibilityType.IN_MENU))
            addNormalButton(button("@bag", key("GLFW_KEY_E"), 0.07f, 0.30f, 1100, VisibilityType.IN_MENU))
            addNormalButton(button("Shift", key("GLFW_KEY_LEFT_SHIFT"), 0.07f, 0.48f, 1100, VisibilityType.IN_MENU, toggle = true))
            addNormalButton(button("Drop", key("GLFW_KEY_Q"), 0.07f, 0.66f, 1100, VisibilityType.IN_MENU))
            addNormalButton(button("Enter", key("GLFW_KEY_ENTER"), 0.93f, 0.30f, 1100, VisibilityType.IN_MENU))
        }

        return ControlLayout(
            info = ControlLayout.Info(createTranslatable("Horizon touch"), createTranslatable("Horizon"),
                createTranslatable("Default Horizon touch layout"), 3, "3"),
            layers = listOf(more.pack(), menu.pack(), gameplay.pack()),
            styles = listOf(glass), joystickStyles = listOf(squareStick),
            editorVersion = 12
        )
    }

    companion object {
        private const val REQUEST_IMPORT_LAYOUT = 7412
        private const val REQUEST_EXPORT_LAYOUT = 7413

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
