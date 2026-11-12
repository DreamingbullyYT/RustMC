//! Android adaptation of the PC launcher's trait-API staging pipeline.
//! The Android game crate remains the JNI/NativeActivity entry point; only its hook list changes.
use crate::dependencies::{self, Dependency, Mod, Package};
use semver::VersionReq;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::fs;
use std::path::{Component, Path};

fn table(path: &Path) -> Result<toml::Table, String> {
    fs::read_to_string(path)
        .map_err(|e| format!("{}: {e}", path.display()))?
        .parse()
        .map_err(|e| format!("{}: {e}", path.display()))
}
fn write_table(path: &Path, value: &toml::Table) -> Result<(), String> {
    let text = toml::to_string(value).map_err(|e| format!("{}: {e}", path.display()))?;
    fs::write(path, text).map_err(|e| format!("{}: {e}", path.display()))
}
fn copy_tree(from: &Path, to: &Path) -> Result<(), String> {
    let meta = fs::symlink_metadata(from).map_err(|e| format!("{}: {e}", from.display()))?;
    if meta.file_type().is_symlink() || !meta.is_dir() {
        return Err(format!("{}: API package contains a linked or non-directory root", from.display()));
    }
    fs::create_dir_all(to).map_err(|e| e.to_string())?;
    for entry in fs::read_dir(from).map_err(|e| e.to_string())? {
        let entry = entry.map_err(|e| e.to_string())?;
        let src = entry.path();
        let dst = to.join(entry.file_name());
        let meta = fs::symlink_metadata(&src).map_err(|e| e.to_string())?;
        if meta.file_type().is_symlink() {
            return Err(format!("{}: linked files are not allowed in an API package", src.display()));
        }
        if meta.is_dir() {
            copy_tree(&src, &dst)?;
        } else if meta.is_file() {
            if let Some(parent) = dst.parent() { fs::create_dir_all(parent).map_err(|e| e.to_string())?; }
            fs::copy(&src, &dst).map_err(|e| format!("{}: {e}", src.display()))?;
        } else {
            return Err(format!("{}: unsupported API package file", src.display()));
        }
    }
    Ok(())
}
fn valid_relative(path: &str) -> bool {
    !path.is_empty() && !path.contains('\\') && !Path::new(path).is_absolute()
        && Path::new(path).components().all(|c| matches!(c, Component::Normal(_)))
}

