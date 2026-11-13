//! The Horizon launcher core, exposed to the Android app over JNI.
//!
//! Every call is blocking and returns JSON (or a plain string); the Kotlin side calls them from
//! `Dispatchers.IO`. The launcher's own `HORIZON_LAUNCHER_HOME` / `HORIZON_HOME` overrides point
//! the core at the app's private storage, so no desktop paths are touched.

use std::panic::AssertUnwindSafe;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};

use horizon_launcher::account::{self, Accounts};
use horizon_launcher::backend::{self, Settings};
use horizon_launcher::{instances, server_list, worlds};
use jni::JNIEnv;
use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jint, jstring};
use serde::Serialize;
use serde_json::{Value, json};

mod browse;
mod update;

fn jstr(env: &mut JNIEnv, s: String) -> jstring {
    match env.new_string(s) {
        Ok(v) => v.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn reply(env: &mut JNIEnv, result: Result<String, String>) -> jstring {
    match result {
        Ok(s) => jstr(env, s),
        Err(e) => {
            log::error!("core call failed: {e}");
            jstr(
                env,
                format!(
                    "{{\"error\":{}}}",
                    serde_json::to_string(&e).unwrap_or_else(|_| "\"unknown\"".into())
                ),
            )
        }
    }
}

fn guarded(env: &mut JNIEnv, f: impl FnOnce() -> Result<String, String>) -> jstring {
    match std::panic::catch_unwind(AssertUnwindSafe(f)) {
        Ok(r) => reply(env, r),
        Err(_) => reply(env, Err("the core panicked".into())),
    }
}

fn string_arg(env: &mut JNIEnv, value: &JString, what: &str) -> Result<String, String> {
    env.get_string(value)
        .map(Into::into)
        .map_err(|e| format!("bad {what}: {e}"))
}

fn optional_string(
    env: &mut JNIEnv,
    value: &JString,
    what: &str,
) -> Result<Option<String>, String> {
    if value.is_null() {
        return Ok(None);
    }
    string_arg(env, value, what).map(Some)
}

fn home() -> PathBuf {
    instances::selected().home
}

static IMPORT_CACHE: std::sync::OnceLock<PathBuf> = std::sync::OnceLock::new();

#[derive(Clone, Serialize, serde::Deserialize)]
#[serde(default)]
struct AndroidTouchControls {
    enabled: bool,
    layout: String,
    left_handed: bool,
    opacity: f32,
    button_size: f32,
    look_sensitivity: f32,
    auto_jump: bool,
    hold_to_sprint: bool,
    haptics: bool,
    positions: std::collections::HashMap<String, [f32; 2]>,
    bindings: std::collections::HashMap<String, String>,
    custom_buttons: Vec<AndroidCustomButton>,
}

#[derive(Clone, Serialize, serde::Deserialize)]
#[serde(default)]
struct AndroidCustomButton {
    id: String,
    label: String,
    output: String,
}

impl Default for AndroidCustomButton {
    fn default() -> Self {
        Self {
            id: String::new(),
            label: "Key".into(),
            output: "E".into(),
        }
    }
}

impl Default for AndroidTouchControls {
    fn default() -> Self {
        Self {
            enabled: true,
            layout: "classic".into(),
            left_handed: false,
            opacity: 0.72,
            button_size: 1.0,
            look_sensitivity: 1.0,
            auto_jump: false,
            hold_to_sprint: false,
            haptics: true,
            positions: std::collections::HashMap::new(),
            bindings: std::collections::HashMap::new(),
            custom_buttons: Vec::new(),
        }
    }
}

impl AndroidTouchControls {
    fn sanitize(&mut self) {
        if !matches!(self.layout.as_str(), "classic" | "compact" | "minimal") {
            self.layout = "classic".into();
        }
        self.opacity = finite_clamp(self.opacity, 0.3, 1.0, 0.72);
        self.button_size = finite_clamp(self.button_size, 0.7, 1.5, 1.0);
        self.look_sensitivity = finite_clamp(self.look_sensitivity, 0.25, 2.0, 1.0);
        self.custom_buttons.truncate(24);
        self.custom_buttons.retain(|button| {
            !button.id.is_empty()
                && button.id.len() <= 32
                && button
                    .id
                    .bytes()
                    .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
                && !button.label.trim().is_empty()
        });
        self.custom_buttons.dedup_by(|a, b| a.id == b.id);
        for button in &mut self.custom_buttons {
            button.label = button.label.trim().chars().take(12).collect();
            if !android_touch_output_allowed(&button.output) {
                button.output = "E".into();
            }
        }
        let allowed_keys = ["move", "jump", "sneak", "sprint", "attack", "use"];
        let custom_ids: std::collections::HashSet<_> =
            self.custom_buttons.iter().map(|b| b.id.as_str()).collect();
        self.positions.retain(|key, point| {
            (allowed_keys.contains(&key.as_str()) || custom_ids.contains(key.as_str()))
                && point.iter().all(|v| v.is_finite())
        });
        for point in self.positions.values_mut() {
            point[0] = point[0].clamp(0.04, 0.96);
            point[1] = point[1].clamp(0.08, 0.94);
        }
        self.bindings.retain(|key, value| {
            (allowed_keys.contains(&key.as_str())
                || matches!(
                    key.as_str(),
                    "inventory"
                        | "drop"
                        | "swap"
                        | "move_up"
                        | "move_down"
                        | "move_left"
                        | "move_right"
                )
                || custom_ids.contains(key.as_str()))
                && android_touch_output_allowed(value)
        });
    }
}

fn android_touch_output_allowed(value: &str) -> bool {
    matches!(
        value,
        "Space"
            | "ShiftLeft"
            | "ControlLeft"
            | "AltLeft"
            | "Tab"
            | "Escape"
            | "Enter"
            | "Backspace"
            | "ArrowUp"
            | "ArrowDown"
            | "ArrowLeft"
            | "ArrowRight"
            | "Home"
            | "End"
            | "PageUp"
            | "PageDown"
            | "W"
            | "A"
            | "B"
            | "C"
            | "D"
            | "E"
            | "Q"
            | "F"
            | "R"
            | "T"
            | "G"
            | "H"
            | "I"
            | "J"
            | "K"
            | "L"
            | "M"
            | "N"
            | "O"
            | "P"
            | "U"
            | "V"
            | "X"
            | "Y"
            | "Z"
            | "0"
            | "1"
            | "2"
            | "3"
            | "4"
            | "5"
            | "6"
            | "7"
            | "8"
            | "9"
            | "MouseLeft"
            | "MouseRight"
            | "MouseMiddle"
            | "F1"
            | "F2"
            | "F3"
            | "F4"
            | "F5"
            | "F6"
            | "F7"
            | "F8"
            | "F9"
            | "F10"
            | "F11"
            | "F12"
    )
}

fn finite_clamp(value: f32, min: f32, max: f32, fallback: f32) -> f32 {
    if value.is_finite() {
        value.clamp(min, max)
    } else {
        fallback
    }
}

fn touch_controls_path() -> PathBuf {
    std::env::var_os("HORIZON_LAUNCHER_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(backend::data_dir)
        .join("android-touch-controls.json")
}

fn touch_controls_load() -> Result<AndroidTouchControls, String> {
    let path = touch_controls_path();
    match std::fs::read(&path) {
        Ok(bytes) => {
            let mut settings: AndroidTouchControls = serde_json::from_slice(&bytes)
                .map_err(|e| format!("could not read touch controls: {e}"))?;
            settings.sanitize();
            Ok(settings)
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(AndroidTouchControls::default()),
        Err(e) => Err(format!("could not read touch controls: {e}")),
    }
}

fn touch_controls_save(settings: &AndroidTouchControls) -> Result<(), String> {
    let path = touch_controls_path();
    let parent = path.parent().ok_or("touch-controls path has no parent")?;
    std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    let bytes = serde_json::to_vec_pretty(settings).map_err(|e| e.to_string())?;
    let temp = path.with_extension("json.tmp");
    std::fs::write(&temp, bytes).map_err(|e| e.to_string())?;
    std::fs::rename(temp, path).map_err(|e| e.to_string())
}

// Android keeps offline identities beside, not inside, Horizon's desktop accounts.json.
// The upstream launcher deliberately removed offline accounts; this Android-only store prevents
// Android compatibility from changing PC account semantics or being erased by Accounts::save().
#[derive(Clone, Debug, Default, Serialize, serde::Deserialize)]
#[serde(default)]
struct AndroidOfflineProfiles {
    names: Vec<String>,
    selected: Option<String>,
}

static ANDROID_OFFLINE_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

fn android_offline_path() -> PathBuf {
    backend::data_dir().join("android-offline-profiles.json")
}

fn android_offline_load() -> AndroidOfflineProfiles {
    let path = android_offline_path();
    if path.is_file() {
        return std::fs::read(&path)
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default();
    }

    // One-time migration from the older launcher's accounts.json format, before an upstream
    // Accounts::save() drops those legacy fields.
    let legacy = backend::data_dir().join("accounts.json");
    let migrated = std::fs::read(&legacy)
        .ok()
        .and_then(|bytes| serde_json::from_slice::<Value>(&bytes).ok())
        .map(|value| {
            let names = value
                .get("offline")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .filter_map(Value::as_str)
                .map(str::to_owned)
                .collect::<Vec<_>>();
            let selected = value
                .get("offline_selected")
                .and_then(Value::as_str)
                .map(str::to_owned);
            AndroidOfflineProfiles { names, selected }
        })
        .unwrap_or_default();
    if !migrated.names.is_empty() || migrated.selected.is_some() {
        let _ = android_offline_save(&migrated);
    }
    migrated
}

fn android_offline_save(profiles: &AndroidOfflineProfiles) -> Result<(), String> {
    let path = android_offline_path();
    let parent = path.parent().ok_or("offline profiles path has no parent")?;
    std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    let bytes = serde_json::to_vec_pretty(profiles).map_err(|e| e.to_string())?;
    let temp = path.with_extension(format!("json.{}.tmp", std::process::id()));
    if let Err(error) = std::fs::write(&temp, bytes).and_then(|()| std::fs::rename(&temp, &path)) {
        let _ = std::fs::remove_file(&temp);
        return Err(error.to_string());
    }
    Ok(())
}

fn validate_offline_name(name: &str) -> Result<String, String> {
    let name = name.trim();
    if !(3..=16).contains(&name.len())
        || !name.bytes().all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
    {
        return Err("Minecraft offline names must be 3–16 characters and use only A–Z, 0–9 or underscore.".into());
    }
    Ok(name.to_owned())
}

fn accounts_json() -> Result<String, String> {
    let accounts = Accounts::load();
    let selected = accounts.selected().map(|a| a.uuid.clone());
    let offline = android_offline_load();
    let active_offline = offline.selected.as_ref().filter(|name| {
        offline.names.iter().any(|saved| saved.eq_ignore_ascii_case(name))
    });
    let (kind, name) = if let Some(name) = active_offline {
        ("offline", Some(name.clone()))
    } else if let Some(account) = accounts.selected() {
        ("microsoft", Some(account.name.clone()))
    } else {
        ("none", None)
    };
    let dto = json!({
        "accounts": accounts
            .accounts
            .iter()
            .map(|a| json!({"uuid": a.uuid, "name": a.name}))
            .collect::<Vec<_>>(),
        "selected": selected,
        "offline": offline.names,
        "offlineSelected": active_offline,
        "player": {"kind": kind, "name": name},
        "hasOwner": accounts.has_owner(account::store()),
        "keychain": account::store().persistent(),
        "signInConfigured": account::client_id().is_some(),
    });
    serde_json::to_string(&dto).map_err(|e| e.to_string())
}

fn add_offline_profile(name: &str) -> Result<String, String> {
    let name = validate_offline_name(name)?;
    let _lock = ANDROID_OFFLINE_LOCK.lock().map_err(|_| "offline profile store is busy")?;
    let mut profiles = android_offline_load();
    if !profiles.names.iter().any(|saved| saved.eq_ignore_ascii_case(&name)) {
        profiles.names.push(name);
        android_offline_save(&profiles)?;
    }
    accounts_json()
}

fn select_offline_profile(name: &str) -> Result<String, String> {
    let name = validate_offline_name(name)?;
    let _lock = ANDROID_OFFLINE_LOCK.lock().map_err(|_| "offline profile store is busy")?;
    let mut profiles = android_offline_load();
    let saved = profiles.names.iter().find(|saved| saved.eq_ignore_ascii_case(&name))
        .cloned().ok_or("That offline profile does not exist.")?;
    profiles.selected = Some(saved);
    android_offline_save(&profiles)?;
    accounts_json()
}

fn remove_offline_profile(name: &str) -> Result<String, String> {
    let _lock = ANDROID_OFFLINE_LOCK.lock().map_err(|_| "offline profile store is busy")?;
    let mut profiles = android_offline_load();
    profiles.names.retain(|saved| !saved.eq_ignore_ascii_case(name));
    if profiles.selected.as_deref().is_some_and(|selected| selected.eq_ignore_ascii_case(name)) {
        profiles.selected = None;
    }
    android_offline_save(&profiles)?;
    accounts_json()
}

fn select_game_name() -> Result<String, String> {
    let _lock = ANDROID_OFFLINE_LOCK.lock().map_err(|_| "offline profile store is busy")?;
    let mut profiles = android_offline_load();
    profiles.selected = None;
    android_offline_save(&profiles)?;
    accounts_json()
}

fn merge(base: &mut Value, patch: Value) {
    match (base, patch) {
        (Value::Object(base), Value::Object(patch)) => {
            for (key, value) in patch {
                match base.get_mut(&key) {
                    Some(slot) => merge(slot, value),
                    None => {
                        base.insert(key, value);
                    }
                }
            }
        }
        (slot, value) => *slot = value,
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeInit(
    mut env: JNIEnv,
    _this: JObject,
    home: JString,
) -> jstring {
    let home: String = match env.get_string(&home) {
        Ok(s) => s.into(),
        Err(e) => return reply(&mut env, Err(format!("bad home: {e}"))),
    };
    let _ = android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );
    let root = PathBuf::from(&home);
    let game = root.join("game");
    let _ = std::fs::create_dir_all(&root);
    let _ = std::fs::create_dir_all(&game);
    std::env::set_var("HORIZON_LAUNCHER_HOME", &root);
    std::env::set_var("HORIZON_HOME", &game);
    log::info!("core home: {}", root.display());
    reply(&mut env, Ok(root.to_string_lossy().into_owned()))
}

static INSTALL_STAGE: AtomicUsize = AtomicUsize::new(0);
static INSTALL_DONE: AtomicU64 = AtomicU64::new(0);
static INSTALL_TOTAL: AtomicU64 = AtomicU64::new(0);

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeGameReady(
    _env: JNIEnv,
    _this: JObject,
) -> jboolean {
    let ready =
        std::panic::catch_unwind(|| horizon_android_installer::official_install::ready(&home()))
            .unwrap_or(false);
    if ready { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeInstallProgress(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    let stages = horizon_android_installer::official_install::InstallStage::ALL;
    let index = INSTALL_STAGE.load(Ordering::Relaxed).min(stages.len() - 1);
    let progress = json!({
        "stage": stages[index].label(),
        "done": INSTALL_DONE.load(Ordering::Relaxed),
        "total": INSTALL_TOTAL.load(Ordering::Relaxed),
    });
    jstr(&mut env, progress.to_string())
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeInstallGame(
    mut env: JNIEnv,
    _this: JObject,
    cache: JString,
) -> jstring {
    let cache = match string_arg(&mut env, &cache, "download cache") {
        Ok(v) => PathBuf::from(v),
        Err(e) => return reply(&mut env, Err(e)),
    };
    guarded(&mut env, || {
        horizon_android_installer::official_install::configure_download_cache_dir(cache);
        INSTALL_STAGE.store(0, Ordering::Relaxed);
        INSTALL_DONE.store(0, Ordering::Relaxed);
        INSTALL_TOTAL.store(0, Ordering::Relaxed);
        horizon_android_installer::official_install::install_with_progress(&home(), |p| {
            let index = horizon_android_installer::official_install::InstallStage::ALL
                .iter()
                .position(|s| *s == p.stage)
                .unwrap_or(0);
            INSTALL_STAGE.store(index, Ordering::Relaxed);
            INSTALL_DONE.store(p.done, Ordering::Relaxed);
            INSTALL_TOTAL.store(p.total, Ordering::Relaxed);
        })
        .map_err(|e| e.to_string())?;
        Ok("{\"ready\":true}".into())
    })
}

/// Copy verified launcher files to the installer's private cache; the installer still rechecks
/// all hashes and replaces incomplete files from Mojang.
#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeImportCachedFile(
    mut env: JNIEnv,
    _this: JObject,
    name: JString,
    source: JString,
) -> jstring {
    let name = match string_arg(&mut env, &name, "cache file name") {
        Ok(v) => v,
        Err(e) => return reply(&mut env, Err(e)),
    };
    let source = match string_arg(&mut env, &source, "source file") {
        Ok(v) => PathBuf::from(v),
        Err(e) => return reply(&mut env, Err(e)),
    };
    guarded(&mut env, || {
        let version = horizon_android_installer::horizon_core::VANILLA_VERSION;
        let relative = Path::new(&name);
        if relative.is_absolute()
            || relative
                .components()
                .any(|c| !matches!(c, std::path::Component::Normal(_)))
        {
            return Err("Invalid imported file path".into());
        }
        let cache = IMPORT_CACHE
            .get()
            .cloned()
            .ok_or("Download cache is not configured")?
            .join(version);
        std::fs::create_dir_all(cache.join(relative.parent().unwrap_or(Path::new(""))))
            .map_err(|e| e.to_string())?;
        std::fs::copy(source, cache.join(relative)).map_err(|e| e.to_string())?;
        Ok("ok".into())
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeSetImportCache(
    mut env: JNIEnv,
    _this: JObject,
    cache: JString,
) -> jstring {
    let cache = match string_arg(&mut env, &cache, "download cache") {
        Ok(v) => PathBuf::from(v),
        Err(e) => return reply(&mut env, Err(e)),
    };
    let _ = IMPORT_CACHE.set(cache.clone());
    horizon_android_installer::official_install::configure_download_cache_dir(cache);
    jstr(&mut env, "ok".into())
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeVersion(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    jstr(&mut env, horizon_launcher::backend::VERSION.to_string())
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeDataDir(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    jstr(
        &mut env,
        horizon_launcher::backend::data_dir()
            .to_string_lossy()
            .into_owned(),
    )
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeReleasesJson(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    guarded(&mut env, || {
        let releases = horizon_launcher::backend::releases()?;
        serde_json::to_string(&releases).map_err(|e| e.to_string())
    })
}

#[derive(Serialize)]
struct InstanceDto {
    id: String,
    name: String,
    group: Option<String>,
    version: Option<String>,
    played_secs: u64,
    last_played: Option<u64>,
    home: String,
}

fn instance_dto(i: instances::Instance) -> InstanceDto {
    InstanceDto {
        id: i.id,
        name: i.name,
        group: i.group,
        version: i.version,
        played_secs: i.played.as_secs(),
        last_played: i
            .last_played
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_secs()),
        home: i.home.to_string_lossy().into_owned(),
    }
}

fn instances_json() -> Result<String, String> {
    let list: Vec<InstanceDto> = instances::list().into_iter().map(instance_dto).collect();
    serde_json::to_string(&list).map_err(|e| e.to_string())
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeInstancesJson(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    guarded(&mut env, instances_json)
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeCreateInstance(
    mut env: JNIEnv,
    _this: JObject,
    name: JString,
) -> jstring {
    let name = match string_arg(&mut env, &name, "instance name") {
        Ok(v) => v,
        Err(e) => return reply(&mut env, Err(e)),
    };
    guarded(&mut env, || {
        let instance = instances::create(&name, None)?;
        instances::select(&instance.id)?;
        serde_json::to_string(&instance_dto(instance)).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeDeleteInstance(
    mut env: JNIEnv,
    _this: JObject,
    id: JString,
) -> jstring {
    let id = match string_arg(&mut env, &id, "instance id") {
        Ok(v) => v,
        Err(e) => return reply(&mut env, Err(e)),
    };
    guarded(&mut env, || {
        instances::delete(&id)?;
        instances_json()
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeSelectInstance(
    mut env: JNIEnv,
    _this: JObject,
    id: JString,
) -> jstring {
    let id = match string_arg(&mut env, &id, "instance id") {
        Ok(v) => v,
        Err(e) => return reply(&mut env, Err(e)),
    };
    guarded(&mut env, || {
        instances::select(&id)?;
        instances_json()
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativePlatformJson(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    let info = json!({
        "os": std::env::consts::OS,
        "arch": std::env::consts::ARCH,
        "asset": horizon_launcher::backend::ASSET,
        "launcherAsset": horizon_launcher::backend::LAUNCHER_ASSET,
        "version": horizon_launcher::backend::VERSION,
        "dataDir": horizon_launcher::backend::data_dir().to_string_lossy(),
    });
    jstr(&mut env, info.to_string())
}

// ----- Accounts ------------------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeAccountsJson(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    guarded(&mut env, accounts_json)
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeAddOfflineName(
    mut env: JNIEnv,
    _this: JObject,
    name: JString,
) -> jstring {
    let name = match string_arg(&mut env, &name, "offline name") {
        Ok(v) => v,
        Err(e) => return reply(&mut env, Err(e)),
    };
    guarded(&mut env, || add_offline_profile(&name))
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativePlayOfflineName(
    mut env: JNIEnv,
    _this: JObject,
    name: JString,
) -> jstring {
    let name = match string_arg(&mut env, &name, "offline name") {
        Ok(v) => v,
        Err(e) => return reply(&mut env, Err(e)),
    };
    guarded(&mut env, || select_offline_profile(&name))
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeRemoveOfflineName(
    mut env: JNIEnv,
    _this: JObject,
    name: JString,
) -> jstring {
    let name = match string_arg(&mut env, &name, "offline name") {
        Ok(v) => v,
        Err(e) => return reply(&mut env, Err(e)),
    };
    guarded(&mut env, || remove_offline_profile(&name))
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativePlayAsGameName(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    guarded(&mut env, select_game_name)
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeSelectAccount(
    mut env: JNIEnv,
    _this: JObject,
    uuid: JString,
) -> jstring {
    let uuid = match string_arg(&mut env, &uuid, "account") {
        Ok(v) => v,
        Err(e) => return reply(&mut env, Err(e)),
    };
    guarded(&mut env, || {
        let mut accounts = Accounts::load();
        accounts.play_as(&uuid)?;
        accounts.save()?;
        {
            let _lock = ANDROID_OFFLINE_LOCK.lock().map_err(|_| "offline profile store is busy")?;
            let mut offline = android_offline_load();
            offline.selected = None;
            android_offline_save(&offline)?;
        }
        accounts_json()
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeRemoveAccount(
    mut env: JNIEnv,
    _this: JObject,
    uuid: JString,
) -> jstring {
    let uuid = match string_arg(&mut env, &uuid, "account") {
        Ok(v) => v,
        Err(e) => return reply(&mut env, Err(e)),
    };
    guarded(&mut env, || {
        let mut accounts = Accounts::load();
        accounts.remove(account::store(), &uuid)?;
        accounts.save()?;
        accounts_json()
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeSignInInfo(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    let info = json!({
        "configured": account::client_id().is_some(),
        "message": if account::client_id().is_some() { Value::Null } else { Value::String(account::NOT_CONFIGURED.into()) },
        "keychain": account::store().persistent(),
        "noKeychain": account::no_keychain(),
    });
    jstr(&mut env, info.to_string())
}

// ----- Settings ------------------------------------------------------------------------------------

fn settings_json() -> Result<String, String> {
    let mut value = serde_json::to_value(Settings::load()).map_err(|e| e.to_string())?;
    if let Some(object) = value.as_object_mut() {
        object.insert("noAi".into(), Value::Bool(backend::no_ai()));
    }
    serde_json::to_string(&value).map_err(|e| e.to_string())
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeSettingsJson(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    guarded(&mut env, settings_json)
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeTouchControlsJson(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    guarded(&mut env, || {
        let settings = touch_controls_load()?;
        serde_json::to_string(&settings).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeSaveTouchControls(
    mut env: JNIEnv,
    _this: JObject,
    patch: JString,
) -> jstring {
    let patch = match string_arg(&mut env, &patch, "touch controls") {
        Ok(value) => value,
        Err(e) => return reply(&mut env, Err(e)),
    };
    guarded(&mut env, || {
        let patch: Value = serde_json::from_str(&patch).map_err(|e| e.to_string())?;
        let mut current =
            serde_json::to_value(touch_controls_load()?).map_err(|e| e.to_string())?;
        merge(&mut current, patch);
        let mut settings: AndroidTouchControls =
            serde_json::from_value(current).map_err(|e| e.to_string())?;
        settings.sanitize();
        touch_controls_save(&settings)?;
        serde_json::to_string(&settings).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeSaveSettings(
    mut env: JNIEnv,
    _this: JObject,
    patch: JString,
) -> jstring {
    let patch = match string_arg(&mut env, &patch, "settings") {
        Ok(v) => v,
        Err(e) => return reply(&mut env, Err(e)),
    };
    guarded(&mut env, || {
        let patch: Value = serde_json::from_str(&patch).map_err(|e| e.to_string())?;
        let no_ai = patch.get("noAi").and_then(Value::as_bool);
        let mut current = serde_json::to_value(Settings::load()).map_err(|e| e.to_string())?;
        let mut patch = patch;
        if let Some(object) = patch.as_object_mut() {
            object.remove("noAi");
        }
        merge(&mut current, patch);
        let settings: Settings = serde_json::from_value(current).map_err(|e| e.to_string())?;
        settings.save();
        if let Some(on) = no_ai {
            backend::set_no_ai(on)?;
        }
        settings_json()
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeSetNoAi(
    mut env: JNIEnv,
    _this: JObject,
    on: jboolean,
) -> jstring {
    guarded(&mut env, || {
        backend::set_no_ai(on != 0)?;
        settings_json()
    })
}

// ----- Versions ------------------------------------------------------------------------------------

fn installed_json() -> Result<String, String> {
    let list: Vec<Value> = backend::installed()
        .into_iter()
        .map(|tag| {
            json!({
                "tag": tag,
                "version": backend::game_version(&tag),
                "prepared": backend::is_prepared(&tag),
                "sounds": backend::has_sounds(&tag),
                "selected": false,
            })
        })
        .collect();
    serde_json::to_string(&list).map_err(|e| e.to_string())
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeInstalledJson(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    guarded(&mut env, installed_json)
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeInstallVersion(
    mut env: JNIEnv,
    _this: JObject,
    tag: JString,
) -> jstring {
    let tag = match string_arg(&mut env, &tag, "version tag") {
        Ok(v) => v,
        Err(e) => return reply(&mut env, Err(e)),
    };
    guarded(&mut env, || {
        let installed = backend::install_tag(&tag, false, |_| {})?;
        Ok(json!({"installed": installed}).to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeRemoveVersion(
    mut env: JNIEnv,
    _this: JObject,
    tag: JString,
) -> jstring {
    let tag = match string_arg(&mut env, &tag, "version tag") {
        Ok(v) => v,
        Err(e) => return reply(&mut env, Err(e)),
    };
    guarded(&mut env, || {
        backend::remove_version(&tag)?;
        installed_json()
    })
}

// ----- Worlds, servers, mods -----------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeWorldsJson(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    guarded(&mut env, || {
        let list: Vec<Value> = worlds::list(&home())
            .into_iter()
            .map(|w| {
                json!({
                    "folder": w.folder,
                    "name": w.name,
                    "mode": w.mode,
                    "version": w.version,
                    "size": w.size,
                    "lastPlayed": w.last_played
                        .duration_since(std::time::UNIX_EPOCH)
                        .ok()
                        .map(|d| d.as_secs()),
                })
            })
            .collect();
        serde_json::to_string(&list).map_err(|e| e.to_string())
    })
}

fn servers_json() -> Result<String, String> {
    let list: Vec<Value> = server_list::List::load(&home())
        .servers()
        .into_iter()
        .map(|s| json!({"name": s.name, "ip": s.ip}))
        .collect();
    serde_json::to_string(&list).map_err(|e| e.to_string())
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeServersJson(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    guarded(&mut env, servers_json)
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeAddServer(
    mut env: JNIEnv,
    _this: JObject,
    name: JString,
    ip: JString,
) -> jstring {
    let name = match string_arg(&mut env, &name, "server name") {
        Ok(v) => v,
        Err(e) => return reply(&mut env, Err(e)),
    };
    let ip = match string_arg(&mut env, &ip, "server address") {
        Ok(v) => v,
        Err(e) => return reply(&mut env, Err(e)),
    };
    guarded(&mut env, || {
        let folder = home();
        let mut list = server_list::List::load(&folder);
        list.add(&name, &ip)?;
        list.save(&folder)?;
        servers_json()
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeRemoveServer(
    mut env: JNIEnv,
    _this: JObject,
    index: jint,
) -> jstring {
    guarded(&mut env, || {
        let folder = home();
        let mut list = server_list::List::load(&folder);
        list.remove(index.max(0) as usize)?;
        list.save(&folder)?;
        servers_json()
    })
}

fn mods_json() -> Result<String, String> {
    let game_home = backend::game_home();
    let folder = game_home.join("horizon-mods");
    let enabled: std::collections::BTreeSet<String> = std::fs::read_to_string(game_home.join("horizon-mods.txt"))
        .unwrap_or_default()
        .lines()
        .filter_map(|line| {
            let (key, value) = line.trim().split_once(':')?;
            (value.trim() == "true").then(|| key.strip_prefix("mod.").map(str::to_owned)).flatten()
        })
        .collect();
    let mut mods: Vec<Value> = std::fs::read_dir(&folder)
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|entry| {
            let name = entry.file_name().into_string().ok()?;
            if !name.ends_with(".hmod") { return None; }
            let path = entry.path();
            let package = horizon_mods::Package::read(&path).ok()?;
            let id = package.manifest.id;
            Some(json!({
                "name": format!("{} {}", package.manifest.name, package.manifest.version),
                "file": name,
                "id": id,
                "enabled": enabled.contains(&id),
            }))
        })
        .collect();
    mods.sort_by(|a, b| {
        a.get("name")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .cmp(b.get("name").and_then(Value::as_str).unwrap_or_default())
    });
    serde_json::to_string(&mods).map_err(|e| e.to_string())
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeModsJson(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    guarded(&mut env, mods_json)
}

fn safe_package_name(path: &Path) -> Result<String, String> {
    let name = path.file_name().and_then(|name| name.to_str()).ok_or("file has no usable name")?;
    if name.is_empty() || name.starts_with('.') || name.contains('/') || name.contains('\\') || name.contains("..") {
        return Err("unsafe package filename".into());
    }
    Ok(name.to_owned())
}

fn copy_new_package(source: &Path, folder: &Path, name: &str) -> Result<PathBuf, String> {
    std::fs::create_dir_all(folder).map_err(|e| format!("{}: {e}", folder.display()))?;
    let target = folder.join(name);
    if target.exists() { return Err(format!("{name} is already imported")); }
    let pending = folder.join(format!(".{name}.{}.pending", std::process::id()));
    std::fs::copy(source, &pending).map_err(|e| format!("copying package: {e}"))?;
    if let Err(error) = std::fs::rename(&pending, &target) {
        let _ = std::fs::remove_file(&pending);
        return Err(format!("installing package: {error}"));
    }
    Ok(target)
}

fn set_content_mod_enabled(id: &str, on: bool) -> Result<(), String> {
    let path = backend::game_home().join("horizon-mods.txt");
    let old = std::fs::read_to_string(&path).unwrap_or_default();
    let key = format!("mod.{id}:");
    let mut lines: Vec<String> = old.lines()
        .filter(|line| !line.trim_start().starts_with(key.as_str()))
        .map(str::to_owned)
        .collect();
    if on { lines.push(format!("mod.{id}:true")); }
    let text = lines.join("\n");
    if text.is_empty() { let _ = std::fs::remove_file(path); }
    else { std::fs::write(&path, text).map_err(|e| format!("{}: {e}", path.display()))?; }
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeImportMod(
    mut env: JNIEnv,
    _this: JObject,
    source: JString,
) -> jstring {
    let source = match string_arg(&mut env, &source, "mod package") {
        Ok(value) => PathBuf::from(value),
        Err(error) => return reply(&mut env, Err(error)),
    };
    guarded(&mut env, || {
        if source.extension().and_then(|e| e.to_str()) != Some("hmod") {
            return Err("Choose a Horizon content mod (.hmod). Java .jar mods do not run in this game.".into());
        }
        let package = horizon_mods::Package::read(&source)?;
        if package.wasm.is_some() || !package.manifest.capabilities.contains(&horizon_mods::Capability::Registries) {
            return Err("This build can import Horizon content mods only (.hmod with registries capability).".into());
        }
        horizon_mods::Content::read(&source, &package.manifest)?;
        let id = package.manifest.id.clone();
        let title = format!("{} {}", package.manifest.name, package.manifest.version);
        let target = copy_new_package(&source, &backend::game_home().join("horizon-mods"), &format!("{id}.hmod"))?;
        if let Err(error) = set_content_mod_enabled(&id, true) {
            let _ = std::fs::remove_file(&target);
            return Err(error);
        }
        Ok(json!({"id": id, "name": title, "path": target}).to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeRemoveMod(
    mut env: JNIEnv,
    _this: JObject,
    id: JString,
) -> jstring {
    let id = match string_arg(&mut env, &id, "mod id") {
        Ok(value) => value,
        Err(error) => return reply(&mut env, Err(error)),
    };
    guarded(&mut env, || {
        if id.is_empty() || !id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_') {
            return Err("invalid mod id".into());
        }
        let path = backend::game_home().join("horizon-mods").join(format!("{id}.hmod"));
        std::fs::remove_file(&path).map_err(|e| format!("{}: {e}", path.display()))?;
        set_content_mod_enabled(&id, false)?;
        Ok(json!({"removed": id}).to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeImportShader(
    mut env: JNIEnv,
    _this: JObject,
    source: JString,
) -> jstring {
    let source = match string_arg(&mut env, &source, "shader pack") {
        Ok(value) => PathBuf::from(value),
        Err(error) => return reply(&mut env, Err(error)),
    };
    guarded(&mut env, || {
        let name = safe_package_name(&source)?;
        match source.extension().and_then(|e| e.to_str()) {
            Some("hshader") => {
                let pack = horizon_shader_api::Pack::read(&source)?;
                horizon_shader_api::check(&pack).map_err(|problems| problems.iter().map(ToString::to_string).collect::<Vec<_>>().join("; "))?;
            }
            Some("zip") => { horizon_shaders::Pack::open(&source).map_err(|e| format!("not a Horizon-compatible shader pack: {e}"))?; }
            _ => return Err("Choose a .hshader pack or a shader .zip containing a shaders/ folder.".into()),
        }
        let target = copy_new_package(&source, &backend::game_home().join("shaderpacks"), &name)?;
        Ok(json!({"name": name, "path": target}).to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeRemoveShader(
    mut env: JNIEnv,
    _this: JObject,
    name: JString,
) -> jstring {
    let name = match string_arg(&mut env, &name, "shader filename") {
        Ok(value) => value,
        Err(error) => return reply(&mut env, Err(error)),
    };
    guarded(&mut env, || {
        let path = Path::new(&name);
        if path.components().count() != 1 || path.file_name().and_then(|n| n.to_str()) != Some(&name) {
            return Err("invalid shader pack filename".into());
        }
        let target = backend::game_home().join("shaderpacks").join(&name);
        std::fs::remove_file(&target).map_err(|e| format!("{}: {e}", target.display()))?;
        Ok(json!({"removed": name}).to_string())
    })
}

// ----- Play ----------------------------------------------------------------------------------------

/// Starts the installed client with the selected offline name, optionally straight into `server`.
/// On Android no game binary is published yet, so this reports the core's own error instead.
#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeLaunch(
    mut env: JNIEnv,
    _this: JObject,
    server: JString,
) -> jstring {
    let server = match optional_string(&mut env, &server, "server address") {
        Ok(v) => v,
        Err(e) => return reply(&mut env, Err(e)),
    };
    guarded(&mut env, || {
        let tag = backend::resolve_version(None)?;
        backend::need_prepared(&tag)?;
        let offline_name = {
            let profiles = android_offline_load();
            profiles.selected.filter(|name| profiles.names.iter().any(|saved| saved.eq_ignore_ascii_case(name)))
        };
        // A selected Android offline profile deliberately skips the Microsoft owner gate.
        // The game already supports its normal offline path when no --session-file is supplied.
        let game_session = if offline_name.is_some() {
            None
        } else {
            Some(account::session_for_game(&backend::game_home())
                .map_err(|refused| match refused.keyring.clone() {
                    Some(why) => why,
                    None => refused.to_string(),
                })?)
        };
        let offline = offline_name.as_ref()
            .map(|name| format!("Playing offline as {name}"))
            .or_else(|| game_session.as_ref().and_then(|session| session.offline.clone()));
        let mut cmd = backend::client_command(&tag);
        if let Some(name) = offline_name {
            cmd.arg("--name").arg(name);
        }
        if let Some(session) = game_session {
            if let Some(file) = session.file {
                cmd.arg("--session-file").arg(file);
            }
            cmd.envs(session.env);
        }
        // Keep the Android controls editor connected to the game launch contract. The
        // Android game target can read this profile before creating its input layer.
        let touch_controls = touch_controls_load()?;
        cmd.env(
            "HORIZON_ANDROID_TOUCH_CONTROLS",
            serde_json::to_string(&touch_controls).map_err(|e| e.to_string())?,
        );
        if let Some(address) = server
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
        {
            cmd.arg("--server").arg(address);
        }
        backend::append_game_args(&mut cmd);
        let child = cmd
            .spawn()
            .map_err(|e| format!("Could not start the game: {e}"))?;
        Ok(json!({
            "started": true,
            "pid": child.id(),
            "version": tag,
            "offline": offline,
        })
        .to_string())
    })
}

// ----- Data collection -----------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeTelemetryJson(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    let consent = horizon_launcher::telemetry::consent();
    let dto = json!({
        "performance": consent.choices.performance,
        "crashes": consent.choices.crashes,
        "bugs": consent.choices.bugs,
        "benchmarks": consent.choices.benchmarks,
        "asked": !consent.needs_asking(),
    });
    jstr(&mut env, dto.to_string())
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeSetTelemetry(
    mut env: JNIEnv,
    _this: JObject,
    on: jboolean,
) -> jstring {
    guarded(&mut env, || {
        let choices = if on != 0 {
            horizon_telemetry::consent::Choices::ALL
        } else {
            horizon_telemetry::consent::Choices::NONE
        };
        horizon_launcher::telemetry::answer(choices)?;
        let consent = horizon_launcher::telemetry::consent();
        Ok(json!({
            "performance": consent.choices.performance,
            "crashes": consent.choices.crashes,
            "bugs": consent.choices.bugs,
            "benchmarks": consent.choices.benchmarks,
            "asked": !consent.needs_asking(),
        })
        .to_string())
    })
}


// ----- Updatable game runtime ------------------------------------------------------------------
//
// NativeActivity loads this library (manifest lib_name = horizon_core) and calls
// `ANativeActivity_onCreate`. We forward it to the game library: the one the in-app updater
// downloaded (path in HORIZON_GAME_LIB, set by HorizonGameActivity) when it loads, else the one
// packaged in the APK. A failing update therefore never prevents the bundled game from starting.

mod boot {
    use std::ffi::{c_char, c_int, c_void, CString};

    unsafe extern "C" {
        fn dlopen(filename: *const c_char, flags: c_int) -> *mut c_void;
        fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
    }
    const RTLD_NOW: c_int = 2;
    const BUNDLED: &str = "libhorizon_android_game.so";

    type OnCreate = unsafe extern "C" fn(*mut c_void, *mut c_void, usize);

    unsafe fn entry(path: &str) -> Option<OnCreate> {
        let path = CString::new(path).ok()?;
        let handle = unsafe { dlopen(path.as_ptr(), RTLD_NOW) };
        if handle.is_null() {
            return None;
        }
        let symbol = unsafe { dlsym(handle, c"ANativeActivity_onCreate".as_ptr()) };
        if symbol.is_null() {
            None
        } else {
            Some(unsafe { std::mem::transmute::<*mut c_void, OnCreate>(symbol) })
        }
    }

    pub unsafe fn start(activity: *mut c_void, saved: *mut c_void, size: usize) {
        let updated = std::env::var("HORIZON_GAME_LIB")
            .ok()
            .filter(|p| !p.is_empty() && std::path::Path::new(p).is_file());
        let function = updated
            .and_then(|path| {
                let f = unsafe { entry(&path) };
                if f.is_none() {
                    log::warn!("updated game library did not load: {path}");
                }
                f
            })
            .or_else(|| unsafe { entry(BUNDLED) });
        match function {
            Some(f) => unsafe { f(activity, saved, size) },
            None => log::error!("no game library could be loaded"),
        }
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn ANativeActivity_onCreate(
    activity: *mut std::ffi::c_void,
    saved_state: *mut std::ffi::c_void,
    saved_state_size: usize,
) {
    unsafe { boot::start(activity, saved_state, saved_state_size) }
}


// ---- Mod browser (see browse.rs) -------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeBrowseCatalog(mut env: JNIEnv, _this: JObject) -> jstring {
    guarded(&mut env, browse::catalog)
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeBrowseSearch(
    mut env: JNIEnv,
    _this: JObject,
    source: JString,
    kind: JString,
    text: JString,
    offset: jint,
    everything: jboolean,
) -> jstring {
    let args = (|| {
        Ok::<_, String>((
            string_arg(&mut env, &source, "source")?,
            string_arg(&mut env, &kind, "kind")?,
            string_arg(&mut env, &text, "search text")?,
        ))
    })();
    match args {
        Ok((source, kind, text)) => guarded(&mut env, move || browse::search(&source, &kind, &text, offset, everything != 0)),
        Err(e) => reply(&mut env, Err(e)),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeBrowseDetails(
    mut env: JNIEnv,
    _this: JObject,
    source: JString,
    id: JString,
) -> jstring {
    let args = (|| Ok::<_, String>((string_arg(&mut env, &source, "source")?, string_arg(&mut env, &id, "id")?)))();
    match args {
        Ok((source, id)) => guarded(&mut env, move || browse::details(&source, &id)),
        Err(e) => reply(&mut env, Err(e)),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeBrowseInstall(
    mut env: JNIEnv,
    _this: JObject,
    source: JString,
    id: JString,
) -> jstring {
    let args = (|| Ok::<_, String>((string_arg(&mut env, &source, "source")?, string_arg(&mut env, &id, "id")?)))();
    match args {
        Ok((source, id)) => guarded(&mut env, move || browse::install(&source, &id)),
        Err(e) => reply(&mut env, Err(e)),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeBrowseProgress(mut env: JNIEnv, _this: JObject) -> jstring {
    guarded(&mut env, browse::progress_json)
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeBrowseInstalled(mut env: JNIEnv, _this: JObject) -> jstring {
    guarded(&mut env, browse::installed_json)
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeBrowseRemove(mut env: JNIEnv, _this: JObject, name: JString) -> jstring {
    match string_arg(&mut env, &name, "name") {
        Ok(name) => guarded(&mut env, move || browse::remove(&name)),
        Err(e) => reply(&mut env, Err(e)),
    }
}

// ---- In-app updates (see update.rs) -----------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeUpdateList(mut env: JNIEnv, _this: JObject) -> jstring {
    guarded(&mut env, update::list)
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeUpdateDownload(
    mut env: JNIEnv,
    _this: JObject,
    tag: JString,
    what: JString,
    dest: JString,
) -> jstring {
    let args = (|| {
        Ok::<_, String>((
            string_arg(&mut env, &tag, "tag")?,
            string_arg(&mut env, &what, "kind")?,
            string_arg(&mut env, &dest, "folder")?,
        ))
    })();
    match args {
        Ok((tag, what, dest)) => guarded(&mut env, move || update::download(&tag, &what, &dest)),
        Err(e) => reply(&mut env, Err(e)),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_horizon_launcher_core_Native_nativeUpdateProgress(mut env: JNIEnv, _this: JObject) -> jstring {
    guarded(&mut env, update::progress_json)
}
