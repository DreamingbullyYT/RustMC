#!/usr/bin/env python3
"""Apply the smallest upstream API seam needed to host Horizon on Android's Winit loop."""
from pathlib import Path
import sys

ROOT = Path(sys.argv[1]).resolve()


def replace_once(path: str, old: str, new: str) -> None:
    file = ROOT / path
    source = file.read_text(encoding="utf-8")
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one patch anchor, found {count}")
    file.write_text(source.replace(old, new, 1), encoding="utf-8")


# 1. Split upstream executable setup into two ordered phases. Android supplies its
# own logger/crash directory, but still executes every upstream engine registration.
entry_path = "crates/horizon-client/src/entry.rs"
entry = ROOT / entry_path
source = entry.read_text(encoding="utf-8")
main_marker = "pub fn main_with(mods: crate::mod_api::Mods) {"
args_marker = "    let args: Vec<String> = std::env::args().skip(1).collect();"
run_marker = "    if let Err(error) = game::run_with() {"
renderers_marker = "    let renderers = crate::mod_api::mods()"
anchor = "/// Start the game with trait mods in dispatch order.\npub fn main_with(mods: crate::mod_api::Mods) {"

main_start = source.index(main_marker) + len(main_marker)
args_start = source.index(args_marker, main_start)
first_phase = source[main_start:args_start]
if "crate::mod_api::MODS.set(mods)" not in first_phase:
    raise SystemExit("entry.rs: initial MODS registration anchor changed")
if "horizon_hooks::engine::seal()" not in first_phase:
    raise SystemExit("entry.rs: engine hook sealing anchor changed")

runtime_start = source.index(renderers_marker, args_start)
runtime_end = source.index(run_marker, runtime_start)
runtime_phase = source[runtime_start:runtime_end]
for required in ("native_render::install", "native_load_hooks::install",
                 "worldgen::native_generate::install", "native_client_hooks::install",
                 "sound_engine::install_playback_hook", "raise_open_file_limit();"):
    if required not in runtime_phase:
        raise SystemExit(f"entry.rs: runtime registration anchor missing: {required}")

helpers = """
// Shared engine/mod initialization for the executable and native frontends.
static HOSTED_INITIALIZED: std::sync::OnceLock<()> = std::sync::OnceLock::new();

pub fn initialize_for_hosted_application(mods: crate::mod_api::Mods) {
    if HOSTED_INITIALIZED.set(()).is_err() {
        log::warn!("Horizon engine initialization already ran; ignoring the second call");
        return;
    }
""" + first_phase + """    logging::set_line_hook(crate::native_client_hooks::native_log_line);
}

// Register runtime hooks after the host has installed logging and its crash directory.
static HOSTED_RUNTIME_INITIALIZED: std::sync::OnceLock<()> = std::sync::OnceLock::new();

pub fn initialize_hosted_runtime() {
    if HOSTED_RUNTIME_INITIALIZED.set(()).is_err() {
        log::warn!("Horizon runtime hooks already installed; ignoring the second call");
        return;
    }
""" + runtime_phase + """}

"""
if source.count(anchor) != 1:
    raise SystemExit("entry.rs: main_with insertion anchor changed")
source = source.replace(anchor, helpers + anchor, 1)

# Recompute offsets after inserting helpers; do not use stale source indices.
main_start = source.index(main_marker) + len(main_marker)
args_start = source.index(args_marker, main_start)
source = source[:main_start] + "\n    initialize_for_hosted_application(mods);\n" + source[args_start:]
main_body_start = source.index(main_marker)
runtime_start = source.index(renderers_marker, main_body_start)
runtime_end = source.index(run_marker, runtime_start)
source = source[:runtime_start] + "    initialize_hosted_runtime();\n" + source[runtime_end:]

# The first phase now owns the original line-hook registration.
main_body_start = source.index(main_marker)
tail = source[main_body_start:]
line_hook = "    logging::set_line_hook(crate::native_client_hooks::native_log_line);\n"
if tail.count(line_hook) != 1:
    raise SystemExit("entry.rs: expected one original line-hook call in main_with")
tail = tail.replace(line_hook, "", 1)
source = source[:main_body_start] + tail
entry.write_text(source, encoding="utf-8")

# 2. Let a native frontend supply explicit launch arguments instead of process argv.
replace_once(
    "crates/horizon-client/src/game/launch_options.rs",
    """    pub(super) fn read() -> Result<Option<Self>> {
        let Some(mut o) = Self::parse(std::env::args().skip(1))""",
    """    pub(super) fn read() -> Result<Option<Self>> {
        Self::read_arguments(std::env::args().skip(1))
    }
    pub(super) fn read_arguments(args: impl Iterator<Item = String>) -> Result<Option<Self>> {
        let Some(mut o) = Self::parse(args)""",
)

