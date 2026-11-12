#!/usr/bin/env python3
"""Apply Android hooks to the pre-module-extraction Horizon game.rs layout.

Used for newer Nightly candidates with a monolithic game.rs. It intentionally
fails closed when upstream moves an integration point.
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
            ", found " + str(count) + ": " + repr(old[:100])
        )
    path.write_text(source.replace(old, new, 1), encoding="utf-8")

game = "crates/horizon-client/src/game.rs"
replace_once(game, "use std::{",
"""mod android;
pub use android::{
    android_cursor_grabbing, android_game_fps, android_hotbar_rect, android_ime_allowed,
    send_android_control_event, send_android_look, send_android_menu_touch,
    set_android_app, set_android_haptic_callback, set_android_ime_allowed, set_android_look_active,
};
use android::new_event_loop as android_new_event_loop;
use std::{""")
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
replace_once(game, "let pointer = self.captured || self.agent_pointer;",
             "let pointer = self.captured || self.agent_pointer || self.android_touch_look();")
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
"""                            self.fps_at = Instant::now();
                        }
                        self.frame_end = Instant::now();""",
"""                            self.fps_at = Instant::now();
                        }
                        self.android_publish_stats();
                        self.frame_end = Instant::now();""")
replace_once(game,
"""        if !ours {
            return;
        }
        follow_keymap(&mut event);""",
"""        if !ours {
            return;
        }
        match self.android_intercept(event_loop, id, origin, event) {
            Some(translated) => event = translated,
            None => return,
        }
        follow_keymap(&mut event);""")
replace_once(game,
"""impl ApplicationHandler for Game<'_> {
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.window.is_some() {
            return;
        }
        debug_keys::warm_debug_cpu();""",
"""impl ApplicationHandler for Game<'_> {
    fn suspended(&mut self, _event_loop: &ActiveEventLoop) {
        android::android_surface_suspended(&mut self.renderer);
    }
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.window.is_some() {
            android::android_surface_resumed(&mut self.renderer, &self.window);
            return;
        }
        debug_keys::warm_debug_cpu();""")
replace_once(game,
"""    fn about_to_wait(&mut self, event_loop: &ActiveEventLoop) {
        self.idle(event_loop);
    }""",
"""    fn about_to_wait(&mut self, event_loop: &ActiveEventLoop) {
        #[cfg(target_os = "android")]
        self.android_poll_input(event_loop);
        self.idle(event_loop);
    }""")
replace_once(game, "    let result = EventLoop::new()?.run_app(&mut split);",
             "    let result = android_new_event_loop()?.run_app(&mut split);")
replace_once(game,
"""        if self.window.is_some() {
            return;
        }
        // The Window constructor: created fullscreen when options.txt or --fullscreen says so.
        let fullscreen =
            self.settings.fullscreen || self.options.as_ref().is_some_and(|o| o.fullscreen);""",
"""        if self.window.is_some() {
            android::android_surface_resumed(&mut self.renderer, &self.window);
            return;
        }
        // The Window constructor: created fullscreen when options.txt or --fullscreen says so.
        let fullscreen =
            self.settings.fullscreen || self.options.as_ref().is_some_and(|o| o.fullscreen);""")
replace_once(game,
"""    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.window.is_some() {
            android::android_surface_resumed(&mut self.renderer, &self.window);
            return;
        }
        // The Window constructor: created fullscreen when options.txt or --fullscreen says so.
        let fullscreen =
            self.settings.fullscreen || self.options.as_ref().is_some_and(|o| o.fullscreen);""",
"""    fn suspended(&mut self, _event_loop: &ActiveEventLoop) {
        android::android_surface_suspended(&mut self.renderer);
    }
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.window.is_some() {
            android::android_surface_resumed(&mut self.renderer, &self.window);
            return;
        }
        // The Window constructor: created fullscreen when options.txt or --fullscreen says so.
        let fullscreen =
            self.settings.fullscreen || self.options.as_ref().is_some_and(|o| o.fullscreen);""")
replace_once(game,
"""impl ApplicationHandler for App {
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {""",
"""impl ApplicationHandler for App {
    fn suspended(&mut self, event_loop: &ActiveEventLoop) {
        match self {
            App::Loading(loading) => android::android_surface_suspended(&mut loading.renderer),
            App::Ready(game) => game.suspended(event_loop),
        }
    }
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {""")
replace_once(game, "    let result = EventLoop::new()?.run_app(&mut app);",
             "    let result = android_new_event_loop()?.run_app(&mut app);")

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

print("Android integration applied to monolithic upstream game.rs.")
