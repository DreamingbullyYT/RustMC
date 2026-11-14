//! Safe Modrinth .mrpack inspection for the Android modpack import pipeline.
//!
//! This module deliberately does not install or execute Java mod JARs. It validates the
//! container, records download metadata and classifies payloads so the compiler can explain
//! which entries need a native Horizon port instead of silently attempting to load them.
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{collections::BTreeMap, io::{Read, Seek}};

const INDEX: &str = "modrinth.index.json";
const MAX_ENTRIES: usize = 4096;
const MAX_INDEX_BYTES: u64 = 4 * 1024 * 1024;
const MAX_TOTAL_UNPACKED: u64 = 2 * 1024 * 1024 * 1024;

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Index {
    format_version: u32,
    game: String,
    version_id: String,
    name: String,
    #[serde(default)]
    files: Vec<IndexedFile>,
    #[serde(default)]
    dependencies: BTreeMap<String, String>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct IndexedFile {
    path: String,
    #[serde(default)]
    hashes: BTreeMap<String, String>,
    #[serde(default)]
    downloads: Vec<String>,
    #[serde(default)]
    file_size: u64,
    #[serde(default)]
    env: Environment,
}

#[derive(Clone, Debug, Default, Deserialize)]
struct Environment {
    client: Option<String>,
    server: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
pub struct ModpackReport {
    pub format: &'static str,
    pub name: String,
    pub version_id: String,
    pub game: String,
    pub dependencies: BTreeMap<String, String>,
    pub indexed_files: usize,
    pub client_files: usize,
    pub server_only_files: usize,
    pub java_mod_jars: Vec<String>,
    pub overrides: Vec<String>,
    pub download_bytes: u64,
    pub index_sha256: String,
    pub notes: Vec<String>,
}

fn safe_relative(path: &str) -> bool {
    !path.is_empty()
        && !path.starts_with('/')
        && !path.contains('\\')
        && !path.contains(':')
        && path.split('/').all(|part| !part.is_empty() && part != "." && part != "..")
}

fn is_java_mod(path: &str) -> bool {
    let lower = path.to_ascii_lowercase();
    lower.ends_with(".jar") && (lower.starts_with("mods/") || lower.contains("/mods/"))
}

/// Validate a Modrinth .mrpack without extracting files or downloading remote payloads.
pub fn inspect<R: Read + Seek>(reader: R) -> Result<ModpackReport, String> {
    let mut zip = zip::ZipArchive::new(reader).map_err(|e| format!("invalid modpack ZIP: {e}"))?;
    if zip.len() > MAX_ENTRIES {
        return Err(format!("modpack has too many entries (maximum {MAX_ENTRIES})"));
    }
    let mut total = 0u64;
    let mut index_bytes = None;
    let mut overrides = Vec::new();
    let mut names = std::collections::BTreeSet::new();
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i).map_err(|e| e.to_string())?;
        let name = entry.name().map_err(|e| e.to_string())?.into_owned();
        let is_dir = entry.is_dir();
        let checked_name = if is_dir { name.trim_end_matches('/') } else { name.as_str() };
        if !safe_relative(checked_name) {
            return Err(format!("unsafe modpack path: {name}"));
        }
        if !names.insert(name.clone()) {
            return Err(format!("duplicate modpack entry: {name}"));
        }
        if is_dir { continue; }
        total = total.saturating_add(entry.size());
        if total > MAX_TOTAL_UNPACKED {
            return Err("modpack expands beyond the 2 GiB safety limit".into());
        }
        if name == INDEX {
            if entry.size() > MAX_INDEX_BYTES {
                return Err("modrinth.index.json exceeds the 4 MiB limit".into());
            }
            let mut bytes = Vec::with_capacity(entry.size() as usize);
            entry.take(MAX_INDEX_BYTES + 1).read_to_end(&mut bytes).map_err(|e| e.to_string())?;
            if bytes.len() as u64 > MAX_INDEX_BYTES {
                return Err("modrinth.index.json exceeds the 4 MiB limit".into());
            }
            index_bytes = Some(bytes);
        } else if name.starts_with("overrides/") || name.starts_with("client-overrides/") {
            overrides.push(name);
        }
    }
    let raw = index_bytes.ok_or_else(|| format!("missing {INDEX}; this is not a Modrinth .mrpack"))?;
    let index: Index = serde_json::from_slice(&raw).map_err(|e| format!("invalid {INDEX}: {e}"))?;
    if index.format_version != 1 {
        return Err(format!("unsupported Modrinth formatVersion {}; expected 1", index.format_version));
    }
    if index.game != "minecraft" {
        return Err(format!("unsupported modpack game {:?}; expected minecraft", index.game));
    }
    if index.name.trim().is_empty() || index.version_id.trim().is_empty() {
        return Err("modpack name and versionId must not be empty".into());
    }
    let mut paths = std::collections::BTreeSet::new();
    let mut client_files = 0usize;
    let mut server_only_files = 0usize;
    let mut download_bytes = 0u64;
    let mut java_mod_jars = Vec::new();
    for file in &index.files {
        if !safe_relative(&file.path) {
            return Err(format!("unsafe indexed path: {}", file.path));
        }
        if !paths.insert(file.path.clone()) {
            return Err(format!("duplicate indexed path: {}", file.path));
        }
        if file.downloads.is_empty() {
            return Err(format!("{} has no download URL", file.path));
        }
        if file.downloads.iter().any(|url| !url.starts_with("https://")) {
            return Err(format!("{} contains a non-HTTPS download URL", file.path));
        }
        if file.hashes.is_empty() {
            return Err(format!("{} has no integrity hashes", file.path));
        }
        if file.env.client.as_deref() == Some("unsupported") {
            if file.env.server.as_deref() == Some("required")
                || file.env.server.as_deref() == Some("optional")
                || file.env.server.is_none()
            {
                server_only_files += 1;
            }
            continue;
        }
        if file.env.client.as_deref() == Some("required")
            || file.env.client.as_deref() == Some("optional")
            || file.env.client.is_none()
        {
            client_files += 1;
            download_bytes = download_bytes.saturating_add(file.file_size);
            if is_java_mod(&file.path) { java_mod_jars.push(file.path.clone()); }
        }
    }
    overrides.sort();
    java_mod_jars.sort();
    let mut notes = Vec::new();
    if !java_mod_jars.is_empty() {
        notes.push("Java/Fabric/Forge/NeoForge JARs are catalogued but are not executable by Horizon; each needs a native Horizon port.".into());
    }
    if !overrides.is_empty() {
        notes.push("Overrides are listed but must be merged through the launcher's safe import path.".into());
    }
    Ok(ModpackReport {
        format: "modrinth",
        name: index.name,
        version_id: index.version_id,
        game: index.game,
        dependencies: index.dependencies,
        indexed_files: index.files.len(),
        client_files,
        server_only_files,
        java_mod_jars,
        overrides,
        download_bytes,
        index_sha256: format!("{:x}", Sha256::digest(&raw)),
        notes,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Cursor, Write};
    use zip::{write::SimpleFileOptions, ZipWriter};

    fn pack(index: &str) -> Cursor<Vec<u8>> {
        let mut zip = ZipWriter::new(Cursor::new(Vec::new()));
        zip.start_file(INDEX, SimpleFileOptions::default()).unwrap();
        zip.write_all(index.as_bytes()).unwrap();
        zip.start_file("overrides/config/example.toml", SimpleFileOptions::default()).unwrap();
        zip.write_all(b"test").unwrap();
        Cursor::new(zip.finish().unwrap().into_inner())
    }

    #[test]
    fn inspects_modrinth_pack_and_marks_java_mods() {
        let index = r#"{"formatVersion":1,"game":"minecraft","versionId":"test","name":"Test pack","dependencies":{"minecraft":"1.21"},"files":[{"path":"mods/example.jar","hashes":{"sha1":"abc"},"downloads":["https://cdn.example/mod.jar"],"fileSize":123}]}"#;
        let report = inspect(pack(index)).unwrap();
        assert_eq!(report.name, "Test pack");
        assert_eq!(report.java_mod_jars, ["mods/example.jar"]);
        assert_eq!(report.overrides, ["overrides/config/example.toml"]);
        assert_eq!(report.download_bytes, 123);
    }

    #[test]
    fn rejects_traversal_and_http_downloads() {
        let traversal = r#"{"formatVersion":1,"game":"minecraft","versionId":"x","name":"x","files":[{"path":"../bad.jar","hashes":{"sha1":"x"},"downloads":["https://example.test/a"],"fileSize":1}]}"#;
        assert!(inspect(pack(traversal)).unwrap_err().contains("unsafe indexed path"));
        let http = r#"{"formatVersion":1,"game":"minecraft","versionId":"x","name":"x","files":[{"path":"mods/a.jar","hashes":{"sha1":"x"},"downloads":["http://example.test/a"],"fileSize":1}]}"#;
        assert!(inspect(pack(http)).unwrap_err().contains("non-HTTPS"));
    }
}
