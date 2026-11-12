//! The mod browser for the Android app: a thin JSON facade over `horizon_launcher::browser`, so
//! searching, support checks, checksums, dependency planning and install rules stay exactly what
//! the desktop launcher does and follow upstream automatically.

use std::sync::Mutex;

use horizon_launcher::backend;
use horizon_launcher::browser::{self, installed, Kind, Project, Query, SourceId, Support};
use serde_json::{json, Value};

fn parse<T: serde::de::DeserializeOwned>(text: &str, what: &str) -> Result<T, String> {
    serde_json::from_value(Value::String(text.to_owned())).map_err(|_| format!("unknown {what}: {text}"))
}

fn project_json(project: &Project) -> Value {
    let support = project.support();
    json!({
        "source": project.source,
        "id": project.id,
        "slug": project.slug,
        "title": project.title,
        "summary": project.summary,
        "author": project.author,
        "downloads": project.downloads,
        "page": project.page,
        "icon": project.icon,
        "license": project.license,
        "kind": project.kind,
        "support": support.label(),
        "installable": support.installable(),
        "reason": support.reason(),
        "works": matches!(support, Support::Works),
    })
}

/// `{ "kinds": [...], "sources": [...] }`: what the browser offers.
pub fn catalog() -> Result<String, String> {
    let kinds: Vec<Value> = Kind::ALL.iter().map(|k| json!({ "id": k, "label": k.label() })).collect();
    let sources: Vec<Value> = [SourceId::Horizon, SourceId::Modrinth]
        .iter()
        .map(|s| json!({ "id": s, "label": s.label() }))
        .collect();
    Ok(json!({ "kinds": kinds, "sources": sources, "game": browser::GAME }).to_string())
}

pub fn search(source: &str, kind: &str, text: &str, offset: i32, everything: bool) -> Result<String, String> {
    let source_id: SourceId = parse(source, "source")?;
    let kind: Kind = parse(kind, "kind")?;
    let source = browser::source(source_id).ok_or_else(|| format!("{} isn't available yet", source_id.label()))?;
    let mut query = Query::new(text, kind);
    query.offset = offset.max(0) as usize;
    query.everything = everything;
    let found = source.search(&query)?;
    let home = backend::game_home();
    let installed = installed::Installed::load(&home);
    let items: Vec<Value> = found
        .iter()
        .map(|p| {
            let mut v = project_json(p);
            v["installed"] = json!(installed.find(p.source, &p.id).is_some());
            v
        })
        .collect();
    Ok(json!({ "results": items, "next": offset.max(0) as usize + found.len() }).to_string())
}

pub fn details(source: &str, id: &str) -> Result<String, String> {
    let source_id: SourceId = parse(source, "source")?;
    let source = browser::source(source_id).ok_or_else(|| format!("{} isn't available yet", source_id.label()))?;
    let project = source.project(id)?;
    let details = source.details(&project)?;
    let mut value = project_json(&project);
    value["body"] = json!(details.body);
    value["authors"] = json!(details.authors);
    value["categories"] = json!(details.categories);
    value["followers"] = json!(details.followers);
    value["updated"] = json!(details.updated);
    value["gallery"] = json!(details.gallery.iter().map(|i| &i.url).collect::<Vec<_>>());
    Ok(value.to_string())
}

#[derive(Default)]
struct Progress {
    line: String,
    fraction: f32,
    running: bool,
    error: Option<String>,
    installed: Vec<String>,
}

static PROGRESS: Mutex<Progress> = Mutex::new(Progress {
    line: String::new(),
    fraction: 0.0,
    running: false,
    error: None,
    installed: Vec::new(),
});

fn progress() -> std::sync::MutexGuard<'static, Progress> {
    PROGRESS.lock().unwrap_or_else(|e| e.into_inner())
}

/// Blocking: the app calls it on IO and polls [`progress_json`] from another call.
pub fn install(source: &str, id: &str) -> Result<String, String> {
    {
        let mut p = progress();
        if p.running {
            return Err("Another install is running".into());
        }
        *p = Progress { running: true, line: "Starting".into(), ..Progress::default() };
    }
    let result = (|| {
        let source_id: SourceId = parse(source, "source")?;
        let src = browser::source(source_id).ok_or_else(|| format!("{} isn't available yet", source_id.label()))?;
        let project = src.project(id)?;
        if matches!(project.kind, Kind::DataPack | Kind::Modpack) {
            return Err("Data packs and modpacks can't be installed from the phone yet".into());
        }
        if !project.support().installable() {
            return Err(project.support().reason().unwrap_or("This doesn't run in Horizon").to_owned());
        }
        let home = backend::game_home();
        installed::install(src.as_ref(), &project, false, &home, &|line, fraction| {
            let mut p = progress();
            p.line = line.to_owned();
            p.fraction = fraction;
        })
    })();
    let mut p = progress();
    p.running = false;
    match result {
        Ok(entries) => {
            p.fraction = 1.0;
            p.installed = entries.iter().map(|e| e.title.clone()).collect();
            Ok(json!({ "installed": p.installed }).to_string())
        }
        Err(e) => {
            p.error = Some(e.clone());
            Err(e)
        }
    }
}

pub fn progress_json() -> Result<String, String> {
    let p = progress();
    Ok(json!({ "running": p.running, "line": p.line, "fraction": p.fraction, "error": p.error }).to_string())
}

pub fn installed_json() -> Result<String, String> {
    let installed = installed::Installed::load(&backend::game_home());
    Ok(json!({ "entries": installed.entries }).to_string())
}

pub fn remove(name: &str) -> Result<String, String> {
    let entry = installed::remove(&backend::game_home(), name)?;
    Ok(json!({ "removed": entry.title }).to_string())
}
