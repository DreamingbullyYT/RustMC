//! Whole-frame resolution, independent of the world's graphics settings.
use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct RenderScale(u32);

impl Default for RenderScale {
    fn default() -> Self {
        Self(100)
    }
}

impl RenderScale {
    pub const PRESETS: [u32; 6] = [100, 85, 75, 67, 50, 25];

    pub fn new(percent: u32) -> Self {
        Self(percent.clamp(25, 100))
    }

    pub fn parse(text: &str) -> Result<Self, &'static str> {
        let percent = text
            .trim()
            .trim_end_matches('%')
            .trim()
            .parse::<u32>()
            .map_err(|_| "Enter a whole percentage from 25 to 100")?;
        if !(25..=100).contains(&percent) {
            return Err("Enter a percentage from 25 to 100");
        }
        Ok(Self(percent))
    }

    pub fn percent(self) -> u32 {
        self.0.clamp(25, 100)
    }

    pub fn buffer_size(self, view: [u32; 2]) -> [u32; 2] {
        let [w, h] = view.map(|v| v.max(1));
        // Keep standard menus usable even on a narrow keypad screen. Never
        // enlarge a surface that is already smaller than the GUI minimum.
        let minimum = (320.0 / w as f64).max(240.0 / h as f64).min(1.0);
        let scale = (self.percent() as f64 / 100.0).max(minimum);
        [w, h].map(|v| (v as f64 * scale).round().max(1.0) as u32)
    }
}

#[cfg(target_os = "android")]
static REQUESTED_SCALE: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(100);
#[cfg(target_os = "android")]
static CONTENT_ASPECT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

#[cfg(target_os = "android")]
pub fn set_native_content_aspect(aspect: [u32; 2]) {
    let packed = (u64::from(aspect[0]) << 32) | u64::from(aspect[1]);
    CONTENT_ASPECT.store(packed, std::sync::atomic::Ordering::Relaxed);
}

#[cfg(target_os = "android")]
fn native_content_rect(physical: [u32; 2]) -> [u32; 4] {
    let [mut x, mut y, mut width, mut height] =
        winit::platform::android::native_content_rect(physical);
    if width == 0 || height == 0 {
        return [x, y, width, height];
    }
    let aspect = CONTENT_ASPECT.load(std::sync::atomic::Ordering::Relaxed);
    let aspect_w = (aspect >> 32) as u32;
    let aspect_h = aspect as u32;
    if aspect_w == 0 || aspect_h == 0 {
        return [x, y, width, height];
    }
    let target = aspect_w as f64 / aspect_h as f64;
    let current = width as f64 / height as f64;
    if current > target {
        let next = ((height as f64 * target).round() as u32).clamp(1, width);
        x += (width - next) / 2;
        width = next;
    } else if current < target {
        let next = ((width as f64 / target).round() as u32).clamp(1, height);
        y += (height - next) / 2;
        height = next;
    }
    [x, y, width, height]
}

#[cfg(target_os = "android")]
pub fn set_scale(scale: RenderScale) {
    REQUESTED_SCALE.store(scale.percent(), std::sync::atomic::Ordering::Relaxed);
}

#[cfg(target_os = "android")]
fn desired(window: &winit::window::Window) -> Option<[u32; 2]> {
    let scale = RenderScale::new(REQUESTED_SCALE.load(std::sync::atomic::Ordering::Relaxed));
    crate::native_ime::refresh_bounds();
    let current = window.inner_size();
    let physical = window.outer_size();
    if physical.width == 0 || physical.height == 0 {
        return None;
    }
    let [_, _, width, height] =
        native_content_rect([physical.width, physical.height]);
    let view = [width.max(1), height.max(1)];
    let size = scale.buffer_size(view);
    winit::platform::android::set_native_input_scale([
        size[0] as f64 / view[0] as f64,
        size[1] as f64 / view[1] as f64,
    ]);
    if size == [current.width, current.height] {
        None
    } else {
        Some(size)
    }
}

