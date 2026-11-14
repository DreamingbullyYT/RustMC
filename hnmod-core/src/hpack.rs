//! Safe reader for Horizon's native hpack modpack format (registry schema 1).
//!
//! A pack pins registry mods by id, version and SHA-256. Mod files are deliberately not embedded;
//! the launcher resolves each pin against its trusted registry index and verifies downloaded bytes.
//! This reader validates the local pack and its permitted overrides only.
use serde::{Deserialize, Serialize};
use std::{collections::BTreeSet, io::{Read, Seek}};

const MANIFEST: &str = "horizon-modpack.toml";
const MAX_OVERRIDE_FILE: u64 = 64 * 1024 * 1024;
const MAX_OVERRIDE_TOTAL: u64 = 256 * 1024 * 1024;
const MAX_ENTRIES: usize = 1024;

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct Manifest {
    format: u32,
    id: String,
    name: String,
    version: String,
    #[serde(default)] description: String,
    #[serde(default)] authors: Vec<String>,
    game: String,
    #[serde(default)] mods: Vec<ModPin>,
}
#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct ModPin {
    id: String,
    version: String,
    sha256: String,
    #[serde(default = "default_on")] on: bool,
}
fn default_on() -> bool { true }

#[derive(Clone, Debug, Serialize)]
pub struct HpackReport {
    pub format: &'static str, pub id: String, pub name: String, pub version: String,
    pub description: String, pub authors: Vec<String>,
    pub game: String, pub mods: Vec<ModPinReport>, pub overrides: Vec<String>,
    pub override_bytes: u64, pub content_fingerprint_sha256: String,
}
#[derive(Clone, Debug, Serialize)]
pub struct ModPinReport {
    pub id: String, pub version: String, pub sha256: String, pub on: bool,
}
fn valid_id(id: &str) -> bool {
    (1..=64).contains(&id.len())
        && id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
}
fn safe_path(path: &str) -> bool {
    !path.is_empty() && !path.starts_with('/') && !path.contains('\\') && !path.contains(':')
        && path.split('/').all(|p| !p.is_empty() && p != "." && p != "..")
}
fn allowed_override(path: &str, mod_ids: &BTreeSet<String>) -> bool {
    if path == "options.txt" || path == "horizon-graphics.txt" || path == "horizon-extras.txt" { return true; }
    let plain = |part: &str| !part.is_empty() && !part.starts_with('.')
        && part.bytes().all(|b| b.is_ascii_alphanumeric() || b". _-+".contains(&b));
    if let Some(rest) = path.strip_prefix("resourcepacks/") { return plain(rest) && rest.ends_with(".zip"); }
    let Some(rest) = path.strip_prefix("horizon-mods/.horizon-mod-data/") else { return false };
    let Some((id, tail)) = rest.split_once('/') else { return false };
    mod_ids.contains(id) && !tail.is_empty() && tail.split('/').all(plain)
}

