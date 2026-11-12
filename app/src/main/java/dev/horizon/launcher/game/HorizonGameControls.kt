package dev.horizon.launcher.game

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.Surface
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.movtery.layer_controller.ControlBoxLayout
import com.movtery.layer_controller.ControlEditorLayer
import com.movtery.zalithlauncher.viewmodel.EditorViewModel
import com.movtery.zalithlauncher.ui.screens.main.control_editor.ControlEditor
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.setting.AllSettings
import java.io.File
import com.movtery.layer_controller.data.ButtonPosition
import com.movtery.layer_controller.data.DefaultSize
import com.movtery.layer_controller.data.HideLayerWhen
import com.movtery.layer_controller.data.JoystickData
import com.movtery.layer_controller.data.NormalData
import com.movtery.layer_controller.data.TextData
import com.movtery.layer_controller.data.VisibilityType
import com.movtery.layer_controller.data.cloneNew
import com.movtery.layer_controller.data.lang.createTranslatable
import com.movtery.layer_controller.event.ClickEvent
import com.movtery.layer_controller.event.EventHandler
import com.movtery.layer_controller.layout.ControlLayout
import com.movtery.layer_controller.observable.ObservableControlLayout
import com.movtery.layer_controller.observable.ObservableJoystickData
import com.movtery.layer_controller.observable.ObservableNormalData
import com.movtery.layer_controller.observable.ObservableTextData
import com.movtery.layer_controller.observable.ObservableWidget
import com.movtery.layer_controller.observable.cloneNormal
import com.movtery.layer_controller.utils.snap.SnapMode
import dev.horizon.launcher.game.controls.ChipChoice
import dev.horizon.launcher.game.controls.ControlHistory
import dev.horizon.launcher.game.controls.Haptics
import dev.horizon.launcher.game.controls.KeyCatalog
import dev.horizon.launcher.game.controls.KeyPickerDialog
import dev.horizon.launcher.game.controls.LabeledSlider
import dev.horizon.launcher.game.controls.LayerPanel
import dev.horizon.launcher.game.controls.LayoutPresets
import dev.horizon.launcher.game.controls.LayoutProfiles
import dev.horizon.launcher.game.controls.LookTuner
import dev.horizon.launcher.game.controls.ProfilesPanel
import dev.horizon.launcher.game.controls.StylesPanel
import dev.horizon.launcher.game.zalith.DualMenuSubscreen
import dev.horizon.launcher.game.zalith.FloatingBall
import dev.horizon.launcher.game.zalith.MenuClearButton
import dev.horizon.launcher.game.zalith.MenuHint
import dev.horizon.launcher.game.zalith.MenuIconTabs
import dev.horizon.launcher.game.zalith.MenuListLayout
import dev.horizon.launcher.game.zalith.MenuPage
import dev.horizon.launcher.game.zalith.MenuSectionLabel
import dev.horizon.launcher.game.zalith.MenuSliderLayout
import dev.horizon.launcher.game.zalith.MenuState
import dev.horizon.launcher.game.zalith.MenuSwitchButton
import dev.horizon.launcher.game.zalith.MenuTextButton
import dev.horizon.launcher.game.zalith.ZalithTheme
import dev.horizon.launcher.game.controls.ToggleRow
import dev.horizon.launcher.game.controls.WidgetInspector
import kotlinx.coroutines.launch
import java.util.UUID

private val EDITOR_TABS = listOf("Control", "Layers", "Styles", "Layouts")
private val QUICK_ICONS = listOf(Icons.Outlined.Tune, Icons.Outlined.Mouse, Icons.Outlined.SportsEsports, Icons.Outlined.TouchApp, Icons.Outlined.ScreenRotation)