#[cfg(target_os = "android")]
fn set_render_size(window: &winit::window::Window, size: [u32; 2]) -> Result<[u32; 2], String> {
    // Winit upstream has no fork-only set_render_size method. Keep the native
    // window untouched and resize Horizon's render target to the requested
    // content rectangle; desired() above keeps input scale in sync.
    let native = REQUESTED_SCALE.load(std::sync::atomic::Ordering::Relaxed) == 100;
    let physical = window.outer_size();
    let rect = native_content_rect([physical.width, physical.height]);
    if native && rect == [0, 0, physical.width, physical.height] {
        let actual = window.inner_size();
        Ok([actual.width, actual.height])
    } else {
        Ok(size)
    }
}

/// Set logical dimensions before a renderer recreates its native surface.
#[cfg(target_os = "android")]
pub fn prepare(window: &winit::window::Window) -> Result<Option<[u32; 2]>, String> {
    desired(window)
        .map(|size| set_render_size(window, size))
        .transpose()
}

/// Update the logical frame target; native presentation keeps its real dimensions.
#[cfg(target_os = "android")]
pub fn apply(
    window: &winit::window::Window,
    renderer: &mut horizon_client::gfx::Renderer,
) -> Result<(), String> {
    if renderer.suspended {
        return Ok(());
    }
    let size = if let Some(size) = desired(window) {
        set_render_size(window, size)?
    } else {
        let size = window.inner_size();
        [size.width, size.height]
    };
    // Some Android firmware updates the native window without delivering a
    // matching Resized event. Reconcile even at 100%, where no scale changes.
    if size[0] > 0 && size[1] > 0 && size != [renderer.config.width, renderer.config.height] {
        renderer.resize(size[0], size[1]);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn halves_surface_area_dimensions_without_changing_aspect() {
        assert_eq!(RenderScale::new(50).buffer_size([2772, 1280]), [1386, 640]);
        assert_eq!(
            RenderScale::default().buffer_size([2772, 1280]),
            [2772, 1280]
        );
    }

    #[test]
    fn keypad_screen_keeps_usable_menu_width() {
        assert_eq!(RenderScale::new(25).buffer_size([480, 854]), [320, 569]);
        assert_eq!(RenderScale::new(25).buffer_size([240, 320]), [240, 320]);
        assert_eq!(RenderScale::new(25).buffer_size([0, 0]), [1, 1]);
    }

    #[test]
    fn edited_and_old_settings_have_bounded_values() {
        assert_eq!(RenderScale::parse(" 67% ").unwrap().percent(), 67);
        assert!(RenderScale::parse("0").is_err());
        assert!(RenderScale::parse("101").is_err());
        assert_eq!(
            serde_json::from_str::<RenderScale>("0").unwrap().percent(),
            25
        );
    }
}

/// Fixed presentation ratios; logical rendering and input share the same bounds.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Aspect {
    #[default]
    Native,
    Wide,
    Classic,
    Square,
}
impl Aspect {
    pub fn label(self) -> &'static str {
        match self {
            Self::Native => "Native",
            Self::Wide => "16:9",
            Self::Classic => "4:3",
            Self::Square => "1:1",
        }
    }
    pub fn ratio(self) -> [u32; 2] {
        match self {
            Self::Native => [0, 0],
            Self::Wide => [16, 9],
            Self::Classic => [4, 3],
            Self::Square => [1, 1],
        }
    }
}

/// Client-wide screen choices. Missing preferences start in navigation-free mode.
#[derive(Clone, Copy, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct ScreenPreferences {
    pub immersive: bool,
    pub avoid_cutout: bool,
    pub aspect: Aspect,
}
impl Default for ScreenPreferences {
    fn default() -> Self {
        Self {
            // Toggling immersive system-bar hiding has freeze reports on some
            // devices; keep navigation bars visible until that is understood.
            immersive: false,
            avoid_cutout: false,
            aspect: Aspect::Native,
        }
    }
}
#[cfg(test)]
mod screen_tests {
    use super::*;
    #[test]
    fn missing_screen_preferences_keep_navigation_bars_visible() {
        let defaults: ScreenPreferences = serde_json::from_str("{}").unwrap();
        assert!(!defaults.immersive);
        let saved: ScreenPreferences =
            serde_json::from_str(r#"{"immersive":false,"aspect":"classic","avoid_cutout":true}"#)
                .unwrap();
        assert!(!saved.immersive);
        assert!(saved.avoid_cutout);
        assert_eq!(saved.aspect, Aspect::Classic);
    }
}
