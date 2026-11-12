//! Plugin loader: a trait mod that loads `horizon-plugins/*.so` and forwards every hook to them.
//!
//! A plugin is a cdylib built with the same rustc and the same Horizon commit as the game. It exports
//! `horizon_plugin_abi() -> &'static str` (must equal `ABI`) and
//! `horizon_plugin_hooks() -> Box<dyn Hooks>`. Plugins are kept loaded for the process lifetime.
use horizon_client::mod_api::{Hooks, RenderHooks};
use std::path::{Path, PathBuf};

mod forward;

/// Plugins must report exactly this: the Horizon commit the loader was generated for.
pub const ABI: &str = "horizon=41f822b659789780f58d32c63ddfb6887ea1b8bf";
const DIR_ENV: &str = "HORIZON_PLUGIN_DIR";

struct Plugin {
    hooks: Box<dyn Hooks>,
    // Never unloaded: hook objects and vtables live in the library.
    _lib: libloading::Library,
}

pub struct Loader {
    list: Vec<Plugin>,
}

impl Loader {
    fn plugins(&self) -> impl Iterator<Item = &dyn Hooks> {
        self.list.iter().map(|p| &*p.hooks)
    }

    fn render_plugins(&self) -> impl Iterator<Item = &dyn RenderHooks> {
        self.list.iter().filter_map(|p| p.hooks.render())
    }
}

fn plugin_dir() -> PathBuf {
    std::env::var_os(DIR_ENV)
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("horizon-plugins"))
}

fn load_one(path: &Path) -> Result<Plugin, String> {
    // SAFETY: loading a native library runs its initializers; the user installed it.
    let lib = unsafe { libloading::Library::new(path) }.map_err(|e| e.to_string())?;
    let abi: libloading::Symbol<fn() -> &'static str> =
        unsafe { lib.get(b"horizon_plugin_abi") }.map_err(|e| format!("no horizon_plugin_abi: {e}"))?;
    let reported = abi();
    if reported != ABI {
        return Err(format!("built for `{reported}`, the game is `{ABI}`"));
    }
    let make: libloading::Symbol<fn() -> Box<dyn Hooks>> =
        unsafe { lib.get(b"horizon_plugin_hooks") }.map_err(|e| format!("no horizon_plugin_hooks: {e}"))?;
    let make = *make;
    let hooks = std::panic::catch_unwind(std::panic::AssertUnwindSafe(make))
        .map_err(|_| "horizon_plugin_hooks panicked".to_owned())?;
    Ok(Plugin { hooks, _lib: lib })
}

/// Loads every `*.so` of the plugin directory in file name order. A broken plugin is skipped with a warning.
pub fn load_dir(dir: &Path) -> Loader {
    let mut paths: Vec<PathBuf> = std::fs::read_dir(dir)
        .map(|rd| {
            rd.filter_map(|e| e.ok().map(|e| e.path()))
                .filter(|p| p.extension().is_some_and(|x| x == "so"))
                .collect()
        })
        .unwrap_or_default();
    paths.sort();
    let mut list = Vec::new();
    for path in paths {
        match load_one(&path) {
            Ok(p) => {
                log::info!("Plugin loaded: {}", path.display());
                list.push(p);
            }
            Err(e) => log::warn!("Plugin {} skipped: {e}", path.display()),
        }
    }
    Loader { list }
}

/// The trait mod's entry point: the launcher links it into the game.
pub fn hooks() -> Box<dyn Hooks> {
    Box::new(load_dir(&plugin_dir()))
}
