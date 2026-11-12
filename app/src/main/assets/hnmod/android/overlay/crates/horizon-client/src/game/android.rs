// Android input bridge and touch state, kept out of game.rs so upstream merges stay clean.
// Declared as `mod android;`; it can still access parent-private game state from this child module.
use super::*;

// Own names for what upstream's imports do not have, so a later upstream import of the same names cannot clash.
#[cfg(target_os = "android")]
use std::sync::{Mutex as AndroidMutex, OnceLock as AndroidOnceLock, atomic::AtomicBool as AndroidAtomicBool};
use winit::event::TouchPhase as AndroidTouchPhase;

#[cfg(target_os = "android")]
static ANDROID_CONTROL_INPUTS: AndroidOnceLock<AndroidMutex<Vec<(String, bool)>>> = AndroidOnceLock::new();
#[cfg(target_os = "android")]
static ANDROID_LOOK_DELTA: AndroidOnceLock<AndroidMutex<(f64, f64)>> = AndroidOnceLock::new();
#[cfg(target_os = "android")]
static ANDROID_LOOK_ACTIVE: AndroidAtomicBool = AndroidAtomicBool::new(false);
#[cfg(target_os = "android")]
static ANDROID_GAME_ACTIVE: AndroidAtomicBool = AndroidAtomicBool::new(false);
#[cfg(target_os = "android")]
static ANDROID_GAME_FPS: AtomicU32 = AtomicU32::new(0);
#[cfg(target_os = "android")]
static ANDROID_IME_ALLOWED: AndroidAtomicBool = AndroidAtomicBool::new(true);
/// Hotbar rectangle in physical pixels (f32 bits): left, top, slot width, height.
#[cfg(target_os = "android")]
static ANDROID_HOTBAR: [AtomicU32; 4] = [
    AtomicU32::new(0),
    AtomicU32::new(0),
    AtomicU32::new(0),
    AtomicU32::new(0),
];
#[cfg(target_os = "android")]
static ANDROID_MENU_TOUCHES: AndroidOnceLock<AndroidMutex<Vec<(u64, f64, f64, u8)>>> = AndroidOnceLock::new();

/// Queue a Zalith layout key or mouse button for the game-loop thread.
#[cfg(target_os = "android")]
pub fn send_android_control_event(key: String, pressed: bool) {
    ANDROID_CONTROL_INPUTS.get_or_init(Default::default).lock().unwrap().push((key, pressed));
}

/// Accumulate relative touch-look motion from the Zalith overlay.
#[cfg(target_os = "android")]
pub fn send_android_look(dx: f64, dy: f64) {
    let mut delta = ANDROID_LOOK_DELTA.get_or_init(Default::default).lock().unwrap();
    delta.0 += dx;
    delta.1 += dy;
}

#[cfg(target_os = "android")]
pub fn set_android_look_active(active: bool) {
    ANDROID_LOOK_ACTIVE.store(active, Ordering::Relaxed);
}

#[cfg(target_os = "android")]
pub fn android_cursor_grabbing() -> bool {
    ANDROID_GAME_ACTIVE.load(Ordering::Relaxed)
}

#[cfg(target_os = "android")]
pub fn android_game_fps() -> u32 {
    ANDROID_GAME_FPS.load(Ordering::Relaxed)
}

/// `[left, top, slot_width, height]` of the on-screen hotbar in physical pixels, or zeros before the first frame.
#[cfg(target_os = "android")]
pub fn android_hotbar_rect() -> [f32; 4] {
    std::array::from_fn(|i| f32::from_bits(ANDROID_HOTBAR[i].load(Ordering::Relaxed)))
}

#[cfg(target_os = "android")]
pub fn set_android_ime_allowed(allowed: bool) {
    ANDROID_IME_ALLOWED.store(allowed, Ordering::Relaxed);
}

#[cfg(target_os = "android")]
pub fn android_ime_allowed() -> bool {
    ANDROID_IME_ALLOWED.load(Ordering::Relaxed)
}

#[cfg(target_os = "android")]
pub fn send_android_menu_touch(id: u64, x: f64, y: f64, phase: u8) {
    ANDROID_MENU_TOUCHES.get_or_init(Default::default).lock().unwrap().push((id, x, y, phase));
}
#[cfg(target_os = "android")]
thread_local! {
    static ANDROID_APP: RefCell<Option<winit::platform::android::activity::AndroidApp>> = const { RefCell::new(None) };
    static ANDROID_HAPTIC: RefCell<Option<fn()>> = const { RefCell::new(None) };
}

