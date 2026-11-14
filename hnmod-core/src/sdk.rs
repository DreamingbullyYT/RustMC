//! Reader for the signed-release SDK index shape used by arismoko/horizon.
//!
//! This module validates identity and target coverage only. It deliberately does not claim to
//! authenticate a download: signature verification and SDK acquisition must happen before an
//! index or any of its assets is trusted or installed.
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::{collections::BTreeMap, path::Path};

#[derive(Clone, Debug, Deserialize)]
pub struct SdkIndex {
    pub schema: u32,
    pub build: Build,
    pub toolchain: Toolchain,
    pub mixins: Mixins,
    pub assets: BTreeMap<String, Asset>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Build {
    pub commit: String,
    pub game: String,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Toolchain {
    pub rust: String,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Mixins {
    pub rev: String,
    pub patch: String,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Asset {
    pub sha256: String,
    pub size: u64,
    pub kind: String,
    pub codec: String,
    pub target: Option<String>,
    pub side: Option<String>,
}

#[derive(Clone, Debug)]
pub struct VerifiedIdentity {
    pub index: SdkIndex,
    pub index_sha256: String,
}

impl VerifiedIdentity {
    /// Read an SDK index and calculate its content identity. This is not a signature check.
    pub fn read(path: &Path) -> Result<Self, String> {
        let bytes = std::fs::read(path).map_err(|e| format!("{}: {e}", path.display()))?;
        let index: SdkIndex = serde_json::from_slice(&bytes)
            .map_err(|e| format!("{}: invalid sdk.json: {e}", path.display()))?;
        let identity = Self {
            index,
            index_sha256: format!("{:x}", Sha256::digest(&bytes)),
        };
        identity.validate()?;
        Ok(identity)
    }

    pub fn validate(&self) -> Result<(), String> {
        let index = &self.index;
        if index.schema != 1 {
            return Err(format!("unsupported SDK schema {}; expected 1", index.schema));
        }
        if !revision(&index.build.commit) || !revision(&index.mixins.rev) {
            return Err("SDK game and mixins revisions must be full lowercase commit IDs".into());
        }
        if index.build.game.trim().is_empty() || index.toolchain.rust.trim().is_empty() {
            return Err("SDK game version and Rust toolchain identity are required".into());
        }
        if !hex(&index.mixins.patch, 64) {
            return Err("SDK mixins patch identity must be a 64-character SHA-256".into());
        }
        for (name, asset) in &index.assets {
            if name.contains('/') || name.contains('\\') || name.is_empty() {
                return Err(format!("SDK asset name is not a plain file name: {name:?}"));
            }
            if !hex(&asset.sha256, 64) || asset.size == 0 {
                return Err(format!("SDK asset {name} has an invalid SHA-256 or size"));
            }
        }
        for name in ["surface.json", "build-data.tar.xz", "api-source.tar.xz"] {
            if !index.assets.contains_key(name) {
                return Err(format!("SDK index is missing required asset {name}"));
            }
        }
        Ok(())
    }

    /// Check that this SDK really describes the selected HMOD pins and contains the slices
    /// required for this target. Android must be explicitly published; host SDKs are not reused.
    pub fn require_target(&self, target: &str, game: &str, horizon: &str, mixins: &str) -> Result<(), String> {
        self.validate()?;
        let index = &self.index;
        if index.build.game != game || index.build.commit != horizon || index.mixins.rev != mixins {
            return Err(format!(
                "HMOD pins game/Horizon/mixins {game}/{horizon}/{mixins} do not match SDK {}/{}/{}",
                index.build.game, index.build.commit, index.mixins.rev
            ));
        }
        for kind in ["runtime", "api", "engine"] {
            let name = format!("{kind}-{target}.tar.xz");
            let asset = index.assets.get(&name)
                .ok_or_else(|| format!("SDK has no {target} {kind} slice; this target is not published"))?;
            if asset.target.as_deref() != Some(target) || asset.codec != "xz" {
                return Err(format!("SDK asset {name} has inconsistent target or codec metadata"));
            }
        }
        Ok(())
    }
}

fn revision(value: &str) -> bool {
    value.len() == 40 && value.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn hex(value: &str, len: usize) -> bool {
    value.len() == len && value.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
