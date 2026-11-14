//! Reader for the current `.hmod` package format (`horizon-mod.toml`, Horizon API update of Nov 12).
//!
//! The legacy `.hnmod` bundle (`horizon-native.json`) is still read by [`crate::read_bundle`], so older
//! engines and discontinued mods keep working. [`inspect`] picks the right reader from the archive.
use crate::{digest, is_revision, read_bundle, read_files, Error};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha512};
use std::collections::{BTreeMap, BTreeSet};
use std::io::{Read, Seek};

pub const HMOD_MANIFEST: &str = "horizon-mod.toml";

/// A `[[native.*]]` table row. Only `id` is interpreted here; the rest belongs to the mixin tool.
#[derive(Clone, Debug, PartialEq, Deserialize, Serialize)]
pub struct Row {
    pub id: String,
    #[serde(flatten)]
    pub options: BTreeMap<String, toml::Value>,
}

#[derive(Clone, Debug, PartialEq, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Native {
    pub game: String,
    pub horizon: String,
    pub mixins: String,
    #[serde(default)] pub mixin: Vec<Row>,
    #[serde(default, rename = "loop")] pub loops: Vec<Row>,
    #[serde(default)] pub bridge: Vec<Row>,
    #[serde(default)] pub module: Vec<Row>,
}

#[derive(Clone, Debug, PartialEq, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Wasm {
    pub sha512: String,
    pub toolchain: String,
    #[serde(default)] pub linked: BTreeMap<String, String>,
    #[serde(default)] pub built_with: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Deserialize, Serialize)]
pub struct Inject {
    pub id: String,
    #[serde(flatten)]
    pub options: BTreeMap<String, toml::Value>,
}

#[derive(Clone, Debug, PartialEq, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct HmodManifest {
    pub id: String,
    #[serde(default)] pub name: String,
    pub version: String,
    pub api: String,
    #[serde(default)] pub capabilities: Vec<String>,
    #[serde(default)] pub native: Option<Native>,
    #[serde(default)] pub wasm: Option<Wasm>,
    #[serde(default)] pub inject: Vec<Inject>,
    #[serde(default)] pub dependencies: BTreeMap<String, toml::Value>,
    #[serde(default)] pub properties: BTreeMap<String, toml::Value>,
    #[serde(default)] pub wasm_memory_mib: Option<u32>,
}

pub fn valid_hmod_id(id: &str) -> bool {
    (1..=64).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
}

