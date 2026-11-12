//! Compatibility facade: the original Android frontend keeps its engine-facing names,
 //! while Android-only facilities live in this crate rather than in the upstream engine.
pub use horizon_engine::*;
pub use crate::{
    android_gamepad, android_resources, android_runtime, content_files, discord_presence,
    jar_transpiler, keypad, official_install, resource_install, touch, touch_ui,
};
