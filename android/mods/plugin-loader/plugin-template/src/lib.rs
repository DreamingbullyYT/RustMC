//! A plugin for the plugin_loader trait mod. Implement any `Hooks` / `RenderHooks` methods you need.
use horizon_client::mod_api::{Hooks, Host, PlayerLoggedInEvent, Text};

/// Must match the loader's ABI string: the Horizon commit this plugin was built against.
#[unsafe(no_mangle)]
pub fn horizon_plugin_abi() -> &'static str {
    "horizon=41f822b659789780f58d32c63ddfb6887ea1b8bf"
}

#[unsafe(no_mangle)]
pub fn horizon_plugin_hooks() -> Box<dyn Hooks> {
    Box::new(Plugin)
}

struct Plugin;

impl Hooks for Plugin {
    fn player_logged_in(&self, host: &mut dyn Host, event: &mut PlayerLoggedInEvent<'_>) {
        host.tell(event.player, Text::literal(format!("plugin says hello, {}!", event.name)));
    }
}
