//! Deterministic multi-mod planning for the Nov 12 native HMOD pipeline.
//!
//! This resolves a set of already validated native packages before any compiler or linker runs.
//! It deliberately refuses mixed game/source/tool revisions: one native build must use one exact
//! prebuilt game SDK and one exact horizon-mixins checkout.
use crate::{dependencies, hmod::Hmod, Error};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Clone, Debug, serde::Serialize)]
pub struct NativeBuildPlan {
    pub game: String,
    pub horizon: String,
    pub mixins: String,
    pub mods: Vec<String>,
    pub dependency_order: Vec<String>,
    pub build_key: String,
}

fn compatibility(message: impl Into<String>) -> Error {
    Error::Compatibility(message.into())
}

/// Resolve all selected native HMODs as one build. The returned key is stable regardless of
/// selection order and includes every archive digest plus target/profile/SDK identity.
pub fn resolve(
    bundles: &[Hmod],
    target: &str,
    profile: &str,
    sdk_identity: &str,
) -> Result<NativeBuildPlan, Error> {
    if bundles.is_empty() {
        return Err(compatibility("no HMOD packages selected"));
    }
    if target.trim().is_empty() || profile.trim().is_empty() || sdk_identity.trim().is_empty() {
        return Err(compatibility("target, profile and SDK identity are required"));
    }

    let first = bundles[0].manifest.native.as_ref()
        .ok_or_else(|| compatibility(format!("{} is not a native HMOD", bundles[0].manifest.id)))?;
    let game = first.game.clone();
    let horizon = first.horizon.clone();
    let mixins = first.mixins.clone();

    let mut ids = BTreeSet::new();
    let mut declared = Vec::with_capacity(bundles.len());
    for bundle in bundles {
        let manifest = &bundle.manifest;
        let native = manifest.native.as_ref()
            .ok_or_else(|| compatibility(format!("{} is content/WASM, not a native HMOD", manifest.id)))?;
        if !ids.insert(manifest.id.clone()) {
            return Err(compatibility(format!("duplicate native mod id: {}", manifest.id)));
        }
        if native.game != game || native.horizon != horizon || native.mixins != mixins {
            return Err(compatibility(format!(
                "{} targets game/Horizon/mixins {}/{}/{}, but this build requires {}/{}/{}",
                manifest.id, native.game, native.horizon, native.mixins, game, horizon, mixins
            )));
        }
        if !bundle.files.contains_key("Cargo.toml") || !bundle.files.contains_key("src/lib.rs") {
            return Err(compatibility(format!(
                "{} is missing root Cargo.toml or src/lib.rs (native HMODs use a flat project layout)",
                manifest.id
            )));
        }
        let dependency_table = toml::Value::Table(
            manifest.dependencies.iter().map(|(key, value)| (key.clone(), value.clone())).collect::<toml::Table>()
        );
        let deps = dependencies::parse(&manifest.id, Some(&dependency_table))
            .map_err(|e| compatibility(e.to_string()))?;
        declared.push(deps);
    }

    let inputs: Vec<_> = bundles.iter().zip(&declared).map(|(bundle, deps)| dependencies::Mod {
        id: &bundle.manifest.id,
        version: &bundle.manifest.version,
        has_api: true,
        dependencies: deps,
    }).collect();
    let order = dependencies::resolve(&inputs).map_err(|e| compatibility(e.to_string()))?;
    let dependency_order = order.order.into_iter().map(|i| bundles[i].manifest.id.clone()).collect();

    let mut hash = Sha256::new();
    for part in [game.as_str(), horizon.as_str(), mixins.as_str(), target, profile, sdk_identity] {
        hash.update((part.len() as u64).to_le_bytes());
        hash.update(part.as_bytes());
    }
    let mut sorted: Vec<_> = bundles.iter().collect();
    sorted.sort_by(|a, b| a.manifest.id.cmp(&b.manifest.id));
    for bundle in sorted {
        hash.update((bundle.manifest.id.len() as u64).to_le_bytes());
        hash.update(bundle.manifest.id.as_bytes());
        hash.update(bundle.sha256.as_bytes());
    }
    let build_key = format!("{:x}", hash.finalize());

    let mut versions = BTreeMap::new();
    for bundle in bundles {
        versions.insert(bundle.manifest.id.clone(), bundle.manifest.version.clone());
    }
    let mods = versions.into_keys().collect();

    Ok(NativeBuildPlan { game, horizon, mixins, mods, dependency_order, build_key })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::hmod::{read_hmod, HMOD_MANIFEST};
    use std::io::{Cursor, Write};
    use zip::{write::SimpleFileOptions, ZipWriter};

    fn archive(id: &str, version: &str, horizon: &str, mixins: &str, dependencies: &str) -> Hmod {
        let manifest = format!(
            "id = \"{id}\"\nversion = \"{version}\"\napi = \"0.2\"\n[native]\ngame = \"26.3\"\nhorizon = \"{horizon}\"\nmixins = \"{mixins}\"\n{dependencies}"
        );
        let cargo = "[package]\nname = \"test-mod\"\nversion = \"0.1.0\"\nedition = \"2024\"\n";
        let mut out = Cursor::new(Vec::new());
        {
            let mut zip = ZipWriter::new(&mut out);
            let options = SimpleFileOptions::default();
            zip.start_file(HMOD_MANIFEST, options).unwrap();
            zip.write_all(manifest.as_bytes()).unwrap();
            zip.start_file("Cargo.toml", options).unwrap();
            zip.write_all(cargo.as_bytes()).unwrap();
            zip.start_file("src/lib.rs", options).unwrap();
            zip.write_all(b"pub struct Mod;").unwrap();
            zip.finish().unwrap();
        }
        read_hmod(Cursor::new(out.into_inner())).unwrap()
    }

    #[test]
    fn resolves_multiple_mods_in_dependency_order_and_stable_key() {
        let base = archive("base", "1.2.0", &"a".repeat(40), &"b".repeat(40), "");
        let consumer = archive("consumer", "1.0.0", &"a".repeat(40), &"b".repeat(40),
            "[dependencies]\nbase = \"^1.0\"\n");
        let first = resolve(&[consumer.clone(), base.clone()], "aarch64-linux-android", "fast", "sdk-sha")
            .unwrap();
        let second = resolve(&[base, consumer], "aarch64-linux-android", "fast", "sdk-sha")
            .unwrap();
        assert_eq!(first.dependency_order, ["base", "consumer"]);
        assert_eq!(first.build_key, second.build_key);
        assert_eq!(first.mods, ["base", "consumer"]);
    }

    #[test]
    fn rejects_mixed_game_build_pins() {
        let one = archive("one", "1.0.0", &"a".repeat(40), &"b".repeat(40), "");
        let two = archive("two", "1.0.0", &"c".repeat(40), &"b".repeat(40), "");
        let error = resolve(&[one, two], "aarch64-linux-android", "fast", "sdk-sha")
            .unwrap_err().to_string();
        assert!(error.contains("targets game/Horizon/mixins"));
    }

    #[test]
    fn rejects_missing_required_dependency() {
        let consumer = archive("consumer", "1.0.0", &"a".repeat(40), &"b".repeat(40),
            "[dependencies]\nmissing = \"^1\"\n");
        let error = resolve(&[consumer], "aarch64-linux-android", "fast", "sdk-sha")
            .unwrap_err().to_string();
        assert!(error.contains("requires missing mod missing"));
    }
}
