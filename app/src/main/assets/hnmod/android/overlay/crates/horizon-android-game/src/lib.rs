//! NativeActivity entry point that hosts Horizon's shared winit game loop.

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_launcher_game_GameInput_nativeSendControl(
    mut env: jni::JNIEnv<'_>, _object: jni::objects::JObject<'_>, key: jni::objects::JString<'_>, pressed: jni::sys::jboolean,
) {
    if let Ok(key) = env.get_string(&key) {
        horizon_client::game::send_android_control_event(key.into(), pressed != 0);
    }
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_launcher_game_GameInput_nativeLook(
    _env: jni::JNIEnv<'_>, _object: jni::objects::JObject<'_>, dx: jni::sys::jfloat, dy: jni::sys::jfloat,
) {
    horizon_client::game::send_android_look(f64::from(dx), f64::from(dy));
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_launcher_game_GameInput_nativeLookActive(
    _env: jni::JNIEnv<'_>, _object: jni::objects::JObject<'_>, active: jni::sys::jboolean,
) {
    horizon_client::game::set_android_look_active(active != 0);
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_launcher_game_GameInput_nativeCursorGrabbing(
    _env: jni::JNIEnv<'_>, _object: jni::objects::JObject<'_>,
) -> jni::sys::jboolean {
    horizon_client::game::android_cursor_grabbing() as jni::sys::jboolean
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_launcher_game_GameInput_nativeGameFps(
    _env: jni::JNIEnv<'_>, _object: jni::objects::JObject<'_>,
) -> jni::sys::jint {
    horizon_client::game::android_game_fps().min(i32::MAX as u32) as jni::sys::jint
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_launcher_game_GameInput_nativeHotbarRect<'a>(
    env: jni::JNIEnv<'a>, _object: jni::objects::JObject<'a>,
) -> jni::sys::jfloatArray {
    let rect = horizon_client::game::android_hotbar_rect();
    match env.new_float_array(4) {
        Ok(array) => {
            let _ = env.set_float_array_region(&array, 0, &rect);
            array.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_launcher_game_GameInput_nativeSetImeAllowed(
    _env: jni::JNIEnv<'_>, _object: jni::objects::JObject<'_>, allowed: jni::sys::jboolean,
) {
    horizon_client::game::set_android_ime_allowed(allowed != 0);
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_horizon_launcher_game_GameInput_nativeMenuTouch(
    _env: jni::JNIEnv<'_>, _object: jni::objects::JObject<'_>, id: jni::sys::jlong,
    x: jni::sys::jfloat, y: jni::sys::jfloat, phase: jni::sys::jint,
) {
    if id >= 0 && (0..=3).contains(&phase) {
        horizon_client::game::send_android_menu_touch(id as u64, f64::from(x), f64::from(y), phase as u8);
    }
}

#[cfg(target_os = "android")]
static ANDROID_APP: std::sync::OnceLock<winit::platform::android::activity::AndroidApp> =
    std::sync::OnceLock::new();

#[cfg(target_os = "android")]
fn vibrate_touch() {
    use jni::{
        JavaVM,
        objects::{JObject, JValue},
    };
    let Some(app) = ANDROID_APP.get() else {
        return;
    };
    let result = (|| -> jni::errors::Result<()> {
        // SAFETY: AndroidApp owns the VM and exposes its Activity as a global ref.
        let vm = unsafe { JavaVM::from_raw(app.vm_as_ptr().cast()) }?;
        let mut env = vm.attach_current_thread()?;
        let raw = app.activity_as_ptr();
        if raw.is_null() {
            return Ok(());
        }
        // SAFETY: this is an unowned JNI global reference owned by AndroidApp.
        let activity = unsafe { JObject::from_raw(raw.cast()) };
        let service = env.new_string("vibrator")?;
        let vibrator = env
            .call_method(
                &activity,
                "getSystemService",
                "(Ljava/lang/String;)Ljava/lang/Object;",
                &[JValue::Object(service.as_ref())],
            )?
            .l()?;
        if vibrator.is_null() {
            return Ok(());
        }
        let effect_class = env.find_class("android/os/VibrationEffect")?;
        let effect = env
            .call_static_method(
                effect_class,
                "createOneShot",
                "(JI)Landroid/os/VibrationEffect;",
                &[JValue::Long(16), JValue::Int(1)],
            )?
            .l()?;
        env.call_method(
            vibrator,
            "vibrate",
            "(Landroid/os/VibrationEffect;)V",
            &[JValue::Object(&effect)],
        )?;
        Ok(())
    })();
    if let Err(error) = result {
        log::debug!("Horizon Android: touch vibration unavailable: {error}");
    }
}

#[cfg(target_os = "android")]
fn system_property(name: &str) -> String {
    std::process::Command::new("getprop")
        .arg(name)
        .output()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_lowercase())
        .unwrap_or_default()
}

/// Vulkan for MediaTek and other non-Qualcomm SoCs, GLES for Snapdragon.
#[cfg(target_os = "android")]
fn choose_graphics_api(launcher: &std::path::Path) -> &'static str {
    if let Ok(text) = std::fs::read_to_string(launcher.join("graphics-api.txt")) {
        match text.trim().to_lowercase().as_str() {
            "vulkan" => return "vulkan",
            "opengl" | "gles" => return "opengl",
            "auto" => return "auto",
            _ => {}
        }
    }
    let marker = launcher.join("vulkan-pending");
    let failed = launcher.join("vulkan-failed");
    if failed.exists() {
        // Vulkan died on this phone before: stay on OpenGL (graphics-api.txt = vulkan overrides).
        return "opengl";
    }
    if marker.exists() {
        // The last Vulkan start died within its first seconds, and the launcher did not see the
        // game close on purpose. Remember it, or Vulkan would be retried (and fail) every second launch.
        let _ = std::fs::remove_file(&marker);
        let _ = std::fs::write(&failed, b"");
        log::warn!("Horizon Android: previous Vulkan start failed, using OpenGL from now on");
        return "opengl";
    }
    let ids = [
        system_property("ro.soc.manufacturer"),
        system_property("ro.hardware"),
        system_property("ro.board.platform"),
    ]
    .join(" ");
    // Adreno (Qualcomm) and Mali phones (MediaTek, Exynos, Kirin, Unisoc, Tensor) start the engine reliably
    // on OpenGL ES; their Vulkan drivers crash it during the first streaming jobs. Other chips try Vulkan.
    let opengl_first = [
        "qualcomm", "qcom", "snapdragon", "mediatek", "mt6", "mt8", "exynos", "samsungexynos",
        "kirin", "hisilicon", "unisoc", "spreadtrum", "tensor", "zuma", "gs101", "gs201",
    ]
    .iter()
    .any(|k| ids.contains(k));
    if opengl_first {
        return "opengl";
    }
    // Survived 20 seconds: Vulkan works here.
    let _ = std::fs::write(&marker, b"");
    std::thread::spawn(move || {
        std::thread::sleep(std::time::Duration::from_secs(20));
        let _ = std::fs::remove_file(marker);
    });
    "vulkan"
}

#[cfg(target_os = "android")]
unsafe extern "C" {
    fn dup2(old_fd: i32, new_fd: i32) -> i32;
}

/// The engine reports a failed start with `eprintln!`, which Android throws away, so the player only
/// saw a black screen and then the launcher. Send stdout/stderr (and panics) to launcher/game-log.txt,
/// reset on every start, so the launcher can show why the game stopped.
#[cfg(target_os = "android")]
fn install_game_log(launcher: &std::path::Path) {
    use std::os::fd::AsRawFd;
    let _ = std::fs::create_dir_all(launcher);
    if let Ok(file) = std::fs::File::create(launcher.join("game-log.txt")) {
        let fd = file.as_raw_fd();
        unsafe {
            dup2(fd, 1);
            dup2(fd, 2);
        }
    }
    std::panic::set_hook(Box::new(|info| {
        let trace = std::backtrace::Backtrace::force_capture();
        let text = format!("PANIC: {info}\n{trace}");
        eprintln!("{text}");
        log::error!("{text}");
    }));
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub fn android_main(app: winit::platform::android::activity::AndroidApp) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );
    let _ = ANDROID_APP.set(app.clone());
    horizon_client::game::set_android_haptic_callback(vibrate_touch);
    let Some(files) = app.internal_data_path() else {
        log::error!("Horizon Android: app-private files directory is unavailable");
        return;
    };
    let launcher = files.join("launcher");
    install_game_log(&launcher);
    horizon_client::game::start_android_diagnostics(launcher.join("game-log.txt"));
    // Android's process executable is app_process, not the installed game library.
    // Resolve post-build data beside the active local HNMod build instead.
    unsafe { std::env::remove_var("HORIZON_NATIVE_DATA_ROOT"); }
    if let Ok(active) = std::fs::read_to_string(files.join("updates/current")) {
        let name = active.trim();
        if !name.is_empty() && !name.contains('/') && !name.contains('\\') && name != "." && name != ".." {
            let native_data = files.join("updates").join(name).join("native-data");
            if native_data.is_dir() {
                unsafe { std::env::set_var("HORIZON_NATIVE_DATA_ROOT", native_data); }
            }
        }
    }
    let game_home = launcher.join("game");
    if let Err(error) = std::fs::create_dir_all(&game_home) {
        log::error!("Horizon Android: cannot create game directory: {error}");
        return;
    }
    // Match the working NativeActivity boot environment: Horizon resolves resources,
    // user files and temporary files before creating Game and its worker threads.
    for dir in ["resources", "saves", "resourcepacks", "mods", "shaderpacks"] {
        if let Err(error) = std::fs::create_dir_all(game_home.join(dir)) {
            log::error!("Horizon Android: cannot create {dir} directory: {error}");
            return;
        }
    }
    let cache_home = files.join("horizon-cache");
    let temp_home = files.join("tmp");
    if let Err(error) = std::fs::create_dir_all(&cache_home).and_then(|_| std::fs::create_dir_all(&temp_home)) {
        log::error!("Horizon Android: cannot prepare cache/temp directories: {error}");
        return;
    }
    // The NativeActivity shares the launcher process. Set the game paths before Horizon starts
    // worker threads so saves and logs stay in app-private storage.
    unsafe {
        std::env::set_var("HORIZON_LAUNCHER_HOME", &launcher);
        std::env::set_var("HORIZON_HOME", &game_home);
        std::env::set_var("HORIZON_RESOURCES", game_home.join("resources"));
        std::env::set_var("HOME", &game_home);
        std::env::set_var("HORIZON_CACHE", &cache_home);
        std::env::set_var("TMPDIR", &temp_home);
        // Qualcomm's Adreno Vulkan driver fails to link Horizon's sky pipeline during the
        // first missing-resources frame, so Snapdragon stays on GLES. MediaTek/Mali and
        // others get Vulkan (much faster than their GLES drivers). A `graphics-api.txt`
        // beside the game folder (auto, vulkan or opengl) overrides the choice, and a
        // Vulkan start that never got going falls back to GLES on the next launch.
        //
        // The launcher exports its renderer setting in HORIZON_GRAPHICS_API, always, even for
        // "Auto". Only an explicit vulkan/opengl is a forced choice; "auto" (or nothing) must still
        // go through the per-phone pick above, otherwise Auto would skip the Adreno/Mali guard.
        let forced = matches!(
            std::env::var("HORIZON_GRAPHICS_API").as_deref(),
            Ok("vulkan") | Ok("opengl")
        );
        if !forced {
            let api = choose_graphics_api(&launcher);
            log::info!("Horizon Android: graphics API {api}");
            eprintln!("Horizon Android: graphics API {api}");
            std::env::set_var("HORIZON_GRAPHICS_API", api);
        }
    }
    if let Err(error) = std::env::set_current_dir(&game_home) {
        log::error!("Horizon Android: cannot enter game directory: {error}");
        return;
    }
    // Android offline profiles live outside upstream accounts.json so desktop account
    // saves cannot erase them. Keep the legacy field as a migration fallback.
    let offline_profile = std::fs::read(launcher.join("android-offline-profiles.json"))
        .ok()
        .and_then(|bytes| serde_json::from_slice::<serde_json::Value>(&bytes).ok())
        .and_then(|value| value.get("selected").and_then(|v| v.as_str()).map(str::to_owned))
        .or_else(|| {
            std::fs::read(launcher.join("accounts.json"))
                .ok()
                .and_then(|bytes| serde_json::from_slice::<serde_json::Value>(&bytes).ok())
                .and_then(|value| value.get("offline_selected").and_then(|v| v.as_str()).map(str::to_owned))
        });
    if let Some(name) = offline_profile {
        unsafe { std::env::set_var("HORIZON_PLAYER_NAME", name) };
    }
    if let Ok(controls) = std::fs::read(launcher.join("android-touch-controls.json"))
        && let Ok(controls) = std::str::from_utf8(&controls)
    {
        unsafe { std::env::set_var("HORIZON_ANDROID_TOUCH_CONTROLS", controls) };
    }
    horizon_client::game::set_android_app(app);
    // Upstream's entry installs the mod hooks and renderers, then runs the game; on Android it
    // returns on failure instead of exiting the shared process.
    horizon_client::entry::main_with(Default::default());
    horizon_client::game::stop_android_diagnostics();
    log::info!("Horizon Android: game loop ended");
    eprintln!("Horizon Android: game loop ended");
}
