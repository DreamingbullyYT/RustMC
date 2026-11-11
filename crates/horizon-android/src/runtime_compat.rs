//! Android-owned runtime settings the launcher hands to the game.
//!
//! The old fork put these in `horizon_client::android_runtime`. Upstream keeps that module to
//! input state only, so the launcher-side knobs live here and forward to upstream's own
//! mechanisms where it has one: the renderer choice goes through `options.txt`
//! (`settings.horizon.api`, written by `catalog`), the worker limit through
//! `HORIZON_WORKER_THREADS`, the texture budget through `sprites::set_texture_budget`, and the
//! clipboard through `horizon_client_input`'s Android hook.

use std::{
    path::{Path, PathBuf},
    sync::{
        Mutex, OnceLock,
        atomic::{AtomicBool, Ordering},
    },
};

static CACHE_DIR: OnceLock<PathBuf> = OnceLock::new();
static OPEN_GL: AtomicBool = AtomicBool::new(false);
static PREFER_VULKAN: AtomicBool = AtomicBool::new(true);
static GPU_BATCHING: AtomicBool = AtomicBool::new(false);
static LAUNCHER_RETURN: AtomicBool = AtomicBool::new(false);

pub fn configure_cache_dir(directory: PathBuf) {
    let _ = CACHE_DIR.set(directory);
}

pub fn cache_dir() -> Option<&'static Path> {
    CACHE_DIR.get().map(PathBuf::as_path)
}

/// How the Android clipboard is read and written.
pub struct ClipboardHooks {
    pub get: fn() -> Option<String>,
    pub set: fn(&str),
}

pub fn install_clipboard(hooks: ClipboardHooks) {
    horizon_client_input::clipboard::install_android_hooks(hooks.get, hooks.set);
}

/// Records the launcher's renderer choice. The game reads the same choice from `options.txt`.
pub fn configure_graphics(open_gl: bool) {
    OPEN_GL.store(open_gl, Ordering::Relaxed);
}

pub fn set_prefer_vulkan(prefer: bool) {
    PREFER_VULKAN.store(prefer, Ordering::Relaxed);
}

pub fn configure_gpu(batching: bool) {
    GPU_BATCHING.store(batching, Ordering::Relaxed);
}

pub fn open_gl() -> bool {
    OPEN_GL.load(Ordering::Relaxed)
}

pub fn gpu_batching() -> bool {
    GPU_BATCHING.load(Ordering::Relaxed)
}

/// `0` leaves the worker count to the engine. Takes effect for pools started afterwards:
/// the engine reads `HORIZON_WORKER_THREADS` once.
pub fn configure_worker_limit(limit: u32) {
    if limit > 0 {
        // SAFETY: called from the launcher's event thread before the game's worker pools start.
        unsafe { std::env::set_var("HORIZON_WORKER_THREADS", limit.to_string()) };
    }
}

/// Block texture budget in MiB; `0` is the engine default.
pub fn configure_cache_budget(mib: u32) {
    horizon_client_render::sprites::set_texture_budget(mib);
}

/// The game asks to go back to the launcher instead of ending the process.
pub fn request_launcher_return() {
    LAUNCHER_RETURN.store(true, Ordering::Relaxed);
}

pub fn take_launcher_return() -> bool {
    LAUNCHER_RETURN.swap(false, Ordering::Relaxed)
}

/// Detects a Vulkan start that never reached a first frame, so the next launch can fall back
/// to the compatibility renderer.
pub mod graphics_startup {
    use super::*;

    static HOME: Mutex<Option<PathBuf>> = Mutex::new(None);
    const MARKER: &str = "graphics-start.pending";

    fn marker() -> Option<PathBuf> {
        HOME.lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
            .map(|home| home.join(MARKER))
    }

    pub fn configure(home: PathBuf) {
        *HOME.lock().unwrap_or_else(|e| e.into_inner()) = Some(home);
    }

    /// A graphics start begins; the marker stays until a frame is shown.
    pub fn begin() {
        if let Some(path) = marker()
            && let Err(error) = std::fs::write(path, b"1")
        {
            log::warn!("Cannot record the graphics start: {error}");
        }
    }

    /// The first frame was shown: the start worked.
    pub fn succeeded() {
        if let Some(path) = marker() {
            let _ = std::fs::remove_file(path);
        }
    }

    /// Whether the previous start was interrupted. Clears the marker.
    pub fn recover(home: &Path) -> bool {
        let path = home.join(MARKER);
        let interrupted = path.exists();
        let _ = std::fs::remove_file(path);
        interrupted
    }
}
