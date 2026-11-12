//! Small command-line front end used by the Android Termux compiler.
use horizon_hnmod_core::{dependencies, is_revision, read_bundle, resolve_compatibility, EngineCandidate};
use serde_json::json;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Component, Path};

fn main() {
    if let Err(error) = run() {
        eprintln!("hnmod: {error}");
        std::process::exit(2);
    }
}

fn run() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    match args.next().as_deref() {
        Some("inspect") => {
            let archive = args.next().ok_or("usage: hnmod inspect FILE.hnmod")?;
            if args.next().is_some() { return Err("too many arguments".into()); }
            let bundle = read_bundle(std::fs::File::open(archive)?)?;
            println!("{}", serde_json::to_string(&json!({
                "manifest": bundle.manifest,
                "sha256": bundle.sha256
            }))?);
        }
        Some("extract") => {
            let archive = args.next().ok_or("usage: hnmod extract FILE.hnmod NEW_DIRECTORY")?;
            let destination = args.next().ok_or("usage: hnmod extract FILE.hnmod NEW_DIRECTORY")?;
            if args.next().is_some() { return Err("too many arguments".into()); }
            let bundle = read_bundle(std::fs::File::open(archive)?)?;
            let root = Path::new(&destination);
            fs::create_dir(root)?;
            let prefix = format!("mixins/{}/", bundle.manifest.id);
            let mut count = 0usize;
            for (name, bytes) in &bundle.files {
                let Some(relative) = name.strip_prefix(&prefix) else { continue };
                let relative_path = Path::new(relative);
                if relative_path.as_os_str().is_empty()
                    || !relative_path.components().all(|part| matches!(part, Component::Normal(_)))
                {
                    return Err(format!("unsafe relative path: {relative}").into());
                }
                let target = root.join(relative_path);
                if let Some(parent) = target.parent() { fs::create_dir_all(parent)?; }
                let mut output = OpenOptions::new().write(true).create_new(true).open(&target)?;
                output.write_all(bytes)?;
                count += 1;
            }
            if count == 0 { return Err("bundle contains no mixin files".into()); }
            println!("{}", serde_json::to_string(&json!({
                "id": bundle.manifest.id,
                "version": bundle.manifest.version,
                "files": count,
                "sha256": bundle.sha256
            }))?);
        }
        Some("prepare") => {
            let mods = args.next().ok_or("usage: hnmod prepare MODS_DIRECTORY PLAN.json")?;
            let plan = args.next().ok_or("usage: hnmod prepare MODS_DIRECTORY PLAN.json")?;
            if args.next().is_some() { return Err("too many arguments".into()); }
            let has_api = horizon_hnmod_core::pc_api::prepare(Path::new(&mods), Path::new(&plan))?;
            println!("{}", serde_json::to_string(&json!({ "has_api": has_api, "plan": plan }))?);
        }
        Some("stage-api") => {
            let mods = args.next().ok_or("usage: hnmod stage-api MODS_DIRECTORY STAGE_DIRECTORY PLAN.json")?;
            let stage = args.next().ok_or("usage: hnmod stage-api MODS_DIRECTORY STAGE_DIRECTORY PLAN.json")?;
            let plan = args.next().ok_or("usage: hnmod stage-api MODS_DIRECTORY STAGE_DIRECTORY PLAN.json")?;
            if args.next().is_some() { return Err("too many arguments".into()); }
            let has_api = horizon_hnmod_core::pc_api::stage(Path::new(&mods), Path::new(&stage), Path::new(&plan))?;
            println!("{}", serde_json::to_string(&json!({ "has_api": has_api }))?);
        }
        Some("resolve") => {
            let revision = args.next().ok_or("usage: hnmod resolve REVISION HOOKS_API MIXINS_REV MOD...")?;
            let hooks_api: u32 = args.next().ok_or("missing hooks API")?.parse()?;
            let mixins_revision = args.next().ok_or("missing mixins revision")?;
            if !is_revision(&revision) || !is_revision(&mixins_revision) {
                return Err("engine and mixins revisions must be full lowercase 40-character commit IDs".into());
            }
            let paths: Vec<_> = args.collect();
            if paths.is_empty() { return Err("give at least one .hnmod archive".into()); }
            let mut bundles = Vec::with_capacity(paths.len());
            for path in &paths {
                let file = std::fs::File::open(path)?;
                bundles.push(read_bundle(file)?);
            }
            // Resolve the exact PC [dependencies] graph before any files are staged.
            // API packages are required for every installed dependency, including present optional deps.
            let mut declared = Vec::with_capacity(bundles.len());
            for bundle in &bundles {
                let manifest_path = format!("mixins/{}/horizon-mixin.toml", bundle.manifest.id);
                let raw = bundle.files.get(&manifest_path)
                    .ok_or_else(|| format!("{}: missing mixin manifest", bundle.manifest.id))?;
                let manifest_text = std::str::from_utf8(raw)
                    .map_err(|e| format!("{}: {e}", manifest_path))?;
                let value: toml::Value = toml::from_str(manifest_text)
                    .map_err(|e| format!("{}: {e}", manifest_path))?;
                let api_path = value.get("api").and_then(|v| v.get("path")).and_then(toml::Value::as_str);
                let has_api = if let Some(path) = api_path {
                    let relative = Path::new(path);
                    if relative.is_absolute() || !relative.components().all(|c| matches!(c, Component::Normal(_))) {
                        return Err(format!("{}: invalid [api] path {path:?}", bundle.manifest.id).into());
                    }
                    let cargo = format!("mixins/{}/{}/Cargo.toml", bundle.manifest.id, path.trim_end_matches('/'));
                    if !bundle.files.contains_key(&cargo) {
                        return Err(format!("{}: [api] path must point to a bundled crate with Cargo.toml", bundle.manifest.id).into());
                    }
                    true
                } else { false };
                let deps = dependencies::parse(&bundle.manifest.id, value.get("dependencies"))
                    .map_err(|e| e.to_string())?;
                declared.push((has_api, deps));
            }
            let inputs: Vec<_> = bundles.iter().zip(&declared).map(|(bundle, (has_api, deps))| dependencies::Mod {
                id: &bundle.manifest.id,
                version: &bundle.manifest.version,
                has_api: *has_api,
                dependencies: deps,
            }).collect();
            let dependency_order = dependencies::resolve(&inputs).map_err(|e| e.to_string())?
                .order.into_iter().map(|i| bundles[i].manifest.id.clone()).collect::<Vec<_>>();
            let candidate = EngineCandidate { revision, hooks_api, mixins_revision };
            let plan = resolve_compatibility(&bundles, &[candidate])?;
            println!("{}", serde_json::to_string(&json!({
                "revision": plan.selected.revision,
                "hooks_api": plan.selected.hooks_api,
                "mixins_revision": plan.selected.mixins_revision,
                "mods": plan.mods,
                "warnings": plan.warnings,
                "dependency_order": dependency_order
            }))?);
        }
        _ => {
            eprintln!("Usage:");
            eprintln!("  hnmod inspect FILE.hnmod");
            eprintln!("  hnmod extract FILE.hnmod NEW_DIRECTORY");
            eprintln!("  hnmod prepare MODS_DIRECTORY PLAN.json");
            eprintln!("  hnmod stage-api MODS_DIRECTORY STAGE_DIRECTORY PLAN.json");
            eprintln!("  hnmod resolve REVISION HOOKS_API MIXINS_REV MOD.hnmod...");
            return Err("unknown command".into());
        }
    }
    Ok(())
}
