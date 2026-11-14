//! Standalone HNMod archive validation and compatibility planning.
//! Based on the MIT-licensed Starling format and compatibility design.
//! This crate never executes mod code and has no UI, Android, JNI or launcher dependencies.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{collections::{BTreeMap, BTreeSet}, io::{Read, Seek}};

pub mod dependencies;
pub mod hmod;
pub mod hpack;
pub mod modpack;
pub mod native_plan;
pub mod sdk;
pub mod pc_api;

pub const MANIFEST_FILE: &str = "horizon-native.json";
pub const MIXIN_MANIFEST: &str = "horizon-mixin.toml";
pub const MAX_ENTRIES: usize = 512;
pub const MAX_FILE_BYTES: u64 = 64 * 1024 * 1024;
pub const MAX_TOTAL_BYTES: u64 = 64 * 1024 * 1024;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Manifest {
    #[serde(default = "default_schema")]
    pub schema: u32,
    pub id: String,
    #[serde(default)]
    pub version: String,
    #[serde(default)]
    pub game: String,
    #[serde(default)]
    pub horizon: String,
    #[serde(default)]
    pub mixins: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub hooks: Option<u32>,
}

fn default_schema() -> u32 { 1 }

impl Manifest {
    pub fn validate(&self) -> Result<(), Error> {
        if self.schema != 1 { return Err(Error::Manifest("unsupported manifest schema".into())); }
        if !valid_id(&self.id) { return Err(Error::Manifest("invalid mod id".into())); }
        if !self.horizon.is_empty() && !is_revision(&self.horizon) {
            return Err(Error::Manifest("horizon must be a full lowercase 40-character commit ID".into()));
        }
        if !self.mixins.is_empty() && !is_revision(&self.mixins) {
            return Err(Error::Manifest("mixins must be a full lowercase 40-character commit ID".into()));
        }
        if self.hooks == Some(0) { return Err(Error::Manifest("hooks API must be greater than zero".into())); }
        Ok(())
    }
}

#[derive(Clone, Debug)]
pub struct Bundle {
    pub manifest: Manifest,
    pub files: BTreeMap<String, Vec<u8>>,
    pub sha256: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    Archive(String), Manifest(String), UnsafePath(String), Limit(String), Compatibility(String),
}
impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Archive(s) | Self::Manifest(s) | Self::UnsafePath(s) |
            Self::Limit(s) | Self::Compatibility(s) => f.write_str(s),
        }
    }
}
impl std::error::Error for Error {}

pub fn valid_id(id: &str) -> bool {
    (1..=100).contains(&id.len()) &&
        id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}
pub fn is_revision(s: &str) -> bool {
    s.len() == 40 && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn safe_path(s: &str) -> Result<(), Error> {
    if s.is_empty() || s.starts_with('/') || s.contains('\\') || s.contains(':') ||
        s.split('/').any(|p| p.is_empty() || p == "." || p == "..") {
        return Err(Error::UnsafePath(s.into()));
    }
    Ok(())
}
fn allowed_payload(name: &str, id: &str) -> bool {
    if name == MANIFEST_FILE { return true; }
    // PC HNMods may include mixin source, API crates, helper modules, data and
    // supporting files. The archive path validator and extraction boundary are
    // the security boundary; do not reject valid PC payload extensions here.
    name.starts_with(&format!("mixins/{id}/")) && name.len() > format!("mixins/{id}/").len()
}

/// Reads every regular file of a ZIP with the path, count and size limits. Nothing is extracted or run.
pub(crate) fn read_files<R: Read + Seek>(reader: R, allow_wasm: bool) -> Result<BTreeMap<String, Vec<u8>>, Error> {
    let mut zip = zip::ZipArchive::new(reader).map_err(|e| Error::Archive(e.to_string()))?;
    let mut files = BTreeMap::new();
    let mut total = 0u64;
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i).map_err(|e| Error::Archive(e.to_string()))?;
        let name = entry.name().map_err(|e| Error::Archive(format!("invalid entry name: {e}")))?.into_owned();
        if entry.unix_mode().is_some_and(|m| {
            let kind = m & 0o170000; kind != 0 && kind != 0o100000
        }) {
            return Err(Error::UnsafePath(format!("non-regular archive entry: {name}")));
        }
        if entry.is_dir() { continue; }
        if files.len() >= MAX_ENTRIES {
            return Err(Error::Limit("too many archive files".into()));
        }
        safe_path(&name)?;
        if name == "mod.wasm" && !allow_wasm {
            return Err(Error::Manifest("legacy WebAssembly payloads are unsupported".into()));
        }
        let mut bytes = Vec::new();
        entry.by_ref().take(MAX_FILE_BYTES + 1).read_to_end(&mut bytes)
            .map_err(|e| Error::Archive(format!("{name}: {e}")))?;
        if bytes.len() as u64 > MAX_FILE_BYTES {
            return Err(Error::Limit(format!("{name} exceeds the per-file limit")));
        }
        total += bytes.len() as u64;
        if total > MAX_TOTAL_BYTES { return Err(Error::Limit("archive expands beyond 64 MiB".into())); }
        if files.insert(name.clone(), bytes).is_some() {
            return Err(Error::UnsafePath(format!("duplicate archive entry: {name}")));
        }
    }
    Ok(files)
}