/// Supplies NativeActivity state to Winit before entering the shared game loop.
#[cfg(target_os = "android")]
pub fn set_android_app(app: winit::platform::android::activity::AndroidApp) {
    ANDROID_APP.with(|slot| *slot.borrow_mut() = Some(app));
}

#[cfg(target_os = "android")]
pub fn set_android_haptic_callback(callback: fn()) {
    ANDROID_HAPTIC.with(|slot| *slot.borrow_mut() = Some(callback));
}

fn android_haptic_feedback() {
    #[cfg(target_os = "android")]
    ANDROID_HAPTIC.with(|slot| {
        if let Some(callback) = *slot.borrow() {
            callback();
        }
    });
}

#[derive(Clone, Copy, Debug)]
enum AndroidTouchRole {
    Joystick { origin: (f64, f64) },
    Look,
    Key(KeyCode),
    Mouse(MouseButton),
    QuickSettings,
    QuickHud,
    QuickAutoJump,
    QuickSensitivity(f32),
    QuickClose,
}

#[derive(Clone, Debug, Default, serde::Deserialize)]
struct AndroidTouchButton {
    id: String,
    label: String,
    output: String,
}

#[derive(Clone, Copy, Debug, Default)]
struct AndroidMenuTouch {
    start: (f64, f64),
    last: (f64, f64),
    moved: bool,
}

#[derive(Default)]
pub(super) struct AndroidTouchState {
    enabled: bool,
    haptics: bool,
    hold_to_sprint: bool,
    opacity: f32,
    button_size: f32,
    look_sensitivity: f32,
    left_handed: bool,
    pub(super) auto_jump: bool,
    layout: String,
    positions: HashMap<String, (f32, f32)>,
    bindings: HashMap<String, String>,
    custom_buttons: Vec<AndroidTouchButton>,
    joystick_outputs: HashSet<String>,
    active: HashMap<u64, AndroidTouchRole>,
    last: HashMap<u64, (f64, f64)>,
    menu_touches: HashMap<u64, AndroidMenuTouch>,
    pending_menu_release: Option<DeviceId>,
    quick_settings_open: bool,
    hud_visible: bool,
}

impl AndroidTouchState {
    fn position(&self, id: &str, default: (f32, f32)) -> (f32, f32) {
        if let Some(point) = self.positions.get(id).copied() {
            return point;
        }
        (
            if self.left_handed {
                1.0 - default.0
            } else {
                default.0
            },
            default.1,
        )
    }

    fn output(&self, id: &str, fallback: AndroidTouchRole) -> AndroidTouchRole {
        let Some(output) = self.bindings.get(id).map(String::as_str) else {
            return fallback;
        };
        android_touch_output(output).unwrap_or(fallback)
    }

    pub(super) fn from_environment() -> Self {
        let Some(raw) = std::env::var_os("HORIZON_ANDROID_TOUCH_CONTROLS") else {
            // A usable default layout must exist even before the player opens touch settings.
            return Self {
                enabled: true,
                haptics: true,
                opacity: 0.72,
                button_size: 1.0,
                look_sensitivity: 1.0,
                layout: "classic".to_owned(),
                hud_visible: true,
                ..Self::default()
            };
        };
        let value: serde_json::Value =
            serde_json::from_str(&raw.to_string_lossy()).unwrap_or_default();
        let number = |key: &str, default: f32| {
            value
                .get(key)
                .and_then(serde_json::Value::as_f64)
                .filter(|v| v.is_finite())
                .map(|v| v as f32)
                .unwrap_or(default)
        };
        let mut positions = HashMap::new();
        if let Some(object) = value
            .get("positions")
            .and_then(serde_json::Value::as_object)
        {
            for (key, point) in object {
                if let Some(values) = point.as_array().filter(|v| v.len() >= 2) {
                    if let (Some(x), Some(y)) = (values[0].as_f64(), values[1].as_f64()) {
                        if x.is_finite() && y.is_finite() {
                            positions.insert(key.clone(), (x as f32, y as f32));
                        }
                    }
                }
            }
        }
        let bindings = value
            .get("bindings")
            .and_then(serde_json::Value::as_object)
            .map(|map| {
                map.iter()
                    .filter_map(|(key, value)| value.as_str().map(|v| (key.clone(), v.to_owned())))
                    .collect()
            })
            .unwrap_or_default();
        let custom_buttons: Vec<AndroidTouchButton> = value
            .get("custom_buttons")
            .and_then(|value| serde_json::from_value(value.clone()).ok())
            .unwrap_or_default();
        Self {
            enabled: value
                .get("enabled")
                .and_then(serde_json::Value::as_bool)
                .unwrap_or(true),
            haptics: value
                .get("haptics")
                .and_then(serde_json::Value::as_bool)
                .unwrap_or(true),
            hold_to_sprint: value
                .get("hold_to_sprint")
                .and_then(serde_json::Value::as_bool)
                .unwrap_or(false),
            opacity: number("opacity", 0.72).clamp(0.3, 1.0),
            button_size: number("button_size", 1.0).clamp(0.7, 1.5),
            look_sensitivity: number("look_sensitivity", 1.0).clamp(0.25, 2.0),
            left_handed: value
                .get("left_handed")
                .and_then(serde_json::Value::as_bool)
                .unwrap_or(false),
            auto_jump: value
                .get("auto_jump")
                .and_then(serde_json::Value::as_bool)
                .unwrap_or(false),
            layout: value
                .get("layout")
                .and_then(serde_json::Value::as_str)
                .unwrap_or("classic")
                .to_owned(),
            positions,
            bindings,
            custom_buttons,
            hud_visible: true,
            ..Self::default()
        }
    }
}

