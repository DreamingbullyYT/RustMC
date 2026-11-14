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
    pub package_format: u32,
    pub toolchain: Toolchain,
    pub content_api: String,
    pub surface: Surface,
    pub inputs: serde_json::Value,
    pub mixins: Mixins,
    pub assets: BTreeMap<String, Asset>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Build {
    pub commit: String,
    pub game: String,
    pub date: String,
    #[serde(default)]
    pub nightly: Option<String>,
}

#[derive(Clone, Debug, Deserialize)]
pub struct Surface {
    pub hash: String,
    #[serde(default)]
    pub previous: Option<String>,
    #[serde(default)]
    pub changes: Vec<String>,
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

#[derive(Clone, Debug, Deserialize)]
struct InstallReceipt {
    signature_verified: bool,
    tag: String,
    index_sha256: String,
    target: String,
    game_commit: String,
    mixins_rev: String,
}

#[derive(Clone, Debug)]
pub struct VerifiedIdentity {
    pub index: SdkIndex,
    pub index_sha256: String,
}

impl VerifiedIdentity {
    /// Read an SDK directory only after the Android installer has verified its signed release.
    /// The receipt is written after Ed25519 verification and all asset checks pass.
    pub fn read(directory: &Path) -> Result<Self, String> {
        if !directory.is_dir() {
            return Err(format!("{} is not an installed SDK directory", directory.display()));
        }
        let index_path = directory.join("sdk.json");
        let bytes = std::fs::read(&index_path).map_err(|e| format!("{}: {e}", index_path.display()))?;
        let index: SdkIndex = serde_json::from_slice(&bytes)
            .map_err(|e| format!("{}: invalid sdk.json: {e}", index_path.display()))?;
        let index_sha256 = format!("{:x}", Sha256::digest(&bytes));
        let receipt_path = directory.join(".install-manifest.json");
        let receipt: InstallReceipt = serde_json::from_slice(
            &std::fs::read(&receipt_path).map_err(|e| format!("{}: {e}", receipt_path.display()))?
        ).map_err(|e| format!("{}: invalid install receipt: {e}", receipt_path.display()))?;
        if !receipt.signature_verified
            || receipt.tag != format!("sdk-{}", index.build.commit)
            || receipt.index_sha256 != index_sha256
            || receipt.game_commit != index.build.commit
            || receipt.mixins_rev != index.mixins.rev
            || receipt.target != "aarch64-linux-android"
        {
            return Err("SDK install receipt is missing, unsigned or does not match sdk.json".into());
        }
        let identity = Self { index, index_sha256 };
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
        if index.build.game.trim().is_empty() || index.toolchain.rust.trim().is_empty()
            || index.content_api.trim().is_empty() || index.surface.hash.trim().is_empty()
            || index.package_format == 0 {
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
        for (name, kind, codec) in [
            ("surface.json", "surface", "none"),
            ("build-data.tar.xz", "build-data", "xz"),
            ("api-source.tar.xz", "api-source", "xz"),
        ] {
            let asset = index.assets.get(name)
                .ok_or_else(|| format!("SDK index is missing required asset {name}"))?;
            if asset.kind != kind || asset.codec != codec || asset.target.is_some() || asset.side.is_some() {
                return Err(format!("SDK asset {name} has inconsistent kind/codec metadata"));
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
            if asset.target.as_deref() != Some(target) || asset.codec != "xz" || asset.kind != kind || asset.side.as_deref() != Some("client") {
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

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn index(target: &str) -> serde_json::Value {
        let mut assets = serde_json::Map::new();
        for name in ["surface.json", "build-data.tar.xz", "api-source.tar.xz"] {
            let kind = if name == "surface.json" { "surface" } else if name == "build-data.tar.xz" { "build-data" } else { "api-source" };
            let codec = if name == "surface.json" { "none" } else { "xz" };
            assets.insert(name.into(), json!({"sha256":"a".repeat(64),"size":1,"kind":kind,"codec":codec}));
        }
        for kind in ["runtime", "api", "engine"] {
            assets.insert(format!("{kind}-{target}.tar.xz"), json!({
                "sha256":"b".repeat(64), "size":1, "kind":kind, "codec":"xz",
                "target":target, "side":"client"
            }));
        }
        json!({
            "schema":1,
            "build":{"commit":"a".repeat(40),"game":"26.3","date":"2026-11-14"},
            "package_format":1,
            "toolchain":{"rust":"1.95.0"},
            "content_api":"0.2",
            "surface":{"hash":"x"},
            "mixins":{"rev":"b".repeat(40),"patch":"c".repeat(64)},
            "assets":assets,
            "inputs":{}
        })
    }

    #[test]

    #[test]
    fn reads_only_an_installed_sdk_with_a_matching_signature_receipt() {
        let temp = std::env::temp_dir().join(format!("hnmod-sdk-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&temp);
        std::fs::create_dir_all(&temp).unwrap();
        let value = index("aarch64-linux-android");
        let bytes = serde_json::to_vec(&value).unwrap();
        std::fs::write(temp.join("sdk.json"), &bytes).unwrap();
        let parsed: SdkIndex = serde_json::from_slice(&bytes).unwrap();
        let digest = format!("{:x}", Sha256::digest(&bytes));
        let receipt = serde_json::json!({
            "files": {},
            "signature_verified": true,
            "tag": format!("sdk-{}", parsed.build.commit),
            "index_sha256": digest,
            "target": "aarch64-linux-android",
            "game_commit": parsed.build.commit,
            "mixins_rev": parsed.mixins.rev
        });
        std::fs::write(temp.join(".install-manifest.json"), serde_json::to_vec(&receipt).unwrap()).unwrap();
        assert!(VerifiedIdentity::read(&temp).is_ok());
        let mut bad = receipt;
        bad["signature_verified"] = serde_json::Value::Bool(false);
        std::fs::write(temp.join(".install-manifest.json"), serde_json::to_vec(&bad).unwrap()).unwrap();
        assert!(VerifiedIdentity::read(&temp).unwrap_err().contains("unsigned"));
        std::fs::remove_dir_all(temp).unwrap();
    }

    fn reads_sdk_index_and_checks_android_slices_and_pins() {
        let value = index("aarch64-linux-android");
        let parsed: SdkIndex = serde_json::from_value(value).unwrap();
        let identity = VerifiedIdentity { index: parsed, index_sha256: "d".repeat(64) };
        identity.require_target("aarch64-linux-android", "26.3", &"a".repeat(40), &"b".repeat(40)).unwrap();
    }

    #[test]
    fn refuses_desktop_sdk_for_android() {
        let value = index("x86_64-unknown-linux-gnu");
        let parsed: SdkIndex = serde_json::from_value(value).unwrap();
        let identity = VerifiedIdentity { index: parsed, index_sha256: "d".repeat(64) };
        let error = identity.require_target("aarch64-linux-android", "26.3", &"a".repeat(40), &"b".repeat(40)).unwrap_err();
        assert!(error.contains("this target is not published"));
    }

    #[test]
    fn refuses_wrong_game_or_mixins_pin() {
        let parsed: SdkIndex = serde_json::from_value(index("aarch64-linux-android")).unwrap();
        let identity = VerifiedIdentity { index: parsed, index_sha256: "d".repeat(64) };
        assert!(identity.require_target("aarch64-linux-android", "26.3", &"e".repeat(40), &"b".repeat(40)).is_err());
        assert!(identity.require_target("aarch64-linux-android", "26.3", &"a".repeat(40), &"f".repeat(40)).is_err());
    }
}
