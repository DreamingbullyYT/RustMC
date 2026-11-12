//! Mojang-verified resource installation for the Android launcher.

pub use horizon_core;
pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;

pub mod android_resources;
pub mod official_install;
pub mod resource_install;

/// File helpers whose errors say which path failed (a bare "No such file or directory" does not).
pub(crate) fn open_file(path: &std::path::Path) -> Result<std::fs::File> {
    std::fs::File::open(path).map_err(|e| format!("open {}: {e}", path.display()).into())
}
pub(crate) fn create_file(path: &std::path::Path) -> Result<std::fs::File> {
    std::fs::File::create(path).map_err(|e| format!("create {}: {e}", path.display()).into())
}
pub(crate) fn copy_file(from: &std::path::Path, to: &std::path::Path) -> Result<u64> {
    std::fs::copy(from, to)
        .map_err(|e| format!("copy {} -> {}: {e}", from.display(), to.display()).into())
}