fn android_touch_output(output: &str) -> Option<AndroidTouchRole> {
    let key = match output {
        "Space" => KeyCode::Space,
        "ShiftLeft" => KeyCode::ShiftLeft,
        "ControlLeft" => KeyCode::ControlLeft,
        "AltLeft" => KeyCode::AltLeft,
        "Tab" => KeyCode::Tab,
        "Escape" => KeyCode::Escape,
        "Enter" => KeyCode::Enter,
        "Backspace" => KeyCode::Backspace,
        "ArrowUp" => KeyCode::ArrowUp,
        "ArrowDown" => KeyCode::ArrowDown,
        "ArrowLeft" => KeyCode::ArrowLeft,
        "ArrowRight" => KeyCode::ArrowRight,
        "Home" => KeyCode::Home,
        "End" => KeyCode::End,
        "PageUp" => KeyCode::PageUp,
        "PageDown" => KeyCode::PageDown,
        "A" => KeyCode::KeyA,
        "B" => KeyCode::KeyB,
        "C" => KeyCode::KeyC,
        "D" => KeyCode::KeyD,
        "E" => KeyCode::KeyE,
        "F" => KeyCode::KeyF,
        "G" => KeyCode::KeyG,
        "H" => KeyCode::KeyH,
        "I" => KeyCode::KeyI,
        "J" => KeyCode::KeyJ,
        "K" => KeyCode::KeyK,
        "L" => KeyCode::KeyL,
        "M" => KeyCode::KeyM,
        "N" => KeyCode::KeyN,
        "O" => KeyCode::KeyO,
        "P" => KeyCode::KeyP,
        "Q" => KeyCode::KeyQ,
        "R" => KeyCode::KeyR,
        "S" => KeyCode::KeyS,
        "T" => KeyCode::KeyT,
        "U" => KeyCode::KeyU,
        "V" => KeyCode::KeyV,
        "W" => KeyCode::KeyW,
        "X" => KeyCode::KeyX,
        "Y" => KeyCode::KeyY,
        "Z" => KeyCode::KeyZ,
        "0" => KeyCode::Digit0,
        "1" => KeyCode::Digit1,
        "2" => KeyCode::Digit2,
        "3" => KeyCode::Digit3,
        "4" => KeyCode::Digit4,
        "5" => KeyCode::Digit5,
        "6" => KeyCode::Digit6,
        "7" => KeyCode::Digit7,
        "8" => KeyCode::Digit8,
        "9" => KeyCode::Digit9,
        "F1" => KeyCode::F1,
        "F2" => KeyCode::F2,
        "F3" => KeyCode::F3,
        "F4" => KeyCode::F4,
        "F5" => KeyCode::F5,
        "F6" => KeyCode::F6,
        "F7" => KeyCode::F7,
        "F8" => KeyCode::F8,
        "F9" => KeyCode::F9,
        "F10" => KeyCode::F10,
        "F11" => KeyCode::F11,
        "F12" => KeyCode::F12,
        _ => {
            return Some(match output {
                "MouseLeft" => AndroidTouchRole::Mouse(MouseButton::Left),
                "MouseRight" => AndroidTouchRole::Mouse(MouseButton::Right),
                "MouseMiddle" => AndroidTouchRole::Mouse(MouseButton::Middle),
                _ => return None,
            });
        }
    };
    Some(AndroidTouchRole::Key(key))
}

