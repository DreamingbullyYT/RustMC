//! Deterministic multi-mod planning against an exact published native SDK.
//!
//! Planning never invokes a compiler. It refuses host-only SDKs for Android and mismatched pins
//! before the launcher downloads or extracts anything.
use crate::{dependencies, hmod::Hmod, sdk::VerifiedIdentity, Error};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Clone, Debug, serde::Serialize)]
pub struct NativeBuildPlan {
    pub game: String,
    pub horizon: String,
    pub mixins: String,
    pub target: String,
    pub profile: String,
    pub sdk_index_sha256: String,
    pub mods: Vec<String>,
    pub dependency_order: Vec<String>,
    pub build_key: String,
}


const API_PACKAGES: [&str; 3] = [
    "horizon-mod-api",
    "horizon-mod-points",
    "horizon-internals",
];

fn dependency_tables(cargo: &toml::Table) -> Vec<(Option<&str>, &toml::Table)> {
    let mut tables = Vec::new();
    if let Some(table) = cargo.get("dependencies").and_then(toml::Value::as_table) {
        tables.push((None, table));
    }
    if let Some(targets) = cargo.get("target").and_then(toml::Value::as_table) {
        for (target, config) in targets {
            if let Some(table) = config.get("dependencies").and_then(toml::Value::as_table) {
                tables.push((Some(target.as_str()), table));
            }
        }
    }
    tables
}


fn all_dependency_tables(cargo: &toml::Table) -> Vec<(String, &toml::Table)> {
    const SECTIONS: [&str; 5] = [
        "dependencies", "dev-dependencies", "build-dependencies",
        "dev_dependencies", "build_dependencies",
    ];
    let mut tables = Vec::new();
    for section in SECTIONS {
        if let Some(table) = cargo.get(section).and_then(toml::Value::as_table) {
            tables.push((section.to_owned(), table));
        }
    }
    if let Some(targets) = cargo.get("target").and_then(toml::Value::as_table) {
        for (target, config) in targets {
            for section in SECTIONS {
                if let Some(table) = config.get(section).and_then(toml::Value::as_table) {
                    tables.push((format!("target.{target}.{section}"), table));
                }
            }
        }
    }
    tables
}

/// Mirror the upstream prebuilt compiler's refusal rules before any Cargo process can run.
fn validate_native_crate(bundle: &Hmod) -> Result<(), Error> {
    let id = &bundle.manifest.id;
    if bundle.files.contains_key(".cargo/config") || bundle.files.contains_key(".cargo/config.toml") {
        return Err(compatibility(format!("{id}: E_NATIVE_CARGO_CONFIG: .cargo/config is not allowed in native mods")));
    }
    let bytes = bundle.files.get("Cargo.toml")
        .ok_or_else(|| compatibility(format!("{id}: missing Cargo.toml")))?;
    let text = std::str::from_utf8(bytes)
        .map_err(|_| compatibility(format!("{id}: Cargo.toml is not UTF-8")))?;
    let cargo: toml::Table = toml::from_str(text)
        .map_err(|e| compatibility(format!("{id}: invalid Cargo.toml: {e}")))?;
    for section in ["patch", "replace"] {
        if cargo.contains_key(section) {
            return Err(compatibility(format!("{id}: E_NATIVE_CARGO_PATCH: [{section}] is not allowed")));
        }
    }
    let package = cargo.get("package").and_then(toml::Value::as_table)
        .ok_or_else(|| compatibility(format!("{id}: Cargo.toml has no [package] table")))?;
    if package.get("name").and_then(toml::Value::as_str).is_none() {
        return Err(compatibility(format!("{id}: Cargo.toml has no package.name")));
    }
    let build = package.get("build");
    let has_build_script = build.is_some_and(|value| value.as_bool() != Some(false))
        || (build.and_then(toml::Value::as_bool) != Some(false) && bundle.files.contains_key("build.rs"))
        || cargo.get("build-dependencies").and_then(toml::Value::as_table).is_some_and(|t| !t.is_empty());
    if has_build_script {
        return Err(compatibility(format!("{id}: E_NATIVE_BUILD_SCRIPT: build scripts/build-dependencies are not supported by the prebuilt SDK route")));
    }
    if cargo.get("features").and_then(toml::Value::as_table).is_some_and(|t| !t.is_empty()) {
        return Err(compatibility(format!("{id}: E_NATIVE_FEATURES: Cargo features are not supported by the pinned SDK route")));
    }

    for (section, dependencies) in all_dependency_tables(&cargo) {
        for (key, value) in dependencies {
            let package_name = value.get("package").and_then(toml::Value::as_str).unwrap_or(key);
            if package_name == "horizon-client" {
                return Err(compatibility(format!("{id}: E_GAME_DEPENDENCY: {key} directly depends on horizon-client")));
            }
            let is_api = API_PACKAGES.contains(&package_name);
            for selector in ["git", "registry", "registry-index", "branch", "rev", "tag", "workspace"] {
                if value.get(selector).is_some() {
                    return Err(compatibility(format!("{id}: E_SDK_SOURCE: {section}.{key} uses forbidden {selector} source selector")));
                }
            }
            if is_api {
                let expected = format!(".horizon/sdk/api/crates/{package_name}");
                if value.get("path").and_then(toml::Value::as_str) != Some(expected.as_str()) {
                    return Err(compatibility(format!("{id}: E_SDK_SOURCE: {section}.{key} must use pinned SDK path {expected}")));
                }
                let features = value.get("features").and_then(toml::Value::as_array).is_some_and(|v| !v.is_empty());
                let no_defaults = value.get("default-features").and_then(toml::Value::as_bool) == Some(false);
                if features || no_defaults {
                    return Err(compatibility(format!("{id}: E_NATIVE_FEATURES: {section}.{key} changes the pinned SDK feature set")));
                }
            }
        }
    }

    let has_api = dependency_tables(&cargo).iter().any(|(target, dependencies)| {
        target.is_none() && dependencies.iter().any(|(key, value)| {
            value.get("package").and_then(toml::Value::as_str).unwrap_or(key) == "horizon-mod-api"
        })
    });
    if !has_api {
        return Err(compatibility(format!("{id}: E_NATIVE_API_DEPENDENCY: Cargo.toml must depend on horizon-mod-api in [dependencies]")));
    }
    Ok(())
}