fn is_hex(s: &str, len: usize) -> bool {
    s.len() == len && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

impl HmodManifest {
    pub fn parse(text: &str) -> Result<Self, Error> {
        let raw: toml::Value = toml::from_str(text).map_err(|e| Error::Manifest(e.to_string()))?;
        if raw.get("internals").is_some() {
            return Err(Error::Manifest("`internals` was removed in the Nov 12 API: move that code to [[native.mixin]]".into()));
        }
        if raw.get("inject").and_then(toml::Value::as_array)
            .is_some_and(|rows| rows.iter().any(|r| r.get("access").is_some())) {
            return Err(Error::Manifest("`access` on [[inject]] was removed in the Nov 12 API: drop it".into()));
        }
        let manifest: Self = toml::from_str(text).map_err(|e| Error::Manifest(e.to_string()))?;
        manifest.validate()?;
        Ok(manifest)
    }

    pub fn validate(&self) -> Result<(), Error> {
        let bad = |m: &str| Err(Error::Manifest(m.into()));
        if !valid_hmod_id(&self.id) { return bad("id must be [a-z0-9_], 1 to 64 characters"); }
        semver::Version::parse(&self.version).map_err(|e| Error::Manifest(format!("version: {e}")))?;
        if self.api.trim().is_empty() { return bad("api must not be empty"); }
        if self.native.is_some() && self.wasm.is_some() { return bad("a mod is [native] or [wasm], never both"); }
        if let Some(m) = self.wasm_memory_mib && !(1..=4096).contains(&m) {
            return bad("wasm_memory_mib must be 1 to 4096");
        }
        if self.properties.len() > 64 { return bad("[properties] allows at most 64 keys"); }
        let mut ids = BTreeSet::new();
        let mut row = |id: &str| -> Result<(), Error> {
            if id.is_empty() { return Err(Error::Manifest("a table row has an empty id".into())); }
            if !ids.insert(id.to_owned()) { return Err(Error::Manifest(format!("duplicate row id: {id}"))); }
            Ok(())
        };
        for i in &self.inject { row(&i.id)?; }
        if let Some(n) = &self.native {
            if n.game.trim().is_empty() { return bad("[native].game must not be empty"); }
            if !is_revision(&n.horizon) { return bad("[native].horizon must be a 40-character lowercase commit ID"); }
            if !is_revision(&n.mixins) { return bad("[native].mixins must be a 40-character lowercase commit ID"); }
            for r in n.mixin.iter().chain(&n.loops).chain(&n.bridge).chain(&n.module) { row(&r.id)?; }
        }
        if let Some(w) = &self.wasm {
            if !is_hex(&w.sha512, 128) { return bad("[wasm].sha512 must be 128 lowercase hex characters"); }
            if w.toolchain.trim().is_empty() { return bad("[wasm].toolchain must not be empty"); }
        }
        Ok(())
    }
}

#[derive(Clone, Debug)]
pub struct Hmod {
    pub manifest: HmodManifest,
    pub files: BTreeMap<String, Vec<u8>>,
    pub sha256: String,
}

/// Validates a `.hmod` ZIP. Nothing is extracted or executed.
pub fn read_hmod<R: Read + Seek>(reader: R) -> Result<Hmod, Error> {
    let files = read_files(reader, true)?;
    let raw = files.get(HMOD_MANIFEST).ok_or_else(|| Error::Manifest(format!("missing {HMOD_MANIFEST}")))?;
    let text = std::str::from_utf8(raw).map_err(|e| Error::Manifest(e.to_string()))?;
    let manifest = HmodManifest::parse(text)?;
    if let Some(w) = &manifest.wasm {
        let wasm = files.get("mod.wasm").ok_or_else(|| Error::Manifest("[wasm] needs mod.wasm".into()))?;
        if !wasm.starts_with(b"\0asm") { return Err(Error::Manifest("mod.wasm is not a WebAssembly module".into())); }
        if format!("{:x}", Sha512::digest(wasm)) != w.sha512 {
            return Err(Error::Manifest("mod.wasm does not match [wasm].sha512".into()));
        }
    }
    if let Some(native) = &manifest.native {
        if !files.contains_key("Cargo.toml") || !files.contains_key("src/lib.rs") {
            return Err(Error::Manifest("a native .hmod needs root Cargo.toml and src/lib.rs".into()));
        }
        for row in native.mixin.iter().chain(&native.loops) {
            if let Some(helper) = row.options.get("helper").and_then(toml::Value::as_str) {
                let path = std::path::Path::new(helper);
                if !helper.starts_with("src/") || path.is_absolute()
                    || !path.components().all(|part| matches!(part, std::path::Component::Normal(_)))
                    || !files.contains_key(helper)
                {
                    return Err(Error::Manifest(format!("{}: helper must be an existing file under src/", row.id)));
                }
            } else {
                return Err(Error::Manifest(format!("{}: missing helper path", row.id)));
            }
        }
    }
    let sha256 = digest(&files);
    Ok(Hmod { manifest, files, sha256 })
}

#[derive(Clone, Debug, Serialize)]
#[serde(tag = "format", rename_all = "lowercase")]
pub enum Inspected {
    /// Current format.
    Hmod { id: String, version: String, kind: &'static str, game: Option<String>, horizon: Option<String>,
           mixins: Option<String>, sha256: String },
    /// Older `.hnmod` bundle, still supported.
    Hnmod { id: String, version: String, game: String, horizon: String, mixins: String, sha256: String },
}

/// Detects the format from the archive and returns the common summary.
pub fn inspect<R: Read + Seek>(mut reader: R) -> Result<Inspected, Error> {
    let has_hmod = zip::ZipArchive::new(&mut reader).map_err(|e| Error::Archive(e.to_string()))?
        .index_for_name(HMOD_MANIFEST).is_some();
    reader.rewind().map_err(|e| Error::Archive(e.to_string()))?;
    if has_hmod {
        let h = read_hmod(reader)?;
        let m = h.manifest;
        let kind = if m.native.is_some() { "native" } else if m.wasm.is_some() { "wasm" } else { "content" };
        Ok(Inspected::Hmod {
            id: m.id, version: m.version, kind,
            game: m.native.as_ref().map(|n| n.game.clone()),
            horizon: m.native.as_ref().map(|n| n.horizon.clone()),
            mixins: m.native.as_ref().map(|n| n.mixins.clone()),
            sha256: h.sha256,
        })
    } else {
        let b = read_bundle(reader)?;
        Ok(Inspected::Hnmod { id: b.manifest.id, version: b.manifest.version, game: b.manifest.game,
            horizon: b.manifest.horizon, mixins: b.manifest.mixins, sha256: b.sha256 })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Cursor, Write};
    use zip::{write::SimpleFileOptions, ZipWriter};

    fn zip_of(files: &[(&str, &[u8])]) -> Cursor<Vec<u8>> {
        let mut z = ZipWriter::new(Cursor::new(Vec::new()));
        for (n, b) in files { z.start_file(*n, SimpleFileOptions::default()).unwrap(); z.write_all(b).unwrap(); }
        Cursor::new(z.finish().unwrap().into_inner())
    }
    fn native_toml(extra: &str) -> String {
        format!("id = \"demo\"\nname = \"Demo\"\nversion = \"1.2.3\"\napi = \"0.2\"\n{extra}\n[native]\ngame = \"26.3\"\nhorizon = \"{}\"\nmixins = \"{}\"\n",
            "a".repeat(40), "b".repeat(40))
    }

    #[test] fn native_mod_with_mixin_rows() {
        let t = native_toml("") + "[[native.mixin]]\nid = \"m1\"\ntarget = \"x::y\"\nkind = \"HEAD\"\n[[native.loop]]\nid = \"l1\"\n";
        let m = HmodManifest::parse(&t).unwrap();
        assert_eq!(m.native.as_ref().unwrap().mixin[0].id, "m1");
        assert_eq!(m.native.unwrap().loops[0].id, "l1");
    }
    #[test] fn removed_fields_are_named() {
        let t = native_toml("") + "[[inject]]\nid = \"i\"\nat = \"a\"\nkind = \"head\"\nwith = \"w\"\naccess = \"internals\"\n";
        assert!(HmodManifest::parse(&t).unwrap_err().to_string().contains("access"));
        let t = "id = \"d\"\nversion = \"1.0.0\"\napi = \"0.2\"\n[[internals]]\npath = \"a\"\n";
        assert!(HmodManifest::parse(t).unwrap_err().to_string().contains("internals"));
    }
    #[test] fn rejects_bad_input() {
        assert!(HmodManifest::parse(&native_toml("bogus = 1")).is_err());
        assert!(HmodManifest::parse(&native_toml("").replace("id = \"demo\"", "id = \"Demo-1\"")).is_err());
        assert!(HmodManifest::parse(&native_toml("").replace(&"a".repeat(40), "abc")).is_err());
        let both = native_toml("") + "[wasm]\nsha512 = \"x\"\ntoolchain = \"t\"\n";
        assert!(HmodManifest::parse(&both).is_err());
        let dup = native_toml("") + "[[native.mixin]]\nid = \"d\"\n[[native.loop]]\nid = \"d\"\n";
        assert!(HmodManifest::parse(&dup).unwrap_err().to_string().contains("duplicate"));
    }
    #[test] fn content_mod_without_code() {
        let m = HmodManifest::parse("id = \"pack\"\nversion = \"0.1.0\"\napi = \"0.2\"\n").unwrap();
        assert!(m.native.is_none() && m.wasm.is_none());
    }
    #[test] fn wasm_hash_is_checked() {
        let wasm = b"\0asm\x01\0\0\0";
        let good = format!("{:x}", Sha512::digest(wasm));
        let t = |h: &str| format!("id = \"w\"\nversion = \"1.0.0\"\napi = \"0.2\"\n[wasm]\nsha512 = \"{h}\"\ntoolchain = \"x\"\n");
        let ok = zip_of(&[(HMOD_MANIFEST, t(&good).as_bytes()), ("mod.wasm", wasm)]);
        assert!(read_hmod(ok).is_ok());
        let bad = zip_of(&[(HMOD_MANIFEST, t(&"0".repeat(128)).as_bytes()), ("mod.wasm", wasm)]);
        assert!(read_hmod(bad).is_err());
    }
    #[test] fn native_package_requires_crate_and_helper_sources() {
        let mut manifest = native_toml("");
        manifest.push_str(
            r#"
[[native.mixin]]
id = "entry"
target = "horizon_client::game::Game::tick"
kind = "HEAD"
helper = "src/mixins/entry.rs"
"#,
        );
        let cargo = br#"[package]