impl Game<'_> {
    fn touch_control_centres(&self, width: f32, height: f32) -> [(f32, f32, KeyCode); 3] {
        let (jx, jy) = self.android_touch.position("jump", (0.90, 0.73));
        let (sx, sy) = self.android_touch.position("sneak", (0.68, 0.87));
        let (rx, ry) = self.android_touch.position("sprint", (0.51, 0.71));
        [
            (jx * width, jy * height, KeyCode::Space),
            (sx * width, sy * height, KeyCode::ShiftLeft),
            (rx * width, ry * height, KeyCode::ControlLeft),
        ]
    }

    fn set_touch_output(
        &mut self,
        id: &str,
        fallback: AndroidTouchRole,
        pressed: bool,
    ) -> Result<()> {
        let role = self.android_touch.output(id, fallback);
        match role {
            AndroidTouchRole::Key(key) => self.mapped_key(key, pressed, false, key, Source::Pad),
            AndroidTouchRole::Mouse(button) => self.play_mouse(button, pressed),
            _ => Ok(()),
        }
    }

    fn touch_key(&mut self, id: &str, key: KeyCode, pressed: bool) -> Result<()> {
        if id == "sprint" && self.android_touch.hold_to_sprint && key == KeyCode::ControlLeft {
            let toggle = self.settings.toggle_sprint;
            self.settings.toggle_sprint = false;
            let result = self.mapped_key(key, pressed, false, key, Source::Pad);
            self.settings.toggle_sprint = toggle;
            result
        } else {
            self.mapped_key(key, pressed, false, key, Source::Pad)
        }
    }

    pub(super) fn draw_android_touch_controls(&self, batch: &mut HudBatch) {
        if !cfg!(target_os = "android")
            || self.screen != Screen::Playing
            || self.title.is_some()
            || self.player.is_none()
        {
            return;
        }
        let (w, h) = (batch.width, batch.height);
        let alpha = (self.android_touch.opacity * 145.0) as u8;
        let radius = 24.0 * self.android_touch.button_size;
        let white = &self.gui.atlas;
        if !self.android_touch.enabled || !self.android_touch.hud_visible {
            return;
        }
        let (move_x, move_y) = self.android_touch.position("move", (0.14, 0.77));
        let joystick_x = move_x * w;
        let joystick_y = move_y * h;
        batch.fill(
            white,
            joystick_x - radius * 1.8,
            joystick_y - radius * 1.8,
            radius * 3.6,
            radius * 3.6,
            rgba(25, 30, 38, alpha),
        );
        batch.fill(
            white,
            joystick_x - radius * 0.55,
            joystick_y - radius * 0.55,
            radius * 1.1,
            radius * 1.1,
            rgba(220, 230, 240, alpha),
        );
        let factor = match self.android_touch.layout.as_str() {
            "compact" => 0.82,
            "minimal" => 0.68,
            _ => 1.0,
        };
        let radius = radius * factor;
        let mut button = |x: f32, y: f32, label: &str, scale: f32| {
            let r = radius * scale;
            batch.fill(
                white,
                x - r,
                y - r,
                r * 2.0,
                r * 2.0,
                rgba(25, 30, 38, alpha),
            );
            let text_width = HudBatch::text_width(&self.gui.atlas, label);
            batch.text(
                &self.gui.atlas,
                label,
                x - text_width / 2.0,
                y - 4.0,
                rgba(255, 255, 255, 220),
                true,
            );
        };
        let (jx, jy) = self.android_touch.position("jump", (0.90, 0.73));
        let (sx, sy) = self.android_touch.position("sneak", (0.68, 0.87));
        let (rx, ry) = self.android_touch.position("sprint", (0.51, 0.71));
        let (ax, ay) = self.android_touch.position("attack", (0.78, 0.88));
        let (ux, uy) = self.android_touch.position("use", (0.92, 0.90));
        button(jx * w, jy * h, "Jump", 1.0);
        button(sx * w, sy * h, "Sneak", 1.0);
        button(rx * w, ry * h, "Run", 1.0);
        button(ax * w, ay * h, "Hit", 1.0);
        button(ux * w, uy * h, "Use", 1.0);
        for (index, control) in self.android_touch.custom_buttons.iter().enumerate() {
            let default = (
                0.48 + (index % 4) as f32 * 0.08,
                0.48 + (index / 4) as f32 * 0.12,
            );
            let (x, y) = self.android_touch.position(&control.id, default);
            button(x * w, y * h, &control.label, 0.9);
        }
    }

    fn android_touch_event(&mut self, touch: winit::event::Touch) -> Result<()> {
        let Some(renderer) = &self.renderer else {
            return Ok(());
        };
        let batch = HudBatch::new([renderer.config.width, renderer.config.height]);
        let (w, h) = (batch.width as f64, batch.height as f64);
        let scale = batch.scale as f64;
        let point = (touch.location.x / scale, touch.location.y / scale);
        match touch.phase {
            AndroidTouchPhase::Started => {
                let nx = point.0 / w;
                let ny = point.1 / h;
                let centers = self.touch_control_centres(w as f32, h as f32);
                let mut role = None;
                if role.is_none()
                    && (!self.android_touch.enabled || !self.android_touch.hud_visible)
                {
                    self.android_touch
                        .active
                        .insert(touch.id, AndroidTouchRole::Look);
                    self.android_touch.last.insert(touch.id, point);
                    return Ok(());
                }
                for (index, (x, y, key)) in centers.into_iter().enumerate() {
                    let dx = point.0 - f64::from(x);
                    let dy = point.1 - f64::from(y);
                    let radius = 30.0 * f64::from(self.android_touch.button_size);
                    if dx * dx + dy * dy <= radius * radius {
                        let id = ["jump", "sneak", "sprint"][index];
                        role = Some(self.android_touch.output(id, AndroidTouchRole::Key(key)));
                        break;
                    }
                }
                for (id, default, button) in [
                    ("attack", (0.78, 0.88), MouseButton::Left),
                    ("use", (0.92, 0.90), MouseButton::Right),
                ] {
                    let (x, y) = self.android_touch.position(id, default);
                    let dx = nx - f64::from(x);
                    let dy = ny - f64::from(y);
                    if dx * dx + dy * dy <= 0.0064 {
                        role = Some(
                            self.android_touch
                                .output(id, AndroidTouchRole::Mouse(button)),
                        );
                        break;
                    }
                }
                if role.is_none() {
                    for (index, control) in self.android_touch.custom_buttons.iter().enumerate() {
                        let default = (
                            0.48 + (index % 4) as f32 * 0.08,
                            0.48 + (index / 4) as f32 * 0.12,
                        );
                        let (x, y) = self.android_touch.position(&control.id, default);
                        let dx = nx - f64::from(x);
                        let dy = ny - f64::from(y);
                        if dx * dx + dy * dy <= 0.0064 {
                            role = Some(
                                self.android_touch.output(
                                    &control.id,
                                    android_touch_output(&control.output)
                                        .unwrap_or(AndroidTouchRole::Key(KeyCode::KeyE)),
                                ),
                            );
                            break;
                        }
                    }
                }
                let (mx, my) = self.android_touch.position("move", (0.14, 0.77));
                let joy_x = f64::from(mx);
                if role.is_none()
                    && ((nx - joy_x).powi(2) + (ny - f64::from(my)).powi(2) <= 0.035
                        || (nx < 0.31 && ny > 0.58 && !self.android_touch.left_handed)
                        || (nx > 0.69 && ny > 0.58 && self.android_touch.left_handed))
                {
                    role = Some(AndroidTouchRole::Joystick { origin: point });
                }
                let role = role.unwrap_or(AndroidTouchRole::Look);
                if self.android_touch.haptics
                    && !matches!(
                        role,
                        AndroidTouchRole::Look | AndroidTouchRole::Joystick { .. }
                    )
                {
                    android_haptic_feedback();
                }
                match role {
                    AndroidTouchRole::Key(key) if key == KeyCode::ControlLeft => {
                        self.touch_key("sprint", key, true)?
                    }
                    AndroidTouchRole::Key(key) => self.mapped_key(key, true, false, key, Source::Pad)?,
                    AndroidTouchRole::Mouse(button) => self.play_mouse(button, true)?,
                    AndroidTouchRole::QuickSettings => {
                        self.android_touch.quick_settings_open =
                            !self.android_touch.quick_settings_open
                    }
                    AndroidTouchRole::QuickHud => {
                        self.android_touch.hud_visible = !self.android_touch.hud_visible
                    }
                    AndroidTouchRole::QuickAutoJump => {
                        self.android_touch.auto_jump = !self.android_touch.auto_jump
                    }
                    AndroidTouchRole::QuickSensitivity(delta) => {
                        self.android_touch.look_sensitivity =
                            (self.android_touch.look_sensitivity + delta).clamp(0.25, 3.0)
                    }
                    AndroidTouchRole::QuickClose => self.android_touch.quick_settings_open = false,
                    _ => {}
                }
                self.android_touch.active.insert(touch.id, role);
                self.android_touch.last.insert(touch.id, point);
            }
            AndroidTouchPhase::Moved => {
                if let Some(role) = self.android_touch.active.get(&touch.id).copied() {
                    match role {
                        AndroidTouchRole::Look => {
                            if let Some(previous) = self.android_touch.last.get(&touch.id).copied()
                            {
                                self.mouse.add_motion(
                                    (point.0 - previous.0)
                                        * scale
                                        * f64::from(self.android_touch.look_sensitivity),
                                    (point.1 - previous.1)
                                        * scale
                                        * f64::from(self.android_touch.look_sensitivity),
                                );
                            }
                        }
                        AndroidTouchRole::Joystick { origin } => {
                            let dx = point.0 - origin.0;
                            let dy = point.1 - origin.1;
                            let threshold = 18.0 * f64::from(self.android_touch.button_size);
                            let mut directions = HashSet::new();
                            if dx.abs() > threshold {
                                directions.insert(if dx < 0.0 {
                                    "move_left"
                                } else {
                                    "move_right"
                                });
                            }
                            if dy.abs() > threshold {
                                directions.insert(if dy < 0.0 { "move_up" } else { "move_down" });
                            }
                            for (id, key) in [
                                ("move_up", KeyCode::KeyW),
                                ("move_down", KeyCode::KeyS),
                                ("move_left", KeyCode::KeyA),
                                ("move_right", KeyCode::KeyD),
                            ] {
                                let pressed = directions.contains(id);
                                if pressed && self.android_touch.joystick_outputs.insert(id.into())
                                {
                                    self.set_touch_output(id, AndroidTouchRole::Key(key), true)?;
                                } else if !pressed && self.android_touch.joystick_outputs.remove(id)
                                {
                                    self.set_touch_output(id, AndroidTouchRole::Key(key), false)?;
                                }
                            }
                        }
                        _ => {}
                    }
                    self.android_touch.last.insert(touch.id, point);
                }
            }
            AndroidTouchPhase::Ended | AndroidTouchPhase::Cancelled => {
                if let Some(role) = self.android_touch.active.remove(&touch.id) {
                    match role {
                        AndroidTouchRole::Key(key) if key == KeyCode::ControlLeft => {
                            self.touch_key("sprint", key, false)?
                        }
                        AndroidTouchRole::Key(key) => self.mapped_key(key, false, false, key, Source::Pad)?,
                        AndroidTouchRole::Mouse(button) => self.play_mouse(button, false)?,
                        AndroidTouchRole::Joystick { .. } => {
                            for (id, key) in [
                                ("move_up", KeyCode::KeyW),
                                ("move_down", KeyCode::KeyS),
                                ("move_left", KeyCode::KeyA),
                                ("move_right", KeyCode::KeyD),
                            ] {
                                if self.android_touch.joystick_outputs.remove(id) {
                                    self.set_touch_output(id, AndroidTouchRole::Key(key), false)?;
                                }
                            }
                        }
                        AndroidTouchRole::Look
                        | AndroidTouchRole::QuickSettings
                        | AndroidTouchRole::QuickHud
                        | AndroidTouchRole::QuickAutoJump
                        | AndroidTouchRole::QuickSensitivity(_)
                        | AndroidTouchRole::QuickClose => {}
                    }
                }
                self.android_touch.last.remove(&touch.id);
            }
        }
        self.last_input = Instant::now();
        Ok(())
    }

    #[cfg(target_os = "android")]
    pub(super) fn android_poll_input(&mut self, event_loop: &ActiveEventLoop) {
            ANDROID_GAME_ACTIVE.store(
                self.screen == Screen::Playing && self.title.is_none() && self.player.is_some(),
                Ordering::Relaxed,
            );
            if let Some(inputs) = ANDROID_CONTROL_INPUTS.get() {
                let queued = std::mem::take(&mut *inputs.lock().unwrap());
                for (name, pressed) in queued {
                    if let Some(button) = name.strip_prefix("GLFW_MOUSE_BUTTON_") {
                        let button = match button {
                            "LEFT" => Some(MouseButton::Left),
                            "RIGHT" => Some(MouseButton::Right),
                            "MIDDLE" => Some(MouseButton::Middle),
                            _ => None,
                        };
                        if let Some(button) = button {
                            if let Err(error) = self.play_mouse(button, pressed) { self.error = Some(error); }
                        }
                        continue;
                    }
                    let key = name.strip_prefix("GLFW_KEY_").unwrap_or(&name);
                    let key = match key {
                        "SPACE" => "Space", "LEFT_SHIFT" => "ShiftLeft", "RIGHT_SHIFT" => "ShiftRight",
                        "LEFT_CONTROL" => "ControlLeft", "RIGHT_CONTROL" => "ControlRight",
                        "LEFT_ALT" => "AltLeft", "RIGHT_ALT" => "AltRight", "ESCAPE" => "Escape",
                        "ENTER" => "Enter", "BACKSPACE" => "Backspace", "TAB" => "Tab",
                        "UP" => "ArrowUp", "DOWN" => "ArrowDown", "LEFT" => "ArrowLeft", "RIGHT" => "ArrowRight",
                        "PAGE_UP" => "PageUp", "PAGE_DOWN" => "PageDown",
                        other => other,
                    };
                    if let Some(AndroidTouchRole::Key(code)) = android_touch_output(key) {
                        if let Err(error) = self.mapped_key(code, pressed, false, code, Source::Pad) { self.error = Some(error); }
                    }
                }
            }
            if let Some(delta) = ANDROID_LOOK_DELTA.get() {
                let (dx, dy) = std::mem::replace(&mut *delta.lock().unwrap(), (0.0, 0.0));
                if dx != 0.0 || dy != 0.0 {
                    self.mouse.on_move(0.0, 0.0, dx, dy, true, true);
                    self.last_input = Instant::now();
                }
            }
            if !ANDROID_GAME_ACTIVE.load(Ordering::Relaxed)
                && let Some(touches) = ANDROID_MENU_TOUCHES.get()
                && let Some(window_id) = self.window.as_ref().map(|window| window.id())
            {
                let queued = std::mem::take(&mut *touches.lock().unwrap());
                for (id, x, y, phase) in queued {
                    let touch = winit::event::Touch {
                        device_id: DeviceId::dummy(),
                        id,
                        phase: match phase {
                            0 => AndroidTouchPhase::Started,
                            1 => AndroidTouchPhase::Moved,
                            3 => AndroidTouchPhase::Cancelled,
                            _ => AndroidTouchPhase::Ended,
                        },
                        location: winit::dpi::PhysicalPosition::new(x, y),
                        force: None,
                    };
                    self.window_event(event_loop, window_id, WindowEvent::Touch(touch));
                }
            }
    }

    /// Finish synthetic menu taps on the next native event, giving touch scroll gestures a clean
    /// press/release boundary.
    fn android_finish_menu_tap(&mut self, event_loop: &dyn EventLoopCtl, id: WindowId, origin: input_trace::InputOrigin) {
        if let Some(device_id) = self.android_touch.pending_menu_release.take() {
            self.handle_window_event(
                event_loop,
                id,
                WindowEvent::MouseInput {
                    device_id,
                    state: ElementState::Released,
                    button: MouseButton::Left,
                },
                origin,
            );
        }
    }

    /// A touch as the pointer event the menus understand, or `None` when virtual controls took it
    /// (or it only moved a gesture the menus ignore). In-world touches use independent virtual
    /// controls; menu touches use the standard pointer UI path.
    fn android_translate_touch(&mut self, event_loop: &dyn EventLoopCtl, touch: winit::event::Touch) -> Option<WindowEvent> {
        Some({
                if cfg!(target_os = "android")
                    && self.android_touch.enabled
                    && self.screen == Screen::Playing
                    && self.title.is_none()
                    && self.player.is_some()
                {
                    if let Err(error) = self.android_touch_event(touch) {
                        self.error = Some(error);
                        event_loop.exit();
                    }
                    return None;
                }
                let position = touch.location;
                let physical = (position.x, position.y);
                if let Some(r) = &self.renderer {
                    let batch = HudBatch::new([r.config.width, r.config.height]);
                    self.cursor = (
                        position.x as f32 / batch.scale,
                        position.y as f32 / batch.scale,
                    );
                }
                match touch.phase {
                    AndroidTouchPhase::Started => {
                        self.android_touch.menu_touches.insert(
                            touch.id,
                            AndroidMenuTouch {
                                start: physical,
                                last: physical,
                                moved: false,
                            },
                        );
                        WindowEvent::CursorMoved {
                            device_id: touch.device_id,
                            position,
                        }
                    }
                    AndroidTouchPhase::Moved => {
                        let Some(mut gesture) =
                            self.android_touch.menu_touches.get(&touch.id).copied()
                        else {
                            return None;
                        };
                        let dy = gesture.last.1 - physical.1;
                        // Accumulate the total displacement for tap-vs-drag,
                        // while sending every vertical delta to vanilla's
                        // existing wheel handlers (world list, inventory,
                        // dialogs, options and other scrollable menus).
                        gesture.moved |= (physical.0 - gesture.start.0).abs() > 14.0
                            || (physical.1 - gesture.start.1).abs() > 14.0;
                        gesture.last = physical;
                        self.android_touch.menu_touches.insert(touch.id, gesture);
                        if gesture.moved && dy.abs() > 0.0 {
                            WindowEvent::MouseWheel {
                                device_id: touch.device_id,
                                delta: MouseScrollDelta::PixelDelta(
                                    winit::dpi::PhysicalPosition::new(0.0, dy),
                                ),
                                phase: winit::event::TouchPhase::Moved,
                            }
                        } else {
                            WindowEvent::CursorMoved {
                                device_id: touch.device_id,
                                position,
                            }
                        }
                    }
                    AndroidTouchPhase::Ended => {
                        let gesture = self
                            .android_touch
                            .menu_touches
                            .remove(&touch.id)
                            .unwrap_or_default();
                        if gesture.moved {
                            WindowEvent::CursorMoved {
                                device_id: touch.device_id,
                                position,
                            }
                        } else {
                            self.android_touch.pending_menu_release = Some(touch.device_id);
                            WindowEvent::MouseInput {
                                device_id: touch.device_id,
                                state: ElementState::Pressed,
                                button: MouseButton::Left,
                            }
                        }
                    }
                    AndroidTouchPhase::Cancelled => {
                        self.android_touch.menu_touches.remove(&touch.id);
                        WindowEvent::CursorMoved {
                            device_id: touch.device_id,
                            position,
                        }
                    }
                }
        })
    }
}

