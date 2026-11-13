#!/usr/bin/env python3
"""Apply the Android integration to Horizon's refactored game module layout.

The historical android.patch targets the pre-module-extraction game.rs. This
script deliberately uses unique, verified source anchors so Nightly refactors
fail closed instead of silently dropping Android input/lifecycle hooks.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()

def replace_once(relative, old, new):
    path = root / relative
    if not path.is_file():
        raise SystemExit("Android overlay: missing upstream file: " + relative)
    source = path.read_text(encoding="utf-8")
    if new in source and old not in source:
        return
    count = source.count(old)
    if count != 1:
        raise SystemExit(
            "Android overlay: expected one anchor in " + relative +
            ", found " + str(count) + ": " + repr(old[:90])
        )
    path.write_text(source.replace(old, new, 1), encoding="utf-8")

game = "crates/horizon-client/src/game.rs"
replace_once(game, "mod window_handler;\n",
             "mod window_handler;\nmod android;\n")
replace_once(game, "use event_loop::EventLoopCtl;\n",
"""pub use android::{
    android_cursor_grabbing, android_game_fps, android_hotbar_rect, android_ime_allowed,
    start_android_diagnostics, stop_android_diagnostics,
    send_android_control_event, send_android_look, send_android_menu_touch,
    set_android_app, set_android_haptic_callback, set_android_ime_allowed, set_android_look_active,
};
use android::new_event_loop as android_new_event_loop;
use event_loop::EventLoopCtl;
""")
replace_once(game,
"""    physical_keys: HashSet<KeyCode>,
    physical_buttons: HashSet<MouseButton>,
    drag_button: Option<MouseButton>,
""",
"""    physical_keys: HashSet<KeyCode>,
    physical_buttons: HashSet<MouseButton>,
    drag_button: Option<MouseButton>,
    android_touch: android::AndroidTouchState,
""")
replace_once(game, "player.auto_jump_enabled = self.settings.auto_jump;",
             "player.auto_jump_enabled = self.settings.auto_jump || self.android_touch.auto_jump;")
replace_once(game,
"""            physical_keys: HashSet::new(),
            physical_buttons: HashSet::new(),
            drag_button: None,
""",
"""            physical_keys: HashSet::new(),
            physical_buttons: HashSet::new(),
            drag_button: None,
            android_touch: android::AndroidTouchState::from_environment(),
""")
replace_once(game,
"""                            self.fps_frames = 0;
                            self.fps_at = Instant::now();
                        }
""",
"""                            self.fps_frames = 0;
                            self.fps_at = Instant::now();
                        }
                        self.android_publish_stats();
""")
# The ordinary launch moved from game.rs into game/resource_loader.rs (run_loading); accept either.
_launch_old = """    let mut app = App::Loading(Box::new(loading));
    let result = EventLoop::new()?.run_app(&mut app);
"""
_launch_new = """    let mut app = App::Loading(Box::new(loading));
    let result = android_new_event_loop()?.run_app(&mut app);
"""
_loader = "crates/horizon-client/src/game/resource_loader.rs"
replace_once(_loader if (root / game).read_text(encoding="utf-8").count(_launch_old) == 0 else game,
             _launch_old, _launch_new)

window = "crates/horizon-client/src/game/window_handler.rs"
# Anchored on follow_keymap alone: newer upstream puts the locks_out/held_focus checks between
# `if !ours { return; }` and it, and the Android intercept belongs right before the keymap either way.
replace_once(window,
"""        follow_keymap(&mut event);
""",
"""        match self.android_intercept(event_loop, id, origin, event) {
            Some(translated) => event = translated,
            None => return,
        }
        follow_keymap(&mut event);
""")
replace_once(window,
"""impl ApplicationHandler for Game<'_> {
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.window.is_some() {
            return;
        }
