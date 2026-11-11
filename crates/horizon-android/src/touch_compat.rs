//! Android-owned touch settings.
//!
//! Upstream's `horizon_client::touch::Settings` is only the on-screen layout. The Android
//! launcher also stores control presets (MCPE / GoyControl style), a profile name, gyro,
//! vibration and virtual-mouse options. Those live here, in the Android crate, and wrap the
//! upstream layout so the shared engine needs no Android-only fields. `Settings` derefs to the
//! upstream layout, so layout fields (`mode`, `sensitivity`, `button_scale`, ...) read as before.
//!
//! Files: the active settings are `horizon-touch.json`; presets are
//! `controls/<style>/<id>.json`; named profiles are `controlmap/<name>.json`.

use horizon_client::touch as core;
use serde::{Deserialize, Serialize};
use std::{
    io,
    ops::{Deref, DerefMut},
    path::{Path, PathBuf},
};

pub use core::Mode;

pub const ACTIVE_FILE: &str = "horizon-touch.json";
pub const DEFAULT_PRESET_ID: &str = "default";

/// Which control family a preset belongs to.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum ControlStyle {
    #[default]
    Mcpe,
    GoyControl,
}

impl ControlStyle {
    pub fn label(self) -> &'static str {
        match self {
            Self::Mcpe => "MCPE",
            Self::GoyControl => "GoyControl",
        }
    }

    pub fn storage_subdir(self) -> &'static str {
        match self {
            Self::Mcpe => "mcpe",
            Self::GoyControl => "goycontrol",
        }
    }

    fn default_name(self) -> String {
        format!("{} Default", self.label())
    }

    fn default_description(self) -> &'static str {
        match self {
            Self::Mcpe => "Factory MCPE-style layout",
            Self::GoyControl => "Factory GoyControl-style layout",
        }
    }
}

/// `Mode::label` for the launcher's scheme row.
pub trait ModeExt {
    fn label(&self) -> &'static str;
}

impl ModeExt for Mode {
    fn label(&self) -> &'static str {
        match self {
            Mode::Crosshair => "Crosshair",
            Mode::Tap => "Tap",
            Mode::Dpad => "D-pad",
        }
    }
}

/// Where the dynamic joystick may appear.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum MoveArea {
    #[default]
    LeftThird,
    LeftHalf,
    FullScreen,
}

impl MoveArea {
    pub fn next(self) -> Self {
        match self {
            Self::LeftThird => Self::LeftHalf,
            Self::LeftHalf => Self::FullScreen,
            Self::FullScreen => Self::LeftThird,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::LeftThird => "Left third ›",
            Self::LeftHalf => "Left half ›",
            Self::FullScreen => "Whole screen ›",
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct JoystickStyle {
    pub move_area: MoveArea,
}

/// A preset as shown in the chooser.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PresetMeta {
    pub id: String,
    pub name: String,
    pub description: String,
    pub system: ControlStyle,
    pub is_default: bool,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    /// The upstream on-screen layout and look/turn tuning.
    #[serde(flatten)]
    pub layout: core::Settings,
    pub control_style: ControlStyle,
    pub preset_id: String,
    pub preset_name: String,
    pub preset_description: String,
    pub active_profile: String,
    pub joystick_style: JoystickStyle,
    /// Extra on-screen buttons (GoyControl-style custom keys), kept as opaque JSON.
    pub custom_buttons: Vec<serde_json::Value>,
    pub virtual_mouse_enabled: bool,
    pub virtual_mouse_sensitivity: f32,
    pub virtual_mouse_pointer_scale: f32,
    pub gyroscope: bool,
    pub gyro_sensitivity: f32,
    pub vibrate: bool,
    pub invert_y: bool,
    pub inventory_controls: bool,
    pub resolution_scale_percent: u32,
    pub disable_game_animations: bool,
    pub zero_latency_touch: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self::default_for(ControlStyle::Mcpe)
    }
}

impl Deref for Settings {
    type Target = core::Settings;
    fn deref(&self) -> &core::Settings {
        &self.layout
    }
}

impl DerefMut for Settings {
    fn deref_mut(&mut self) -> &mut core::Settings {
        &mut self.layout
    }
}

fn finite_or(value: f32, fallback: f32) -> f32 {
    if value.is_finite() { value } else { fallback }
}

fn write_json(path: &Path, value: &impl Serialize) -> io::Result<()> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir)?;
    }
    let temp = path.with_extension("json.tmp");
    std::fs::write(&temp, serde_json::to_vec_pretty(value)?)?;
    std::fs::rename(temp, path)
}

fn style_dir(game_home: &Path, style: ControlStyle) -> PathBuf {
    game_home.join("controls").join(style.storage_subdir())
}

fn preset_path(game_home: &Path, style: ControlStyle, id: &str) -> PathBuf {
    style_dir(game_home, style).join(format!("{}.json", Settings::sanitize_profile_name(id)))
}