/// Winit's event loop; on Android it is built on the NativeActivity `AndroidApp` handed over earlier.
pub(super) fn new_event_loop() -> Result<EventLoop<()>> {
    #[cfg(target_os = "android")]
    {
        use winit::platform::android::EventLoopBuilderExtAndroid;
        let mut builder = EventLoop::builder();
        ANDROID_APP.with(|slot| {
            if let Some(app) = slot.borrow().as_ref() {
                builder.with_android_app(app.clone());
            }
        });
        return Ok(builder.build()?);
    }
    #[cfg(not(target_os = "android"))]
    {
        Ok(EventLoop::new()?)
    }
}

/// Android destroys the native window on pause (a file picker, the home button): the surface made
/// for it is dead from here on.
pub(super) fn android_surface_suspended(renderer: &mut Option<Renderer>) {
    if cfg!(target_os = "android")
        && let Some(renderer) = renderer.as_mut()
    {
        renderer.drop_surface();
    }
}

/// Back from a pause on Android: the window has a new native surface.
pub(super) fn android_surface_resumed(renderer: &mut Option<Renderer>, window: &Option<Arc<Window>>) {
    if cfg!(target_os = "android") {
        if let Some(renderer) = renderer.as_mut()
            && let Err(error) = renderer.recreate_surface()
        {
            log::error!("Horizon Android: cannot recreate the surface: {error}");
        }
        if let Some(window) = window {
            window.request_redraw();
        }
    }
}

