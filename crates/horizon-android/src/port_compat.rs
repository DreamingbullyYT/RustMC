//! Android-owned stand-in for the old `horizon_port_pack::android_port` module.
//!
//! Upstream `horizon-port-pack` has the desktop pipeline (`check`, `carry`, `pipeline`, ...)
//! but no on-device "port this jar / modpack / data pack" entry points. The launcher screens
//! still ask for them, so they are declared here and report that the feature is not
//! available yet. Nothing here touches the shared engine. Wiring the real pipeline in is
//! a separate task: it is not needed to launch and play.

use horizon_port_pack::check::Verdict;
use std::path::{Path, PathBuf};

pub const UNAVAILABLE: &str =
    "Java mod, modpack and data-pack porting is not available in this Android build yet.";

/// What the port screens show about one ported mod.
#[derive(Clone, Debug)]
pub struct AndroidPortResult {
    pub mod_id: String,
    pub name: String,
    pub version: String,
    pub loader: String,
    pub license: String,
    pub verdict: Verdict,
    pub summary: String,
    pub ports: Vec<String>,
    pub skipped: Vec<String>,
    pub depends: Vec<String>,
    pub namespaces: Vec<String>,
    pub mapped_hooks: Vec<String>,
    pub carry_dir: PathBuf,
    pub hmod_path: PathBuf,
    pub check_json_path: PathBuf,
    pub blocks_ported: usize,
    pub items_ported: usize,
    pub recipes_upgraded: usize,
    pub loot_tables_upgraded: usize,
    pub worldgen_ported: usize,
    pub classes_scanned: usize,
}

/// A Modrinth modpack's identity.
#[derive(Clone, Debug, Default)]
pub struct Modpack {
    pub name: String,
    pub minecraft: String,
    pub loader: String,
    pub loader_version: String,
}

/// One line of a modpack's port report.
#[derive(Clone, Debug)]
pub struct ModpackRow {
    pub name: String,
    pub tier: String,
    pub plain: String,
}

pub fn port_jar_natively(
    _jar: &Path,
    _profile: &Path,
    _progress: &dyn Fn(&str, f32),
) -> Result<AndroidPortResult, String> {
    Err(UNAVAILABLE.into())
}

/// The modpack, its per-file rows and how many mods were carried over.
pub fn port_modpack_natively(
    _pack: &Path,
    _profile: &Path,
    _progress: &dyn Fn(&str, f32),
) -> Result<(Modpack, Vec<ModpackRow>, usize), String> {
    Err(UNAVAILABLE.into())
}

/// The log lines of the upgrade.
pub fn upgrade_datapack_natively(_pack: &Path, _out: &Path) -> Result<Vec<String>, String> {
    Err(UNAVAILABLE.into())
}