# 3. Factor only the existing Loading handler construction out of run_loading.
# Desktop still creates/runs its own EventLoop and performs its original cleanup.
resource_path = "crates/horizon-client/src/game/resource_loader.rs"
resource = ROOT / resource_path
source = resource.read_text(encoding="utf-8")
run_start = source.index("pub(super) fn run_loading(")
construct_start = source.index("    let progress = Progress::new();", run_start)
app_start = source.index("    let mut app = App::Loading(Box::new(loading));", construct_start)
end_marker = "    Ok(())\n}"
run_end = source.index(end_marker, app_start) + len(end_marker)
constructor = source[construct_start:app_start]
if "let loading = Loading {" not in constructor:
    raise SystemExit("resource_loader.rs: loading constructor changed")
factory = """
/// Construct the existing loading/game handler without creating a Winit event loop.
pub(super) fn loading_app(
    options: Options,
    settings: Settings,
    vanilla_root: PathBuf,
    pack_error: Option<String>,
    started: Instant,
) -> Result<App> {
""" + constructor + """    Ok(App::Loading(Box::new(loading)))
}
"""
if "pub(super) fn loading_app(" in source:
    raise SystemExit("resource_loader.rs: loading_app already exists")
source = source[:run_end] + "\n\n" + factory + source[run_end:]
resource.write_text(source, encoding="utf-8")

# 4. Add the hosted-client factory next to run_with. It mirrors run_with's
# client setup, but returns the same upstream App handler rather than nesting
# a second EventLoop inside Android's existing launcher loop.
game_path = "crates/horizon-client/src/game.rs"
factory = """/// Prepare the normal windowed client launch and return its existing handler.
///
/// A native frontend hosts this handler on its own Winit event loop. The
/// desktop run_with() path remains unchanged.
pub fn create_application_handler(
    arguments: Vec<String>,
) -> Result<Box<dyn winit::application::ApplicationHandler>> {
    let Some(options) = Options::read_arguments(arguments.into_iter())? else {
        return Err("missing game launch arguments".into());
    };
    if !options.execution_role.is_client() || options.offscreen || options.headless {
        return Err("hosted application requires a windowed client launch".into());
    }
    if synchronous_startup(&options) {
        return Err("synchronous startup is not supported by the hosted application API".into());
    }

    // Same gameplay hooks and thread priority as the ordinary client launch.
    let _ = horizon_client::lan::conn::SERVER_PACKET.set(Game::native_server_packet);
    let _ = horizon_client::lan::conn::ENTITY_DATA_TO.set(Game::native_entity_data_to);
    let _ = horizon_client::health::HURT_SERVER.set(native_hooks::hurt_server);
    let _ = horizon_client::health::DAMAGE_PRE.set(native_hooks::damage_pre);
    let _ = horizon_client::health::DAMAGE_POST.set(native_hooks::damage_post);
    let _ = horizon_client::block_contact::HOOK.set(native_hooks::block_contact);
    let _ = horizon_client::health::KNOCKBACK.set(native_hooks::knockback);
    let _ = horizon_client::lan::hub::PLAYER_MOVE.set(Game::native_player_move);
    let _ = horizon_client::lan::hub::PLAYER_TELEPORT.set(Game::native_player_teleport);
    let _ = horizon_client::block_contact::TRAMPLE.set(Game::native_trample);
    horizon_threads::put_helpers_behind();
    horizon_threads::favour_current();
    horizon_threads::catch_stop_signals();

    let started = Instant::now();
    tick_profile::want_spans(tick_profile::SPANS_SPIKES, options.spikes.is_some());
    if horizon_telemetry::Consent::load(&horizon_client::logging::game_home())
        .allows(horizon_telemetry::Kind::Perf)
    {
        horizon_client::spans::record_here(true);
    }
    let vanilla_root = options.resources.clone();
    horizon_world::datafix::set_resources(
        &std::path::absolute(&vanilla_root).unwrap_or_else(|_| vanilla_root.clone()),
    );
    if let Some(error) =
        loading_screen::missing_resources(&vanilla_root, horizon_hooks::engine::get_the_game())
    {
        return Err(error.into());
    }
    horizon_hooks::engine::started(&horizon_hooks::Started::new(
        &horizon_client::telemetry::version(),
        &horizon_client::logging::player_dir(),
    ));
    mods::register_components(&vanilla_root)?;
    mods::register_standalone_models();
    mods::register_layer_definitions();

    let mut early_settings = Settings::load(&Game::options_path());
    horizon_client::packs::set_polytone_choices(horizon_client::packs::load_polytone_choices(
        &horizon_client::packs::polytone_choices_file(),
    ));
    if unclean_start_recovery(&mut early_settings) {
        log::warn!("Detected unexpected shutdown during last game startup: resetting fullscreen mode");
    }
    early_settings.file.set(
        "startedCleanly",
        horizon_client::options_file::V::Bool(false),
    );
    early_settings.save(&Game::options_path());
    narrator::start(&early_settings);
    activity::client_started(&options);
    Behaviours::set_cache_dir(std::env::current_dir()?.join(".horizon-cache"));

    let app = resource_loader::loading_app(options, early_settings, vanilla_root, None, started)?;
    Ok(Box::new(app))
}

"""
replace_once(game_path, "pub fn run_with() -> Result<()> {", factory + "pub fn run_with() -> Result<()> {")
print("Updated strict upstream patcher.")
