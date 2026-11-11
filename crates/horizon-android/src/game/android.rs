//! Android-specific game-loop handoff, compiled into Horizon's game module by a path hook.
use super::{launch_options::Options, resource_loader, Settings};
use horizon_client::Result;
use std::{path::Path, time::Instant};
use winit::application::ApplicationHandler;

pub fn android_game(arguments: Vec<String>) -> Result<Box<dyn ApplicationHandler>> {
    horizon_client::auth::clear_current();
    ensure_factory_touch_layout();
    let options = Options::read_arguments(arguments)?.ok_or("missing Android options")?;
    horizon_client::spans::enable(options.spikes.is_some());
    let settings = Settings::load(&std::env::current_dir()?.join("options.txt"));
    let root = options.resources.clone();
    horizon_threads::put_helpers_behind();
    horizon_world::level::Behaviours::set_cache_dir(std::env::current_dir()?.join(".horizon-cache"));
    Ok(Box::new(resource_loader::loading_app(
        options,
        settings,
        root,
        None,
        Instant::now(),
    )?))
}

fn ensure_factory_touch_layout() {
    let path = Path::new("horizon-touch.json");
    if path.exists() {
        return;
    }
    if let Err(error) = horizon_client::touch::Settings::default().save(path) {
        log::warn!("Cannot write the factory MCPE touch layout: {error}");
    }
}