/// Validate the PC dependency graph, persist a plan, and remove launcher-only tables
/// before horizon-mixins parses the ordinary mixin manifest.
pub fn prepare(mods_dir: &Path, plan_path: &Path) -> Result<bool, String> {
    let mut dirs = Vec::new();
    for entry in fs::read_dir(mods_dir).map_err(|e| e.to_string())? {
        let entry = entry.map_err(|e| e.to_string())?;
        if entry.file_type().map_err(|e| e.to_string())?.is_dir() { dirs.push(entry.path()); }
    }
    dirs.sort();
    let mut rows = Vec::new();
    let mut declared: Vec<Vec<Dependency>> = Vec::new();
    for dir in &dirs {
        let manifest_path = dir.join("horizon-mixin.toml");
        let mut manifest = table(&manifest_path)?;
        let id = manifest.get("mod").and_then(|v| v.get("id")).and_then(toml::Value::as_str)
            .ok_or_else(|| format!("{}: missing [mod].id", manifest_path.display()))?.to_owned();
        let version = manifest.get("mod").and_then(|v| v.get("version")).and_then(toml::Value::as_str)
            .ok_or_else(|| format!("{}: missing [mod].version", manifest_path.display()))?.to_owned();
        let api_path = if let Some(api) = manifest.get("api") {
            let api = api.as_table().ok_or_else(|| format!("{id}: [api] must be a table"))?;
            if api.len() != 1 || !api.contains_key("path") {
                return Err(format!("{id}: [api] accepts only path"));
            }
            let path = api.get("path").and_then(toml::Value::as_str)
                .ok_or_else(|| format!("{id}: [api].path must be a string"))?.to_owned();
            if !valid_relative(&path) { return Err(format!("{id}: invalid [api] path {path:?}")); }
            let crate_dir = dir.join(&path);
            let root = dir.canonicalize().map_err(|e| e.to_string())?;
            let resolved = crate_dir.canonicalize().map_err(|e| format!("{id}: {e}"))?;
            if !resolved.starts_with(&root) || !resolved.join("Cargo.toml").is_file() {
                return Err(format!("{id}: [api] path must be a crate inside the bundle with Cargo.toml"));
            }
            let crate_manifest = table(&resolved.join("Cargo.toml"))?;
            let package = crate_manifest.get("package").and_then(|p| p.get("name"))
                .and_then(toml::Value::as_str).ok_or_else(|| format!("{id}: API Cargo.toml has no package.name"))?;
            Some((path, package.to_owned()))
        } else { None };
        let deps = dependencies::parse(&id, manifest.get("dependencies")).map_err(|e| e.to_string())?;
        declared.push(deps);
        rows.push(json!({
            "id": id,
            "version": version,
            "api_path": api_path.as_ref().map(|v| v.0.clone()),
            "package": api_path.as_ref().map(|v| v.1.clone()),
            "dependencies": declared.last().unwrap().iter().map(|d| json!({
                "id": d.id, "version": d.requirement.to_string(), "optional": d.optional
            })).collect::<Vec<_>>()
        }));
        manifest.remove("api");
        manifest.remove("dependencies");
        write_table(&manifest_path, &manifest)?;
    }
    let inputs: Vec<_> = rows.iter().zip(&declared).map(|(row, deps)| Mod {
        id: row["id"].as_str().unwrap(),
        version: row["version"].as_str().unwrap(),
        has_api: row["api_path"].is_string(),
        dependencies: deps,
    }).collect();
    let resolution = dependencies::resolve(&inputs).map_err(|e| e.to_string())?;
    let order: Vec<String> = resolution.order.iter().map(|&i| rows[i]["id"].as_str().unwrap().to_owned()).collect();
    let has_api = rows.iter().any(|r| r["api_path"].is_string());
    let plan = json!({ "mods": rows, "order": order, "has_api": has_api });
    if let Some(parent) = plan_path.parent() { fs::create_dir_all(parent).map_err(|e| e.to_string())?; }
    fs::write(plan_path, serde_json::to_vec_pretty(&plan).map_err(|e| e.to_string())?)
        .map_err(|e| e.to_string())?;
    Ok(has_api)
}