impl Settings {
    /// Factory settings for a control family.
    pub fn default_for(style: ControlStyle) -> Self {
        Self {
            layout: core::Settings::default(),
            control_style: style,
            preset_id: DEFAULT_PRESET_ID.into(),
            preset_name: style.default_name(),
            preset_description: style.default_description().into(),
            active_profile: String::new(),
            joystick_style: JoystickStyle::default(),
            custom_buttons: Vec::new(),
            virtual_mouse_enabled: true,
            virtual_mouse_sensitivity: 1.0,
            virtual_mouse_pointer_scale: 1.0,
            gyroscope: false,
            gyro_sensitivity: 1.0,
            vibrate: true,
            invert_y: false,
            inventory_controls: false,
            resolution_scale_percent: 100,
            disable_game_animations: false,
            zero_latency_touch: false,
        }
    }

    pub fn default_mcpe() -> Self {
        Self::default_for(ControlStyle::Mcpe)
    }

    /// Reads `path`; a missing or unreadable file gives the MCPE defaults.
    pub fn load(path: &Path) -> Self {
        let mut settings: Self = std::fs::read(path)
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default();
        settings.sanitize();
        settings
    }

    pub fn save(&self, path: &Path) -> io::Result<()> {
        write_json(path, self)
    }

    pub fn sanitize(&mut self) {
        self.layout.sanitize();
        if self.preset_id.trim().is_empty() {
            self.preset_id = DEFAULT_PRESET_ID.into();
        }
        if self.preset_name.trim().is_empty() {
            self.preset_name = self.control_style.default_name();
        }
        self.virtual_mouse_sensitivity =
            finite_or(self.virtual_mouse_sensitivity, 1.0).clamp(0.25, 3.0);
        self.virtual_mouse_pointer_scale =
            finite_or(self.virtual_mouse_pointer_scale, 1.0).clamp(0.5, 2.0);
        self.gyro_sensitivity = finite_or(self.gyro_sensitivity, 1.0).clamp(0.1, 3.0);
        self.resolution_scale_percent = self.resolution_scale_percent.clamp(25, 100);
    }

