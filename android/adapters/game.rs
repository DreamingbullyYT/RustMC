//! Android-side game host adapter. The renderer/UI helpers stay outside upstream.

use std::sync::Arc;
use winit::{application::ApplicationHandler, window::Window};

use crate::horizon_client_adapter::{
    Result,
    gfx::Renderer,
    graphics::GraphicsApi,
    hud::HudAtlas,
};

/// Resources used by the original Android launcher while the game is not active.
pub struct UiResources {
    pub atlas: HudAtlas,
    pub panorama: Arc<crate::horizon_client::gfx::panorama::Faces>,
    pub sounds: Option<crate::horizon_client::sound_events::SoundRegistry>,
}

/// Build the same small renderer used by the original Android launcher's loading view.
pub fn loading_renderer(
    window: Arc<Window>,
    offscreen: bool,
    atlas: Option<&HudAtlas>,
    panorama: Option<&crate::horizon_client::gfx::panorama::Faces>,
    previous: Option<&Renderer>,
    api: GraphicsApi,
) -> Result<Renderer> {
    static FORCEFIELD: [u8; 4] = [0; 4];
    static EMPTY_HUD: [u8; 4] = [0, 0, 0, 255];
    static EMPTY_GLINT: [u8; 4] = [0; 4];
    let empty_panorama = crate::horizon_client::gfx::panorama::Faces {
        size: 1,
        pixels: [0u8; 4].repeat(6),
    };
    let panorama = panorama.unwrap_or(&empty_panorama);
    let (pixels, width, height) = atlas.map_or((EMPTY_HUD.as_slice(), 1, 1), |a| {
        (a.pixels.as_slice(), a.width, a.height)
    });
    let (glint_pixels, glint_width, glint_height) =
        atlas.map_or((EMPTY_GLINT.as_slice(), 1, 1), |a| {
            (a.glint.0.as_slice(), a.glint.1, a.glint.2)
        });
    if let Some(atlas) = atlas {
        atlas.font.reset_cache();
    }
    let mut renderer = pollster::block_on(Renderer::new(
        window,
        offscreen,
        &[255, 255, 255, 255],
        1,
        1,
        1,
        &[],
        &[],
        (pixels, width, height),
        (glint_pixels, glint_width, glint_height),
        (glint_pixels, glint_width, glint_height),
        (&FORCEFIELD[..], 1),
        panorama,
        None,
        previous,
        api,
    ))?;
    if let Some(atlas) = atlas {
        renderer.hud.sprite_animations = atlas
            .sprite_animations
            .iter()
            .map(|(rect, animation)| (*rect, animation.clone(), (usize::MAX, 0, 0)))
            .collect();
    }
    Ok(renderer)
}

/// Ask upstream to construct its existing loading/game ApplicationHandler.
/// The Android launcher will host this handler on its one existing Winit loop.
pub fn android_game(arguments: Vec<String>) -> Result<Box<dyn ApplicationHandler>> {
    crate::horizon_client::game::create_application_handler(arguments)
}

/// Upstream owns gameplay/mod lifecycle; there is no fork-side global registry to reset here.
pub fn android_reset_bundled_mods() {
    horizon_narrator::stop();
    crate::horizon_client::discord_presence::stop();
}