fn compatibility(message: impl Into<String>) -> Error {
    Error::Compatibility(message.into())
}

/// Resolve all selected native HMODs against a parsed SDK index. The SDK index itself must already
/// have been authenticated by the release-signature verifier before this function is used to fetch
/// or install any SDK assets.
pub fn resolve(
    bundles: &[Hmod],
    target: &str,
    profile: &str,
    sdk: &VerifiedIdentity,
) -> Result<NativeBuildPlan, Error> {
    if bundles.is_empty() {
        return Err(compatibility("no HMOD packages selected"));
    }
    if target.trim().is_empty() || profile.trim().is_empty() {
        return Err(compatibility("target and profile are required"));
    }
    sdk.validate().map_err(compatibility)?;

    let first = bundles[0].manifest.native.as_ref()
        .ok_or_else(|| compatibility(format!("{} is not a native HMOD", bundles[0].manifest.id)))?;
    let game = first.game.clone();
    let horizon = first.horizon.clone();
    let mixins = first.mixins.clone();
    sdk.require_target(target, &game, &horizon, &mixins).map_err(compatibility)?;

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
        if !bundle.files.contains_key("src/lib.rs") {
            return Err(compatibility(format!(
                "{} is missing root src/lib.rs",
                manifest.id
            )));
        }
        validate_native_crate(bundle)?;
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
    for part in [
        game.as_str(), horizon.as_str(), mixins.as_str(), target, profile,
        sdk.index.build.commit.as_str(), sdk.index_sha256.as_str(),
        sdk.index.toolchain.rust.as_str(),
    ] {
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

    Ok(NativeBuildPlan {
        game, horizon, mixins, target: target.to_owned(), profile: profile.to_owned(),
        sdk_index_sha256: sdk.index_sha256.clone(), mods, dependency_order, build_key,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{hmod::{read_hmod, HMOD_MANIFEST}, sdk::{Asset, Build, Mixins, SdkIndex, Surface, Toolchain}};
    use std::io::{Cursor, Write};
    use zip::{write::SimpleFileOptions, ZipWriter};

    fn archive(id: &str, version: &str, horizon: &str, mixins: &str, dependencies: &str) -> Hmod {
        let dependency_table = if dependencies.is_empty() {
            "dependencies = {}\\n".to_owned()
        } else {
            format!("{dependencies}\\n")
        };
        let manifest = format!(
            "id = \\"{id}\\"\\nname = \\"{id}\\"\\nversion = \\"{version}\\"\\ndescription = \\"native test mod\\"\\nauthors = []\\nlicense = \\"MIT\\"\\ncredits = \\"\\"\\napi = \\"0.2\\"\\nside = \\"client\\"\\ncapabilities = []\\nnamespaces = []\\n{dependency_table}[native]\\ngame = \\"26.3\\"\\nhorizon = \\"{horizon}\\"\\nmixins = \\"{mixins}\\"\\n"
        );
        let cargo = "[package]\nname = \"test-mod\"\nversion = \"0.1.0\"\nedition = \"2024\"\n[dependencies]\nhorizon-mod-api = { path = \".horizon/sdk/api/crates/horizon-mod-api\" }\n";
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

    fn sdk() -> VerifiedIdentity {
        let mut assets = BTreeMap::new();
        for name in ["surface.json", "build-data.tar.xz", "api-source.tar.xz"] {
            let kind = if name == "surface.json" { "surface" } else if name == "build-data.tar.xz" { "build-data" } else { "api-source" };
            let codec = if name == "surface.json" { "none" } else { "xz" };
            assets.insert(name.into(), Asset { sha256: "a".repeat(64), size: 1, kind: kind.into(), codec: codec.into(), target: None, side: None });
        }
        for kind in ["runtime", "api", "engine"] {
            let name = format!("{kind}-aarch64-linux-android.tar.xz");
            assets.insert(name, Asset { sha256: "b".repeat(64), size: 1, kind: kind.into(), codec: "xz".into(), target: Some("aarch64-linux-android".into()), side: Some("client".into()) });
        }
        VerifiedIdentity {
            index: SdkIndex {
                schema: 1,
                build: Build { commit: "a".repeat(40), game: "26.3".into(), date: "2026-11-14".into(), nightly: None },
                package_format: 1,
                toolchain: Toolchain { rust: "1.95.0".into() },
                content_api: "0.2".into(),
                surface: Surface { hash: "e".repeat(64), previous: None, changes: vec![] },
                inputs: serde_json::json!({}),
                mixins: Mixins { rev: "b".repeat(40), patch: "c".repeat(64) },
                assets,
            },
            index_sha256: "d".repeat(64),
        }
    }


    #[test]
    fn rejects_native_cargo_build_scripts_and_features_before_cargo() {
        let mut build_script = archive("build_script", "1.0.0", &"a".repeat(40), &"b".repeat(40), "");
        build_script.files.insert("build.rs".into(), b"fn main() {}".to_vec());
        assert!(resolve(&[build_script], "aarch64-linux-android", "release", &sdk()).unwrap_err().to_string().contains("E_NATIVE_BUILD_SCRIPT"));

        let mut feature = archive("feature_mod", "1.0.0", &"a".repeat(40), &"b".repeat(40), "");
        let mut cargo = String::from_utf8(feature.files["Cargo.toml"].clone()).unwrap();
        cargo.push_str("[features]\ndefault = []\nfast = []\n");
        feature.files.insert("Cargo.toml".into(), cargo.into_bytes());
        assert!(resolve(&[feature], "aarch64-linux-android", "release", &sdk()).unwrap_err().to_string().contains("E_NATIVE_FEATURES"));
    }

    #[test]
    fn rejects_unpinned_sdk_sources_and_direct_game_dependencies() {
        let mut wrong_path = archive("wrong_path", "1.0.0", &"a".repeat(40), &"b".repeat(40), "");
        let cargo = String::from_utf8(wrong_path.files["Cargo.toml"].clone()).unwrap()
            .replace(".horizon/sdk/api/crates/horizon-mod-api", "../horizon-mod-api");
        wrong_path.files.insert("Cargo.toml".into(), cargo.into_bytes());
        assert!(resolve(&[wrong_path], "aarch64-linux-android", "release", &sdk()).unwrap_err().to_string().contains("E_SDK_SOURCE"));

        let mut game_dep = archive("game_dep", "1.0.0", &"a".repeat(40), &"b".repeat(40), "");
        let cargo = String::from_utf8(game_dep.files["Cargo.toml"].clone()).unwrap()
            + "horizon-client = { version = \"0.1\" }\n";
        game_dep.files.insert("Cargo.toml".into(), cargo.into_bytes());
        assert!(resolve(&[game_dep], "aarch64-linux-android", "release", &sdk()).unwrap_err().to_string().contains("E_GAME_DEPENDENCY"));
    }

    #[test]
    fn resolves_multiple_mods_in_dependency_order_and_stable_key() {
        let base = archive("base", "1.2.0", &"a".repeat(40), &"b".repeat(40), "");
        let consumer = archive("consumer", "1.0.0", &"a".repeat(40), &"b".repeat(40),
            "[dependencies]\nbase = \"^1.0\"\n");
        let first = resolve(&[consumer.clone(), base.clone()], "aarch64-linux-android", "release", &sdk()).unwrap();
        let second = resolve(&[base, consumer], "aarch64-linux-android", "release", &sdk()).unwrap();
        assert_eq!(first.dependency_order, ["base", "consumer"]);
        assert_eq!(first.build_key, second.build_key);
        assert_eq!(first.mods, ["base", "consumer"]);
    }

    #[test]
    fn rejects_mixed_game_build_pins() {
        let one = archive("one", "1.0.0", &"a".repeat(40), &"b".repeat(40), "");
        let two = archive("two", "1.0.0", &"c".repeat(40), &"b".repeat(40), "");
        let error = resolve(&[one, two], "aarch64-linux-android", "release", &sdk()).unwrap_err().to_string();
        assert!(error.contains("but this build requires"));
    }

    #[test]
    fn rejects_sdk_without_android_target_assets() {
        let mut sdk = sdk();
        sdk.index.assets.retain(|name, _| !name.contains("aarch64-linux-android"));
        let mod_one = archive("one", "1.0.0", &"a".repeat(40), &"b".repeat(40), "");
        let error = resolve(&[mod_one], "aarch64-linux-android", "release", &sdk).unwrap_err().to_string();
        assert!(error.contains("this target is not published"));
    }

    #[test]
    fn rejects_missing_required_dependency() {
        let consumer = archive("consumer", "1.0.0", &"a".repeat(40), &"b".repeat(40),
            "[dependencies]\nmissing = \"^1\"\n");
        let error = resolve(&[consumer], "aarch64-linux-android", "release", &sdk()).unwrap_err().to_string();
        assert!(error.contains("requires missing mod missing"));
    }
}