""",
"""impl ApplicationHandler for Game<'_> {
    fn suspended(&mut self, _event_loop: &ActiveEventLoop) {
        android::android_surface_suspended(&mut self.renderer);
    }
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.window.is_some() {
            android::android_surface_resumed(&mut self.renderer, &self.window);
            return;
        }
""")
replace_once(window,
"""    fn about_to_wait(&mut self, event_loop: &ActiveEventLoop) {
        self.idle(event_loop);
    }
""",
"""    fn about_to_wait(&mut self, event_loop: &ActiveEventLoop) {
        #[cfg(target_os = "android")]
        self.android_poll_input(event_loop);
        #[cfg(target_os = "android")]
        android::android_diag_event_loop();
        self.idle(event_loop);
    }
""")

pointer = "crates/horizon-client/src/game/pointer_capture.rs"
replace_once(pointer, "let pointer = self.captured || self.agent_pointer;",
             "let pointer = self.captured || self.agent_pointer || self.android_touch_look();")

loading = "crates/horizon-client/src/game/loading_screen.rs"
replace_once(loading,
             "let result = EventLoop::new()?.run_app(&mut split);",
             "let result = android_new_event_loop()?.run_app(&mut split);")
replace_once(loading,
"""    pub(super) fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.window.is_some() {
            return;
        }
""",
"""    pub(super) fn suspended(&mut self, _event_loop: &ActiveEventLoop) {
        android::android_surface_suspended(&mut self.renderer);
    }
    pub(super) fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.window.is_some() {
            android::android_surface_resumed(&mut self.renderer, &self.window);
            return;
        }
""")
replace_once(loading,
"""impl ApplicationHandler for App {
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
""",
"""impl ApplicationHandler for App {
    fn suspended(&mut self, event_loop: &ActiveEventLoop) {
        match self {
            App::Loading(loading) => loading.suspended(event_loop),
            App::Ready(game) => game.suspended(event_loop),
        }
    }
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
""")

# One frame in flight on Android, so touch input reaches the screen a frame sooner. Upstream moved the
# surface configuration out of gfx.rs into gfx/surface_setup.rs, so this is anchored, not patched.
_surface_setup = "crates/horizon-client-render/src/gfx/surface_setup.rs"
if (root / _surface_setup).is_file():
    replace_once(_surface_setup,
"""        .ok_or("surface has no supported configuration")?;
    config.format = surface_format(&caps.formats, config.format);""",
"""        .ok_or("surface has no supported configuration")?;
    // Android: one frame in flight, so touch input reaches the screen a frame sooner.
    #[cfg(target_os = "android")]
    {
        config.desired_maximum_frame_latency = 1;
    }
    config.format = surface_format(&caps.formats, config.format);""")
else:
    replace_once("crates/horizon-client-render/src/gfx.rs",
"""                .ok_or("surface has no supported configuration")?;
            let caps = surface.get_capabilities(&adapter);""",
"""                .ok_or("surface has no supported configuration")?;
            // Android: one frame in flight, so touch input reaches the screen a frame sooner.
            #[cfg(target_os = "android")]
            {
                config.desired_maximum_frame_latency = 1;
            }
            let caps = surface.get_capabilities(&adapter);""")

# Worker threads run chunk/mesh generation with large stack arrays. Rust's default 2 MiB thread stack is
# enough on desktop but a stack overflow shows up on Android as a SIGSEGV inside memcpy on a
# "horizon_threads::jobs::work" thread right after the loading screen. Reserve a larger (lazily
# committed) stack for the pool workers.
replace_once("crates/horizon-threads/src/jobs.rs",
"""                .name(format!("{name}-{i}"))
                .spawn(move || {
                    // Ticks and frames come first.""",
"""                .name(format!("{name}-{i}"))
                .stack_size(32 << 20)
                .spawn(move || {
                    // Ticks and frames come first.""")

# The common patch handles IME, clipboard, render surface latency/drop, Split
# forwarding, the no-resources loop, the workspace crates, and launcher cfg.
print("Android integration applied to the refactored upstream game modules.")
