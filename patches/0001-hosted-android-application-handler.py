#!/usr/bin/env python3
"""Apply the minimal hosted-ApplicationHandler API to the pinned upstream tree."""
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


entry_path = "crates/horizon-client/src/entry.rs"
entry = ROOT / entry_path
source = entry.read_text(encoding="utf-8")
anchor = "/// Start the game with trait mods in dispatch order.\npub fn main_with(mods: crate::mod_api::Mods) {"
helper = """// Shared initialization for the executable and frontends that host the game's
// ApplicationHandler on an event loop they already own.
static HOSTED_INITIALIZED: std::sync::OnceLock<()> = std::sync::OnceLock::new();

pub fn initialize_for_hosted_application(mods: crate::mod_api::Mods) {
    if HOSTED_INITIALIZED.set(()).is_err() {
        log::warn!("Horizon engine initialization already ran; ignoring the second call");
        return;
    }
    if crate::mod_api::MODS.set(mods).is_err() {
        log::warn!("Trait mods already installed; ignoring the second list");
    }
    horizon_threads::configure_allocator();
    crate::horizon_look::give();
    horizon_client::pack_formats::install();
    if !cfg!(feature = "horizon-extras") {
        horizon_client::options_ui::HORIZON_EXTRAS
            .store(false, std::sync::atomic::Ordering::Relaxed);
    }
    horizon_client::telemetry::mark_start();
    crash::on_report(horizon_client::telemetry::keep_crash);
    game::player_hooks::register();
    horizon_hooks::engine::seal();
    horizon_client::lang::install_crate_files(horizon_hooks::lang::engine::all());
    horizon_client::spans::startup::init(std::time::Instant::now());
    horizon_client::spans::trace::start_from_env();
}

"""
if source.count(anchor) != 1:
    raise SystemExit(f"{entry_path}: hosted initialization insertion anchor changed")
source = source.replace(anchor, helper + anchor, 1)
start = source.index("    // First: a trait injection into anything below")
end_marker = "    horizon_client::spans::trace::start_from_env();\n"
end = source.index(end_marker, start) + len(end_marker)
source = source[:start] + "    initialize_for_hosted_application(mods);\n" + source[end:]
entry.write_text(source, encoding="utf-8")

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

resource_path = "crates/horizon-client/src/game/resource_loader.rs"
resource = ROOT / resource_path
source = resource.read_text(encoding="utf-8")
run_start = source.index("pub(super) fn run_loading(")
construct_start = source.index("    let progress = Progress::new();", run_start)
app_start = source.index("    let mut app = App::Loading(Box::new(loading));", construct_start)
end_marker = "    Ok(())\n}"
run_end = source.index(end_marker, app_start) + len(end_marker)
constructor = source[construct_start:app_start]
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
    raise SystemExit(f"{resource_path}: loading_app already exists")
source = source[:run_end] + "\n\n" + factory + source[run_end:]
resource.write_text(source, encoding="utf-8")

game_path = "crates/horizon-client/src/game.rs"
factory = """/// Prepare the normal client launch and return its existing application handler.
///
/// A native frontend can host this handler on its own Winit event loop. This
/// deliberately does not create an EventLoop or change the desktop launch path.
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

    // Install the same game hooks as run_with before any game or loader worker starts.
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
    let version = horizon_client::telemetry::version();
    let game_dir = horizon_client::logging::player_dir();
    horizon_hooks::engine::started(&horizon_hooks::Started::new(&version, &game_dir));
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

print("Hosted Android application API patch applied.")
