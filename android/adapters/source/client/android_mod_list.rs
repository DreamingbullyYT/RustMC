//! Android APK-bundled native mods. Kept outside the upstream mod-list crate.
pub const ANDROID_BUNDLED: [(&str, &str, &str); 4] = [
    (
        "zoom",
        "Zoom",
        "Hold Z to zoom; configurable in Options > Mods",
    ),
    (
        "minimap",
        "Minimap",
        "A configurable map in the screen corner",
    ),
    (
        "recipes",
        "Recipes",
        "Item recipes and uses beside the inventory",
    ),
    (
        "smartf3",
        "SmartF3",
        "Configurable modules for the F3 debug screen",
    ),
];