/// Stage PC trait crates into the Android workspace and attach their hooks to the existing
/// horizon-android-game JNI library, rather than generating the PC desktop executable.
pub fn stage(mods_dir: &Path, stage: &Path, plan_path: &Path) -> Result<bool, String> {
    let plan: Value = serde_json::from_slice(&fs::read(plan_path).map_err(|e| e.to_string())?)
        .map_err(|e| e.to_string())?;
    if plan["has_api"] != true { return Ok(false); }
    let rows = plan["mods"].as_array().ok_or("invalid HNMod API plan")?;
    let order = plan["order"].as_array().ok_or("invalid HNMod dependency order")?;
    let by_id: HashMap<&str, &Value> = rows.iter().map(|r| (r["id"].as_str().unwrap_or(""), r)).collect();
    let api_ids: Vec<&str> = order.iter().filter_map(Value::as_str)
        .filter(|id| by_id.get(id).is_some_and(|r| r["api_path"].is_string())).collect();
    let mut packages = HashMap::new();
    for id in &api_ids {
        let row = by_id[id];
        packages.insert(*id, row["package"].as_str().ok_or("missing API package name")?.to_owned());
    }
    let mut game_deps = table(&stage.join("crates/horizon-android-game/Cargo.toml"))?;
    let game_dependencies = game_deps.entry("dependencies")
        .or_insert_with(|| toml::Value::Table(toml::Table::new()))
        .as_table_mut().ok_or("horizon-android-game dependencies is not a table")?;
    let mut workspace = table(&stage.join("Cargo.toml"))?;
    let members = workspace.get_mut("workspace").and_then(|w| w.get_mut("members"))
        .and_then(toml::Value::as_array_mut).ok_or("workspace has no members array")?;

    for (index, id) in api_ids.iter().enumerate() {
        let row = by_id[id];
        let relative = row["api_path"].as_str().ok_or("missing API path")?;
        let source = mods_dir.join(id).join(relative);
        let dest_rel = format!("native-api/{id}");
        let dest = stage.join(&dest_rel);
        if dest.exists() { fs::remove_dir_all(&dest).map_err(|e| e.to_string())?; }
        copy_tree(&source, &dest)?;
        let manifest_path = dest.join("Cargo.toml");
        let mut manifest = table(&manifest_path)?;
        let deps_table = manifest.get_mut("dependencies").and_then(toml::Value::as_table_mut)
            .ok_or_else(|| format!("{id}: API crate must depend on horizon-client"))?;
        let client = deps_table.iter_mut().find(|(key, value)| {
            *key == "horizon-client" || value.get("package").and_then(toml::Value::as_str) == Some("horizon-client")
        }).map(|(_, value)| value).ok_or_else(|| format!("{id}: API crate must depend on horizon-client"))?;
        let mut rewritten = toml::Table::new();
        rewritten.insert("path".into(), "../../crates/horizon-client".into());
        rewritten.insert("default-features".into(), false.into());
        if let Some(old) = client.as_table() {
            for key in ["package", "features"] {
                if let Some(value) = old.get(key) { rewritten.insert(key.into(), value.clone()); }
            }
        }
        *client = toml::Value::Table(rewritten);
        let dep_rows = row["dependencies"].as_array().ok_or("invalid dependency list")?;
        let parsed: Vec<Dependency> = dep_rows.iter().map(|d| Ok(Dependency {
            id: d["id"].as_str().ok_or("invalid dependency id")?.to_owned(),
            requirement: VersionReq::parse(d["version"].as_str().ok_or("invalid dependency version")?)
                .map_err(|e| e.to_string())?,
            optional: d["optional"].as_bool().unwrap_or(false),
        })).collect::<Result<_, String>>()?;
        let targets: Vec<Package<'_>> = parsed.iter().map(|d| Package {
            dependency: d,
            package: packages.get(d.id.as_str()).map(String::as_str),
        }).collect();
        let enabled = dependencies::rewrite(id, &mut manifest, &targets).map_err(|e| e.to_string())?;
        write_table(&manifest_path, &manifest)?;
        let mut dep = toml::Table::new();
        dep.insert("path".into(), format!("../../{dest_rel}").into());
        dep.insert("package".into(), row["package"].as_str().unwrap().into());
        if !enabled.is_empty() {
            dep.insert("features".into(), toml::Value::Array(enabled.into_iter().map(toml::Value::String).collect()));
        }
        game_dependencies.insert(format!("mod_{index}"), toml::Value::Table(dep));
        let member = toml::Value::String(dest_rel);
        if !members.contains(&member) { members.push(member); }
    }
    write_table(&stage.join("Cargo.toml"), &workspace)?;
    write_table(&stage.join("crates/horizon-android-game/Cargo.toml"), &game_deps)?;

    let mut game = fs::read_to_string(stage.join("crates/horizon-android-game/src/lib.rs")).map_err(|e| e.to_string())?;
    let entries = (0..api_ids.len()).map(|i| format!("        mod_{i}::hooks(),\n")).collect::<String>();
    let ids = format!("{:?}", api_ids);
    let source = stage.join("crates/horizon-client/src/mod_api.rs");
    let naming = if fs::read_to_string(&source).is_ok_and(|s| s.contains("pub fn name_mods(")) {
        format!("    horizon_client::mod_api::name_mods(&{ids});\n")
    } else { String::new() };
    let replacement = format!(
        "let mods: horizon_client::mod_api::Mods = vec![\n{entries}    ].into();\n{naming}    horizon_client::entry::main_with(mods);"
    );
    let old = "horizon_client::entry::main_with(Default::default());";
    if !game.contains(old) { return Err("Android game entry point changed; cannot safely attach native trait mods".into()); }
    game = game.replace(old, &replacement);
    fs::write(stage.join("crates/horizon-android-game/src/lib.rs"), game).map_err(|e| e.to_string())?;
    Ok(true)
}