@Composable
internal fun HorizonGameControls(
    layout: ObservableControlLayout,
    profiles: LayoutProfiles,
    defaultLayout: () -> ControlLayout,
    setKeepAwake: (Boolean) -> Unit,
    setMaxRefresh: (Boolean) -> Unit,
    save: (ControlLayout) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? HorizonGameActivity
    val view = LocalView.current
    val prefs = remember { context.getSharedPreferences("zalith-touch-settings", Context.MODE_PRIVATE) }

    var grabbing by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf(false) }
    var editorViewModel by remember { mutableStateOf<EditorViewModel?>(null) }
    LaunchedEffect(context) { PathManager.initialize(context.filesDir); AllSettings.initialize(context) }
    var quickSettings by remember { mutableStateOf(false) }

    // ---- touch HUD / look settings -------------------------------------------------------
    var opacity by remember { mutableFloatStateOf(prefs.getFloat("opacity", 0.88f)) }
    var lookSensitivity by remember { mutableFloatStateOf(prefs.getFloat("look_sensitivity", 1f)) }
    var lookSensX by remember { mutableFloatStateOf(prefs.getFloat("look_sens_x", 1f)) }
    var lookSensY by remember { mutableFloatStateOf(prefs.getFloat("look_sens_y", 1f)) }
    var lookAccel by remember { mutableFloatStateOf(prefs.getFloat("look_accel", 0f)) }
    var lookSmoothing by remember { mutableFloatStateOf(prefs.getFloat("look_smoothing", 0f)) }
    var lookArea by remember { mutableIntStateOf(prefs.getInt("look_area", 0)) }
    var invertLookX by remember { mutableStateOf(prefs.getBoolean("invert_look_x", false)) }
    var invertLookY by remember { mutableStateOf(prefs.getBoolean("invert_look_y", false)) }
    var swipeLook by remember { mutableStateOf(prefs.getBoolean("swipe_look", true)) }
    var gyroEnabled by remember { mutableStateOf(prefs.getBoolean("gyro_enabled", false)) }
    var gyroSensitivity by remember { mutableFloatStateOf(prefs.getFloat("gyro_sensitivity", 1f)) }
    var gesturesEnabled by remember { mutableStateOf(prefs.getBoolean("touch_gestures_enabled", true)) }
    var tapAction by remember { mutableIntStateOf(prefs.getInt("gesture_tap_action", 2).coerceIn(0, 2)) }
    var longPressAction by remember { mutableIntStateOf(prefs.getInt("gesture_long_action", 1).coerceIn(0, 2)) }
    var longPressDelay by remember { mutableIntStateOf(prefs.getInt("gesture_long_delay", 300).coerceIn(100, 1000)) }
    var storedHud by remember { mutableStateOf(prefs.getBoolean("hud_visible", true)) }
    var hapticLevel by remember { mutableIntStateOf(prefs.getInt("haptic_level", 0)) }
    var autoHideForPad by remember { mutableStateOf(prefs.getBoolean("hide_hud_for_gamepad", false)) }
    var quickTab by remember { mutableIntStateOf(0) }
    var ballPos by remember { mutableStateOf(Offset(prefs.getFloat("ball_x", 0f), prefs.getFloat("ball_y", 0f))) }
    val quickScroll = remember(quickTab) { ScrollState(0) }
    var keepAwake by remember { mutableStateOf(prefs.getBoolean("keep_awake", true)) }
    var brightness by remember { mutableFloatStateOf(prefs.getFloat("screen_brightness", -1f)) }
    var showFps by remember { mutableStateOf(prefs.getBoolean("show_fps", false)) }
    var showMemory by remember { mutableStateOf(prefs.getBoolean("show_memory", false)) }
    var maxRefresh by remember { mutableStateOf(prefs.getBoolean("max_refresh", false)) }
    var fpsMode by remember { mutableIntStateOf(prefs.getInt("fps_mode", 0)) }
    var quickButtonOpacity by remember { mutableFloatStateOf(prefs.getFloat("quick_button_opacity", 0.88f)) }
    var autoKeyboard by remember { mutableStateOf(prefs.getBoolean("auto_keyboard", true)) }
    var hotbarTouch by remember { mutableStateOf(prefs.getBoolean("hotbar_touch", true)) }
    var currentFps by remember { mutableIntStateOf(0) }
    var currentMemoryMb by remember { mutableIntStateOf(0) }
    val fpsHistory = remember { mutableStateListOf<Float>() }

    // ---- gamepad ------------------------------------------------------------------------
    val padSlots = remember { HorizonGameActivity.PAD_DEFAULTS.keys.toList() }
    val padMappings = remember {
        mutableStateMapOf<String, String>().apply {
            padSlots.forEach { slot -> this[slot] = prefs.getString("pad_$slot", HorizonGameActivity.PAD_DEFAULTS[slot]) ?: "" }
        }
    }
    var padPick by remember { mutableStateOf<String?>(null) }
    var padDeadzone by remember { mutableFloatStateOf(prefs.getFloat("pad_deadzone", 0.22f)) }
    var padTriggerThreshold by remember { mutableFloatStateOf(prefs.getFloat("pad_trigger_threshold", 0.22f)) }
    var padCameraSensitivity by remember { mutableFloatStateOf(prefs.getFloat("pad_camera_sensitivity", 1.4f)) }
    var padInvertY by remember { mutableStateOf(prefs.getBoolean("pad_invert_y", false)) }
    var padSwapSticks by remember { mutableStateOf(prefs.getBoolean("pad_swap_sticks", false)) }
    var padConnected by remember { mutableStateOf(false) }

    // ---- layout editor state ------------------------------------------------------------
    var activeLayout by remember(layout) { mutableStateOf(layout) }
    var selected by remember { mutableStateOf<ObservableWidget?>(null) }
    var selectedLayer by remember(activeLayout) { mutableStateOf(activeLayout.layers.value.firstOrNull()) }
    var panelOpen by remember { mutableStateOf(true) }
    var editTab by remember { mutableIntStateOf(0) }
    var snapEnabled by remember { mutableStateOf(prefs.getBoolean("editor_snap", true)) }
    val history = remember { ControlHistory() }
    var editSnapshot by remember { mutableStateOf<ControlLayout?>(null) }
    val occupied = remember { mutableSetOf<Long>() }
    // Pointer ownership is input-only; keep it non-observable to avoid recomposing the HUD on every touch.
    val controlPointers = remember { mutableSetOf<Long>() }
    val tuner = remember { LookTuner() }
    val haptics = remember(view) { Haptics(view) }

    fun record() = history.record(activeLayout.pack())
    fun swapLayout(next: ControlLayout) { activeLayout = ObservableControlLayout(next); selected = null }

    fun startEditing() {
        editSnapshot = activeLayout.pack(); history.clear()
        selected = null; panelOpen = true; editTab = 0; quickSettings = false
        editorViewModel = EditorViewModel().apply { initLayout(activeLayout.pack()); styleCabinet.load() }
        editing = true
    }
    fun finishEditing(keep: Boolean, updated: ControlLayout? = null) {
        if (keep) {
            updated?.let { activeLayout = ObservableControlLayout(it) }
            activeLayout.applyEditorHide()
            save(activeLayout.pack())
            profiles.activeName.takeIf { it.isNotBlank() }?.let { name ->
                // Keep the active saved layout in sync with what the player just edited.
                runCatching { if (name in profiles.names()) profiles.save(name, activeLayout.pack()) }
            }
        } else editSnapshot?.let { swapLayout(it) }
        selected = null; editing = false; editSnapshot = null; editorViewModel = null
    }
    fun addButton() {
        val layer = selectedLayer ?: activeLayout.layers.value.firstOrNull() ?: return
        record()
        layer.addNormalButton(NormalData(
            text = createTranslatable("Key"), uuid = UUID.randomUUID().toString(), position = ButtonPosition(5000, 5000),
            buttonSize = DefaultSize, visibilityType = VisibilityType.IN_GAME,
            _clickEvents = listOf(ClickEvent(ClickEvent.Type.Key, "GLFW_KEY_E")),
            isSwipple = false, isPenetrable = false, isToggleable = false))
        selected = layer.normalButtons.value.lastOrNull(); selectedLayer = layer; editTab = 0; panelOpen = true
    }
    fun addStick() {
        val layer = selectedLayer ?: activeLayout.layers.value.firstOrNull() ?: return
        record()
        layer.addJoystickButton(JoystickData(uuid = UUID.randomUUID().toString(), position = ButtonPosition(5000, 5000),
            sizePercentage = 2200, visibilityType = VisibilityType.IN_GAME))
        selected = layer.joystickButtons.value.lastOrNull(); selectedLayer = layer; editTab = 0; panelOpen = true
    }
    fun addLabel() {
        val layer = selectedLayer ?: activeLayout.layers.value.firstOrNull() ?: return
        record()
        layer.addTextBox(TextData(text = createTranslatable("Label"), uuid = UUID.randomUUID().toString(),
            position = ButtonPosition(5000, 5000), buttonSize = DefaultSize.copy(widthPercentage = 2500, heightPercentage = 1000),
            visibilityType = VisibilityType.IN_GAME))
        selected = layer.textBoxes.value.lastOrNull(); selectedLayer = layer; editTab = 0; panelOpen = true
    }
    fun duplicateSelected() {
        val widget = selected
        val layer = selectedLayer ?: return
        when (widget) {
            is ObservableNormalData -> { val copy = widget.cloneNormal(); layer.addNormalButton(copy); selected = copy }
            is ObservableJoystickData -> { val copy = ObservableJoystickData(widget.packJoystick().cloneNew()); layer.addJoystickButton(copy); selected = copy }
            else -> {}
        }
    }
    fun deleteSelected() {
        val widget = selected
        val layer = selectedLayer ?: return
        when (widget) {
            is ObservableNormalData -> layer.removeNormalButton(widget.uuid)
            is ObservableJoystickData -> layer.removeJoystickButton(widget.uuid)
            is ObservableTextData -> layer.removeTextBox(widget.uuid)
            else -> {}
        }
        selected = null
    }

    // ---- background polling / effects -----------------------------------------------------
    // Cursor-lock state is only needed by the touch HUD. Stop polling while Quick Settings
    // or the full editor is visible, and sample less aggressively when the HUD is active.
    LaunchedEffect(quickSettings, editing) {
        if (quickSettings || editing) return@LaunchedEffect
        while (true) {
            grabbing = runCatching { GameInput.nativeCursorGrabbing() }.getOrDefault(true)
            kotlinx.coroutines.delay(350)
        }
    }
    // InputManager reports gamepad changes directly; avoid polling every two seconds on the UI thread.
    DisposableEffect(context) {
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        fun refreshGamepad() {
            padConnected = InputDevice.getDeviceIds().any { id ->
                val device = InputDevice.getDevice(id)
                device != null && !device.isVirtual &&
                    device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
            }
        }
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = refreshGamepad()
            override fun onInputDeviceRemoved(deviceId: Int) = refreshGamepad()
            override fun onInputDeviceChanged(deviceId: Int) = refreshGamepad()
        }
        refreshGamepad()
        inputManager?.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        onDispose { inputManager?.unregisterInputDeviceListener(listener) }
    }
    LaunchedEffect(hapticLevel) { haptics.level = hapticLevel }
    // Stats updates invalidate the overlay composition. Pause them while Quick Settings is
    // open, and only collect graph samples when the graph is actually displayed.
    LaunchedEffect(showFps, showMemory, fpsMode, quickSettings) {
        if (quickSettings) return@LaunchedEffect
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        while (showFps || showMemory) {
            if (showFps) {
                currentFps = runCatching { GameInput.nativeGameFps() }.getOrDefault(0)
                if (fpsMode == 1) {
                    fpsHistory.add(currentFps.toFloat())
                    while (fpsHistory.size > 30) fpsHistory.removeAt(0)
                }
            }
            if (showMemory) currentMemoryMb = runCatching {
                manager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).firstOrNull()?.totalPss?.div(1024) ?: 0
            }.getOrDefault(0)
            kotlinx.coroutines.delay(1000)
        }
    }
    LaunchedEffect(keepAwake) { setKeepAwake(keepAwake) }
    LaunchedEffect(maxRefresh) { setMaxRefresh(maxRefresh) }
    LaunchedEffect(brightness) {
        (context as? Activity)?.window?.let { w ->
            val attributes = w.attributes
            attributes.screenBrightness = if (brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else brightness
            w.attributes = attributes
        }
    }
    LaunchedEffect(autoKeyboard) { GameInput.nativeSetImeAllowed(autoKeyboard) }
    LaunchedEffect(gyroEnabled) { GameInput.nativeLookActive(gyroEnabled) }
    DisposableEffect(gyroEnabled, gyroSensitivity, invertLookX, invertLookY) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        var lastTimestamp = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!gyroEnabled || lastTimestamp == 0L) { lastTimestamp = event.timestamp; return }
                val dt = ((event.timestamp - lastTimestamp) / 1_000_000_000f).coerceIn(0f, 0.05f)
                lastTimestamp = event.timestamp
                @Suppress("DEPRECATION")
                val rotation = (context as? Activity)?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
                val (x, y) = when (rotation) {
                    Surface.ROTATION_90 -> event.values[1] to -event.values[0]
                    Surface.ROTATION_180 -> -event.values[0] to -event.values[1]
                    Surface.ROTATION_270 -> -event.values[1] to event.values[0]
                    else -> event.values[0] to event.values[1]
                }
                GameInput.nativeLookActive(true)
                GameInput.nativeLook(y * dt * gyroSensitivity * 900f * if (invertLookX) -1f else 1f,
                    x * dt * gyroSensitivity * 900f * if (invertLookY) -1f else 1f)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (gyroEnabled && sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { manager.unregisterListener(listener) }
    }

    val handler = remember(haptics) {
        EventHandler { event, pressed ->
            if (event.type == ClickEvent.Type.Key) {
                GameInput.nativeSendControl(event.key, pressed)
                if (pressed) haptics.tick()
            }
        }
    }

    MaterialTheme {
    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(Unit) {
            val pointerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext())
            awaitPointerEventScope {
                val positions = mutableMapOf<Long, Offset>()
                val starts = mutableMapOf<Long, Offset>()
                val downAt = mutableMapOf<Long, Long>()
                val lookPointers = mutableSetOf<Long>()
                val gesturePointers = mutableSetOf<Long>()
                val ignored = mutableSetOf<Long>()
                val hotbarPointers = mutableSetOf<Long>()
                val longPressJobs = mutableMapOf<Long, kotlinx.coroutines.Job>()
                val heldButtons = mutableMapOf<Long, String>()
                val tapSuppressed = mutableSetOf<Long>()
                // A pointer claimed by a HUD widget must never become a world tap on release.
                fun cancelTrackedPointer(id: Long) {
                    longPressJobs.remove(id)?.cancel()
                    heldButtons.remove(id)?.let { GameInput.nativeSendControl(it, false) }
                    if (lookPointers.remove(id) && swipeLook) {
                        GameInput.nativeLookActive(lookPointers.isNotEmpty())
                    }
                    gesturePointers.remove(id)
                    positions.remove(id)
                    starts.remove(id)
                    downAt.remove(id)
                    ignored.remove(id)
                    hotbarPointers.remove(id)
                    tapSuppressed.remove(id)
                }
                /** Zalith-style hotbar: index 0..8 of the slot under [p], or -1. Geometry comes from the game's HUD. */
                fun hotbarSlotAt(p: Offset): Int {
                    val r = runCatching { GameInput.nativeHotbarRect() }.getOrNull() ?: return -1
                    if (r.size < 4 || r[2] <= 0f) return -1
                    val pad = r[3] * 0.25f
                    if (p.y < r[1] - pad || p.y > r[1] + r[3] + pad) return -1
                    val slot = ((p.x - r[0] - r[2] * 0.05f) / r[2]).toInt()
                    return if (p.x >= r[0] && slot in 0..8) slot else -1
                }
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (editing || quickSettings) {
                        // Editor and Quick Settings own the screen; do not route touches into Minecraft.
                        // Release any synthetic mouse button before the panel takes over input.
                        longPressJobs.values.forEach { it.cancel() }
                        longPressJobs.clear()
                        heldButtons.values.forEach { GameInput.nativeSendControl(it, false) }
                        if (swipeLook) GameInput.nativeLookActive(false)
                        heldButtons.clear()
                        positions.clear(); starts.clear(); downAt.clear(); lookPointers.clear(); gesturePointers.clear(); ignored.clear(); tapSuppressed.clear(); controlPointers.clear(); hotbarPointers.clear(); tuner.reset()
                        continue
                    }
                    // Arbitrate every pointer before processing this frame. Compose dispatches the
                    // same multi-touch frame to the parent and HUD children; inspecting only the
                    // currently visited change makes the result depend on pointer iteration order.
                    // Any second finger (HUD control OR empty-world touch) cancels world tap actions.
                    val activePointerIds = mutableSetOf<Long>()
                    event.changes.filter { it.pressed }.forEach { activePointerIds += it.id.value }
                    activePointerIds.addAll(positions.keys)
                    activePointerIds.addAll(occupied)
                    activePointerIds.addAll(controlPointers)
                    val multiTouchActive = activePointerIds.size > 1
                    if (multiTouchActive && gesturePointers.isNotEmpty()) {
                        tapSuppressed.addAll(gesturePointers)
                        longPressJobs.values.forEach { it.cancel() }
                        longPressJobs.clear()
                        heldButtons.values.forEach { GameInput.nativeSendControl(it, false) }
                        heldButtons.clear()
                    }
                    val areaStart = when (lookArea) { 1 -> 0.3f; 2 -> 0.5f; else -> 0f }
                    event.changes.forEach { change ->
                        val id = change.id.value
                        // Track ownership for the entire pointer stream, not just DOWN. A HUD
                        // widget may claim the pointer between DOWN and UP (e.g. joystick + jump).
                        if (id in controlPointers) {
                            if (!change.pressed) controlPointers.remove(id)
                            return@forEach
                        }
                        if (change.isConsumed || id in occupied) {
                            if (change.pressed) controlPointers.add(id)
                            else controlPointers.remove(id)
                            cancelTrackedPointer(id)
                            return@forEach
                        }
                        if (change.pressed) {
                            if (id in hotbarPointers) return@forEach
                            if (hotbarTouch && grabbing && id !in positions) {
                                val slot = hotbarSlotAt(change.position)
                                if (slot >= 0) {
                                    hotbarPointers += id; ignored += id; positions[id] = change.position
                                    GameInput.nativeSendControl("GLFW_KEY_${slot + 1}", true)
                                    GameInput.nativeSendControl("GLFW_KEY_${slot + 1}", false)
                                    return@forEach
                                }
                            }
                            val prior = positions.put(id, change.position)
                            if (prior == null) {
                                starts[id] = change.position
                                downAt[id] = SystemClock.elapsedRealtime()
                            }
                            if (grabbing) {
                                if (prior == null) {
                                    if (multiTouchActive || occupied.isNotEmpty() || controlPointers.isNotEmpty()) {
                                        tapSuppressed += id
                                    }
                                    gesturePointers += id
                                    if (change.position.x >= size.width * areaStart) {
                                        lookPointers += id
                                        tuner.reset()
                                        if (swipeLook) GameInput.nativeLookActive(true)
                                    }
                                    if (gesturesEnabled && longPressAction in 1..2 &&
                                        id !in tapSuppressed && !multiTouchActive &&
                                        occupied.isEmpty() && controlPointers.isEmpty()
                                    ) {
                                        longPressJobs[id] = pointerScope.launch {
                                            kotlinx.coroutines.delay(longPressDelay.toLong())
                                            if (id in gesturePointers && id !in tapSuppressed && occupied.isEmpty() && controlPointers.isEmpty() && gesturesEnabled && longPressAction in 1..2) {
                                                val button = if (longPressAction == 1) "GLFW_MOUSE_BUTTON_LEFT" else "GLFW_MOUSE_BUTTON_RIGHT"
                                                GameInput.nativeSendControl(button, true)
                                                heldButtons[id] = button
                                            }
                                        }
                                    }
                                } else if (id in gesturePointers) {
                                    val start = starts[id]
                                    if (start != null && (start - change.position).getDistance() > 24f) {
                                        longPressJobs.remove(id)?.cancel()
                                        heldButtons.remove(id)?.let { GameInput.nativeSendControl(it, false) }
                                    }
                                    if (id in lookPointers && swipeLook) {
                                        val dx = (change.position.x - prior.x) * lookSensitivity * lookSensX * if (invertLookX) -1f else 1f
                                        val dy = (change.position.y - prior.y) * lookSensitivity * lookSensY * if (invertLookY) -1f else 1f
                                        tuner.apply(dx, dy, lookAccel, lookSmoothing)
                                        GameInput.nativeLook(tuner.outX, tuner.outY)
                                    }
                                }
                            } else {
                                GameInput.nativeMenuTouch(id, change.position.x, change.position.y, if (prior == null) 0 else 1)
                            }
                        } else if (!change.pressed && positions.remove(id) != null) {
                            hotbarPointers.remove(id)
                            occupied.remove(id)
                            val start = starts.remove(id)
                            val duration = SystemClock.elapsedRealtime() - (downAt.remove(id) ?: 0L)
                            if (gesturePointers.remove(id)) {
                                if (lookPointers.remove(id) && swipeLook) GameInput.nativeLookActive(lookPointers.isNotEmpty())
                                longPressJobs.remove(id)?.cancel()
                                val heldButton = heldButtons.remove(id)
                                val suppressTap = tapSuppressed.remove(id) || occupied.isNotEmpty() || controlPointers.isNotEmpty()
                                val moved = start?.let { (it - change.position).getDistance() > 24f } ?: true
                                if (heldButton != null) {
                                    GameInput.nativeSendControl(heldButton, false)
                                } else if (!moved && gesturesEnabled && !suppressTap) {
                                    val action = if (duration >= longPressDelay) longPressAction else tapAction
                                    if (action in 1..2) {
                                        val button = if (action == 1) "GLFW_MOUSE_BUTTON_LEFT" else "GLFW_MOUSE_BUTTON_RIGHT"
                                        GameInput.nativeSendControl(button, true)
                                        GameInput.nativeSendControl(button, false)
                                    }
                                }
                            } else if (!ignored.remove(id)) {
                                GameInput.nativeMenuTouch(id, change.position.x, change.position.y, 2)
                            }
                        }
                    }
                }
            }
        }
    ) {
        if (editing && editorViewModel != null) {
            val activeEditor = editorViewModel!!
            ControlEditor(
                viewModel = activeEditor,
                targetFile = File(File(context.filesDir, "launcher"), "touch-layout.json"),
                exit = { finishEditing(keep = true, updated = activeEditor.observableLayout.pack()) },
                menuExit = { finishEditing(keep = false) },
                onSaveLayout = { updated ->
                    activeLayout = ObservableControlLayout(updated)
                    save(updated)
                }
            )
        } else if (editing) {
            ControlEditorLayer(
                observedLayout = activeLayout,
                selectedWidget = selected,
                onButtonTap = { data, layer ->
                    if (data !== selected) record()
                    selected = data; selectedLayer = layer; editTab = 0
                },
                onBackgroundClick = { selected = null },
                floatingButtons = {}, enableSnap = snapEnabled, snapInAllLayers = true,
                snapMode = SnapMode.FullScreen, isDark = true
            )

            // Toolbar
            androidx.compose.material3.Surface(
                Modifier.align(Alignment.TopStart).padding(8.dp).fillMaxWidth(if (panelOpen) 0.58f else 0.9f),
                shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), tonalElevation = 6.dp
            ) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { addButton() }) { Text("+ Button") }
                    Button(onClick = { addStick() }) { Text("+ Stick") }
                    Button(onClick = { addLabel() }) { Text("+ Label") }
                    OutlinedButton(enabled = history.canUndo, onClick = {
                        history.undo(activeLayout.pack())?.let { swapLayout(it) }
                    }) { Text("Undo") }
                    OutlinedButton(enabled = history.canRedo, onClick = {
                        history.redo(activeLayout.pack())?.let { swapLayout(it) }
                    }) { Text("Redo") }
                    FilterChip(selected = snapEnabled, onClick = { snapEnabled = !snapEnabled; prefs.edit().putBoolean("editor_snap", snapEnabled).apply() }, label = { Text("Snap") })
                    FilterChip(selected = panelOpen, onClick = { panelOpen = !panelOpen }, label = { Text("Panel") })
                    OutlinedButton(onClick = { finishEditing(keep = false) }) { Text("Cancel") }
                    Button(onClick = { finishEditing(keep = true) }) { Text("Save / Done") }
                }
            }

            // Side panel
            if (panelOpen) androidx.compose.material3.Surface(
                Modifier.align(Alignment.CenterEnd).width(340.dp).fillMaxHeight().padding(8.dp),
                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), tonalElevation = 8.dp
            ) {
                Column(Modifier.fillMaxSize()) {
                    ScrollableTabRow(selectedTabIndex = editTab, edgePadding = 0.dp) {
                        EDITOR_TABS.forEachIndexed { i, label -> Tab(selected = editTab == i, onClick = { editTab = i }, text = { Text(label) }) }
                    }
                    Box(Modifier.weight(1f)) {
                        when (editTab) {
                            0 -> {
                                val widget = selected
                                if (widget == null) Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Nothing selected", style = MaterialTheme.typography.titleMedium)
                                    Text("Tap a button, stick or label on the canvas to edit its keys, size, position and look. Drag to move it, drag a corner to resize.", style = MaterialTheme.typography.bodyMedium)
                                    Text("New controls go on layer: ${selectedLayer?.name?.ifBlank { "(unnamed)" } ?: "—"}", style = MaterialTheme.typography.bodySmall)
                                } else WidgetInspector(
                                    widget = widget, layout = activeLayout, layer = selectedLayer,
                                    recordHistory = { record() }, onDelete = { deleteSelected() }, onDuplicate = { duplicateSelected() },
                                    onClose = { selected = null }, modifier = Modifier.fillMaxSize()
                                )
                            }
                            1 -> LayerPanel(activeLayout, selectedLayer, { selectedLayer = it }, { record() }, Modifier.fillMaxSize())
                            2 -> StylesPanel(activeLayout, { record() }, Modifier.fillMaxSize())
                            else -> ProfilesPanel(
                                profiles = profiles, layout = activeLayout,
                                onLoad = { loaded, _ -> record(); swapLayout(loaded) },
                                onResetToDefault = { record(); swapLayout(defaultLayout()); profiles.activeName = "" },
                                onImport = { activity?.chooseTouchLayout { imported -> record(); swapLayout(imported) } },
                                onExport = { activity?.exportTouchLayout(activeLayout.pack()) },
                                onShare = { activity?.shareTouchLayout(activeLayout.pack()) },
                                onCopy = { activity?.copyTouchLayout(activeLayout.pack()) },
                                onPaste = { activity?.pasteTouchLayout { imported -> record(); swapLayout(imported) } },
                                onError = { message -> activity?.toast(message) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        } else {
            // The settings sheet dims the whole game; do not render or hit-test the HUD beneath it.
            if (storedHud && !quickSettings) ControlBoxLayout(
                modifier = Modifier.fillMaxSize(), observedLayout = activeLayout, eventHandler = handler,
                isCursorGrabbing = grabbing,
                checkOccupiedPointers = { it.value in occupied },
                onOccupiedPointer = { occupied += it.value },
                onReleasePointer = { occupied -= it.value },
                onControlPointerChanged = { pointer, pressed ->
                    if (pressed) controlPointers.add(pointer.value) else controlPointers.remove(pointer.value)
                },
                opacity = { opacity },
                hideLayerWhen = if (autoHideForPad && padConnected) HideLayerWhen.WhenGamepad else HideLayerWhen.None,
                isDark = true
            ) {}
            FloatingBall(
                position = ballPos,
                onPositionChanged = { ballPos = it; prefs.edit().putFloat("ball_x", it.x).putFloat("ball_y", it.y).apply() },
                onClick = { quickSettings = !quickSettings },
                alpha = quickButtonOpacity,
            ) {
                Text(
                    if (showFps && fpsMode == 0) "$currentFps FPS" else "⚙",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = Color.White,
                )
            }
            if ((showFps && fpsMode == 1) || showMemory) {
                Column(Modifier.align(Alignment.TopStart).padding(12.dp)) {
                    if (showFps && fpsMode == 1) Canvas(Modifier.width(120.dp).height(42.dp)) {
                        if (fpsHistory.size > 1) {
                            val maxFps = (fpsHistory.maxOrNull() ?: 1f).coerceAtLeast(1f)
                            val points = fpsHistory.mapIndexed { i, value ->
                                Offset(size.width * i / (fpsHistory.size - 1), size.height * (1f - value / maxFps))
                            }
                            points.zipWithNext().forEach { (a, b) -> drawLine(Color.Green, a, b, strokeWidth = 3f) }
                        }
                    }
                    if (showMemory) Text("${currentMemoryMb} MB", color = Color.White)
                }
            }
            if (quickSettings) ZalithTheme {
                DualMenuSubscreen(
                    state = MenuState.SHOW,
                    closeScreen = { quickSettings = false },
                    leftMenuContent = {
                        MenuIconTabs(QUICK_ICONS, quickTab) { quickTab = it }
                        MenuPage(Modifier.weight(1f)) {
                            when (quickTab) {
                                0 -> {
                                    MenuSectionLabel("On-screen controls")
                                    MenuSwitchButton(text = "Show touch HUD", switch = storedHud) { storedHud = it; prefs.edit().putBoolean("hud_visible", it).apply() }
                                    MenuSliderLayout(title = "Control opacity", value = opacity, valueRange = 0.2f..1f, format = { "${(it * 100).toInt()}%" }) {
                                        opacity = it; prefs.edit().putFloat("opacity", it).apply()
                                    }
                                    MenuTextButton(text = "Open full control editor") {
                                         quickSettings = false
                                         activity?.let { host ->
                                             host.startActivity(Intent(host, TouchControlEditorActivity::class.java))
                                         }
                                     }
                                    MenuListLayout(title = "Haptic feedback", items = listOf(0, 1, 2, 3), currentItem = hapticLevel, getItemText = { Haptics.labels[it] }) {
                                        hapticLevel = it; prefs.edit().putInt("haptic_level", it).apply(); haptics.level = it; haptics.tick()
                                    }
                                    MenuSwitchButton(text = "Hide controls when a gamepad is connected", switch = autoHideForPad) {
                                        autoHideForPad = it; prefs.edit().putBoolean("hide_hud_for_gamepad", it).apply()
                                    }
                                    MenuSectionLabel("Layouts")
                                    val saved = remember(quickSettings) { profiles.names() }
                                    if (saved.isEmpty()) MenuHint("Save layouts from Customize controls → Layouts to switch between them here.")
                                    else MenuListLayout(
                                        title = "Saved layouts", items = saved, currentItem = profiles.activeName.takeIf { it in saved } ?: "",
                                        getItemText = { it.ifEmpty { "Choose a layout" } },
                                    ) { name ->
                                        runCatching { profiles.load(name) }
                                            .onSuccess { swapLayout(it); save(it); profiles.activeName = name }
                                            .onFailure { activity?.toast("Could not load $name: ${it.message}") }
                                    }
                                    MenuListLayout(
                                        title = "Presets", items = LayoutPresets.labels(), currentItem = "",
                                        getItemText = { it.ifEmpty { "Choose a preset" } },
                                    ) { label ->
                                        LayoutPresets.byLabel(label)?.let { swapLayout(it); save(it); profiles.activeName = "" }
                                    }
                                    MenuTextButton(text = "Import layout file") {
                                        activity?.chooseTouchLayout { imported -> swapLayout(imported); save(imported); profiles.activeName = "" }
                                    }
                                    MenuSectionLabel("Input")
                                    MenuSwitchButton(text = "Tap hotbar slots directly", switch = hotbarTouch) { hotbarTouch = it; prefs.edit().putBoolean("hotbar_touch", it).apply() }
                                    MenuSwitchButton(text = "Auto-show keyboard in text fields", switch = autoKeyboard) { autoKeyboard = it; prefs.edit().putBoolean("auto_keyboard", it).apply() }
                                    MenuTextButton(text = "Choose Android keyboard") {
                                        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                                    }
                                    MenuSliderLayout(title = "Menu button opacity", value = quickButtonOpacity, valueRange = 0.25f..1f, format = { "${(it * 100).toInt()}%" }) {
                                        quickButtonOpacity = it; prefs.edit().putFloat("quick_button_opacity", it).apply()
                                    }
                                }
                                1 -> {
                                    MenuSectionLabel("Touch mouse / camera")
                                    MenuSwitchButton(text = "Swipe to look", switch = swipeLook) { swipeLook = it; prefs.edit().putBoolean("swipe_look", it).apply() }
                                    MenuSliderLayout(title = "Look sensitivity", value = lookSensitivity, valueRange = 0.25f..3f, format = { "%.2f×".format(it) }) {
                                        lookSensitivity = it; prefs.edit().putFloat("look_sensitivity", it).apply()
                                    }
                                    MenuSliderLayout(title = "Horizontal multiplier", value = lookSensX, valueRange = 0.25f..3f, format = { "%.2f×".format(it) }) {
                                        lookSensX = it; prefs.edit().putFloat("look_sens_x", it).apply()
                                    }
                                    MenuSliderLayout(title = "Vertical multiplier", value = lookSensY, valueRange = 0.25f..3f, format = { "%.2f×".format(it) }) {
                                        lookSensY = it; prefs.edit().putFloat("look_sens_y", it).apply()
                                    }
                                    MenuSliderLayout(title = "Flick acceleration", value = lookAccel, valueRange = 0f..1f, format = { "${(it * 100).toInt()}%" }) {
                                        lookAccel = it; prefs.edit().putFloat("look_accel", it).apply()
                                    }
                                    MenuSliderLayout(title = "Smoothing", value = lookSmoothing, valueRange = 0f..0.9f, format = { "${(it * 100).toInt()}%" }) {
                                        lookSmoothing = it; prefs.edit().putFloat("look_smoothing", it).apply()
                                    }
                                    MenuListLayout(title = "Look area", items = listOf(0, 1, 2), currentItem = lookArea, getItemText = { listOf("Full screen", "Right 70%", "Right half")[it] }) {
                                        lookArea = it; prefs.edit().putInt("look_area", it).apply()
                                    }
                                    MenuSwitchButton(text = "Invert horizontal look", switch = invertLookX) { invertLookX = it; prefs.edit().putBoolean("invert_look_x", it).apply() }
                                    MenuSwitchButton(text = "Invert vertical look", switch = invertLookY) { invertLookY = it; prefs.edit().putBoolean("invert_look_y", it).apply() }
                                    MenuHint("Drag empty screen space while the game captures the pointer. Touch controls stay clickable. Restricting the look area keeps stray thumbs on the left side from moving the camera.")
                                }
                                2 -> {
                                    MenuSectionLabel("Controller input")
                                    MenuHint(if (padConnected) "A game controller is connected." else "No game controller detected. Connect a Bluetooth or USB controller.")
                                    MenuHint("Tap a slot to choose any key or mouse button. ✕ clears it.")
                                    padSlots.forEach { slot ->
                                        val title = slot.replace('_', ' ').replaceFirstChar { it.uppercase() }
                                        val binding = padMappings[slot] ?: ""
                                        MenuTextButton(
                                            text = "$title → ${if (binding.isBlank()) "None" else KeyCatalog.label(binding)}",
                                            appendLayout = { MenuClearButton { padMappings[slot] = ""; prefs.edit().putString("pad_$slot", "").apply() } },
                                        ) { padPick = slot }
                                    }
                                    MenuTextButton(text = "Reset bindings") {
                                        padSlots.forEach { slot ->
                                            val default = HorizonGameActivity.PAD_DEFAULTS[slot] ?: ""
                                            padMappings[slot] = default; prefs.edit().putString("pad_$slot", default).apply()
                                        }
                                    }
                                    MenuSwitchButton(text = "Swap sticks (right moves, left looks)", switch = padSwapSticks) { padSwapSticks = it; prefs.edit().putBoolean("pad_swap_sticks", it).apply() }
                                    MenuSwitchButton(text = "Invert camera Y", switch = padInvertY) { padInvertY = it; prefs.edit().putBoolean("pad_invert_y", it).apply() }
                                    MenuSliderLayout(title = "Stick deadzone", value = padDeadzone, valueRange = 0.05f..0.6f, format = { "${(it * 100).toInt()}%" }) {
                                        padDeadzone = it; prefs.edit().putFloat("pad_deadzone", it).apply()
                                    }
                                    MenuSliderLayout(title = "Trigger threshold", value = padTriggerThreshold, valueRange = 0.05f..0.9f, format = { "${(it * 100).toInt()}%" }) {
                                        padTriggerThreshold = it; prefs.edit().putFloat("pad_trigger_threshold", it).apply()
                                    }
                                    MenuSliderLayout(title = "Camera sensitivity", value = padCameraSensitivity, valueRange = 0.2f..4f, format = { "%.1f×".format(it) }) {
                                        padCameraSensitivity = it; prefs.edit().putFloat("pad_camera_sensitivity", it).apply()
                                    }
                                }
                                3 -> {
                                    MenuSectionLabel("Touch gestures")
                                    MenuSwitchButton(text = "Enable tap and long-press actions", switch = gesturesEnabled) {
                                        gesturesEnabled = it; prefs.edit().putBoolean("touch_gestures_enabled", it).apply()
                                    }
                                    MenuListLayout(title = "Short tap action", items = listOf(0, 1, 2), currentItem = tapAction, getItemText = { listOf("None", "Break / Attack", "Place / Use")[it] }) {
                                        tapAction = it; prefs.edit().putInt("gesture_tap_action", it).apply()
                                    }
                                    MenuListLayout(title = "Long-press action", items = listOf(0, 1, 2), currentItem = longPressAction, getItemText = { listOf("None", "Break / Attack", "Place / Use")[it] }) {
                                        longPressAction = it; prefs.edit().putInt("gesture_long_action", it).apply()
                                    }
                                    MenuSliderLayout(title = "Long-press delay", value = longPressDelay.toFloat(), valueRange = 100f..1000f, steps = 17, format = { "${it.toInt()} ms" }) {
                                        longPressDelay = it.toInt(); prefs.edit().putInt("gesture_long_delay", longPressDelay).apply()
                                    }
                                    MenuHint("Gestures are active only while the game captures the pointer. A stationary short tap sends the tap action; reaching the long-press delay holds the selected mouse button until you lift your finger. Menus continue to receive normal touches.")
                                }
                                else -> {
                                    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                                    val gyroAvailable = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
                                    MenuSectionLabel("Gyroscope camera")
                                    MenuSwitchButton(text = "Enable gyro look", switch = gyroEnabled && gyroAvailable, enabled = gyroAvailable) { gyroEnabled = it; prefs.edit().putBoolean("gyro_enabled", it).apply() }
                                    MenuHint(if (gyroAvailable) "Move the phone to look while the game has captured the pointer." else "This device has no gyroscope sensor.")
                                    MenuSliderLayout(title = "Gyro sensitivity", value = gyroSensitivity, valueRange = 0.2f..3f, enabled = gyroAvailable, format = { "%.1f×".format(it) }) {
                                        gyroSensitivity = it; prefs.edit().putFloat("gyro_sensitivity", it).apply()
                                    }
                                }
                            }
                        }
                    },
                    rightMenuTitle = { Text("Game menu", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.titleMedium) },
                    rightMenuContent = {
                        MenuPage(Modifier.weight(1f)) {
                            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
                            val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            MenuSectionLabel("Device")
                            MenuSliderLayout(title = "Media volume", value = volume, valueRange = 0f..maxVolume.toFloat(), steps = (maxVolume - 1).coerceAtLeast(0), format = { "${it.toInt()}/$maxVolume" }) {
                                volume = it; audio.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0)
                            }
                            MenuSliderLayout(title = "Screen brightness", value = if (brightness < 0f) 0.5f else brightness, valueRange = 0.05f..1f, format = { if (brightness < 0f) "system" else "${(brightness * 100).toInt()}%" }) { value ->
                                brightness = value
                                (context as? Activity)?.window?.let { w ->
                                    val a = w.attributes; a.screenBrightness = value; w.attributes = a
                                }
                                prefs.edit().putFloat("screen_brightness", value).apply()
                            }
                            MenuTextButton(text = "Use system brightness") {
                                brightness = -1f
                                (context as? Activity)?.window?.let { w ->
                                    val a = w.attributes; a.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; w.attributes = a
                                }
                                prefs.edit().putFloat("screen_brightness", -1f).apply()
                            }
                            MenuSwitchButton(text = "Keep screen awake", switch = keepAwake) { keepAwake = it; prefs.edit().putBoolean("keep_awake", it).apply(); setKeepAwake(it) }
                            MenuSwitchButton(text = "Request maximum refresh rate", switch = maxRefresh) { maxRefresh = it; prefs.edit().putBoolean("max_refresh", it).apply(); setMaxRefresh(it) }
                            MenuSectionLabel("Overlay")
                            MenuSwitchButton(text = "Show FPS", switch = showFps) { showFps = it; prefs.edit().putBoolean("show_fps", it).apply() }
                            MenuListLayout(title = "FPS display", items = listOf(0, 1), currentItem = fpsMode, getItemText = { if (it == 0) "Number" else "Chart" }) {
                                fpsMode = it; prefs.edit().putInt("fps_mode", it).apply()
                            }
                            MenuSwitchButton(text = "Show process memory", switch = showMemory) { showMemory = it; prefs.edit().putBoolean("show_memory", it).apply() }
                            MenuSectionLabel("Session")
                            MenuTextButton(text = "Close game") { (context as? HorizonGameActivity)?.closeGame() }
                        }
                    },
                )
            }
            padPick?.let { slot ->
                KeyPickerDialog(title = "Bind ${slot.replace('_', ' ')}", onPick = { id ->
                    padMappings[slot] = id; prefs.edit().putString("pad_$slot", id).apply(); padPick = null
                }, onDismiss = { padPick = null })
            }
        }
    }
    }
}