    /// Keeps only letters, digits, `-`, `_` and `.`; everything else becomes `-`.
    pub fn sanitize_profile_name(name: &str) -> String {
        let cleaned: String = name
            .trim()
            .chars()
            .map(|c| {
                if c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.') {
                    c
                } else {
                    '-'
                }
            })
            .take(48)
            .collect();
        let cleaned = cleaned.trim_matches(['-', '.']).to_owned();
        if cleaned.is_empty() { "profile".into() } else { cleaned }
    }

    pub fn is_default_preset(&self) -> bool {
        self.preset_id.eq_ignore_ascii_case(DEFAULT_PRESET_ID)
    }

    /// Only the D-pad scheme has a split variant.
    pub fn toggle_split_touch(&mut self) {
        let split = self.layout.mode == Mode::Dpad && !self.layout.split_controls;
        self.layout.split_controls = split;
    }

    /// Creates the preset folder and the protected default preset if missing.
    pub fn ensure_system_storage(game_home: &Path, style: ControlStyle) {
        let path = preset_path(game_home, style, DEFAULT_PRESET_ID);
        if !path.exists()
            && let Err(error) = Self::default_for(style).save(&path)
        {
            log::warn!("Cannot create the default {} preset: {error}", style.label());
        }
    }

    /// The presets of one family, the protected default first, then by name.
    pub fn list_presets(game_home: &Path, style: ControlStyle) -> Vec<PresetMeta> {
        Self::ensure_system_storage(game_home, style);
        let mut presets: Vec<PresetMeta> = std::fs::read_dir(style_dir(game_home, style))
            .into_iter()
            .flatten()
            .flatten()
            .filter_map(|entry| {
                let path = entry.path();
                if path.extension().and_then(|e| e.to_str()) != Some("json") {
                    return None;
                }
                let stem = path.file_stem()?.to_str()?.to_owned();
                let loaded = Self::load(&path);
                Some(PresetMeta {
                    is_default: stem.eq_ignore_ascii_case(DEFAULT_PRESET_ID),
                    id: stem,
                    name: loaded.preset_name,
                    description: loaded.preset_description,
                    system: style,
                })
            })
            .collect();
        presets.sort_by(|a, b| {
            b.is_default
                .cmp(&a.is_default)
                .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
        });
        presets
    }

    pub fn find_preset_by_name(
        game_home: &Path,
        style: ControlStyle,
        name: &str,
    ) -> Option<PresetMeta> {
        Self::list_presets(game_home, style)
            .into_iter()
            .find(|preset| preset.name.trim().eq_ignore_ascii_case(name.trim()))
    }

    /// Makes preset `id` the active settings.
    pub fn select_preset(&mut self, game_home: &Path, style: ControlStyle, id: &str) -> bool {
        let path = preset_path(game_home, style, id);
        let Ok(bytes) = std::fs::read(&path) else {
            return false;
        };
        let Ok(mut loaded) = serde_json::from_slice::<Self>(&bytes) else {
            return false;
        };
        loaded.control_style = style;
        loaded.preset_id = Self::sanitize_profile_name(id);
        loaded.sanitize();
        *self = loaded;
        self.save(&game_home.join(ACTIVE_FILE)).is_ok()
    }

    /// Switches to the other control family, starting from its default preset.
    pub fn switch_control_system(&mut self, game_home: &Path, style: ControlStyle) {
        if self.control_style == style {
            return;
        }
        if !self.is_default_preset() {
            let _ = self.save_replace_preset(game_home);
        }
        Self::ensure_system_storage(game_home, style);
        if !self.select_preset(game_home, style, DEFAULT_PRESET_ID) {
            *self = Self::default_for(style);
            let _ = self.save(&game_home.join(ACTIVE_FILE));
        }
    }

    /// Writes the active settings over their preset file (never the protected default).
    pub fn save_replace_preset(&self, game_home: &Path) -> io::Result<()> {
        if !self.is_default_preset() {
            self.save(&preset_path(game_home, self.control_style, &self.preset_id))?;
        }
        self.save(&game_home.join(ACTIVE_FILE))
    }

    /// Saves the active settings as a new preset and makes it active.
    pub fn save_new_preset(
        &mut self,
        game_home: &Path,
        name: &str,
        description: &str,
    ) -> io::Result<PresetMeta> {
        let meta = self.store_new_preset(game_home, name, description)?;
        self.save(&game_home.join(ACTIVE_FILE))?;
        Ok(meta)
    }

    /// Writes a new preset file without touching the active settings.
    fn store_new_preset(
        &mut self,
        game_home: &Path,
        name: &str,
        description: &str,
    ) -> io::Result<PresetMeta> {
        let base = Self::sanitize_profile_name(name).to_lowercase();
        let base = if base == DEFAULT_PRESET_ID { "custom".to_owned() } else { base };
        let mut id = base.clone();
        let mut n = 2;
        while preset_path(game_home, self.control_style, &id).exists() {
            id = format!("{base}-{n}");
            n += 1;
        }
        self.preset_id = id.clone();
        self.preset_name = name.trim().to_owned();
        self.preset_description = description.trim().to_owned();
        self.save(&preset_path(game_home, self.control_style, &id))?;
        Ok(PresetMeta {
            id,
            name: self.preset_name.clone(),
            description: self.preset_description.clone(),
            system: self.control_style,
            is_default: false,
        })
    }

    /// Replaces the preset with this name, or saves a new one.
    pub fn save_or_replace_preset_by_name(
        &mut self,
        game_home: &Path,
        name: &str,
        description: &str,
    ) -> io::Result<PresetMeta> {
        match Self::find_preset_by_name(game_home, self.control_style, name) {
            Some(existing) if !existing.is_default => {
                self.preset_id = existing.id.clone();
                self.preset_name = name.trim().to_owned();
                self.preset_description = description.trim().to_owned();
                self.save_replace_preset(game_home)?;
                Ok(PresetMeta {
                    name: self.preset_name.clone(),
                    description: self.preset_description.clone(),
                    ..existing
                })
            }
            _ => self.save_new_preset(game_home, name, description),
        }
    }

    /// A named profile in `controlmap/`.
    pub fn save_profile(&self, game_home: &Path, name: &str) -> io::Result<()> {
        let clean = Self::sanitize_profile_name(name);
        self.save(&game_home.join("controlmap").join(format!("{clean}.json")))
    }

    /// Writes preset `id`'s file and returns its path, for the file manager clipboard.
    pub fn export_profile(&self, _home: &Path, game_home: &Path, id: &str) -> io::Result<PathBuf> {
        let path = preset_path(game_home, self.control_style, id);
        self.save(&path)?;
        Ok(path)
    }

    /// Imports a control `.json` into the right preset folder. Only Horizon control files are
    /// recognised: ones with a `control_style`, or any layout `Settings` can read.
    pub fn detect_and_import_preset(
        game_home: &Path,
        bytes: &[u8],
        file_name: &str,
    ) -> Result<PresetMeta, String> {
        let value: serde_json::Value =
            serde_json::from_slice(bytes).map_err(|e| format!("Not a control layout: {e}"))?;
        if !value.is_object() {
            return Err("Not a control layout: expected a JSON object".into());
        }
        let mut loaded: Self = serde_json::from_value(value)
            .map_err(|e| format!("Not a Horizon control layout: {e}"))?;
        loaded.sanitize();
        let stem = Path::new(file_name)
            .file_stem()
            .and_then(|s| s.to_str())
            .unwrap_or("imported");
        let name = if loaded.is_default_preset() || loaded.preset_name.trim().is_empty() {
            stem.to_owned()
        } else {
            loaded.preset_name.clone()
        };
        let description = if loaded.preset_description.trim().is_empty() {
            "Imported control layout".to_owned()
        } else {
            loaded.preset_description.clone()
        };
        let style = loaded.control_style;
        Self::ensure_system_storage(game_home, style);
        loaded
            .store_new_preset(game_home, &name, &description)
            .map_err(|e| e.to_string())
    }
}