#[cfg(test)]
mod tests {
    use super::*;

    fn put(path: &Path, body: &str) {
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, body).unwrap();
    }

    #[test]
    fn stages_pc_trait_apis_in_dependency_order_on_android_entry() {
        let root = std::env::temp_dir().join(format!("hnmod-pc-api-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let mods = root.join("mods");
        let stage_dir = root.join("stage");
        let plan = root.join("plan.json");

        for (id, version, dep) in [
            ("addon", "1.0.0", Some("[dependencies]\nbase = \"^1\"\n")),
            ("base", "1.2.0", None),
        ] {
            let dir = mods.join(id);
            let dependency = dep.unwrap_or("");
            put(&dir.join("horizon-mixin.toml"), &format!(
                "[mod]\nid = \"{id}\"\nversion = \"{version}\"\n[api]\npath = \"engine\"\n{dependency}"
            ));
            put(&dir.join("engine/Cargo.toml"), &format!(
                "[package]\nname = \"{id}-api\"\nversion = \"0.1.0\"\nedition = \"2024\"\n[dependencies]\nhorizon-client = \"0.1\"\n"
            ));
            put(&dir.join("engine/src/lib.rs"), "pub fn hooks() {}");
        }

        put(&stage_dir.join("Cargo.toml"), "[workspace]\nmembers = [\"crates/horizon-android-game\", \"crates/horizon-client\"]\n");
        put(&stage_dir.join("crates/horizon-client/Cargo.toml"), "[package]\nname = \"horizon-client\"\nversion = \"0.1.0\"\n");
        put(&stage_dir.join("crates/horizon-client/src/mod_api.rs"), "pub fn name_mods() {}\n");
        put(&stage_dir.join("crates/horizon-android-game/Cargo.toml"),
            "[package]\nname = \"horizon-android-game\"\nversion = \"0.1.0\"\n[dependencies]\nhorizon-client = { path = \"../horizon-client\" }\n");
        put(&stage_dir.join("crates/horizon-android-game/src/lib.rs"),
            "fn start() { horizon_client::entry::main_with(Default::default()); }\n");

        assert!(prepare(&mods, &plan).unwrap());
        let addon_manifest = table(&mods.join("addon/horizon-mixin.toml")).unwrap();
        assert!(!addon_manifest.contains_key("api"));
        assert!(!addon_manifest.contains_key("dependencies"));
        assert!(stage(&mods, &stage_dir, &plan).unwrap());

        let game_manifest = table(&stage_dir.join("crates/horizon-android-game/Cargo.toml")).unwrap();
        assert_eq!(game_manifest["dependencies"]["mod_0"]["package"].as_str(), Some("base-api"));
        assert_eq!(game_manifest["dependencies"]["mod_0"]["path"].as_str(), Some("../../native-api/base"));
        let addon_api = table(&stage_dir.join("native-api/addon/Cargo.toml")).unwrap();
        assert_eq!(addon_api["dependencies"]["base"]["path"].as_str(), Some("../base"));
        assert_eq!(addon_api["dependencies"]["base"]["package"].as_str(), Some("base-api"));
        let main = fs::read_to_string(stage_dir.join("crates/horizon-android-game/src/lib.rs")).unwrap();
        assert!(main.contains("mod_0::hooks()"));
        assert!(main.contains("mod_1::hooks()"));
        assert!(main.contains("name_mods"));
        assert!(main.contains("entry::main_with(mods)"));
        let workspace = table(&stage_dir.join("Cargo.toml")).unwrap();
        assert!(workspace["workspace"]["members"].as_array().unwrap().iter()
            .any(|m| m.as_str() == Some("native-api/base")));

        let _ = fs::remove_dir_all(root);
    }
}
