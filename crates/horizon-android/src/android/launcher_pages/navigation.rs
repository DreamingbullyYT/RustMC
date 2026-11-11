impl Launcher {
    /// The same spatial focus algorithm as the game's controller menus.
    pub(super) fn navigate(&mut self, key: &KeyEvent) -> bool {
        self.navigate_key(&key.logical_key)
    }
    pub(super) fn navigate_key(&mut self, key: &winit::keyboard::Key) -> bool {
        use horizon_client::gamepad::{self, Button};
        use winit::keyboard::{Key, NamedKey};
        if self.pending_focus.is_some() {
            return true;
        }
        if self.catalog.editing.is_some() || self.targets.is_empty() {
            return false;
        }
        let button = match key {
            Key::Named(NamedKey::ArrowUp) => Button::DPadUp,
            Key::Named(NamedKey::ArrowDown) => Button::DPadDown,
            Key::Named(NamedKey::ArrowLeft) => Button::DPadLeft,
            Key::Named(NamedKey::ArrowRight) => Button::DPadRight,
            Key::Named(NamedKey::Tab) => {
                self.focus = Some(self.focus.map_or(0, |i| (i + 1) % self.targets.len()));
                return true;
            }
            _ => return false,
        };
        if matches!(button, Button::DPadUp | Button::DPadDown)
            && let Some((_, action)) = self.focus.and_then(|i| self.targets.get(i))
            && let Some(index) = self.list_targets.iter().position(|(_, a)| a == action)
        {
            let rects: Vec<_> = self.list_targets.iter().map(|(r, _)| *r).collect();
            if let Some(next) = gamepad::focus_step_strict(rects[index], button, &rects)
                && let Some(view) = self.list_view
            {
                let (rect, action) = self.list_targets[next];
                let scroll = if let Some(chooser) = &mut self.chooser {
                    &mut chooser.scroll
                } else if let Some(modal) = &mut self.catalog.project_modal {
                    &mut modal.scroll
                } else {
                    &mut self.catalog.scroll
                };
                *scroll = super::gesture::reveal(*scroll, rect, view);
                self.pending_focus = Some(action);
                self.focus = None;
                if let Some(window) = &self.window {
                    window.request_redraw();
                }
                return true;
            }
        }
        self.focus = Some(
            if let Some(index) = self.focus.filter(|i| *i < self.targets.len()) {
                let rects: Vec<_> = self.targets.iter().map(|(rect, _)| *rect).collect();
                gamepad::focus_step_rect(rects[index], button, &rects).unwrap_or(index)
            } else {
                0
            },
        );
        true
    }

    pub(super) fn draw_pages(&mut self) -> Result<()> {
        // Page drawing borrows `self` mutably (settings and the project modal do), so lend the
        // renderer and its resources out as plain locals while the frame is built, and put them
        // back whatever the result is.
        let Some(mut r) = self.renderer.take() else {
            return Ok(());
        };
        let Some(gui) = self.gui.take() else {
            self.renderer = Some(r);
            return Ok(());
        };
        let result = self.draw_pages_with(&mut r, &gui);
        if result.is_ok() {
            crate::android::first_frame_shown();
        }
        self.renderer = Some(r);
        self.gui = Some(gui);
        result
    }

    /// Draws one launcher frame with the renderer lent out (see `draw_pages`).
    fn draw_pages_with(&mut self, r: &mut Renderer, gui: &game::UiResources) -> Result<()> {
        if r.suspended {
            return Ok(());
        }
        self.targets.clear();
        SLIDER_TRACK_TARGETS.with(|t| t.borrow_mut().clear());
        self.list_targets.clear();
        self.list_view = None;

        let mut b = HudBatch::new([r.config.width, r.config.height]);
        let mut ui = Ui::mobile(&gui.atlas, &mut b, (0.0, 0.0));
        // Keep ui.w and ui.h synchronized with Launcher::ui_density(). When the soft keyboard is
        // active, compute ime_top_dp and bias scroll offsets / modal Y positions instead of shrinking ui.h.
        let ime_top_dp = if self.catalog.editing.is_some() {
            crate::native_ime::viewport_bottom()
                .map(|bottom_px| (bottom_px as f32 / ui.density).clamp(110.0, ui.h))
        } else {
            None
        };
        let (w, h) = (ui.w, ui.h);
        let active_th = crate::ui_theme::active_theme();
        ui.draw_theme_background(0.0, 0.0, w, h, &active_th);

        // ---- Top Header Bar (brand lockup ... account pill) ----
        ui.horizon_logo(18.0, 12.0, 28.0);
        let account_name = self
            .login
            .profile
            .clone()
            .unwrap_or_else(|| self.catalog.config.name.clone());
        let account_text_w = ui.measure(Face::Medium, 13.5, &account_name).min(136.0);
        let pill_w = (account_text_w + 64.0).clamp(114.0, 204.0);
        let pill_x = (w - 18.0 - pill_w).max(190.0);
        let pill_y = 11.0;
        let pill_h = 36.0;

        let eq_btn_rect = [pill_x - 44.0, pill_y, 36.0, pill_h];
        ui.rounded(
            eq_btn_rect[0],
            eq_btn_rect[1],
            eq_btn_rect[2],
            eq_btn_rect[3],
            8.0,
            my3_surface(),
        );
        ui.border(
            eq_btn_rect[0],
            eq_btn_rect[1],
            eq_btn_rect[2],
            eq_btn_rect[3],
            8.0,
            my3_border(),
        );
        ui.icon(
            Icon::MenuEquals,
            eq_btn_rect[0] + 9.0,
            eq_btn_rect[1] + 9.0,
            18.0,
            my3_accent(),
        );
        self.targets.push((eq_btn_rect, Action::OpenControlEditor));

        let brand_w = (eq_btn_rect[0] - 66.0).max(70.0);
        ui.text(Face::SemiBold, 16.0, 54.0, 10.0, my3_text(), "Horizon");
        let version_line = format!("Android {}", crate::backend::VERSION);
        let fitted_version = ui.fit_text(Face::Regular, 11.0, &version_line, brand_w);
        ui.text(
            Face::Regular,
            11.0,
            54.0,
            30.0,
            my3_muted(),
            &fitted_version,
        );

        // Top-right Account Selector with avatar head, username, and chevron.
        ui.rounded(pill_x, pill_y, pill_w, pill_h, 9.0, my3_surface());
        ui.border(pill_x, pill_y, pill_w, pill_h, 9.0, my3_border());
        ui.avatar_head_2d(pill_x + 6.0, pill_y + 4.0, 28.0, &account_name);
        if self.login.signed_in {
            ui.rounded(pill_x + 26.0, pill_y + 24.0, 9.0, 9.0, 4.5, my3_bg());
            ui.rounded(
                pill_x + 27.0,
                pill_y + 25.0,
                7.0,
                7.0,
                3.5,
                color::hex(0x59D98E, 255),
            );
        }
        let fitted_acct = ui.fit_text(Face::Medium, 13.5, &account_name, pill_w - 62.0);
        ui.text(
            Face::Medium,
            13.5,
            pill_x + 37.0,
            pill_y + 8.0,
            my3_text(),
            &fitted_acct,
        );
        ui.icon(
            Icon::ChevronDown,
            pill_x + pill_w - 22.0,
            pill_y + 12.0,
            12.0,
            my3_muted(),
        );
        self.targets
            .push(([pill_x, pill_y, pill_w, pill_h], Action::Account));

        // ---- Left Jetpack Compose Material You NavigationRail + Stadium Active Indicator ----
        let sidebar_w = if w < 560.0 { 136.0 } else { 184.0 };
        let rail_h = (h - 64.0).max(40.0);
        ui.m3_tonal_card(6.0, 54.0, sidebar_w - 8.0, rail_h, 18.0, false);
        let nav_start_y = 60.0;
        let nav_step = ((h - nav_start_y - 12.0) / Page::ALL.len() as f32).clamp(31.0, 43.0);
        let nav_h = (nav_step - 4.0).clamp(27.0, 38.0);
        let selected_idx = Page::ALL
            .iter()
            .position(|p| *p == self.catalog.page)
            .unwrap_or(0);
        let target_nav_y = nav_start_y + selected_idx as f32 * nav_step;
        let has_any_modal = self.catalog.project_modal.is_some()
            || self.catalog.fm_modal.is_some()
            || self.chooser.is_some()
            || self.login.visible
            || self.exit_confirmation;
        self.anim.step(
            self.catalog.page,
            self.catalog.settings_category,
            target_nav_y,
            has_any_modal,
            self.catalog.sections,
        );

        // Jetpack Compose NavigationRailItem stadium active indicator (secondaryContainer fill, no harsh wireframe border)
        let vel_stretch = (self.anim.nav_pill_vel.abs() * 0.012).clamp(0.0, 4.5);
        let pill_draw_y = self.anim.nav_pill_y - vel_stretch * 0.5;
        let pill_draw_h = nav_h + vel_stretch;
        let pill_rad = crate::ui_theme::motion::morph_radius(
            pill_draw_h * 0.5,
            pill_draw_h * 0.42,
            pill_draw_h * 0.36,
            (vel_stretch / 4.5).clamp(0.0, 1.0),
            0.0,
        );
        ui.rounded(
            12.0,
            pill_draw_y,
            sidebar_w - 24.0,
            pill_draw_h,
            pill_rad,
            my3_nav_active(),
        );

        for (i, page) in Page::ALL.iter().enumerate() {
            let nx = 10.0;
            let ny = nav_start_y + i as f32 * nav_step;
            let nw = sidebar_w - 20.0;
            let selected = *page == self.catalog.page;
            let ic_col = if selected { my3_accent() } else { my3_muted() };
            let fg = if selected { my3_text() } else { my3_muted() };
            ui.icon(
                page_icon(*page),
                nx + 16.0,
                ny + (nav_h - 17.0) * 0.5,
                17.0,
                ic_col,
            );
            let face = if selected {
                Face::SemiBold
            } else {
                Face::Medium
            };
            let label = ui.fit_text(face, 13.5, page.name(), nw - 44.0);
            ui.text(face, 13.5, nx + 41.0, ny + (nav_h - 19.0) * 0.5, fg, &label);
            self.targets
                .push(([nx, ny, nw, nav_h], Action::Page(*page)));
        }

        // ---- Main Content Area (with spatial staggered entrance & glassmorphism) ----
        let content_x = sidebar_w + 24.0;
        let content_w = (w - content_x - 24.0).max(160.0);
        let compact = content_w < 430.0;
        let page = self.catalog.page;
        let slide_y = self.anim.page_slide * 0.35;
        ui.set_stagger_transition(self.anim.page_fade, content_x, 52.0);

        // When any catalog item (Mod, Pack, Shader) is downloading/installing, take the entire
        // middle screen to show the MY3 downloading view with Speed, ETA, and Steps.
        if let Some((source_id, _)) = self
            .catalog
            .downloading
            .as_ref()
            .filter(|_| self.catalog.busy())
        {
            let frac = self.catalog.progress_fraction().clamp(0.0, 1.0);
            self.anim.progress_smooth += (frac - self.anim.progress_smooth) * 0.25;
            let smooth_frac = self.anim.progress_smooth.clamp(0.04, 1.0);
            let total_mib = f64::from(self.catalog.downloading_size_mib.max(1.0));
            let done_mib = (total_mib * f64::from(frac)).clamp(0.0, total_mib);
            let title = if self.catalog.downloading_title.is_empty() {
                "Downloading add-on...".to_owned()
            } else {
                format!("Installing {}", self.catalog.downloading_title)
            };
            let sub_left = format!(
                "Minecraft 26.3 • {} • Profile {}",
                source_id.label(),
                self.catalog.config.profile + 1
            );
            let sub_right = format!("{done_mib:.1} / {total_mib:.1} MiB ({:.0}%)", frac * 100.0);
            let stage = self.catalog.progress_stage();
            let step_idx = if frac < 0.15 {
                0
            } else if frac < 0.72 {
                1
            } else if frac < 0.92 {
                2
            } else {
                3
            };
            draw_my3_progress_view(
                &mut ui,
                &mut self.targets,
                content_x,
                content_w,
                h,
                &title,
                &sub_left,
                &sub_right,
                &self.catalog.transfer.speed_label(),
                &self.catalog.transfer.eta_label(done_mib, total_mib),
                step_idx,
                &["Resolve", "Download", "Verify", "Install"],
                smooth_frac,
                self.anim.shimmer_phase,
                &stage,
                self.catalog.is_paused(),
                Action::PauseCatalogDownload,
                Action::CancelCatalogDownload,
            );
        } else if page == Page::Play {
            use crate::official_install::InstallStage;
            let installed = crate::official_install::ready(&self.home);
            let installing = self.worker.is_some();
            let progress = *self
                .install
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            if installing {
                let current = InstallStage::ALL
                    .iter()
                    .position(|s| *s == progress.stage)
                    .unwrap_or(0);
                let fraction = if progress.total > 0 {
                    progress.done as f32 / progress.total as f32
                } else {
                    0.0
                };
                let overall = ((current as f32 + fraction.clamp(0.0, 1.0)) / 6.0).clamp(0.02, 1.0);
                self.anim.progress_smooth += (overall - self.anim.progress_smooth) * 0.25;
                let smooth_overall = self.anim.progress_smooth.clamp(0.02, 1.0);
                let (done_mib, total_mib) =
                    if progress.stage == InstallStage::Download && progress.total > 0 {
                        (
                            progress.done as f64 / 1_048_576.0,
                            progress.total as f64 / 1_048_576.0,
                        )
                    } else {
                        (55.3 * f64::from(overall), 55.3)
                    };
                let paused = self.install_paused.load(Ordering::Relaxed);
                self.install_transfer.observe(done_mib, paused);
                let sub_left = format!(
                    "Minecraft 26.3 • Profile {}",
                    self.catalog.config.profile + 1
                );
                let sub_right = format!(
                    "{done_mib:.1} / {total_mib:.1} MiB ({:.0}%)",
                    overall * 100.0
                );
                let stage_text = match progress.stage {
                    InstallStage::Download => "Downloading Java Edition 26.3 client.jar",
                    other => other.label(),
                };
                draw_my3_progress_view(
                    &mut ui,
                    &mut self.targets,
                    content_x,
                    content_w,
                    h,
                    "Getting your game ready",
                    &sub_left,
                    &sub_right,
                    &self.install_transfer.speed_label(),
                    &self.install_transfer.eta_label(done_mib, total_mib),
                    current,
                    &[
                        "Manifest",
                        "Metadata",
                        "Client JAR",
                        "Verify",
                        "Extract",
                        "Ready",
                    ],
                    smooth_overall,
                    self.anim.shimmer_phase,
                    stage_text,
                    paused,
                    Action::PauseInstall,
                    Action::CancelInstall,
                );
            } else {
                let game_home = self.catalog.config.game_home(&self.home);
                let touch_cfg = crate::touch_compat::Settings::load(
                    &game_home.join("horizon-touch.json"),
                );
                let card_y = 58.0 + slide_y;
                let bottom_reserve = if self.error.is_some() || !self.catalog.status.is_empty() {
                    46.0
                } else {
                    16.0
                };
                let total_avail_h = (h - card_y - bottom_reserve).clamp(210.0, 360.0);
                let hero_h = (total_avail_h * 0.44).clamp(96.0, 126.0);
                let bento_y = card_y + hero_h + 10.0;
                let bento_h = (total_avail_h - hero_h - 10.0).max(108.0);

                // 1. Jetpack Compose Material You Hero Banner Card (`ElevatedCard`) with integrated Split Button CTA
                ui.m3_tonal_card(content_x, card_y, content_w, hero_h, 18.0, true);
                ui.rounded(
                    content_x + 16.0,
                    card_y + 14.0,
                    46.0,
                    46.0,
                    14.0,
                    my3_accent_soft(),
                );
                ui.icon(
                    Icon::HorizonLogo,
                    content_x + 28.0,
                    card_y + 26.0,
                    22.0,
                    my3_accent(),
                );

                // Primary Play Split Button integrated inside the top-right of the Hero Banner Card
                let is_launching = self.launching || self.session_worker.is_some();
                let split_total_w = if compact {
                    (content_w * 0.48).clamp(176.0, 240.0)
                } else {
                    (content_w * 0.44).clamp(248.0, 316.0)
                };
                let split_x = content_x + content_w - split_total_w - 16.0;
                let split_y = card_y + 14.0;
                let split_h = 44.0;

                if is_launching && installed {
                    ui.rounded(
                        split_x,
                        split_y,
                        split_total_w,
                        split_h,
                        12.0,
                        my3_accent_soft(),
                    );
                    ui.stroke_rounded(
                        split_x,
                        split_y,
                        split_total_w,
                        split_h,
                        12.0,
                        1.5,
                        my3_accent(),
                    );
                    ui.m3_wavy_circular_spinner(
                        split_x + 22.0,
                        split_y + 16.0,
                        7.0,
                        self.anim.shimmer_phase,
                    );
                    ui.m3_wavy_progress(
                        split_x + 12.0,
                        split_y + 32.0,
                        (split_total_w - 24.0).max(40.0),
                        5.5,
                        0.68,
                        self.anim.shimmer_phase,
                        false,
                    );
                    ui.centered_fit(
                        Face::SemiBold,
                        14.0,
                        [split_x + 34.0, split_y + 3.0, split_total_w - 44.0, 24.0],
                        my3_text(),
                        "Launching...",
                    );
                } else {
                    let play_label = if installed {
                        "Play Minecraft 26.3"
                    } else if self.error.is_some() {
                        "Retry installation"
                    } else {
                        "Download Minecraft 26.3"
                    };
                    let prof_label = format!("Profile {} v", self.catalog.config.profile + 1);
                    let (lead_r, trail_r) = ui.m3_split_button(
                        split_x,
                        split_y,
                        split_total_w,
                        split_h,
                        Some(Icon::Play),
                        play_label,
                        &prof_label,
                        true,
                    );
                    self.targets.push((lead_r, Action::Play));
                    self.targets.push((trail_r, Action::Profile));
                }

                let hero_text_max = (split_x - (content_x + 74.0) - 12.0).max(110.0);
                let hero_title = ui.fit_text(
                    Face::SemiBold,
                    20.5,
                    if installed {
                        "Ready to play"
                    } else if compact {
                        "Your Java worlds"
                    } else {
                        "Your Java worlds, on Android"
                    },
                    hero_text_max,
                );
                ui.text(
                    Face::SemiBold,
                    20.5,
                    content_x + 74.0,
                    card_y + 12.0,
                    my3_text(),
                    &hero_title,
                );
                let hero_sub = ui.fit_text(
                    Face::Regular,
                    12.5,
                    &format!(
                        "Minecraft 26.3 • Profile {} • {}",
                        self.catalog.config.profile + 1,
                        self.catalog.config.renderer.label()
                    ),
                    hero_text_max,
                );
                ui.text(
                    Face::Regular,
                    12.5,
                    content_x + 74.0,
                    card_y + 38.0,
                    my3_muted(),
                    &hero_sub,
                );

                // Interactive Active Configuration Chips (Renderer Mode, FPS Mode, Control System)
                let chip_y = card_y + hero_h - 32.0;
                let renderer_tag = format!("Renderer: {}", self.catalog.config.renderer.label());
                let fps_tag = if self.catalog.config.battery_perf_mode {
                    "FPS: 60 Hz Battery".to_owned()
                } else if self.catalog.config.max_fps_mode {
                    "FPS: Max Uncapped (260)".to_owned()
                } else {
                    "FPS: 120 Hz Balanced".to_owned()
                };
                let ctrl_tag = format!(
                    "Controls: {} ({})",
                    touch_cfg.control_style.label(),
                    touch_cfg.preset_name
                );
                let mut chip_x = content_x + 16.0;
                for (lbl, act, hi, ic) in [
                    (renderer_tag.as_str(), Action::Renderer, true, Icon::Monitor),
                    (
                        fps_tag.as_str(),
                        Action::ToggleMaxFpsMode,
                        self.catalog.config.max_fps_mode,
                        Icon::Play,
                    ),
                    (
                        ctrl_tag.as_str(),
                        Action::SettingsCat(SettingsCategory::Controls),
                        false,
                        Icon::Gamepad,
                    ),
                ] {
                    let cw_chip = (ui.measure(Face::SemiBold, 10.5, lbl) + 34.0)
                        .clamp(92.0, (content_w - 32.0) * 0.46);
                    if chip_x + cw_chip > content_x + content_w - 12.0 {
                        break;
                    }
                    let cr = [chip_x, chip_y, cw_chip, 24.0];
                    self.targets.push((cr, act));
                    ui.m3_filter_chip(cr, lbl, hi, Some(ic));
                    chip_x += cw_chip + 8.0;
                }

                // 2. Jetpack Compose Material You 2-Column Bento Grid:
                //    Left Bento Card: What's New in 26.3 + integrated Discord & GitHub tonal pills
                //    Right Bento Card: Mod Loader & Compiler Engine live status + quick actions
                let bento_gap = 10.0;
                let has_two_cols = content_w >= 460.0;
                let left_w = if has_two_cols {
                    ((content_w - bento_gap) * 0.58).floor()
                } else {
                    content_w
                };
                let right_w = if has_two_cols {
                    content_w - left_w - bento_gap
                } else {
                    0.0
                };

                ui.m3_tonal_card(content_x, bento_y, left_w, bento_h, 16.0, false);
                let ver_header = if let Some(ref info) = self.catalog.update_info {
                    format!(
                        "CHANGELOG — MINECRAFT 26.3 • HORIZON ANDROID v{} (DOWNLOADED)",
                        info.version
                    )
                } else if installed {
                    format!(
                        "CHANGELOG — MINECRAFT 26.3 • HORIZON ANDROID v{} (DOWNLOADED)",
                        crate::updater::CURRENT_VERSION
                    )
                } else {
                    "RELEASE HIGHLIGHTS — MINECRAFT 26.3 • HORIZON ANDROID".to_owned()
                };
                let fit_vh = ui.fit_text(Face::SemiBold, 10.8, &ver_header, left_w - 28.0);
                ui.text(
                    Face::SemiBold,
                    10.8,
                    content_x + 14.0,
                    bento_y + 10.0,
                    my3_accent(),
                    &fit_vh,
                );

                let changelog_lines: Vec<String> = if let Some(ref info) = self.catalog.update_info
                    && !info.changelog.trim().is_empty()
                {
                    info.changelog
                        .lines()
                        .map(str::trim)
                        .filter(|l| !l.is_empty())
                        .take(3)
                        .map(|l| {
                            if l.starts_with('•') || l.starts_with('-') || l.starts_with('*') {
                                format!("• {}", l.trim_start_matches(['•', '-', '*', ' ']))
                            } else {
                                format!("• {l}")
                            }
                        })
                        .collect()
                } else if installed {
                    vec![
                        "• Uncapped Max FPS (260 FPS) with Vulkan non-blocking swapchain & 120Hz+ display".into(),
                        "• Native horizon-port-pack compiler (.jar -> .hmod + .hnmod) & Modrinth auto-port".into(),
                        "• Google Sans typography, Jetpack Compose Material You UI & GoyControl snap-grid".into(),
                    ]
                } else {
                    vec![
                        "• Downloads official Minecraft 26.3 assets directly from Mojang with 6-step verification".into(),
                        "• Hardware Vulkan GPU batching, Vulkan compatibility & experimental OpenGL ES rendering".into(),
                        "• Full MCPE & GoyControl touch studio with file manager import/export & Microsoft sign-in".into(),
                    ]
                };
                let max_lines = ((bento_h - 64.0) / 18.0).floor().clamp(1.0, 3.0) as usize;
                for (index, line) in changelog_lines.iter().take(max_lines).enumerate() {
                    let fitted = ui.fit_text(Face::Regular, 11.8, line, (left_w - 28.0).max(80.0));
                    ui.text(
                        Face::Regular,
                        11.8,
                        content_x + 14.0,
                        bento_y + 29.0 + index as f32 * 18.0,
                        my3_text(),
                        &fitted,
                    );
                }

                // Single integrated Community Tonal Pill Row inside Left Bento Card (no duplicate Discord!)
                let comm_y = bento_y + bento_h - 36.0;
                button(
                    &mut ui,
                    &mut self.targets,
                    [content_x + 14.0, comm_y, 92.0, 26.0],
                    "Discord",
                    Action::Discord,
                    false,
                );
                button(
                    &mut ui,
                    &mut self.targets,
                    [content_x + 112.0, comm_y, 92.0, 26.0],
                    "GitHub",
                    Action::Repository,
                    false,
                );

                // Right Bento Card: Mod Loader & Native Compiler Telemetry
                if has_two_cols && right_w > 140.0 {
                    let rx = content_x + left_w + bento_gap;
                    ui.m3_tonal_card(rx, bento_y, right_w, bento_h, 16.0, false);
                    ui.rounded(
                        rx + 14.0,
                        bento_y + 10.0,
                        28.0,
                        28.0,
                        8.0,
                        my3_accent_soft(),
                    );
                    ui.icon(
                        Icon::Terminal,
                        rx + 20.0,
                        bento_y + 16.0,
                        16.0,
                        my3_accent(),
                    );
                    let rt_hdr = ui.fit_text(
                        Face::SemiBold,
                        12.5,
                        "Mod Loader & Compiler",
                        right_w - 54.0,
                    );
                    ui.text(
                        Face::SemiBold,
                        12.5,
                        rx + 48.0,
                        bento_y + 10.0,
                        my3_text(),
                        &rt_hdr,
                    );
                    let rt_sub = ui.fit_text(
                        Face::Regular,
                        10.5,
                        "horizon-port-pack • API 415 • .hnmod/.hmod",
                        right_w - 54.0,
                    );
                    ui.text(
                        Face::Regular,
                        10.5,
                        rx + 48.0,
                        bento_y + 25.0,
                        my3_accent(),
                        &rt_sub,
                    );

                    let active_mods = self
                        .catalog
                        .local
                        .iter()
                        .filter(|i| {
                            i.kind == crate::content_files::Kind::Mods && i.enabled
                        })
                        .count()
                        + self.catalog.bundled_enabled.len();
                    let active_shaders = self
                        .catalog
                        .local
                        .iter()
                        .filter(|i| {
                            i.kind == crate::content_files::Kind::Shaders && i.enabled
                        })
                        .count();
                    let stat1 = ui.fit_text(
                        Face::Medium,
                        11.2,
                        &format!("• Active Profile Mods: {active_mods} enabled (.hnmod / .hmod)"),
                        right_w - 28.0,
                    );
                    let stat2 = ui.fit_text(
                        Face::Medium,
                        11.2,
                        &format!(
                            "• Shaders: {} active • 60 Built-in Mods Ready",
                            active_shaders
                        ),
                        right_w - 28.0,
                    );
                    ui.text(
                        Face::Medium,
                        11.2,
                        rx + 14.0,
                        bento_y + 45.0,
                        my3_text(),
                        &stat1,
                    );
                    if bento_h >= 118.0 {
                        ui.text(
                            Face::Medium,
                            11.2,
                            rx + 14.0,
                            bento_y + 63.0,
                            my3_muted(),
                            &stat2,
                        );
                    }

                    let act_y = bento_y + bento_h - 36.0;
                    let act_w = ((right_w - 34.0) * 0.5).max(58.0);
                    button(
                        &mut ui,
                        &mut self.targets,
                        [rx + 14.0, act_y, act_w, 26.0],
                        "Open Compiler",
                        Action::Page(Page::Compiler),
                        true,
                    );
                    button(
                        &mut ui,
                        &mut self.targets,
                        [rx + 20.0 + act_w, act_y, act_w, 26.0],
                        "Browse Mods",
                        Action::Page(Page::Mods),
                        false,
                    );
                }
            }
        } else if page == Page::Skins {
            self.wardrobe.draw(
                &mut ui,
                &mut self.targets,
                self.catalog.scroll,
                content_x,
                content_w,
                62.0 + slide_y,
            );
            self.list_view = Some(self.wardrobe.view);
            self.catalog.scroll = self.catalog.scroll.min(self.wardrobe.view[2]);
        } else if page.is_catalog() {
            let header_y = 62.0 + slide_y;
            ui.text(
                Face::SemiBold,
                19.0,
                content_x,
                header_y,
                my3_text(),
                if page == Page::Mods {
                    "Mods"
                } else if page == Page::Shaders {
                    "Shader Packs"
                } else {
                    "Resource Packs"
                },
            );
            if page == Page::Shaders {
                use crate::shader_catalog::Origin;
                let seg_w = 104.0;
                let group_x = content_x + content_w - seg_w * 2.0 - 3.0;
                for (i, origin) in [Origin::Horizon, Origin::Modrinth].into_iter().enumerate() {
                    let rx = group_x + i as f32 * (seg_w + 3.0);
                    let rect = [rx, header_y - 2.0, seg_w, 30.0];
                    let active = self.catalog.config.shader_source == origin;
                    ui.m3_connected_segment(
                        rect,
                        i,
                        2,
                        origin.source().label(),
                        active,
                        Some(Icon::Shaders),
                    );
                    self.targets.push((rect, Action::ShaderSource(origin)));
                }
            } else if page == Page::Mods {
                use crate::shader_catalog::Origin;
                let seg_w = 104.0;
                let group_x = content_x + content_w - seg_w * 2.0 - 3.0;
                for (i, origin) in [Origin::Horizon, Origin::Modrinth].into_iter().enumerate() {
                    let rx = group_x + i as f32 * (seg_w + 3.0);
                    let rect = [rx, header_y - 2.0, seg_w, 30.0];
                    let active = self.catalog.config.mod_source == origin;
                    ui.m3_connected_segment(
                        rect,
                        i,
                        2,
                        origin.source().label(),
                        active,
                        Some(Icon::Mods),
                    );
                    self.targets.push((rect, Action::ModSource(origin)));
                }
            }

            // Search bar + Search button + Open folder / Import file pills
            let search_y = 98.0 + slide_y;
            let search_btn_w = 98.0;
            let field_w = (content_w - search_btn_w - 8.0).max(120.0);
            let search_hint = if page == Page::Mods {
                "Search mods or paste .hmod HTTPS link..."
            } else {
                "Search catalog..."
            };
            let search_rect = ui.text_field(
                content_x,
                search_y,
                field_w,
                36.0,
                "",
                &self.catalog.query,
                search_hint,
                self.catalog.editing == Some("search"),
            );
            self.targets.push((search_rect, Action::Field("search")));

            button(
                &mut ui,
                &mut self.targets,
                [content_x + field_w + 8.0, search_y, search_btn_w, 36.0],
                if page == Page::Mods && self.catalog.query.trim().starts_with("https://") {
                    "Import .hmod"
                } else {
                    "Search"
                },
                Action::Search,
                true,
            );

            let kind = match page {
                Page::Mods => crate::content_files::Kind::Mods,
                Page::Shaders => crate::content_files::Kind::Shaders,
                _ => crate::content_files::Kind::Packs,
            };
            let sub_y = 140.0 + slide_y;
            button(
                &mut ui,
                &mut self.targets,
                [content_x, sub_y, 114.0, 29.0],
                "Open folder",
                Action::OpenFolder(kind),
                false,
            );
            button(
                &mut ui,
                &mut self.targets,
                [content_x + 122.0, sub_y, 114.0, 29.0],
                "Import file",
                Action::Import(kind),
                false,
            );
            if !compact {
                let status_line = self.error.as_deref().unwrap_or(&self.catalog.status);
                let fitted_status = ui.fit_text(
                    Face::Regular,
                    12.5,
                    status_line,
                    (content_w - 248.0).max(60.0),
                );
                ui.text(
                    Face::Regular,
                    12.5,
                    content_x + 246.0,
                    sub_y + 5.0,
                    my3_muted(),
                    &fitted_status,
                );
            }

            let bundled: Vec<_> = if page == Page::Mods
                && self.catalog.config.mod_source == crate::shader_catalog::Origin::Horizon
            {
                let query = self.catalog.query.trim().to_lowercase();
                crate::ANDROID_BUNDLED
                    .into_iter()
                    .filter(|(id, title, _)| {
                        query.is_empty()
                            || id.contains(&query)
                            || title.to_lowercase().contains(&query)
                    })
                    .collect()
            } else {
                Vec::new()
            };
            let catalog_rows: Vec<_> = self
                .catalog
                .results
                .iter()
                .enumerate()
                .filter(|(_, p)| {
                    !(page == Page::Mods
                        && p.source == crate::browser::SourceId::Horizon
                        && crate::ANDROID_BUNDLED
                            .iter()
                            .any(|(id, _, _)| *id == p.id))
                })
                .collect();
            let is_horizon_paged = (page == Page::Mods
                && self.catalog.config.mod_source == crate::shader_catalog::Origin::Horizon)
                || (page == Page::Shaders
                    && self.catalog.config.shader_source == crate::shader_catalog::Origin::Horizon);
            let total_horizon_items = bundled.len() + catalog_rows.len();
            let total_pages = if is_horizon_paged {
                total_horizon_items
                    .div_ceil(crate::catalog::CATALOG_PAGE_SIZE)
                    .max(1)
            } else {
                1
            };
            if is_horizon_paged {
                let max_offset = (total_pages - 1) * crate::catalog::CATALOG_PAGE_SIZE;
                if self.catalog.offset > max_offset {
                    self.catalog.offset = max_offset;
                }
            }
            let page_start = if is_horizon_paged {
                self.catalog.offset.min(total_horizon_items)
            } else {
                0
            };
            let page_end = if is_horizon_paged {
                (page_start + crate::catalog::CATALOG_PAGE_SIZE).min(total_horizon_items)
            } else {
                catalog_rows.len().min(crate::catalog::CATALOG_PAGE_SIZE)
            };
            let bundled_start = page_start.min(bundled.len());
            let bundled_end = page_end.min(bundled.len());
            let page_bundled = &bundled[bundled_start..bundled_end];
            let cat_start = page_start
                .saturating_sub(bundled.len())
                .min(catalog_rows.len());
            let cat_end = page_end
                .saturating_sub(bundled.len())
                .min(catalog_rows.len());
            let page_catalog_rows = &catalog_rows[cat_start..cat_end];

            let row_h = 72.0;
            let row_count = page_bundled.len() + page_catalog_rows.len();
            let top = 176.0 + slide_y;
            let bottom = h - 46.0;
            self.catalog.scroll = self
                .catalog
                .scroll
                .clamp(0.0, (row_count as f32 * row_h - (bottom - top)).max(0.0));
            self.list_view = Some([
                top,
                bottom,
                (row_count as f32 * row_h - (bottom - top)).max(0.0),
            ]);

            let fallback_icon = page_icon(page);
            ui.set_clip(Some([
                content_x - 2.0,
                top,
                content_w + 4.0,
                (bottom - top).max(1.0),
            ]));

            for (row, (id, title, description)) in page_bundled.iter().enumerate() {
                let y = top + row as f32 * row_h - self.catalog.scroll;
                let enabled = self.catalog.bundled_enabled.contains(*id);
                let card_rect = [content_x, y, content_w - 118.0, 64.0];
                let btn_rect = [content_x + content_w - 112.0, y + 13.0, 100.0, 38.0];
                self.list_targets
                    .push((card_rect, Action::OpenBundledProject(id)));
                self.list_targets
                    .push((btn_rect, Action::ToggleBundled(id)));
                if y + 64.0 < top || y > bottom {
                    continue;
                }
                ui.rounded(content_x, y, content_w, 64.0, 14.0, my3_card_bg());
                ui.border(content_x, y, content_w, 64.0, 14.0, my3_card_border());

                // Project icon: fetched Modrinth PFP if available, else MY3 vector icon badge.
                let drawn_icon = if let Some((iw, ih, rgba)) = self.catalog.icons.get(*id) {
                    ui.image(id, *iw, *ih, rgba, content_x + 10.0, y + 10.0, 44.0, 44.0)
                } else {
                    false
                };
                if !drawn_icon {
                    ui.rounded(
                        content_x + 10.0,
                        y + 10.0,
                        44.0,
                        44.0,
                        12.0,
                        my3_accent_soft(),
                    );
                    ui.icon(
                        fallback_icon,
                        content_x + 21.0,
                        y + 21.0,
                        22.0,
                        my3_accent(),
                    );
                }

                // Badge chip ("Built-in • Tap for info")
                let text_max = (content_w - 190.0).max(80.0);
                let fitted_title = ui.fit_text(Face::SemiBold, 15.0, title, text_max);
                let rich_desc = self
                    .catalog
                    .enriched_summaries
                    .get(*id)
                    .map_or(*description, String::as_str);
                let summary = ui.fit_text(Face::Regular, 12.5, rich_desc, text_max);
                ui.text(
                    Face::SemiBold,
                    15.0,
                    content_x + 64.0,
                    y + 8.0,
                    my3_text(),
                    &fitted_title,
                );
                ui.text(
                    Face::Regular,
                    12.5,
                    content_x + 64.0,
                    y + 28.0,
                    my3_muted(),
                    &summary,
                );
                ui.text(
                    Face::Medium,
                    11.0,
                    content_x + 64.0,
                    y + 45.0,
                    my3_accent(),
                    "Horizon Built-in • Tap for versions, Modrinth info & screenshots",
                );
                if let Some(c) = ui.clip_rect(card_rect) {
                    self.targets.push((c, Action::OpenBundledProject(id)));
                }
                button(
                    &mut ui,
                    &mut self.targets,
                    btn_rect,
                    if enabled { "Installed" } else { "Install" },
                    Action::ToggleBundled(id),
                    !enabled,
                );
            }

            for (row, &(i, p)) in page_catalog_rows.iter().enumerate() {
                let y = top + (row + page_bundled.len()) as f32 * row_h - self.catalog.scroll;
                let download = self.catalog.download_label(p);
                let card_rect = [content_x, y, content_w - 118.0, 64.0];
                let btn_rect = [content_x + content_w - 112.0, y + 13.0, 100.0, 38.0];
                self.list_targets.push((card_rect, Action::OpenProject(i)));
                if download.is_none() {
                    self.list_targets.push((btn_rect, Action::Install(i)));
                }
                if y + 64.0 < top || y > bottom {
                    continue;
                }
                ui.rounded(content_x, y, content_w, 64.0, 14.0, my3_card_bg());
                ui.border(content_x, y, content_w, 64.0, 14.0, my3_card_border());

                // Project icon: fetched RGBA icon if ready, else MY3 vector icon badge.
                let drawn_icon = if let Some((iw, ih, rgba)) = self.catalog.icons.get(&p.id) {
                    ui.image(
                        &p.id,
                        *iw,
                        *ih,
                        rgba,
                        content_x + 10.0,
                        y + 10.0,
                        44.0,
                        44.0,
                    )
                } else {
                    false
                };
                if !drawn_icon {
                    ui.rounded(
                        content_x + 10.0,
                        y + 10.0,
                        44.0,
                        44.0,
                        12.0,
                        my3_accent_soft(),
                    );
                    ui.icon(
                        fallback_icon,
                        content_x + 21.0,
                        y + 21.0,
                        22.0,
                        my3_accent(),
                    );
                }

                let text_max = (content_w - 190.0).max(80.0);
                let title = ui.fit_text(Face::SemiBold, 15.0, &p.title, text_max);
                let rich_summary = self
                    .catalog
                    .enriched_summaries
                    .get(&p.id)
                    .map_or(p.summary.as_str(), String::as_str);
                let summary = ui.fit_text(Face::Regular, 12.5, rich_summary, text_max);
                ui.text(
                    Face::SemiBold,
                    15.0,
                    content_x + 64.0,
                    y + 8.0,
                    my3_text(),
                    &title,
                );
                ui.text(
                    Face::Regular,
                    12.5,
                    content_x + 64.0,
                    y + 28.0,
                    my3_muted(),
                    &summary,
                );
                let meta_tag = if p.downloads > 0 {
                    format!(
                        "{} • {} downloads • Tap for versions & screenshots",
                        p.source.label(),
                        format_downloads(p.downloads)
                    )
                } else {
                    format!(
                        "{} • Tap for versions, description & screenshots",
                        p.source.label()
                    )
                };
                let fitted_meta = ui.fit_text(Face::Medium, 11.0, &meta_tag, text_max);
                ui.text(
                    Face::Medium,
                    11.0,
                    content_x + 64.0,
                    y + 45.0,
                    my3_accent(),
                    &fitted_meta,
                );
                if let Some(c) = ui.clip_rect(card_rect) {
                    self.targets.push((c, Action::OpenProject(i)));
                }

                if let Some(state) = download {
                    ui.rounded(
                        btn_rect[0],
                        btn_rect[1],
                        btn_rect[2],
                        btn_rect[3],
                        19.0,
                        my3_surface_high(),
                    );
                    ui.icon(
                        Icon::Check,
                        btn_rect[0] + 10.0,
                        btn_rect[1] + 11.0,
                        15.0,
                        my3_accent(),
                    );
                    ui.text(
                        Face::Medium,
                        13.0,
                        btn_rect[0] + 28.0,
                        btn_rect[1] + 9.0,
                        my3_text(),
                        &state,
                    );
                    if let Some(c) = ui.clip_rect(btn_rect) {
                        self.targets.push((c, Action::OpenProject(i)));
                    }
                } else {
                    button(
                        &mut ui,
                        &mut self.targets,
                        btn_rect,
                        self.catalog.install_button(p),
                        Action::Install(i),
                        true,
                    );
                }
            }

            ui.set_clip(None);
            let pager_y = h - 42.0;
            let has_prev = self.catalog.offset >= crate::catalog::CATALOG_PAGE_SIZE;
            let has_next = if is_horizon_paged {
                self.catalog.offset + crate::catalog::CATALOG_PAGE_SIZE < total_horizon_items
            } else {
                self.catalog.results.len() >= crate::catalog::CATALOG_PAGE_SIZE
            };
            button(
                &mut ui,
                &mut self.targets,
                [content_x, pager_y, 102.0, 32.0],
                "Previous",
                Action::Previous,
                has_prev,
            );
            button(
                &mut ui,
                &mut self.targets,
                [content_x + 110.0, pager_y, 102.0, 32.0],
                "Next",
                Action::Next,
                has_next,
            );
            let page_num = (self.catalog.offset / crate::catalog::CATALOG_PAGE_SIZE) + 1;
            let page_info = if is_horizon_paged {
                let shown_first = if total_horizon_items == 0 {
                    0
                } else {
                    page_start + 1
                };
                format!(
                    "Page {page_num} of {total_pages}  •  Showing {shown_first}–{page_end} of {total_horizon_items}"
                )
            } else {
                format!(
                    "Page {page_num}  •  {} per page",
                    crate::catalog::CATALOG_PAGE_SIZE
                )
            };
            let fitted_page_info = ui.fit_text(
                Face::Medium,
                12.5,
                &page_info,
                (content_w - 226.0).max(60.0),
            );
            ui.text(
                Face::Medium,
                12.5,
                content_x + 222.0,
                pager_y + 7.0,
                my3_muted(),
                &fitted_page_info,
            );
        } else if page == Page::Compiler {
            self.draw_compiler_page(&mut ui, content_x, content_w, h, compact, slide_y);
        } else if page == Page::Installed {
            self.draw_installed(&mut ui, content_x, content_w, h, compact, slide_y);
        } else {
            self.draw_settings_page(
                &mut ui, content_x, content_w, h, compact, slide_y, ime_top_dp,
            );
        }

        // Bottom status strip when not in a full-screen progress dialog.
        let in_progress_dialog = (page == Page::Play && self.worker.is_some())
            || (self.catalog.downloading.is_some() && self.catalog.busy());
        if !in_progress_dialog {
            let show_corner_links = page == Page::Settings
                && self.catalog.settings_category == SettingsCategory::Overview;
            let status = self.error.as_deref().unwrap_or(&self.catalog.status);
            if !status.is_empty() && !page.is_catalog() {
                let max_snack_w = if show_corner_links {
                    (content_w - 224.0).max(120.0)
                } else {
                    content_w.max(160.0)
                };
                ui.m3_snackbar_toast(
                    content_x,
                    h - 38.0,
                    max_snack_w,
                    status,
                    self.error.is_some(),
                    self.anim.toast_anim,
                );
            }
            if show_corner_links {
                button(
                    &mut ui,
                    &mut self.targets,
                    [w - 118.0, h - 38.0, 92.0, 28.0],
                    "GitHub",
                    Action::Repository,
                    false,
                );
                button(
                    &mut ui,
                    &mut self.targets,
                    [w - 222.0, h - 38.0, 96.0, 28.0],
                    "Discord",
                    Action::Discord,
                    false,
                );
            }
        }

        ui.set_stagger_transition(1.0, 0.0, 0.0);
        // Material You 3 Project Detail Modal (Versions, Modrinth Description, Gallery Screenshots)
        if self.catalog.project_modal.is_some() {
            self.draw_project_modal(&mut ui, w, h);
        }

        if self.login.visible {
            self.list_targets.clear();
            self.list_view = None;
            self.login.draw(
                &mut ui,
                &mut self.targets,
                &self.catalog.config.name,
                self.catalog.editing,
                self.anim.modal_anim,
            );
        }
        if let Some(chooser) = &mut self.chooser {
            chooser.draw(&mut ui, &mut self.targets, self.anim.modal_anim);
            self.list_targets = chooser.navigation(ui.w);
            self.list_view = Some(chooser.viewport(ui.h));
        }
        if let Some([top, bottom, maximum]) = self.list_view
            && maximum > 0.0
            && bottom > top
            && self.catalog.project_modal.is_none()
        {
            let scroll = self
                .chooser
                .as_ref()
                .map_or(self.catalog.scroll, |c| c.scroll);
            let height = bottom - top;
            let thumb = (height * height / (height + maximum)).max(20.0).min(height);
            let y = top + (height - thumb) * (scroll / maximum).clamp(0.0, 1.0);
            ui.rounded(w - 10.0, top, 4.0, height, 2.0, my3_border());
            ui.rounded(w - 10.0, y, 4.0, thumb, 2.0, my3_muted());
        }
        if self.catalog.fm_modal.is_some() {
            self.draw_fm_modal(&mut ui, w, h, ime_top_dp);
        }
        if let Some((_, ref dup_name, _)) = self.catalog.duplicate_preset_confirm.clone() {
            self.targets.clear();
            self.list_targets.clear();
            self.list_view = None;
            ui.rounded(
                0.0,
                0.0,
                w,
                h,
                0.0,
                horizon_client::hud::rgba(4, 5, 10, 210),
            );
            let mw = (w - 32.0).clamp(300.0, 440.0);
            let mh = 190.0;
            let mx = (w - mw) * 0.5;
            let my = (h - mh) * 0.5;
            ui.rounded(mx, my, mw, mh, 20.0, my3_surface());
            ui.border(mx, my, mw, mh, 20.0, my3_accent());
            ui.text(
                Face::SemiBold,
                16.5,
                mx + 20.0,
                my + 18.0,
                my3_text(),
                "Replace Existing Control Preset?",
            );
            let line1 = format!("A control preset named '{}' already exists.", dup_name);
            let fitted1 = ui.fit_text(Face::Regular, 12.0, &line1, mw - 40.0);
            ui.text(
                Face::Regular,
                12.0,
                mx + 20.0,
                my + 52.0,
                my3_muted(),
                &fitted1,
            );
            let line2 = "Duplicate names are not allowed. Save to replace the current one?";
            let fitted2 = ui.fit_text(Face::Regular, 11.5, line2, mw - 40.0);
            ui.text(
                Face::Regular,
                11.5,
                mx + 20.0,
                my + 76.0,
                my3_muted(),
                &fitted2,
            );
            let bw = (mw - 52.0) * 0.5;
            button(
                &mut ui,
                &mut self.targets,
                [mx + 20.0, my + mh - 52.0, bw, 38.0],
                "Save",
                Action::ConfirmReplaceDuplicatePreset,
                true,
            );
            button(
                &mut ui,
                &mut self.targets,
                [mx + 32.0 + bw, my + mh - 52.0, bw, 38.0],
                "Cancel",
                Action::CancelReplaceDuplicatePreset,
                false,
            );
        }
        if self.diagnostics_modal_open {
            self.draw_diagnostics_summary_modal(&mut ui, w, h);
        }
        self.draw_update_fullscreen_ui(&mut ui, w, h);
        if self.exit_confirmation {
            self.targets.clear();
            self.list_targets.clear();
            self.list_view = None;
            let modal_t = self.anim.modal_anim.clamp(0.2, 1.0);
            ui.rounded(
                0.0,
                0.0,
                w,
                h,
                0.0,
                horizon_client::hud::rgba(0, 0, 0, (180.0 * modal_t) as u8),
            );
            let scale_in = 0.94 + 0.06 * modal_t;
            let width = (w - 32.0).min(360.0) * scale_in;
            let x = (w - width) * 0.5;
            let y = (h - 160.0) * 0.5 + (1.0 - modal_t) * 8.0;
            ui.rounded(x, y, width, 160.0, 20.0, my3_surface());
            ui.border(x, y, width, 160.0, 20.0, my3_border());
            ui.text(
                Face::SemiBold,
                20.0,
                x + 22.0,
                y + 24.0,
                my3_text(),
                "Exit Horizon Android?",
            );
            let bw = (width - 52.0) * 0.5;
            button(
                &mut ui,
                &mut self.targets,
                [x + 20.0, y + 94.0, bw, 42.0],
                "Cancel",
                Action::Dismiss,
                false,
            );
            button(
                &mut ui,
                &mut self.targets,
                [x + 32.0 + bw, y + 94.0, bw, 42.0],
                "Exit",
                Action::ConfirmExit,
                true,
            );
        }
        if let Some(action) = self.pending_focus.take() {
            self.focus = self.targets.iter().position(|(_, a)| *a == action);
        }
        if let Some(index) = self.focus
            && let Some(([x, y, w, h], _)) = self.targets.get(index)
        {
            ui.border(*x - 2.0, *y - 2.0, *w + 4.0, *h + 4.0, 12.0, my3_accent());
            self.cursor = (
                f64::from((x + w / 2.0) * ui.density),
                f64::from((y + h / 2.0) * ui.density),
            );
        }
        if self.mouse_down || self.drag.as_ref().is_some_and(|drag| !drag.moved) {
            let point = [
                self.cursor.0 as f32 / ui.density,
                self.cursor.1 as f32 / ui.density,
            ];
            if let Some(([x, y, w, h], _)) = self.targets.iter().rev().find(|(rect, _)| {
                point[0] >= rect[0]
                    && point[0] <= rect[0] + rect[2]
                    && point[1] >= rect[1]
                    && point[1] <= rect[1] + rect[3]
            }) {
                let sc = crate::ui_theme::motion::press_scale(true, 0.0);
                let sw = *w * sc;
                let sh = *h * sc;
                let sx = *x + (*w - sw) * 0.5;
                let sy = *y + (*h - sh) * 0.5;
                let rad = crate::ui_theme::motion::morph_radius(
                    (*h * 0.22).clamp(6.0, 10.0),
                    6.0,
                    4.5,
                    0.0,
                    1.0,
                );
                ui.rounded(
                    sx,
                    sy,
                    sw,
                    sh,
                    rad,
                    horizon_client::hud::rgba(255, 255, 255, 38),
                );
                ui.border(sx, sy, sw, sh, rad, my3_accent());
            }
        }
        if !self.mouse_down
            && self.drag.is_none()
            && let Some((action, time)) = self.feedback
            && time.elapsed() < std::time::Duration::from_millis(260)
            && let Some(([x, y, w, h], _)) = self.targets.iter().find(|(_, a)| *a == action)
        {
            let p = (time.elapsed().as_secs_f32() / 0.26).clamp(0.0, 1.0);
            let spring = crate::ui_theme::motion::spring_expressive_spatial(p);
            let fade = (1.0 - crate::ui_theme::motion::spring_expressive_effects(p))
                .clamp(0.0, 1.0);
            let rad = crate::ui_theme::motion::morph_radius(
                (*h * 0.22).clamp(6.0, 10.0),
                7.0,
                5.0,
                spring,
                1.0 - p,
            );
            let expand = spring * 3.2;
            ui.rounded(
                *x,
                *y,
                *w,
                *h,
                rad,
                horizon_client::hud::rgba(255, 255, 255, (42.0 * fade) as u8),
            );
            ui.border(
                *x - expand,
                *y - expand,
                *w + expand * 2.0,
                *h + expand * 2.0,
                rad + expand * 0.6,
                color::with_alpha(my3_accent(), 0.78 * fade),
            );
        }
        crate::documents::draw_ui(&mut ui);
        if !self.anim.intro_done {
            let session_prog = if self.session_worker.is_some() {
                0.30
            } else {
                0.55
            };
            let loading_progress = (0.45f32 + session_prog).clamp(0.0, 1.0);
            if loading_progress >= 0.999 && self.anim.intro_elapsed >= 1.55 {
                self.anim.intro_done = true;
            } else {
                let intro_status = if self.session_worker.is_some() {
                    "Syncing account session & skins..."
                } else if self.catalog.search_worker_active() {
                    "Loading content catalog..."
                } else {
                    ""
                };
                ui.draw_horizon_intro(self.anim.intro_elapsed, loading_progress, intro_status);
                if loading_progress >= 0.999 {
                    self.targets.clear();
                    self.list_targets.clear();
                    self.targets.push(([0.0, 0.0, w, h], Action::SkipIntro));
                }
            }
        }
        ui.finish();

        if !r.render(&game::loading_frame(&b))? && !b.patches.is_empty() {
            // A suspended/lost surface skips the frame, including its glyph
            // uploads. Rebuild them next time instead of caching invisible text.
            crate::ui_theme::reset_atlas();
        }
        Ok(())
    }
}
