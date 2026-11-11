impl Launcher {
    /// Revamped Settings tab matching `45062.jpg` with 2-column MY3 category cards
    /// and dedicated sub-pages for Video, Launcher, Controls, Experimental, and Diagnostics.
    #[allow(clippy::too_many_arguments)]
    fn draw_settings_page(
        &mut self,
        ui: &mut Ui,
        content_x: f32,
        content_w: f32,
        h: f32,
        compact: bool,
        slide_y: f32,
        ime_top_dp: Option<f32>,
    ) {
        let cat = self.catalog.settings_category;
        if cat == SettingsCategory::Overview {
            ui.text(
                Face::SemiBold,
                20.0,
                content_x,
                62.0 + slide_y,
                my3_text(),
                "Settings",
            );

            let cards: [(Icon, &str, &str, Action); 6] = [
                (
                    Icon::Monitor,
                    "Video Settings",
                    "Rendering, resolution and performance",
                    Action::SettingsCat(SettingsCategory::Video),
                ),
                (
                    Icon::Gear,
                    "Launcher Settings",
                    "Theme customization, profiles, DPI scale & preferences",
                    Action::SettingsCat(SettingsCategory::Launcher),
                ),
                (
                    Icon::Gamepad,
                    "Controls",
                    "HUD touch layout, buttons, sensitivity and profiles",
                    Action::SettingsCat(SettingsCategory::Controls),
                ),
                (
                    Icon::Flask,
                    "Experimental Stuff",
                    "Unstable features. We put the weird shit here.",
                    Action::SettingsCat(SettingsCategory::Experimental),
                ),
                (
                    Icon::Terminal,
                    "Diagnostics",
                    "Logs, device info and troubleshooting",
                    Action::SettingsCat(SettingsCategory::Diagnostics),
                ),
                (
                    Icon::MicrosoftGrid,
                    "Account",
                    "Microsoft and Xbox sign-in",
                    Action::Account,
                ),
            ];

            let grid_top = 98.0 + slide_y;
            let gap_x = 14.0;
            let gap_y = 12.0;
            let cols = if compact { 1 } else { 2 };
            let card_w = if cols == 2 {
                (content_w - gap_x) * 0.5
            } else {
                content_w
            };
            let card_h = ((h - grid_top - 52.0 - gap_y * 2.0) / 3.0).clamp(68.0, 92.0);

            for (idx, (icon, title, subtitle, action)) in cards.into_iter().enumerate() {
                let col = idx % cols;
                let row = idx / cols;
                let cx = content_x + col as f32 * (card_w + gap_x);
                let cy = grid_top + row as f32 * (card_h + gap_y);
                let rect = [cx, cy, card_w, card_h];

                ui.rounded(cx, cy, card_w, card_h, 16.0, my3_card_bg());
                ui.border(cx, cy, card_w, card_h, 16.0, my3_card_border());

                // Left vector icon + vertical divider line matching 45062.jpg
                let icon_size = 20.0;
                ui.icon(
                    icon,
                    cx + 20.0,
                    cy + (card_h - icon_size) * 0.5,
                    icon_size,
                    my3_accent(),
                );
                ui.rounded(
                    cx + 54.0,
                    cy + card_h * 0.26,
                    1.5,
                    card_h * 0.48,
                    0.75,
                    my3_border(),
                );

                let text_x = cx + 68.0;
                let text_max = (card_w - 82.0).max(60.0);
                let fitted_title = ui.fit_text(Face::SemiBold, 15.5, title, text_max);
                let fitted_sub = ui.fit_text(Face::Regular, 12.5, subtitle, text_max);
                let text_block_y = cy + (card_h - 40.0) * 0.5;
                ui.text(
                    Face::SemiBold,
                    15.5,
                    text_x,
                    text_block_y,
                    my3_text(),
                    &fitted_title,
                );
                ui.text(
                    Face::Regular,
                    12.5,
                    text_x,
                    text_block_y + 22.0,
                    my3_muted(),
                    &fitted_sub,
                );

                self.list_targets.push((rect, action));
                self.targets.push((rect, action));
            }
            return;
        }

        // Sub-category Header Bar with hierarchical Back button
        let header_y = 60.0 + slide_y;
        let back_cat = if cat == SettingsCategory::Theme {
            SettingsCategory::Launcher
        } else {
            SettingsCategory::Overview
        };
        button(
            ui,
            &mut self.targets,
            [content_x, header_y, 88.0, 32.0],
            "← Back",
            Action::SettingsCat(back_cat),
            false,
        );
        let fitted_cat = ui.fit_text(
            Face::SemiBold,
            18.5,
            cat.title(),
            (content_w - 104.0).max(80.0),
        );
        ui.text(
            Face::SemiBold,
            18.5,
            content_x + 100.0,
            header_y + 3.0,
            my3_text(),
            &fitted_cat,
        );

        let list_top = 102.0 + slide_y;
        let list_bottom = h - 42.0;
        let card_h = 58.0;
        let step_y = 66.0;

        let game_home = self.catalog.config.game_home(&self.home);
        let opts = horizon_client::settings::Settings::load(&game_home.join("options.txt"));
        let touch_cfg =
            crate::touch_compat::Settings::load(&game_home.join("horizon-touch.json"));

        match cat {
            SettingsCategory::Video => {
                let c = &self.catalog.config;
                let count = 17;
                let extra_ime = if ime_top_dp.is_some() { 180.0 } else { 0.0 };
                let max_scroll =
                    (count as f32 * step_y + extra_ime - (list_bottom - list_top)).max(0.0);
                self.catalog.scroll = self.catalog.scroll.clamp(0.0, max_scroll);
                self.list_view = Some([list_top, list_bottom, max_scroll]);

                let renderer_str = format!("{} v", c.renderer.label());
                let res_pct = c.render_scale.percent().clamp(25, 100);
                let res_frac = ((res_pct - 25) as f32 / 75.0).clamp(0.0, 1.0);
                let res_str = if self.catalog.editing == Some("render_scale") {
                    self.catalog.render_scale_text.clone()
                } else {
                    format!("{res_pct}%")
                };
                let hz_str = format!("{} v", c.device.display_label());
                let aspect_str = format!("{} v", c.screen.aspect.label());
                let orient_str = format!("{} v", c.orientation.label());
                let rd_str = format!("{} chunks", opts.render_distance);
                let rd_frac = ((opts.render_distance - 2) as f32 / 30.0).clamp(0.0, 1.0);
                let sd_str = format!("{} chunks", opts.simulation_distance);
                let sd_frac = ((opts.simulation_distance - 5) as f32 / 27.0).clamp(0.0, 1.0);
                let fov_str = format!("{:.0}°", opts.fov);
                let fov_frac = ((opts.fov - 30.0) / 80.0).clamp(0.0, 1.0);
                let preset_str = format!("{} ›", capitalize(&opts.graphics_preset));
                let fps_str = if opts.max_fps >= 260 {
                    "Unlimited ›".to_owned()
                } else {
                    format!("{} FPS ›", opts.max_fps)
                };
                let clouds_str = match opts.clouds.as_str() {
                    "false" => "Off ›",
                    "fast" => "Fast ›",
                    _ => "Fancy ›",
                };
                let game_anim_off = c.disable_game_animations || touch_cfg.disable_game_animations;

                let rows: [(&str, &str, SettingControl<'_>); 17] = [
                    (
                        "DISPLAY, RESOLUTION & RENDERER PIPELINE",
                        "Hardware GPU backend, 3D resolution scale, refresh rate & display geometry",
                        SettingControl::SectionHeader(c.renderer.label(), Icon::Monitor),
                    ),
                    (
                        "Rendering backend (Vulkan / GLES)",
                        "Hardware GPU driver pipeline used when launching Minecraft",
                        SettingControl::Button(&renderer_str, Action::Renderer, true),
                    ),
                    (
                        "Client resolution scale",
                        "3D framebuffer resolution percentage (also adjustable live in-game via Floating Menu)",
                        if self.catalog.editing == Some("render_scale") {
                            SettingControl::TextField {
                                label: "SCALE %",
                                value: &self.catalog.render_scale_text,
                                hint: "25 - 100%",
                                active: true,
                                action: Action::Field("render_scale"),
                            }
                        } else {
                            SettingControl::Slider {
                                value_label: &res_str,
                                fraction: res_frac,
                                minus_action: Action::RenderScaleDelta(-5),
                                plus_action: Action::RenderScaleDelta(5),
                            }
                        },
                    ),
                    (
                        "Display refresh rate",
                        "Preferred Android screen refresh rate (60Hz / 90Hz / 120Hz)",
                        SettingControl::Button(&hz_str, Action::DisplayHz, false),
                    ),
                    (
                        "Display aspect ratio",
                        "Native fullscreen, 16:9 widescreen, 4:3 classic or square",
                        SettingControl::Button(&aspect_str, Action::Aspect, false),
                    ),
                    (
                        "Screen orientation",
                        "Sensor landscape, locked landscape or portrait layout",
                        SettingControl::Button(&orient_str, Action::Orientation, false),
                    ),
                    (
                        "Immersive fullscreen",
                        "Hide Android system navigation bars during launcher and gameplay",
                        SettingControl::Toggle(c.screen.immersive, Action::Immersive),
                    ),
                    (
                        "Avoid camera cutout / notch",
                        "Inset HUD away from punch-hole cameras and curved display edges",
                        SettingControl::Toggle(c.screen.avoid_cutout, Action::Cutout),
                    ),
                    (
                        "IN-GAME GRAPHICS, DISTANCES & MOTION",
                        "Chunk visibility, simulation radius, FOV, framerate & animation controls",
                        SettingControl::SectionHeader("Minecraft Video", Icon::Shaders),
                    ),
                    (
                        "Render Distance",
                        "Visible chunk radius (6–8 chunks recommended on Mali-G52 with shaders)",
                        SettingControl::Slider {
                            value_label: &rd_str,
                            fraction: rd_frac,
                            minus_action: Action::RenderDistDelta(-2),
                            plus_action: Action::RenderDistDelta(2),
                        },
                    ),
                    (
                        "Simulation Distance",
                        "Active entity and block-tick radius around the player",
                        SettingControl::Slider {
                            value_label: &sd_str,
                            fraction: sd_frac,
                            minus_action: Action::SimDistDelta(-1),
                            plus_action: Action::SimDistDelta(1),
                        },
                    ),
                    (
                        "Field of View (FOV)",
                        "Horizontal camera view angle in degrees",
                        SettingControl::Slider {
                            value_label: &fov_str,
                            fraction: fov_frac,
                            minus_action: Action::FovDelta(-5.0),
                            plus_action: Action::FovDelta(5.0),
                        },
                    ),
                    (
                        "Graphics Quality Preset",
                        "Cycle between Fast, Fancy and Fabulous rendering modes",
                        SettingControl::Button(&preset_str, Action::CycleGraphicsPreset, false),
                    ),
                    (
                        "Max Framerate Ceiling",
                        "Cap gameplay FPS to save power or unlock for high-refresh displays",
                        SettingControl::Button(&fps_str, Action::CycleMaxFps, false),
                    ),
                    (
                        "Vertical Sync & Cloud Rendering",
                        "Tap switch to toggle VSync, or cycle Clouds in in-game video options",
                        SettingControl::Toggle(opts.vsync, Action::ToggleVsync),
                    ),
                    (
                        "Menu Background Blur",
                        "Cached zero-lag backdrop blur when opening inventory or pause menus",
                        SettingControl::Toggle(opts.menu_blur > 0, Action::ToggleMenuBlur),
                    ),
                    (
                        "Disable In-Game Animations",
                        "Turn off animated block textures, chunk fade, hand bobbing & UI transitions completely",
                        SettingControl::Toggle(game_anim_off, Action::ToggleGameAnimations),
                    ),
                ];
                let _ = clouds_str;

                ui.set_clip(Some([
                    content_x - 2.0,
                    list_top,
                    content_w + 4.0,
                    (list_bottom - list_top).max(1.0),
                ]));
                for (i, (title, sub, ctrl)) in rows.into_iter().enumerate() {
                    let y = list_top + i as f32 * step_y - self.catalog.scroll;
                    if y + card_h < list_top || y > list_bottom {
                        continue;
                    }
                    draw_setting_card(
                        ui,
                        &mut self.targets,
                        &mut self.list_targets,
                        &mut self.anim,
                        content_x,
                        y,
                        content_w,
                        card_h,
                        title,
                        sub,
                        ctrl,
                    );
                }
                ui.set_clip(None);
            }
            SettingsCategory::Launcher => {
                let c = &self.catalog.config;
                let count = 12;
                let extra_ime = if ime_top_dp.is_some() { 180.0 } else { 0.0 };
                let max_scroll =
                    (count as f32 * step_y + extra_ime - (list_bottom - list_top)).max(0.0);
                if self.catalog.editing == Some("server")
                    && let Some(ime_y) = ime_top_dp
                {
                    let server_y = list_top + 10.0 * step_y - self.catalog.scroll;
                    if server_y + card_h > ime_y - 12.0 {
                        self.catalog.scroll = (list_top + 10.0 * step_y + card_h - (ime_y - 16.0))
                            .clamp(0.0, max_scroll);
                    }
                }
                self.catalog.scroll = self.catalog.scroll.clamp(0.0, max_scroll);
                self.list_view = Some([list_top, list_bottom, max_scroll]);

                let theme_str = format!("{} ›", c.theme.preset.label());
                let ui_scale_str = format!("{} v", c.ui_scale_label());
                let profile_str = format!("Profile {} v", c.profile + 1);
                let input_str = format!("{} v", c.input_host.label());
                let worker_str = if c.worker_limit == 0 {
                    "Auto (Big.LITTLE Safe) v".to_owned()
                } else {
                    format!("{} threads v", c.worker_limit)
                };
                let cache_str = if c.cache_budget_mib == 0 {
                    "Default (80 MiB) v".to_owned()
                } else {
                    format!("{} MiB v", c.cache_budget_mib)
                };
                let server_str = if c.server.is_empty() {
                    "Singleplayer / Tap to set".to_owned()
                } else {
                    c.server.clone()
                };
                let game_anim_off = c.disable_game_animations || touch_cfg.disable_game_animations;

                let rows: [(&str, &str, SettingControl<'_>); 12] = [
                    (
                        "APPEARANCE, DPI SCALING & MOTION",
                        "Material 3 Expressive theme studio, adaptive phone DPI scaling & animation toggles",
                        SettingControl::SectionHeader(c.theme.preset.label(), Icon::Settings),
                    ),
                    (
                        "Theme",
                        "Preset themes, custom solid/accent colors, multi-stop gradients & light/dark mode",
                        SettingControl::Button(
                            &theme_str,
                            Action::SettingsCat(SettingsCategory::Theme),
                            true,
                        ),
                    ),
                    (
                        "Launcher UI scale (Phone DPI)",
                        "Adapts launcher cards, text and buttons to your screen DPI",
                        SettingControl::Button(&ui_scale_str, Action::UiScale, false),
                    ),
                    (
                        "Disable Launcher Animations",
                        "Turn off page transitions, nav rail slides, shimmer & modal animations in launcher",
                        SettingControl::Toggle(
                            c.disable_launcher_animations,
                            Action::ToggleLauncherAnimations,
                        ),
                    ),
                    (
                        "Disable In-Game Animations",
                        "Turn off animated block textures, chunk fade, view bobbing & HUD animations in-game",
                        SettingControl::Toggle(game_anim_off, Action::ToggleGameAnimations),
                    ),
                    (
                        "PROFILES, WORKER THREADS & STORAGE",
                        "Isolated game profiles, CPU worker threads, RAM cache & direct server launch",
                        SettingControl::SectionHeader(&profile_str, Icon::Folder),
                    ),
                    (
                        "Active game profile",
                        "Each profile isolates its own worlds, mods and options",
                        SettingControl::Button(&profile_str, Action::Profile, false),
                    ),
                    (
                        "Input host mode",
                        "Switch between touchscreen HUD and physical mouse/keyboard",
                        SettingControl::Button(&input_str, Action::InputHost, false),
                    ),
                    (
                        "Chunk worker thread ceiling",
                        "Background worldgen and chunk meshing worker limit",
                        SettingControl::Button(&worker_str, Action::WorkerLimit, false),
                    ),
                    (
                        "Reusable RAM cache budget",
                        "Pooled audio & mesh buffer cache ceiling",
                        SettingControl::Button(&cache_str, Action::CacheBudget, false),
                    ),
                    (
                        "Quick-join server address",
                        "Optional server IP:port to connect immediately on Play",
                        SettingControl::TextField {
                            label: "SERVER IP",
                            value: &c.server,
                            hint: &server_str,
                            active: self.catalog.editing == Some("server"),
                            action: Action::Field("server"),
                        },
                    ),
                    (
                        "Open game & world folders",
                        "Manage saves, resource packs and mods in Android Files",
                        SettingControl::Button("Open Files", Action::GameFiles, false),
                    ),
                ];

                ui.set_clip(Some([
                    content_x - 2.0,
                    list_top,
                    content_w + 4.0,
                    (list_bottom - list_top).max(1.0),
                ]));
                for (i, (title, sub, ctrl)) in rows.into_iter().enumerate() {
                    let y = list_top + i as f32 * step_y - self.catalog.scroll;
                    if y + card_h < list_top || y > list_bottom {
                        continue;
                    }
                    draw_setting_card(
                        ui,
                        &mut self.targets,
                        &mut self.list_targets,
                        &mut self.anim,
                        content_x,
                        y,
                        content_w,
                        card_h,
                        title,
                        sub,
                        ctrl,
                    );
                }
                ui.set_clip(None);
            }
            SettingsCategory::Theme => {
                use crate::ui_theme::{
                    BackgroundMode, GradientDirection, THEME_ACCENT_SWATCHES,
                    THEME_BG_SWATCHES_DARK, THEME_BG_SWATCHES_LIGHT, ThemeAppearance, ThemePreset,
                    color,
                };
                let theme_cfg = self.catalog.config.theme.clone();
                let resolved = theme_cfg.resolve();
                let total_h = if theme_cfg.background_mode == BackgroundMode::Gradient {
                    690.0
                } else {
                    580.0
                };
                let extra_ime = if ime_top_dp.is_some() { 200.0 } else { 0.0 };
                let max_scroll = (total_h + extra_ime - (list_bottom - list_top)).max(0.0);
                if let Some(ime_y) = ime_top_dp {
                    let target_row_y = match self.catalog.editing {
                        Some("theme_accent") => Some(list_top + 320.0),
                        Some("theme_bg" | "theme_surface") => Some(list_top + 404.0),
                        Some("theme_grad_start" | "theme_grad_mid" | "theme_grad_end") => {
                            Some(list_top + 532.0)
                        }
                        _ => None,
                    };
                    if let Some(raw_y) = target_row_y {
                        let vis_y = raw_y - self.catalog.scroll;
                        if vis_y + 56.0 > ime_y - 12.0 {
                            self.catalog.scroll =
                                (raw_y + 56.0 - (ime_y - 16.0)).clamp(0.0, max_scroll);
                        }
                    }
                }
                self.catalog.scroll = self.catalog.scroll.clamp(0.0, max_scroll);
                self.list_view = Some([list_top, list_bottom, max_scroll]);

                ui.set_clip(Some([
                    content_x - 2.0,
                    list_top,
                    content_w + 4.0,
                    (list_bottom - list_top).max(1.0),
                ]));
                let mut cy = list_top - self.catalog.scroll;

                // 1. Live Interactive Theme Preview Card (with smooth pulse on change)
                let pulse = 0.985 + 0.015 * self.anim.theme_pulse;
                let prev_h = 96.0;
                let pw = content_w * pulse;
                let px = content_x + (content_w - pw) * 0.5;
                if cy + prev_h >= list_top && cy <= list_bottom {
                    ui.rounded(px, cy, pw, prev_h, 16.0, resolved.surface);
                    ui.clipped([px + 2.0, cy + 2.0, pw - 4.0, prev_h - 4.0], |ui| {
                        ui.draw_theme_background(
                            px + 2.0,
                            cy + 2.0,
                            pw - 4.0,
                            prev_h - 4.0,
                            &resolved,
                        );
                    });
                    ui.stroke_rounded(px, cy, pw, prev_h, 16.0, 1.5, resolved.accent);

                    // Mini surface card inside Live Preview
                    let inner_w = (pw - 28.0).max(160.0);
                    let inner_x = px + 14.0;
                    let inner_y = cy + 12.0;
                    ui.rounded(inner_x, inner_y, inner_w, 72.0, 12.0, resolved.surface);
                    ui.border(inner_x, inner_y, inner_w, 72.0, 12.0, resolved.border);
                    ui.rounded(
                        inner_x + 12.0,
                        inner_y + 12.0,
                        42.0,
                        20.0,
                        6.0,
                        resolved.accent_soft,
                    );
                    ui.centered_fit(
                        Face::SemiBold,
                        9.5,
                        [inner_x + 12.0, inner_y + 12.0, 42.0, 20.0],
                        resolved.accent,
                        "LIVE",
                    );
                    let p_title = format!(
                        "{} • {} • {}",
                        theme_cfg.preset.label(),
                        if resolved.is_light { "Light" } else { "Dark" },
                        theme_cfg.background_mode.label()
                    );
                    let fit_pt =
                        ui.fit_text(Face::SemiBold, 13.5, &p_title, (inner_w - 190.0).max(80.0));
                    ui.text(
                        Face::SemiBold,
                        13.5,
                        inner_x + 62.0,
                        inner_y + 11.0,
                        resolved.text_primary,
                        &fit_pt,
                    );
                    let p_sub = format!(
                        "Accent {}  •  Background {}",
                        theme_cfg.custom_accent_hex,
                        if resolved.is_gradient {
                            format!(
                                "{} → {}",
                                theme_cfg.gradient_start_hex, theme_cfg.gradient_end_hex
                            )
                        } else {
                            theme_cfg.custom_bg_hex.clone()
                        }
                    );
                    let fit_ps =
                        ui.fit_text(Face::Regular, 11.0, &p_sub, (inner_w - 190.0).max(80.0));
                    ui.text(
                        Face::Regular,
                        11.0,
                        inner_x + 12.0,
                        inner_y + 42.0,
                        resolved.text_muted,
                        &fit_ps,
                    );

                    // Reset to Default button inside Live Preview card
                    let reset_r = [inner_x + inner_w - 118.0, inner_y + 18.0, 106.0, 36.0];
                    button(
                        ui,
                        &mut self.targets,
                        reset_r,
                        "Reset Default",
                        Action::ResetTheme,
                        false,
                    );
                    self.list_targets.push((reset_r, Action::ResetTheme));
                }
                cy += prev_h + 14.0;

                // 2. Appearance Mode (Dark Mode vs Light Mode) & Background Mode (Solid vs Gradient)
                if cy + 44.0 >= list_top && cy <= list_bottom {
                    let half = (content_w - 10.0) * 0.5;
                    let quarter = (half - 6.0) * 0.5;
                    for (i, (lbl, mode, active)) in [
                        ("Dark", ThemeAppearance::Dark, !resolved.is_light),
                        ("Light", ThemeAppearance::Light, resolved.is_light),
                    ]
                    .into_iter()
                    .enumerate()
                    {
                        let r = [content_x + i as f32 * (quarter + 6.0), cy, quarter, 38.0];
                        button(
                            ui,
                            &mut self.targets,
                            r,
                            lbl,
                            Action::SetThemeAppearance(mode),
                            active,
                        );
                        self.list_targets
                            .push((r, Action::SetThemeAppearance(mode)));
                    }
                    for (i, (lbl, bmode, active)) in [
                        (
                            "Solid BG",
                            BackgroundMode::Solid,
                            theme_cfg.background_mode == BackgroundMode::Solid,
                        ),
                        (
                            "Gradient BG",
                            BackgroundMode::Gradient,
                            theme_cfg.background_mode == BackgroundMode::Gradient,
                        ),
                    ]
                    .into_iter()
                    .enumerate()
                    {
                        let r = [
                            content_x + half + 10.0 + i as f32 * (quarter + 6.0),
                            cy,
                            quarter,
                            38.0,
                        ];
                        button(
                            ui,
                            &mut self.targets,
                            r,
                            lbl,
                            Action::SetThemeBackgroundMode(bmode),
                            active,
                        );
                        self.list_targets
                            .push((r, Action::SetThemeBackgroundMode(bmode)));
                    }
                }
                cy += 50.0;

                // 3. Preset Themes Grid (8 Presets: 2 rows x 4 columns on wide, or 4 rows x 2 columns on compact)
                ui.text(
                    Face::SemiBold,
                    12.5,
                    content_x,
                    cy,
                    my3_accent(),
                    "PRESET THEMES",
                );
                cy += 22.0;
                let p_cols = if compact { 2 } else { 4 };
                let p_gap = 8.0;
                let p_w = (content_w - p_gap * (p_cols as f32 - 1.0)) / p_cols as f32;
                let p_h = 54.0;
                let p_rows = ThemePreset::ALL.len().div_ceil(p_cols);
                for (idx, preset) in ThemePreset::ALL.into_iter().enumerate() {
                    let col = idx % p_cols;
                    let row = idx / p_cols;
                    let rx = content_x + col as f32 * (p_w + p_gap);
                    let ry = cy + row as f32 * (p_h + p_gap);
                    let r = [rx, ry, p_w, p_h];
                    let active = theme_cfg.preset == preset;
                    self.list_targets
                        .push((r, Action::SelectThemePreset(preset)));
                    if ry + p_h >= list_top && ry <= list_bottom {
                        ui.rounded(
                            rx,
                            ry,
                            p_w,
                            p_h,
                            12.0,
                            if active {
                                my3_nav_active()
                            } else {
                                my3_card_bg()
                            },
                        );
                        ui.stroke_rounded(
                            rx,
                            ry,
                            p_w,
                            p_h,
                            12.0,
                            if active { 1.8 } else { 1.0 },
                            if active {
                                my3_accent()
                            } else {
                                my3_card_border()
                            },
                        );
                        let (p_bg, _, p_acc, _, _, _, _) = preset.palette_rgb();
                        ui.rounded(rx + 10.0, ry + 11.0, 16.0, 16.0, 8.0, color::hex(p_bg, 255));
                        ui.stroke_rounded(
                            rx + 10.0,
                            ry + 11.0,
                            16.0,
                            16.0,
                            8.0,
                            1.2,
                            color::hex(p_acc, 255),
                        );
                        ui.rounded(
                            rx + 20.0,
                            ry + 15.0,
                            12.0,
                            12.0,
                            6.0,
                            color::hex(p_acc, 255),
                        );
                        let fit_lbl = ui.fit_text(Face::SemiBold, 11.8, preset.label(), p_w - 42.0);
                        ui.text(
                            Face::SemiBold,
                            11.8,
                            rx + 36.0,
                            ry + 8.0,
                            my3_text(),
                            &fit_lbl,
                        );
                        let fit_sub =
                            ui.fit_text(Face::Regular, 9.5, preset.subtitle(), p_w - 20.0);
                        ui.text(
                            Face::Regular,
                            9.5,
                            rx + 10.0,
                            ry + 31.0,
                            my3_muted(),
                            &fit_sub,
                        );
                        if let Some(c) = ui.clip_rect(r) {
                            self.targets.push((c, Action::SelectThemePreset(preset)));
                        }
                    }
                }
                cy += p_rows as f32 * (p_h + p_gap) + 8.0;

                // 4. Custom Accent Color Swatches + Hex Input
                ui.text(
                    Face::SemiBold,
                    12.5,
                    content_x,
                    cy,
                    my3_accent(),
                    "CUSTOM ACCENT COLOR",
                );
                cy += 22.0;
                if cy + 52.0 >= list_top && cy <= list_bottom {
                    ui.rounded(content_x, cy, content_w, 50.0, 12.0, my3_card_bg());
                    ui.border(content_x, cy, content_w, 50.0, 12.0, my3_card_border());
                    let sw_size = 30.0;
                    let sw_gap = 8.0;
                    for (i, (rgb, hex_str, _name)) in THEME_ACCENT_SWATCHES.into_iter().enumerate()
                    {
                        let sx = content_x + 12.0 + i as f32 * (sw_size + sw_gap);
                        if sx + sw_size > content_x + content_w - 134.0 {
                            break;
                        }
                        let sr = [sx, cy + 10.0, sw_size, sw_size];
                        let is_sel = theme_cfg.custom_accent_hex.eq_ignore_ascii_case(hex_str);
                        ui.rounded(sr[0], sr[1], sr[2], sr[3], 15.0, color::hex(rgb, 255));
                        if is_sel {
                            ui.stroke_rounded(
                                sr[0] - 2.0,
                                sr[1] - 2.0,
                                sr[2] + 4.0,
                                sr[3] + 4.0,
                                17.0,
                                2.0,
                                my3_text(),
                            );
                        }
                        if let Some(c) = ui.clip_rect(sr) {
                            self.targets.push((c, Action::SelectAccentSwatch(rgb)));
                            self.list_targets.push((c, Action::SelectAccentSwatch(rgb)));
                        }
                    }
                    let hex_btn_r = ui.text_field(
                        content_x + content_w - 126.0,
                        cy + 5.0,
                        114.0,
                        40.0,
                        "ACCENT HEX",
                        &theme_cfg.custom_accent_hex,
                        "#9A7BFF",
                        self.catalog.editing == Some("theme_accent"),
                    );
                    if let Some(c) = ui.clip_rect(hex_btn_r) {
                        self.targets.push((c, Action::Field("theme_accent")));
                    }
                    self.list_targets
                        .push((hex_btn_r, Action::Field("theme_accent")));
                }
                cy += 62.0;

                // 5. Custom Background & Surface Colors
                ui.text(
                    Face::SemiBold,
                    12.5,
                    content_x,
                    cy,
                    my3_accent(),
                    "CUSTOM SOLID BACKGROUND & SURFACE COLORS",
                );
                cy += 22.0;
                if cy + 52.0 >= list_top && cy <= list_bottom {
                    ui.rounded(content_x, cy, content_w, 50.0, 12.0, my3_card_bg());
                    ui.border(content_x, cy, content_w, 50.0, 12.0, my3_card_border());
                    let bg_swatches = if resolved.is_light {
                        THEME_BG_SWATCHES_LIGHT
                    } else {
                        THEME_BG_SWATCHES_DARK
                    };
                    let sw_size = 30.0;
                    let sw_gap = 8.0;
                    for (i, (rgb, hex_str, _name)) in bg_swatches.into_iter().enumerate() {
                        let sx = content_x + 12.0 + i as f32 * (sw_size + sw_gap);
                        if sx + sw_size > content_x + content_w - 238.0 {
                            break;
                        }
                        let sr = [sx, cy + 10.0, sw_size, sw_size];
                        let is_sel = theme_cfg.custom_bg_hex.eq_ignore_ascii_case(hex_str);
                        ui.rounded(sr[0], sr[1], sr[2], sr[3], 15.0, color::hex(rgb, 255));
                        ui.stroke_rounded(
                            sr[0],
                            sr[1],
                            sr[2],
                            sr[3],
                            15.0,
                            if is_sel { 2.0 } else { 1.0 },
                            if is_sel { my3_accent() } else { my3_border() },
                        );
                        if let Some(c) = ui.clip_rect(sr) {
                            self.targets.push((c, Action::SelectBgSwatch(rgb)));
                            self.list_targets.push((c, Action::SelectBgSwatch(rgb)));
                        }
                    }
                    let bg_hex_r = ui.text_field(
                        content_x + content_w - 232.0,
                        cy + 5.0,
                        108.0,
                        40.0,
                        "BG HEX",
                        &theme_cfg.custom_bg_hex,
                        "#13111C",
                        self.catalog.editing == Some("theme_bg"),
                    );
                    if let Some(c) = ui.clip_rect(bg_hex_r) {
                        self.targets.push((c, Action::Field("theme_bg")));
                    }
                    self.list_targets
                        .push((bg_hex_r, Action::Field("theme_bg")));

                    let surf_hex_r = ui.text_field(
                        content_x + content_w - 118.0,
                        cy + 5.0,
                        108.0,
                        40.0,
                        "CARD HEX",
                        &theme_cfg.custom_surface_hex,
                        "#1D192B",
                        self.catalog.editing == Some("theme_surface"),
                    );
                    if let Some(c) = ui.clip_rect(surf_hex_r) {
                        self.targets.push((c, Action::Field("theme_surface")));
                    }
                    self.list_targets
                        .push((surf_hex_r, Action::Field("theme_surface")));
                }
                cy += 62.0;

                // 6. Custom Gradient Direction & Color Stops
                ui.text(
                    Face::SemiBold,
                    12.5,
                    content_x,
                    cy,
                    my3_accent(),
                    "CUSTOM GRADIENT DIRECTION & COLOR STOPS",
                );
                cy += 22.0;
                if cy + 44.0 >= list_top && cy <= list_bottom {
                    let dir_w = (content_w - 18.0) * 0.25;
                    for (i, dir) in GradientDirection::ALL.into_iter().enumerate() {
                        let r = [content_x + i as f32 * (dir_w + 6.0), cy, dir_w, 36.0];
                        button(
                            ui,
                            &mut self.targets,
                            r,
                            dir.label(),
                            Action::SetGradientDirection(dir),
                            theme_cfg.gradient_direction == dir,
                        );
                        self.list_targets
                            .push((r, Action::SetGradientDirection(dir)));
                    }
                }
                cy += 44.0;

                if cy + 54.0 >= list_top && cy <= list_bottom {
                    ui.rounded(content_x, cy, content_w, 50.0, 12.0, my3_card_bg());
                    ui.border(content_x, cy, content_w, 50.0, 12.0, my3_card_border());
                    let stop_w = ((content_w - 36.0) / 3.0).max(80.0);
                    for (i, (lbl, hex_val, field_name)) in [
                        (
                            "Stop 1",
                            theme_cfg.gradient_start_hex.as_str(),
                            "theme_grad_start",
                        ),
                        (
                            if theme_cfg.use_three_stops {
                                "Mid Stop"
                            } else {
                                "Mid (Off)"
                            },
                            theme_cfg.gradient_mid_hex.as_str(),
                            "theme_grad_mid",
                        ),
                        (
                            "End Stop",
                            theme_cfg.gradient_end_hex.as_str(),
                            "theme_grad_end",
                        ),
                    ]
                    .into_iter()
                    .enumerate()
                    {
                        let r = ui.text_field(
                            content_x + 8.0 + i as f32 * (stop_w + 8.0),
                            cy + 5.0,
                            stop_w,
                            40.0,
                            lbl,
                            hex_val,
                            "#1A1333",
                            self.catalog.editing == Some(field_name),
                        );
                        if let Some(c) = ui.clip_rect(r) {
                            self.targets.push((c, Action::Field(field_name)));
                        }
                        self.list_targets.push((r, Action::Field(field_name)));
                    }
                }
                cy += 58.0;

                if cy + 44.0 >= list_top && cy <= list_bottom {
                    let half = (content_w - 8.0) * 0.5;
                    let three_r = [content_x, cy, half, 38.0];
                    button(
                        ui,
                        &mut self.targets,
                        three_r,
                        if theme_cfg.use_three_stops {
                            "3-Stop Gradient: Enabled"
                        } else {
                            "2-Stop Gradient (Tap for 3)"
                        },
                        Action::ToggleGradientThreeStops,
                        theme_cfg.use_three_stops,
                    );
                    self.list_targets
                        .push((three_r, Action::ToggleGradientThreeStops));

                    let mid_lbl = format!(
                        "Mid Stop Position: {:.0}%",
                        theme_cfg.gradient_mid_pos * 100.0
                    );
                    let mid_r = [content_x + half + 8.0, cy, half, 38.0];
                    button(
                        ui,
                        &mut self.targets,
                        mid_r,
                        &mid_lbl,
                        Action::StepGradientMidPos(1),
                        false,
                    );
                    self.list_targets
                        .push((mid_r, Action::StepGradientMidPos(1)));
                }

                ui.set_clip(None);
            }
            SettingsCategory::Controls => {
                let studio_offset = 196.0;
                let count = 26;
                let max_scroll =
                    (studio_offset + count as f32 * step_y - (list_bottom - list_top)).max(0.0);
                self.catalog.scroll = self.catalog.scroll.clamp(0.0, max_scroll);
                self.list_view = Some([list_top, list_bottom, max_scroll]);

                let style = touch_cfg.control_style;
                let is_mcpe = style == crate::touch_compat::ControlStyle::Mcpe;
                let is_goy = style == crate::touch_compat::ControlStyle::GoyControl;
                let sys_name = style.label();
                let sys_presets = crate::touch_compat::Settings::list_presets(&game_home, style);
                let preset_label = format!(
                    "{} ({} presets) v",
                    touch_cfg.preset_name,
                    sys_presets.len()
                );
                let preset_sub = format!(
                    "{}  •  controls/{}/{}.json{}",
                    touch_cfg.preset_description,
                    style.storage_subdir(),
                    touch_cfg.preset_id,
                    if touch_cfg.is_default_preset() {
                        " (Protected Default)"
                    } else {
                        ""
                    }
                );
                let editor_title = format!("{} Control Editor", sys_name);
                let editor_sub = match style {
                    crate::touch_compat::ControlStyle::Mcpe => {
                        "Customize MCPE controls with exact Minecraft sprites, size, opacity & alignment"
                    }
                    crate::touch_compat::ControlStyle::GoyControl => {
                        "Customize GoyControl buttons, custom keys, toggle, swipe & pass-through"
                    }
                };
                let preset_row_title = format!("{} Preset Selector", sys_name);
                let copy_title = format!("Copy {} Preset ('{}')", sys_name, touch_cfg.preset_name);
                let copy_btn_label = if self.catalog.editing == Some("profile_name")
                    && self.catalog.config.preset_edit_mode == "copy"
                {
                    "Typing Name..."
                } else if self.catalog.editing == Some("profile_desc")
                    && self.catalog.config.preset_edit_mode == "copy"
                {
                    "Typing Desc..."
                } else {
                    "Copy Preset"
                };
                let edit_meta_title = format!("Edit {} Preset Name & Description", sys_name);
                let edit_meta_sub = if touch_cfg.is_default_preset() {
                    "Default layout is protected and cannot be renamed or overwritten; use Copy Preset"
                } else {
                    "Pencil / Edit: rename or update description of the selected user preset"
                };
                let edit_meta_btn = if touch_cfg.is_default_preset() {
                    "Protected"
                } else if self.catalog.editing == Some("profile_name")
                    && self.catalog.config.preset_edit_mode == "edit"
                {
                    "Typing Name..."
                } else if self.catalog.editing == Some("profile_desc")
                    && self.catalog.config.preset_edit_mode == "edit"
                {
                    "Typing Desc..."
                } else {
                    "Edit (Pencil)"
                };

                let mouse_sens = format!("{:.0}%", opts.sensitivity * 200.0);
                let touch_sens = format!("{:.0}%", touch_cfg.sensitivity * 100.0);
                let btn_scale = format!("{:.0}%", touch_cfg.button_scale * 100.0);
                let ptr_size = format!("{:.0}%", touch_cfg.virtual_mouse_pointer_scale * 100.0);
                let gyro_sens = format!("{:.0}%", touch_cfg.gyro_sensitivity * 100.0);
                let touch_sens_frac = ((touch_cfg.sensitivity - 0.20) / 2.30).clamp(0.0, 1.0);
                let btn_scale_frac = ((touch_cfg.button_scale - 0.50) / 1.50).clamp(0.0, 1.0);
                let ptr_size_frac =
                    ((touch_cfg.virtual_mouse_pointer_scale - 0.50) / 1.50).clamp(0.0, 1.0);
                let gyro_sens_frac = ((touch_cfg.gyro_sensitivity - 0.20) / 2.80).clamp(0.0, 1.0);
                let mouse_sens_frac = opts.sensitivity.clamp(0.0, 1.0);
                let scheme_str = touch_cfg.mode.label();
                let zone_str = touch_cfg.joystick_style.move_area.label();
                let invert_y_active = opts.invert_y || touch_cfg.invert_y;
                let sprint_latch_active = opts.toggle_sprint || touch_cfg.sprint_toggle;

                let rows: [(&str, &str, SettingControl<'_>); 26] = [
                    (
                        "LAYOUT STUDIO, IMPORT & EXPORT PRESETS",
                        "Material 3 HUD editor, automatic MCPE/GoyControl file importer & location picker export",
                        SettingControl::SectionHeader(sys_name, Icon::MenuEquals),
                    ),
                    (
                        &editor_title,
                        editor_sub,
                        SettingControl::Button(
                            "Customize Controls",
                            Action::OpenControlEditor,
                            true,
                        ),
                    ),
                    (
                        &preset_row_title,
                        &preset_sub,
                        SettingControl::Button(&preset_label, Action::ControlProfileChooser, false),
                    ),
                    (
                        "Import Controls from File Manager",
                        "Pick a control .json file; auto-detects MCPE or GoyControl and saves to its folder",
                        SettingControl::Button(
                            "Import Controls",
                            Action::ImportControlProfile,
                            true,
                        ),
                    ),
                    (
                        "Export Active Control Preset",
                        "Open system File Manager to choose where to save your exported control .json",
                        SettingControl::Button(
                            "Export Controls",
                            Action::ExportControlProfile,
                            false,
                        ),
                    ),
                    (
                        &copy_title,
                        "Duplicate selected layout into a new independent preset (asks to replace if same name)",
                        if self.catalog.editing == Some("profile_name") {
                            SettingControl::TextField {
                                label: "PRESET NAME",
                                value: &self.catalog.config.custom_profile_name,
                                hint: "Enter preset name",
                                active: true,
                                action: Action::Field("profile_name"),
                            }
                        } else {
                            SettingControl::Button(copy_btn_label, Action::CopyActivePreset, false)
                        },
                    ),
                    (
                        &edit_meta_title,
                        edit_meta_sub,
                        if self.catalog.editing == Some("profile_desc") {
                            SettingControl::TextField {
                                label: "DESCRIPTION",
                                value: &self.catalog.config.custom_profile_desc,
                                hint: "Enter preset description",
                                active: true,
                                action: Action::Field("profile_desc"),
                            }
                        } else {
                            SettingControl::Button(
                                edit_meta_btn,
                                Action::EditActivePresetMeta,
                                false,
                            )
                        },
                    ),
                    (
                        "CAMERA LOOK & AIMING SENSITIVITY",
                        "Touch swipe responsiveness, 3D look multiplier, Y-axis inversion & crosshair mode",
                        SettingControl::SectionHeader("CAMERA", Icon::Shaders),
                    ),
                    (
                        "Sensitivity (Touch Look Speed)",
                        "Direct finger drag camera rotation speed multiplier",
                        SettingControl::Slider {
                            value_label: &touch_sens,
                            fraction: touch_sens_frac,
                            minus_action: Action::TouchSensDelta(-0.10),
                            plus_action: Action::TouchSensDelta(0.10),
                        },
                    ),
                    (
                        "3D Camera Base Sensitivity",
                        "Base in-game 3D look sensitivity stored in options.txt",
                        SettingControl::Slider {
                            value_label: &mouse_sens,
                            fraction: mouse_sens_frac,
                            minus_action: Action::MouseSensDelta(-0.05),
                            plus_action: Action::MouseSensDelta(0.05),
                        },
                    ),
                    (
                        "Invert Y-Axis",
                        "Inverts vertical camera look direction for touch and mouse",
                        SettingControl::Toggle(invert_y_active, Action::ToggleInvertY),
                    ),
                    (
                        "Split Touch (Aim & Look)",
                        "Separates aiming and camera look with center crosshair",
                        SettingControl::Toggle(touch_cfg.crosshair(), Action::ToggleSplitControls),
                    ),
                    (
                        "MOVEMENT, HUD SCALE & ERGONOMICS",
                        "On-screen button scale, thumbstick vs D-Pad, active touch zone & haptics",
                        SettingControl::SectionHeader("ERGONOMICS", Icon::Settings),
                    ),
                    (
                        "HUD Button Scale",
                        "Global size multiplier for on-screen touch buttons",
                        SettingControl::Slider {
                            value_label: &btn_scale,
                            fraction: btn_scale_frac,
                            minus_action: Action::ButtonScaleDelta(-0.10),
                            plus_action: Action::ButtonScaleDelta(0.10),
                        },
                    ),
                    (
                        "Movement Control Scheme",
                        "Switch between classic D-Pad buttons and analog thumb joystick",
                        SettingControl::Button(scheme_str, Action::ToggleControlScheme, false),
                    ),
                    (
                        "Joystick Active Touch Zone",
                        "Choose Follow Finger, Mid-Bottom Left, Right Side, or Fixed origin",
                        SettingControl::Button(zone_str, Action::CycleJoystickZone, false),
                    ),
                    (
                        "Left-Handed HUD Mirroring",
                        "Mirrors movement controls to the right side of the screen",
                        SettingControl::Toggle(touch_cfg.left_handed, Action::ToggleLeftHanded),
                    ),
                    (
                        "Toggle Sprint Mode",
                        "Latch sprint on a single tap instead of holding the sprint button",
                        SettingControl::Toggle(sprint_latch_active, Action::ToggleSprintLatch),
                    ),
                    (
                        "Inventory Walk Controls",
                        "Keep movement controls active while inventory or chest screens are open",
                        SettingControl::Toggle(
                            touch_cfg.inventory_controls,
                            Action::ToggleInventoryControls,
                        ),
                    ),
                    (
                        "Auto-Jump",
                        "Automatically jump when walking up 1-block terrain steps",
                        SettingControl::Toggle(opts.auto_jump, Action::ToggleAutoJump),
                    ),
                    (
                        "Haptic Touch Vibration",
                        "Tactile vibration feedback when pressing HUD buttons and breaking blocks",
                        SettingControl::Toggle(touch_cfg.vibrate, Action::ToggleTouchVibrate),
                    ),
                    (
                        "VIRTUAL MOUSE & GYROSCOPE MOTION",
                        "Windows 11 menu cursor scaling and motion-assisted gyroscope aiming",
                        SettingControl::SectionHeader("POINTER & GYRO", Icon::Play),
                    ),
                    (
                        "Authoritative Virtual Mouse",
                        "Enable virtual mouse pointer in menus and on-demand in GoyControl",
                        SettingControl::Toggle(
                            touch_cfg.virtual_mouse_enabled,
                            Action::ToggleVirtualMouseEnabled,
                        ),
                    ),
                    (
                        "Virtual Cursor Scale",
                        "Adjust size of the Windows 11 virtual mouse arrow in menus",
                        SettingControl::Slider {
                            value_label: &ptr_size,
                            fraction: ptr_size_frac,
                            minus_action: Action::PointerSizeDelta(-0.10),
                            plus_action: Action::PointerSizeDelta(0.10),
                        },
                    ),
                    (
                        "Gyroscope Aiming",
                        "Tilt your phone for fine bow and crosshair aiming adjustments",
                        SettingControl::Toggle(touch_cfg.gyroscope, Action::ToggleGyro),
                    ),
                    (
                        "Gyroscope Sensitivity",
                        "Fine-tune motion tilt speed when Gyroscope Aiming is enabled",
                        SettingControl::Slider {
                            value_label: &gyro_sens,
                            fraction: gyro_sens_frac,
                            minus_action: Action::GyroSensDelta(-0.10),
                            plus_action: Action::GyroSensDelta(0.10),
                        },
                    ),
                ];

                ui.set_clip(Some([
                    content_x - 2.0,
                    list_top,
                    content_w + 4.0,
                    (list_bottom - list_top).max(1.0),
                ]));

                // M3 Expressive Control Engine Hero Selector Cards
                let picker_y = list_top - self.catalog.scroll;
                if picker_y + 66.0 >= list_top && picker_y <= list_bottom {
                    let half_w = (content_w - 10.0) * 0.5;
                    for (i, (title, sub, badge, selected, action)) in [
                        (
                            "MCPE (Default)",
                            "Vanilla touch sprites, D-Pad & thumbstick",
                            "CLASSIC HUD",
                            is_mcpe,
                            Action::SelectControlMode(crate::touch_compat::ControlStyle::Mcpe),
                        ),
                        (
                            "GoyControl",
                            "Custom multi-bind HUD, macros & pass-through",
                            "PRO OVERLAY",
                            is_goy,
                            Action::SelectControlMode(
                                crate::touch_compat::ControlStyle::GoyControl,
                            ),
                        ),
                    ]
                    .into_iter()
                    .enumerate()
                    {
                        let rx = content_x + i as f32 * (half_w + 10.0);
                        let rect = [rx, picker_y, half_w, 66.0];
                        if let Some(c) = ui.clip_rect(rect) {
                            self.targets.push((c, action));
                        }
                        self.list_targets.push((rect, action));
                        ui.rounded(
                            rx,
                            picker_y,
                            half_w,
                            66.0,
                            18.0,
                            if selected {
                                my3_surface()
                            } else {
                                my3_card_bg()
                            },
                        );
                        ui.border(
                            rx,
                            picker_y,
                            half_w,
                            66.0,
                            18.0,
                            if selected {
                                my3_accent()
                            } else {
                                my3_card_border()
                            },
                        );
                        if selected {
                            ui.rounded(rx + 4.0, picker_y + 12.0, 4.0, 42.0, 2.0, my3_accent());
                        }
                        let rad_x = rx + 16.0;
                        let rad_y = picker_y + 23.0;
                        ui.stroke_rounded(
                            rad_x,
                            rad_y,
                            20.0,
                            20.0,
                            10.0,
                            1.8,
                            if selected { my3_accent() } else { my3_muted() },
                        );
                        if selected {
                            ui.rounded(rad_x + 5.0, rad_y + 5.0, 10.0, 10.0, 5.0, my3_accent());
                        }
                        let chip_w = 78.0;
                        let chip_x = rx + half_w - chip_w - 10.0;
                        ui.rounded(
                            chip_x,
                            picker_y + 10.0,
                            chip_w,
                            20.0,
                            10.0,
                            if selected {
                                my3_accent_soft()
                            } else {
                                my3_surface_high()
                            },
                        );
                        ui.centered_fit(
                            Face::SemiBold,
                            8.5,
                            [chip_x + 4.0, picker_y + 10.0, chip_w - 8.0, 20.0],
                            if selected { my3_accent() } else { my3_muted() },
                            if selected { "ACTIVE" } else { badge },
                        );
                        let text_x = rx + 44.0;
                        let max_w = (half_w - 134.0).max(56.0);
                        let fitted = ui.fit_text(Face::SemiBold, 14.5, title, max_w);
                        ui.text(
                            Face::SemiBold,
                            14.5,
                            text_x,
                            picker_y + 11.0,
                            my3_text(),
                            &fitted,
                        );
                        let sub_max_w = (half_w - 54.0).max(60.0);
                        let fitted_sub = ui.fit_text(Face::Regular, 11.5, sub, sub_max_w);
                        ui.text(
                            Face::Regular,
                            11.5,
                            text_x,
                            picker_y + 36.0,
                            my3_muted(),
                            &fitted_sub,
                        );
                    }
                }

                // Material Design 3 Control Studio Workbench Card (Two-tier: Full-width Header + 3-Button Action Bar)
                let wb_y = list_top + 74.0 - self.catalog.scroll;
                let wb_h = 114.0;
                if wb_y + wb_h >= list_top && wb_y <= list_bottom {
                    ui.rounded(content_x, wb_y, content_w, wb_h, 20.0, my3_surface());
                    ui.border(content_x, wb_y, content_w, wb_h, 20.0, my3_accent());
                    ui.rounded(
                        content_x + 14.0,
                        wb_y + 12.0,
                        42.0,
                        42.0,
                        13.0,
                        my3_accent_soft(),
                    );
                    ui.icon(
                        Icon::Gamepad,
                        content_x + 25.0,
                        wb_y + 23.0,
                        20.0,
                        my3_accent(),
                    );
                    let studio_hdr = format!(
                        "{} Studio  •  Active Preset: {}",
                        sys_name, touch_cfg.preset_name
                    );
                    let hdr_max = (content_w - 82.0).max(120.0);
                    let fitted_hdr = ui.fit_text(Face::SemiBold, 14.0, &studio_hdr, hdr_max);
                    ui.text(
                        Face::SemiBold,
                        14.0,
                        content_x + 66.0,
                        wb_y + 10.0,
                        my3_text(),
                        &fitted_hdr,
                    );
                    let path_tag = format!(
                        "Storage: {}/{}.json  •  {} buttons",
                        style.storage_subdir(),
                        touch_cfg.preset_id,
                        if is_goy {
                            touch_cfg.custom_buttons.len()
                        } else {
                            14 + touch_cfg.custom_buttons.len()
                        }
                    );
                    let fitted_path = ui.fit_text(Face::Regular, 11.2, &path_tag, hdr_max);
                    ui.text(
                        Face::Regular,
                        11.2,
                        content_x + 66.0,
                        wb_y + 30.0,
                        my3_muted(),
                        &fitted_path,
                    );
                    let fitted_desc =
                        ui.fit_text(Face::Regular, 10.8, &touch_cfg.preset_description, hdr_max);
                    ui.text(
                        Face::Regular,
                        10.8,
                        content_x + 66.0,
                        wb_y + 47.0,
                        my3_accent(),
                        &fitted_desc,
                    );

                    // Studio Quick Action Buttons along the bottom row of the Workbench Card
                    let btn_gap = 8.0;
                    let btn_w_sm = ((content_w - 28.0 - 2.0 * btn_gap) / 3.0).max(76.0);
                    let btn_y_wb = wb_y + 68.0;
                    let r_editor = [content_x + 14.0, btn_y_wb, btn_w_sm, 36.0];
                    let r_import = [r_editor[0] + btn_w_sm + btn_gap, btn_y_wb, btn_w_sm, 36.0];
                    let r_export = [r_import[0] + btn_w_sm + btn_gap, btn_y_wb, btn_w_sm, 36.0];
                    for (rect, lbl, act, primary) in [
                        (r_editor, "Open Editor", Action::OpenControlEditor, true),
                        (r_import, "Import JSON", Action::ImportControlProfile, false),
                        (r_export, "Export Save", Action::ExportControlProfile, false),
                    ] {
                        button(ui, &mut self.targets, rect, lbl, act, primary);
                        self.list_targets.push((rect, act));
                    }
                }

                for (i, (title, sub, ctrl)) in rows.into_iter().enumerate() {
                    let y = list_top + studio_offset + i as f32 * step_y - self.catalog.scroll;
                    if y + card_h < list_top || y > list_bottom {
                        continue;
                    }
                    draw_setting_card(
                        ui,
                        &mut self.targets,
                        &mut self.list_targets,
                        &mut self.anim,
                        content_x,
                        y,
                        content_w,
                        card_h,
                        title,
                        sub,
                        ctrl,
                    );
                }
                ui.set_clip(None);
            }
            SettingsCategory::Experimental => {
                let c = &self.catalog.config;
                let count = 9;
                let max_scroll = (count as f32 * step_y - (list_bottom - list_top)).max(0.0);
                self.catalog.scroll = self.catalog.scroll.clamp(0.0, max_scroll);
                self.list_view = Some([list_top, list_bottom, max_scroll]);

                let active_mode_badge = if c.super_perf_mode {
                    "Super Perf Active"
                } else if c.battery_perf_mode {
                    "Battery Saver Active"
                } else if c.max_fps_mode {
                    "260 FPS Uncapped"
                } else {
                    "Balanced Default"
                };

                let rows: [(&str, &str, SettingControl<'_>); 9] = [
                    (
                        "PERFORMANCE PROFILES & GPU PIPELINE",
                        "Hardware-tuned presets for Mali-G52 / Adreno GPUs and high-refresh displays",
                        SettingControl::SectionHeader(active_mode_badge, Icon::Flask),
                    ),
                    (
                        "Super Performance Mode",
                        "Aggressive chunk culling, fast clouds, minimal particles & Vulkan batching",
                        SettingControl::Toggle(c.super_perf_mode, Action::ToggleSuperPerf),
                    ),
                    (
                        "Battery Performance Mode",
                        "Caps framerate to 60 FPS with VSync and reduces background worker wakeups",
                        SettingControl::Toggle(c.battery_perf_mode, Action::ToggleBatteryPerf),
                    ),
                    (
                        "Max FPS Mode (Uncapped 260 FPS)",
                        "Disables VSync, unlocks framerate ceiling and requests maximum display Hz",
                        SettingControl::Toggle(c.max_fps_mode, Action::ToggleMaxFpsMode),
                    ),
                    (
                        "LOW-LATENCY INPUT, STREAMING & SCHEDULER",
                        "Direct touch dispatch, 4 MiB chunk upload budget boost & Android ADPF thermal hints",
                        SettingControl::SectionHeader("Engine Tuning", Icon::Gear),
                    ),
                    (
                        "Zero-Latency Touch Pipeline",
                        "Bypasses redundant pointer filtering for instant camera & virtual mouse response",
                        SettingControl::Toggle(
                            c.zero_latency_touch,
                            Action::ToggleZeroLatencyTouch,
                        ),
                    ),
                    (
                        "Fast Chunk Mesh Upload Boost",
                        "Increases per-frame GPU upload budget to 4 MiB to eliminate movement stutter",
                        SettingControl::Toggle(c.fast_chunk_upload, Action::ToggleFastChunkUpload),
                    ),
                    (
                        "Android ADPF Performance Hints",
                        "Sends real-time CPU thermal & frame-work hints to the Android scheduler",
                        SettingControl::Toggle(
                            c.device.performance_hints,
                            Action::PerformanceHints,
                        ),
                    ),
                    (
                        "Detailed GPU Pass Profiling",
                        "Records per-pass GPU timings in diagnostic reports (adds minor overhead)",
                        SettingControl::Toggle(c.device.gpu_profile, Action::GpuProfile),
                    ),
                ];

                ui.set_clip(Some([
                    content_x - 2.0,
                    list_top,
                    content_w + 4.0,
                    (list_bottom - list_top).max(1.0),
                ]));
                for (i, (title, sub, ctrl)) in rows.into_iter().enumerate() {
                    let y = list_top + i as f32 * step_y - self.catalog.scroll;
                    if y + card_h < list_top || y > list_bottom {
                        continue;
                    }
                    draw_setting_card(
                        ui,
                        &mut self.targets,
                        &mut self.list_targets,
                        &mut self.anim,
                        content_x,
                        y,
                        content_w,
                        card_h,
                        title,
                        sub,
                        ctrl,
                    );
                }
                ui.set_clip(None);
            }
            SettingsCategory::Diagnostics => {
                let c = &self.catalog.config;
                let count = 7;
                let max_scroll = (count as f32 * step_y - (list_bottom - list_top)).max(0.0);
                self.catalog.scroll = self.catalog.scroll.clamp(0.0, max_scroll);
                self.list_view = Some([list_top, list_bottom, max_scroll]);

                let has_err = self.home.join("android-error.txt").is_file();
                let health_badge = if has_err {
                    "Crash Log Recorded"
                } else {
                    "Runtime Healthy"
                };

                let rows: [(&str, &str, SettingControl<'_>); 7] = [
                    (
                        "RUNTIME HEALTH & CRASH DIAGNOSTICS",
                        "Live engine summary, shader/Vulkan health check, crash logs & ZIP bundle export",
                        SettingControl::SectionHeader(health_badge, Icon::Terminal),
                    ),
                    (
                        "Inspect logs & device summary",
                        "Open interactive Diagnostics & Runtime Health Summary dialog",
                        SettingControl::Button("View Summary", Action::Logs, true),
                    ),
                    (
                        "Export diagnostic report (.zip)",
                        "Packages logs and device info into a local ZIP for troubleshooting",
                        SettingControl::Button("Export ZIP", Action::ExportDiagnostics, false),
                    ),
                    (
                        "FRAME TELEMETRY & WORLD STORAGE",
                        "JSONL frame-time recorder and direct access to profile world saves",
                        SettingControl::SectionHeader("Telemetry & Saves", Icon::Folder),
                    ),
                    (
                        "Record gameplay performance",
                        "Captures frame-time and chunk-build telemetry during Play (up to 30 min)",
                        SettingControl::Toggle(c.record_performance, Action::RecordPerformance),
                    ),
                    (
                        "Export raw performance data (.jsonl)",
                        "Share or inspect recorded frame telemetry in Android Files",
                        SettingControl::Button("Export JSONL", Action::ExportPerformance, false),
                    ),
                    (
                        "World backup & file manager",
                        "Open worlds folder in system Files to back up or copy saves",
                        SettingControl::Button("World Files", Action::WorldFiles, false),
                    ),
                ];

                ui.set_clip(Some([
                    content_x - 2.0,
                    list_top,
                    content_w + 4.0,
                    (list_bottom - list_top).max(1.0),
                ]));
                for (i, (title, sub, ctrl)) in rows.into_iter().enumerate() {
                    let y = list_top + i as f32 * step_y - self.catalog.scroll;
                    if y + card_h < list_top || y > list_bottom {
                        continue;
                    }
                    draw_setting_card(
                        ui,
                        &mut self.targets,
                        &mut self.list_targets,
                        &mut self.anim,
                        content_x,
                        y,
                        content_w,
                        card_h,
                        title,
                        sub,
                        ctrl,
                    );
                }
                ui.set_clip(None);
            }
            // Not reachable from the category grid yet.
            SettingsCategory::Overview | SettingsCategory::Account => {}
        }
    }
}