/// Validate a Horizon hpack ZIP without installing mods or copying overrides.
pub fn inspect<R: Read + Seek>(reader: R) -> Result<HpackReport, String> {
    let mut zip = zip::ZipArchive::new(reader).map_err(|e| format!("invalid hpack ZIP: {e}"))?;
    if zip.len() > MAX_ENTRIES { return Err(format!("hpack has too many entries (maximum {MAX_ENTRIES})")); }
    let mut manifest_bytes = None;
    let mut override_paths = Vec::new();
    let mut override_total = 0u64;
    let mut seen = BTreeSet::new();
    let mut digest = sha2::Sha256::new();
    use sha2::Digest;
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i).map_err(|e| e.to_string())?;
        let name = entry.name().map_err(|e| e.to_string())?.into_owned();
        let is_dir = entry.is_dir();
        if entry.unix_mode().is_some_and(|mode| {
            let kind = mode & 0o170000;
            kind != 0 && kind != 0o100000 && kind != 0o040000
        }) {
            return Err(format!("non-regular hpack entry: {name}"));
        }
        let checked = if is_dir { name.trim_end_matches('/') } else { name.as_str() };
        if !safe_path(checked) { return Err(format!("unsafe hpack path: {name}")); }
        if !seen.insert(name.clone()) { return Err(format!("duplicate hpack entry: {name}")); }
        if is_dir {
            if checked != "overrides" && !checked.starts_with("overrides/") {
                return Err(format!("unexpected hpack directory: {name}"));
            }
            continue;
        }
        if name == MANIFEST {
            if entry.size() > 1024 * 1024 { return Err("horizon-modpack.toml exceeds 1 MiB".into()); }
            let mut bytes = Vec::new();
            entry.take(1024 * 1024 + 1).read_to_end(&mut bytes).map_err(|e| e.to_string())?;
            if bytes.len() > 1024 * 1024 { return Err("horizon-modpack.toml exceeds 1 MiB".into()); }
            digest.update((name.len() as u64).to_le_bytes());
            digest.update(name.as_bytes());
            digest.update((bytes.len() as u64).to_le_bytes());
            digest.update(&bytes);
            manifest_bytes = Some(bytes);
        } else if let Some(path) = name.strip_prefix("overrides/") {
            if !safe_path(path) { return Err(format!("unsafe override path: {name}")); }
            if entry.size() > MAX_OVERRIDE_FILE { return Err(format!("{name} exceeds 64 MiB")); }
            digest.update((name.len() as u64).to_le_bytes());
            digest.update(name.as_bytes());
            let mut file_bytes = 0u64;
            let mut buffer = [0u8; 16 * 1024];
            loop {
                let count = entry.read(&mut buffer).map_err(|e| e.to_string())?;
                if count == 0 { break; }
                file_bytes = file_bytes.saturating_add(count as u64);
                override_total = override_total.saturating_add(count as u64);
                if file_bytes > MAX_OVERRIDE_FILE { return Err(format!("{name} exceeds 64 MiB")); }
                if override_total > MAX_OVERRIDE_TOTAL { return Err("hpack overrides exceed 256 MiB".into()); }
                digest.update(&buffer[..count]);
            }
            digest.update(file_bytes.to_le_bytes());
            override_paths.push(path.to_owned());
        } else {
            return Err(format!("unexpected hpack entry: {name}"));
        }
    }
    let raw = manifest_bytes.ok_or_else(|| format!("missing {MANIFEST}"))?;
    let text = std::str::from_utf8(&raw).map_err(|e| e.to_string())?;
    let manifest: Manifest = toml::from_str(text).map_err(|e| format!("invalid {MANIFEST}: {e}"))?;
    if manifest.format != 1 { return Err(format!("unsupported hpack format {}; expected 1", manifest.format)); }
    if !valid_id(&manifest.id) { return Err("hpack id must be [a-z0-9_], 1 to 64 characters".into()); }
    if manifest.name.trim().is_empty() || manifest.game.trim().is_empty() { return Err("hpack name and game must not be empty".into()); }
    semver::Version::parse(&manifest.version).map_err(|e| format!("hpack version: {e}"))?;
    let mut mod_ids = BTreeSet::new();
    let mut mods = Vec::new();
    for item in manifest.mods {
        if !valid_id(&item.id) { return Err(format!("invalid mod id: {}", item.id)); }
        if !mod_ids.insert(item.id.clone()) { return Err(format!("duplicate mod id: {}", item.id)); }
        semver::Version::parse(&item.version).map_err(|e| format!("{} version: {e}", item.id))?;
        if item.sha256.len() != 64 || !item.sha256.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) {
            return Err(format!("{} has an invalid SHA-256 pin", item.id));
        }
        mods.push(ModPinReport { id: item.id, version: item.version, sha256: item.sha256, on: item.on });
    }
    for path in &override_paths {
        if !allowed_override(path, &mod_ids) { return Err(format!("override path is not permitted by hpack format 1: {path}")); }
    }
    override_paths.sort();
    Ok(HpackReport {
        format: "hpack", id: manifest.id, name: manifest.name, version: manifest.version,
        description: manifest.description, authors: manifest.authors,
        game: manifest.game, mods, overrides: override_paths, override_bytes: override_total,
        content_fingerprint_sha256: format!("{:x}", digest.finalize()),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Cursor, Write};
    use zip::{write::SimpleFileOptions, ZipWriter};
    fn pack(manifest: &str, override_path: &str) -> Cursor<Vec<u8>> {
        let mut zip = ZipWriter::new(Cursor::new(Vec::new()));
        zip.start_file(MANIFEST, SimpleFileOptions::default()).unwrap();
        zip.write_all(manifest.as_bytes()).unwrap();
        if !override_path.is_empty() {
            zip.start_file(format!("overrides/{override_path}"), SimpleFileOptions::default()).unwrap();
            zip.write_all(b"data").unwrap();
        }
        Cursor::new(zip.finish().unwrap().into_inner())
    }
    fn manifest() -> String {
        format!("format = 1\nid = \"cozy\"\nname = \"Cozy\"\nversion = \"1.0.0\"\ngame = \"26.3\"\n[[mods]]\nid = \"coords_hud\"\nversion = \"0.1.0\"\nsha256 = \"{}\"\non = true\n", "a".repeat(64))
    }
    #[test] fn reads_pinned_horizon_modpack() {
        let report = inspect(pack(&manifest(), "options.txt")).unwrap();
        assert_eq!(report.id, "cozy"); assert_eq!(report.mods.len(), 1);
        assert_eq!(report.overrides, ["options.txt"]);
    }
    #[test] fn resourcepack_overrides_must_be_zip_files() {
        assert!(inspect(pack(&manifest(), "resourcepacks/visual.zip")).is_ok());
        assert!(inspect(pack(&manifest(), "resourcepacks/visual.txt")).unwrap_err().contains("not permitted"));
    }

    #[test]
    fn rejects_directories_outside_overrides_and_hidden_resourcepacks() {
        let mut zip = ZipWriter::new(Cursor::new(Vec::new()));
        zip.start_file(MANIFEST, SimpleFileOptions::default()).unwrap();
        zip.write_all(manifest().as_bytes()).unwrap();
        zip.add_directory("random/", SimpleFileOptions::default()).unwrap();
        assert!(inspect(Cursor::new(zip.finish().unwrap().into_inner())).unwrap_err().contains("unexpected hpack directory"));
        assert!(inspect(pack(&manifest(), "resourcepacks/.hidden.zip")).unwrap_err().contains("not permitted"));
    }
    #[test] fn refuses_unlisted_mod_settings_and_unknown_files() {
        assert!(inspect(pack(&manifest(), "horizon-mods/.horizon-mod-data/other/settings.json")).unwrap_err().contains("not permitted"));
        let mut zip = ZipWriter::new(Cursor::new(Vec::new()));
        zip.start_file(MANIFEST, SimpleFileOptions::default()).unwrap();
        zip.write_all(manifest().as_bytes()).unwrap();
        zip.start_file("random.txt", SimpleFileOptions::default()).unwrap();
        zip.write_all(b"x").unwrap();
        assert!(inspect(Cursor::new(zip.finish().unwrap().into_inner())).unwrap_err().contains("unexpected"));
    }
}