name = "demo"
version = "1.0.0"
edition = "2024"
"#;
        let ok = zip_of(&[
            (HMOD_MANIFEST, manifest.as_bytes()), ("Cargo.toml", cargo),
            ("src/lib.rs", b"//"), ("src/mixins/entry.rs", b"fn entry() {}"),
        ]);
        assert!(read_hmod(ok).is_ok());

        let missing_helper = zip_of(&[
            (HMOD_MANIFEST, manifest.as_bytes()), ("Cargo.toml", cargo), ("src/lib.rs", b"//"),
        ]);
        assert!(read_hmod(missing_helper).unwrap_err().to_string().contains("existing file under src"));

        let missing_crate = zip_of(&[(HMOD_MANIFEST, native_toml("").as_bytes())]);
        assert!(read_hmod(missing_crate).unwrap_err().to_string().contains("Cargo.toml and src/lib.rs"));
    }

    #[test] fn inspect_picks_format() {
        let hmod = zip_of(&[
            (HMOD_MANIFEST, native_toml("").as_bytes()),
            ("Cargo.toml", b"[package]\nname = \"demo\"\nversion = \"1.0.0\"\nedition = \"2024\"\n"),
            ("src/lib.rs", b"//"),
        ]);
        assert!(matches!(inspect(hmod).unwrap(), Inspected::Hmod { kind: "native", .. }));
        let manifest = format!("{{\"id\":\"old_mod\",\"game\":\"26.3\",\"horizon\":\"{}\",\"mixins\":\"{}\"}}", "a".repeat(40), "b".repeat(40));
        let mixin = "[mod]\nid = \"old_mod\"\nversion = \"1.0.0\"\n";
        let old = zip_of(&[("horizon-native.json", manifest.as_bytes()), ("mixins/old_mod/horizon-mixin.toml", mixin.as_bytes())]);
        assert!(matches!(inspect(old).unwrap(), Inspected::Hnmod { .. }));
    }
}
