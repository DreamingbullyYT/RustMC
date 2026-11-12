//! Android-side game host adapter. The renderer/UI helpers stay outside upstream.

use std::sync::Arc;
use winit::{application::ApplicationHandler, window::Window};

use crate::horizon_client_adapter::{
    Result,
    gfx::Renderer,
    graphics::GraphicsApi,
    hud::{HudAtlas, HudBatch},
};
use horizon_client::gfx::Frame;

/// Resources used by the original Android launcher while the game is not active.
pub struct UiResources {
    pub atlas: HudAtlas,
    pub panorama: Arc<crate::horizon_client_adapter::gfx::panorama::Faces>,
    pub sounds: Option<crate::horizon_client_adapter::sound_events::SoundRegistry>,
}

/// Build the same small renderer used by the original Android launcher's loading view.
pub fn loading_renderer(
    window: Arc<Window>,
    offscreen: bool,
    atlas: Option<&HudAtlas>,
    panorama: Option<&crate::horizon_client_adapter::gfx::panorama::Faces>,
    previous: Option<&Renderer>,
    api: GraphicsApi,
) -> Result<Renderer> {
    static FORCEFIELD: [u8; 4] = [0; 4];
    static EMPTY_HUD: [u8; 4] = [0, 0, 0, 255];
    static EMPTY_GLINT: [u8; 4] = [0; 4];
    let empty_panorama = crate::horizon_client_adapter::gfx::panorama::Faces::colour([96, 110, 111, 255]);
    let panorama = panorama.unwrap_or(&empty_panorama);
    let (pixels, width, height) = atlas.map_or((EMPTY_HUD.as_slice(), 1, 1), |a| {
        (a.pixels.as_slice(), a.width, a.height)
    });
    let (glint_pixels, glint_width, glint_height) =
        atlas.map_or((EMPTY_GLINT.as_slice(), 1, 1), |a| {
            (a.glint.0.as_slice(), a.glint.1, a.glint.2)
        });
    let (armor_glint_pixels, armor_glint_width, armor_glint_height) =
        atlas.map_or((EMPTY_GLINT.as_slice(), 1, 1), |a| {
            (a.glint_armor.0.as_slice(), a.glint_armor.1, a.glint_armor.2)
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
        (armor_glint_pixels, armor_glint_width, armor_glint_height),
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
    crate::horizon_client_adapter::game::create_application_handler(arguments)
}

/// Upstream owns gameplay/mod lifecycle; there is no fork-side global registry to reset here.
pub fn android_reset_bundled_mods() {
    horizon_narrator::clear();
    crate::discord_presence::stop();
}


pub fn loading_frame(hud: &HudBatch) -> Frame<'_> {
    Frame {
        weather: &[],
        weather_time: horizon_client::weather::Time::new(0, 0.0),
        camera: glam::DVec3::new(0.5, 100.0, 0.5),
        yaw: 0.0,
        pitch: 10.0,
        fov_degrees: 85.0,
        eye: glam::DVec3::new(0.5, 100.0, 0.5),
        eye_rotation: [0.0, 10.0],
        first_person: true,
        bob: glam::Mat4::IDENTITY,
        spin: glam::Mat4::IDENTITY,
        sky: horizon_client::environment::Sky::default(),
        shadow_scene_revision: (0, 0),
        shadow_cache: false,
        distance: 12.0,
        detail_distance: 12.0,
        depth_far: 1000.0,
        underwater: false,
        water_plane: None,
        cave_culling: false,
        title: true,
        sky_blocked: false,
        skybox: false,
        end_sky: false,
        end_sky_sprite: None,
        boss_world_fog: false,
        environment_fog: None,
        water_fog: [0.0; 3],
        fog: None,
        dark_disc: false,
        outline: Vec::new(),
        outline_after_terrain: false,
        outline_high_contrast: false,
        pvp: Default::default(),
        debug_lines: &[],
        debug_lines_on_top: &[],
        debug_crosshair: &[],
        hud,
        glint_alpha: 0.75,
        glint_ticks: 0.0,
        nether_lighting: false,
        entities: &[],
        static_block_entities: &[],
        visible_static_block_entity_sections: &[],
        static_block_entity_stats: Default::default(),
        entity_motion: &[],
        entity_motion_posed: &[],
        entity_spans: &[],
        skin: None,
        glowing: &[],
        dynamic_lights: &[],
        colored_lights: &[],
        emissive_layers: &[],
        clouds: None,
        lightning: Default::default(),
        additive: &[],
        see_through: &[],
        world_primitives: Default::default(),
        shadow_only: &[],
        celestial: &[],
        stars: None,
        hand: &[],
        hand_items: &[],
        hand_fov_degrees: 70.0,
        screen: &[],
        pack: Default::default(),
        border: None,
        game_time: 0.0,
        shader_game_time: 0.0,
        end_portal_sprites: [[0.0; 4]; 2],
        crumbling_sprite: [0.0; 4],
        ticks: 0,
        darkness: (0.0, 0.0),
        night_vision: 0.0,
        block_flicker: 0.0,
        boss_darken: 0.0,
        chunk_fade: 0.0,
        post_effects: horizon_client::post_chain::Request::none(),
        menu_blur: None,
    }
}
