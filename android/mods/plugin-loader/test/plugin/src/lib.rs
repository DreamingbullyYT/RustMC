//! Test plugin: doubles healing and cancels it above 100.
use horizon_client::mod_api::{Flow, Hooks, LivingHealEvent};

#[unsafe(no_mangle)]
pub fn horizon_plugin_abi() -> &'static str {
    "horizon=41f822b659789780f58d32c63ddfb6887ea1b8bf"
}

#[unsafe(no_mangle)]
pub fn horizon_plugin_hooks() -> Box<dyn Hooks> {
    Box::new(Doubler)
}

struct Doubler;

impl Hooks for Doubler {
    fn living_heal(&self, event: &mut LivingHealEvent) -> Flow {
        event.amount *= 2.0;
        if event.amount > 100.0 { Flow::Cancel } else { Flow::Continue }
    }
}