pub(crate) fn digest(files: &BTreeMap<String, Vec<u8>>) -> String {
    let mut h = Sha256::new();
    for (name, bytes) in files {
        h.update((name.len() as u64).to_le_bytes()); h.update(name.as_bytes());
        h.update((bytes.len() as u64).to_le_bytes()); h.update(bytes);
    }
    format!("{:x}", h.finalize())
}

/// Validates an HNMod ZIP without extracting files or executing native code.
pub fn read_bundle<R: Read + Seek>(reader: R) -> Result<Bundle, Error> {
    let files = read_files(reader, false)?;
    let raw = files.get(MANIFEST_FILE).ok_or_else(|| Error::Manifest("missing horizon-native.json".into()))?;
    let mut manifest: Manifest = serde_json::from_slice(raw).map_err(|e| Error::Manifest(e.to_string()))?;
    if !valid_id(&manifest.id) { return Err(Error::Manifest("invalid mod id".into())); }
    for name in files.keys() {
        if !allowed_payload(name, &manifest.id) {
            return Err(Error::UnsafePath(format!("unexpected payload path: {name}")));
        }
    }
    let path = format!("mixins/{}/{}", manifest.id, MIXIN_MANIFEST);
    let raw = files.get(&path).ok_or_else(|| Error::Manifest(format!("missing {path}")))?;
    let text = std::str::from_utf8(raw).map_err(|e| Error::Manifest(e.to_string()))?;
    let mixin: toml::Value = toml::from_str(text).map_err(|e| Error::Manifest(e.to_string()))?;
    let field = |section: &str, key: &str| mixin.get(section).and_then(|v| v.get(key)).and_then(toml::Value::as_str);
    let mixin_id = field("mod", "id").ok_or_else(|| Error::Manifest("mixin manifest is missing [mod].id".into()))?;
    let mixin_version = field("mod", "version").ok_or_else(|| Error::Manifest("mixin manifest is missing [mod].version".into()))?;
    if mixin_id != manifest.id {
        return Err(Error::Manifest("native and mixin manifests disagree on mod identity".into()));
    }
    let mixin_horizon = field("compatibility", "horizon_revision").unwrap_or("");
    if !manifest.horizon.is_empty() && !mixin_horizon.is_empty() && manifest.horizon != mixin_horizon {
        return Err(Error::Manifest("native and mixin manifests disagree on Horizon revision".into()));
    }
    if manifest.horizon.is_empty() { manifest.horizon = mixin_horizon.to_owned(); }
    manifest.version = mixin_version.to_owned();
    manifest.validate()?;
    let sha256 = digest(&files);
    Ok(Bundle { manifest, files, sha256 })
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EngineCandidate {
    pub revision: String,
    pub hooks_api: u32,
    pub mixins_revision: String,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Compatibility {
    pub selected: EngineCandidate,
    pub mods: Vec<String>,
    pub warnings: Vec<String>,
}

/// Selects the first explicitly available engine candidate satisfying every enabled mod.
/// Candidates must be supplied newest-first using verified Git ancestry, never hash sorting.
/// Commit IDs are opaque: this function never guesses chronology from their spelling.
pub fn resolve_compatibility(bundles: &[Bundle], candidates: &[EngineCandidate]) -> Result<Compatibility, Error> {
    if bundles.is_empty() { return Err(Error::Compatibility("no native mods selected".into())); }
    let mut ids = BTreeSet::new();
    for b in bundles {
        if !ids.insert(b.manifest.id.clone()) {
            return Err(Error::Compatibility(format!("duplicate mod id: {}", b.manifest.id)));
        }
    }
    let selected = candidates.iter().find(|c| bundles.iter().all(|b| {
        let m = &b.manifest;
        let engine_ok = match m.hooks {
            Some(required) => c.hooks_api >= required,
            None => true,
        };
        engine_ok
    })).cloned().ok_or_else(|| {
        let requirements = bundles.iter().map(|b| match b.manifest.hooks {
            Some(n) => format!("{} needs hooks API >= {n}, tested on {}", b.manifest.id, b.manifest.horizon),
            None if !b.manifest.horizon.is_empty() => format!("{} is pinned to {}", b.manifest.id, b.manifest.horizon),
            None => format!("{} has no Horizon pin", b.manifest.id),
        }).collect::<Vec<_>>().join("; ");
        Error::Compatibility(format!("no candidate satisfies all selected mods and mixins pins: {requirements}"))
    })?;
    let warnings = bundles.iter().filter(|b| !b.manifest.horizon.is_empty() && b.manifest.horizon != selected.revision)
        .map(|b| if b.manifest.hooks.is_some() {
            format!("{} was tested on {}, using stable hooks on {}", b.manifest.id, b.manifest.horizon, selected.revision)
        } else {
            format!("{} is pinned to {}, trying it on newer selected revision {}", b.manifest.id, b.manifest.horizon, selected.revision)
        })
        .collect();
    Ok(Compatibility { selected, mods: bundles.iter().map(|b| b.manifest.id.clone()).collect(), warnings })
}

/// Stable build key, independent of the order in which mods were selected.
pub fn build_key(candidate: &EngineCandidate, bundles: &[Bundle], profile: &str) -> String {
    let mut h = Sha256::new();
    for part in [candidate.revision.as_str(), candidate.mixins_revision.as_str(), profile] {
        h.update((part.len() as u64).to_le_bytes()); h.update(part.as_bytes());
    }
    let mut sorted: Vec<_> = bundles.iter().collect();
    sorted.sort_by(|a, b| a.manifest.id.cmp(&b.manifest.id));
    for b in sorted {
        h.update((b.manifest.id.len() as u64).to_le_bytes()); h.update(b.manifest.id.as_bytes());
        h.update(b.sha256.as_bytes());
    }
    format!("{:x}", h.finalize())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn archive(hooks: Option<u32>, horizon: &str, mixins: &str) -> Vec<u8> {
        let m = Manifest { schema: 1, id: "demo_mod".into(), version: "1.0.0".into(),
            game: "26.3".into(), horizon: horizon.into(), mixins: mixins.into(), hooks };
        let toml = format!("[mod]\nid = \"demo_mod\"\nversion = \"1.0.0\"\n[compatibility]\nhorizon_revision = \"{horizon}\"\n");
        let mut out = std::io::Cursor::new(Vec::new());
        {
            let mut z = zip::ZipWriter::new(&mut out);
            let o = zip::write::SimpleFileOptions::default();
            z.start_file(MANIFEST_FILE, o).unwrap(); z.write_all(&serde_json::to_vec(&m).unwrap()).unwrap();
            z.start_file("mixins/demo_mod/horizon-mixin.toml", o).unwrap(); z.write_all(toml.as_bytes()).unwrap();
            z.start_file("mixins/demo_mod/hooks.rs", o).unwrap(); z.write_all(b"pub fn hook() {}").unwrap();
            z.finish().unwrap();
        }
        out.into_inner()
    }

    #[test]
    fn mod_ids_match_horizons_native_manifest_rules() {
        assert!(valid_id("mod_123"));
        assert!(valid_id(&"a".repeat(64)));
        assert!(!valid_id(""));
        assert!(valid_id("Uppercase"));
        assert!(valid_id("hyphen-name"));
        assert!(valid_id(&"a".repeat(100)));
        assert!(!valid_id(&"a".repeat(101)));
    }

    #[test]
    fn payload_paths_accept_full_pc_bundle_contents_only_under_mod_root() {
        assert!(allowed_payload("mixins/mod/hooks.rs", "mod"));
        assert!(allowed_payload("mixins/mod/engine/Cargo.toml", "mod"));
        assert!(allowed_payload("mixins/mod/data/nested/table.bin", "mod"));
        assert!(allowed_payload("mixins/mod/engine/src/data/table.bin", "mod"));
        assert!(allowed_payload("mixins/mod/assets/icon.png", "mod"));
        assert!(allowed_payload("mixins/mod/payload.dll", "mod"));
        assert!(!allowed_payload("mixins/another-mod/payload.dll", "mod"));
        assert!(!allowed_payload("payload.dll", "mod"));
    }
    #[test]
    fn reads_official_pc_bundle_without_android_specific_fields() {
        let horizon = "a".repeat(40);
        let mixins = "b".repeat(40);
        let native = serde_json::json!({
            "id": "pc-mod",
            "horizon": horizon,
            "mixins": mixins,
            "hooks": 3,
            "publisher": "example"
        });
        let manifest = format!(
            "[mod]\nid = \"pc-mod\"\nversion = \"0.2.0\"\n[compatibility]\nhorizon_revision = \"{}\"\n",
            "a".repeat(40)
        );
        let mut out = std::io::Cursor::new(Vec::new());
        {
            let mut z = zip::ZipWriter::new(&mut out);
            let o = zip::write::SimpleFileOptions::default();
            z.start_file(MANIFEST_FILE, o).unwrap();
            z.write_all(&serde_json::to_vec(&native).unwrap()).unwrap();
            z.start_file("mixins/pc-mod/horizon-mixin.toml", o).unwrap();
            z.write_all(manifest.as_bytes()).unwrap();
            z.start_file("mixins/pc-mod/engine/Cargo.toml", o).unwrap();
            z.write_all(b"[package]\nname = \"pc-mod-api\"\nversion = \"0.1.0\"\n").unwrap();
            z.start_file("mixins/pc-mod/engine/src/lib.rs", o).unwrap();
            z.write_all(b"pub fn helper() {}").unwrap();
            z.start_file("mixins/pc-mod/data/assets/table.bin", o).unwrap();
            z.write_all(b"data").unwrap();
            z.finish().unwrap();
        }
        let bundle = read_bundle(std::io::Cursor::new(out.into_inner())).unwrap();
        assert_eq!(bundle.manifest.id, "pc-mod");
        assert_eq!(bundle.manifest.version, "0.2.0");
        assert_eq!(bundle.manifest.game, "");
        assert_eq!(bundle.manifest.horizon, horizon);
        assert_eq!(bundle.manifest.mixins, mixins);
    }

    #[test]
    fn stable_hook_mod_can_target_later_compatible_engine() {
        let a = "a".repeat(40); let b = "b".repeat(40);
        let bundle = read_bundle(std::io::Cursor::new(archive(Some(3), &a, &b))).unwrap();
        let candidate = EngineCandidate { revision: "c".repeat(40), hooks_api: 4, mixins_revision: b };
        let result = resolve_compatibility(&[bundle], &[candidate.clone()]).unwrap();
        assert_eq!(result.selected, candidate); assert_eq!(result.warnings.len(), 1);
    }

    #[test]
    fn pinned_mod_can_be_reconciled_to_newest_selected_engine() {
        let a = "a".repeat(40); let b = "b".repeat(40);
        let bundle = read_bundle(std::io::Cursor::new(archive(None, &a, &b))).unwrap();
        let candidate = EngineCandidate { revision: "c".repeat(40), hooks_api: 99, mixins_revision: b };
        let plan = resolve_compatibility(&[bundle], &[candidate]).unwrap();
        assert_eq!(plan.selected.revision, "c".repeat(40));
        assert_eq!(plan.warnings.len(), 1);
        assert!(plan.warnings[0].contains("trying it on newer selected revision"));
    }

    #[test]
    fn archive_rejects_traversal_paths() {
        let mut out = std::io::Cursor::new(Vec::new());
        {
            let mut z = zip::ZipWriter::new(&mut out);
            z.start_file("../escape.rs", zip::write::SimpleFileOptions::default()).unwrap();
            z.write_all(b"bad").unwrap(); z.finish().unwrap();
        }
        assert!(read_bundle(std::io::Cursor::new(out.into_inner())).is_err());
    }
}