impl Game<'_> {
    /// Android touch has no native cursor to capture. A finger held in the look region supplies
    /// relative motion instead, so that active pointer counts as captured for its gesture.
    pub(super) fn android_touch_look(&self) -> bool {
        #[cfg(target_os = "android")]
        {
            ANDROID_LOOK_ACTIVE.load(Ordering::Relaxed)
                || self.android_touch.active.values().any(|role| matches!(role, AndroidTouchRole::Look))
        }
        #[cfg(not(target_os = "android"))]
        {
            false
        }
    }

    /// Ends a tap that opened a menu, then turns the finger's touch into the mouse event the game
    /// already understands. `None`: the event was used up here.
    pub(super) fn android_intercept(
        &mut self,
        event_loop: &dyn EventLoopCtl,
        id: WindowId,
        origin: input_trace::InputOrigin,
        event: WindowEvent,
    ) -> Option<WindowEvent> {
        self.android_finish_menu_tap(event_loop, id, origin);
        match event {
            WindowEvent::Touch(touch) => self.android_translate_touch(event_loop, touch),
            other => Some(other),
        }
    }

    /// Every frame: the hotbar's rectangle (once a second) and the frame rate, for the Kotlin side.
    pub(super) fn android_publish_stats(&self) {
        #[cfg(target_os = "android")]
        {
            ANDROID_GAME_FPS.store(self.fps.round().max(0.0) as u32, Ordering::Relaxed);
            if self.frames == 1 || self.frames % 60 == 0 {
                if let Some(renderer) = &self.renderer {
                    let size = [renderer.config.width, renderer.config.height];
                    let batch = HudBatch::new(size);
                    let (scale, w, h) = (batch.scale, batch.width as i32, batch.height as i32);
                    // `Hud.extractItemHotbar`: 182x22 sprite centred, slots 20 wide from +1.
                    let rect = [
                        (w / 2 - 91) as f32 * scale,
                        (h - 22) as f32 * scale,
                        20.0 * scale,
                        22.0 * scale,
                    ];
                    for (slot, v) in ANDROID_HOTBAR.iter().zip(rect) {
                        slot.store(v.to_bits(), Ordering::Relaxed);
                    }
                }
            }
        }
    }
}
