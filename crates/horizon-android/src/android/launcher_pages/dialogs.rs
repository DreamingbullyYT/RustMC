impl Launcher {
    /// Full screen Material 3 view for update detection and automated installation.
    fn draw_update_fullscreen_ui(&mut self, ui: &mut Ui, w: f32, h: f32) {
        let status = crate::updater::get_status();
        let (info, is_downloading, is_downloaded) = match &status {
            crate::updater::UpdateStatus::Available(info) => (Some(info.clone()), false, false),
            crate::updater::UpdateStatus::Downloading { .. } => {
                let info = self.catalog.update_info.clone();
                (info, true, false)
            }
            crate::updater::UpdateStatus::Downloaded { .. } => {
                let info = self.catalog.update_info.clone();
                (info, false, true)
            }
            crate::updater::UpdateStatus::Error(_) => {
                let info = self.catalog.update_info.clone();
                (info, false, false)
            }
            _ => (None, false, false),
        };

        if let Some(ref i) = info {
            self.catalog.update_info = Some(i.clone());
            if !self.catalog.update_ui_open && status != crate::updater::UpdateStatus::Dismissed {
                self.catalog.update_ui_open = true;
            }
        }

        if !self.catalog.update_ui_open {
            return;
        }

        let Some(info) = self.catalog.update_info.clone() else {
            return;
        };

        // Material 3 Animation: smooth slide-up and fade-in
        self.catalog.update_anim_time = (self.catalog.update_anim_time + 0.06).min(1.0);
        let ease = 1.0 - (1.0 - self.catalog.update_anim_time).powi(3);
        let slide_y = (1.0 - ease) * 60.0;

        self.targets.clear();
        self.list_targets.clear();
        self.list_view = None;

        // Full screen background scrim
        ui.rounded(
            0.0,
            0.0,
            w,
            h,
            0.0,
            horizon_client::hud::rgba(8, 7, 16, (235.0 * ease) as u8),
        );

        // Center Material 3 Container Card
        let mw = (w - 32.0).clamp(320.0, 680.0);
        let mh = (h - 28.0).clamp(340.0, 520.0);
        let mx = (w - mw) * 0.5;
        let my = (h - mh) * 0.5 + slide_y;

        ui.rounded(mx, my, mw, mh, 12.0, my3_surface());
        ui.border(mx, my, mw, mh, 12.0, my3_border());

        // Header with Material Icon & Accent Glow
        let head_y = my + 18.0;
        let icon_box_r = [mx + 20.0, head_y, 44.0, 44.0];
        ui.rounded(
            icon_box_r[0],
            icon_box_r[1],
            icon_box_r[2],
            icon_box_r[3],
            14.0,
            my3_accent_soft(),
        );
        ui.border(
            icon_box_r[0],
            icon_box_r[1],
            icon_box_r[2],
            icon_box_r[3],
            14.0,
            my3_accent(),
        );
        ui.icon(
            Icon::HorizonLogo,
            mx + 31.0,
            head_y + 11.0,
            22.0,
            my3_accent(),
        );

        let title_x = mx + 74.0;
        ui.text(
            Face::SemiBold,
            18.5,
            title_x,
            head_y + 2.0,
            my3_text(),
            "Horizon Android Update",
        );
        let badge_text = format!("v1.0.0  →  v{}", info.version);
        let badge_w = ui.measure(Face::SemiBold, 11.0, &badge_text) + 16.0;
        let badge_r = [title_x, head_y + 24.0, badge_w, 20.0];
        ui.rounded(
            badge_r[0],
            badge_r[1],
            badge_r[2],
            badge_r[3],
            6.0,
            my3_surface_high(),
        );
        ui.border(
            badge_r[0],
            badge_r[1],
            badge_r[2],
            badge_r[3],
            6.0,
            my3_accent(),
        );
        ui.centered_fit(Face::SemiBold, 11.0, badge_r, my3_accent(), &badge_text);

        // Changelog Content Box
        let box_y = head_y + 54.0;
        let box_h = mh - 138.0;
        let box_w = mw - 40.0;
        ui.rounded(mx + 20.0, box_y, box_w, box_h, 16.0, my3_surface_high());
        ui.border(mx + 20.0, box_y, box_w, box_h, 16.0, my3_border());

        ui.text(
            Face::SemiBold,
            13.0,
            mx + 32.0,
            box_y + 12.0,
            my3_accent(),
            "What's New in this release:",
        );

        let lines: Vec<&str> = info.changelog.lines().collect();
        let line_h = 18.0;
        let text_top = box_y + 34.0;
        let text_bottom = box_y + box_h - 10.0;
        let mut cur_ty = text_top;
        ui.set_clip(Some([
            mx + 28.0,
            text_top,
            box_w - 16.0,
            (text_bottom - text_top).max(1.0),
        ]));
        for line in lines {
            if cur_ty + line_h > text_bottom {
                break;
            }
            let is_header = line.starts_with('#');
            let is_bullet = line.starts_with('-') || line.starts_with('*');
            let (draw_text, face, size, col) = if is_header {
                (
                    line.trim_start_matches('#').trim(),
                    Face::SemiBold,
                    13.0,
                    my3_text(),
                )
            } else if is_bullet {
                (line, Face::Regular, 12.0, my3_text())
            } else {
                (line, Face::Regular, 11.5, my3_muted())
            };
            ui.text(face, size, mx + 32.0, cur_ty, col, draw_text);
            cur_ty += line_h;
        }
        ui.set_clip(None);

        // Progress or Status Bar when downloading / installing
        let status_y = my + mh - 70.0;
        match &status {
            crate::updater::UpdateStatus::Downloading {
                progress,
                downloaded,
                total,
            } => {
                let track_w = mw - 40.0;
                let track_r = [mx + 20.0, status_y, track_w, 8.0];
                ui.rounded(
                    track_r[0],
                    track_r[1],
                    track_r[2],
                    track_r[3],
                    4.0,
                    my3_border(),
                );
                let fill_w = (track_w * progress).clamp(4.0, track_w);
                ui.rounded(
                    track_r[0],
                    track_r[1],
                    fill_w,
                    track_r[3],
                    4.0,
                    my3_accent(),
                );
                let prog_lbl = format!(
                    "Downloading: {:.1} / {:.1} MiB ({:.0}%)",
                    *downloaded as f32 / 1048576.0,
                    *total as f32 / 1048576.0,
                    progress * 100.0
                );
                ui.text(
                    Face::Medium,
                    11.0,
                    mx + 20.0,
                    status_y - 16.0,
                    my3_accent(),
                    &prog_lbl,
                );
            }
            crate::updater::UpdateStatus::Downloaded { .. } => {
                ui.text(
                    Face::Medium,
                    11.5,
                    mx + 20.0,
                    status_y - 14.0,
                    my3_accent(),
                    "Download complete. Ready to install.",
                );
            }
            crate::updater::UpdateStatus::Error(e) => {
                ui.text(
                    Face::Medium,
                    11.0,
                    mx + 20.0,
                    status_y - 14.0,
                    horizon_client::hud::rgba(239, 68, 68, 255),
                    e,
                );
            }
            _ => {}
        }

        // Bottom CTA Buttons
        let btn_y = my + mh - 44.0;
        let btn_h = 32.0;
        let dismiss_w = 90.0;
        let update_btn_w = 170.0;

        button(
            ui,
            &mut self.targets,
            [mx + 20.0, btn_y, dismiss_w, btn_h],
            "Later",
            Action::CloseUpdateScreen,
            false,
        );

        let (btn_lbl, btn_act, btn_enabled) = if is_downloading {
            ("Downloading...", Action::TriggerUpdateInstall, false)
        } else if is_downloaded {
            ("Install Now", Action::TriggerUpdateInstall, true)
        } else {
            ("Update & Install", Action::TriggerUpdateInstall, true)
        };

        button(
            ui,
            &mut self.targets,
            [mx + mw - 20.0 - update_btn_w, btn_y, update_btn_w, btn_h],
            btn_lbl,
            btn_act,
            btn_enabled,
        );
    }

    /// Material You 3 Project Detail Dialog showing Modrinth PFP, Description,
    /// Gallery Screenshots, and selectable Versions to Download.
    fn draw_project_modal(&mut self, ui: &mut Ui, w: f32, h: f32) {
        // The install label asks the catalog about the project the modal is showing, so work it out
        // before the modal is borrowed mutably for drawing.
        let Some(install_label) = self.catalog.project_modal.as_ref().map(|modal| {
            let version = modal
                .versions
                .get(modal.selected_version)
                .map_or_else(|| "Latest".to_owned(), |v| v.number.clone());
            if let Some(bundled) = modal.bundled_id {
                if self.catalog.bundled_enabled.contains(bundled) {
                    "Installed (Tap to Disable)".to_owned()
                } else {
                    format!("Enable Built-in ({version})")
                }
            } else if let Some(state) = self.catalog.download_label(&modal.project) {
                state
            } else {
                format!("Download {} ({version})", modal.project.kind.label())
            }
        }) else {
            return;
        };
        let Some(modal) = &mut self.catalog.project_modal else {
            return;
        };
        self.targets.clear();
        self.list_targets.clear();

        // Scrim backdrop
        let alpha = (185.0 * self.anim.modal_anim.clamp(0.2, 1.0)) as u8;
        ui.rounded(
            0.0,
            0.0,
            w,
            h,
            0.0,
            horizon_client::hud::rgba(6, 5, 12, alpha),
        );

        let scale_in = 0.94 + 0.06 * self.anim.modal_anim.clamp(0.0, 1.0);
        let mw = ((w - 44.0).min(640.0) * scale_in).max(280.0);
        let mh = ((h - 36.0).min(385.0) * scale_in).max(230.0);
        let mx = (w - mw) * 0.5;
        let my = (h - mh) * 0.5;

        ui.rounded(mx, my, mw, mh, 12.0, my3_surface());
        ui.border(mx, my, mw, mh, 12.0, my3_border());

        // Modal Top Header (PFP + Title + Author/Downloads + Close Button)
        let icon_drawn = if let Some((iw, ih, rgba)) = self.catalog.icons.get(&modal.project.id) {
            ui.image(
                &modal.project.id,
                *iw,
                *ih,
                rgba,
                mx + 18.0,
                my + 16.0,
                50.0,
                50.0,
            )
        } else {
            false
        };
        if !icon_drawn {
            ui.rounded(mx + 18.0, my + 16.0, 50.0, 50.0, 14.0, my3_accent_soft());
            ui.icon(
                page_icon(self.catalog.page),
                mx + 31.0,
                my + 29.0,
                24.0,
                my3_accent(),
            );
        }

        let close_rect = [mx + mw - 54.0, my + 16.0, 38.0, 38.0];
        icon_action_button(
            ui,
            &mut self.targets,
            close_rect,
            my3_surface_high(),
            Icon::Close,
            Action::CloseProjectModal,
        );

        let title_max = (mw - 146.0).max(100.0);
        let fitted_title = ui.fit_text(Face::SemiBold, 18.5, &modal.project.title, title_max);
        ui.text(
            Face::SemiBold,
            18.5,
            mx + 78.0,
            my + 16.0,
            my3_text(),
            &fitted_title,
        );

        let mut meta_parts = vec![format!("by {}", modal.author)];
        if modal.downloads > 0 {
            meta_parts.push(format!("{} downloads", format_downloads(modal.downloads)));
        }
        if !modal.license.is_empty() {
            meta_parts.push(modal.license.clone());
        }
        if modal.loading {
            meta_parts.push("Syncing Modrinth details...".into());
        }
        let meta_line = meta_parts.join("  •  ");
        let fitted_meta = ui.fit_text(Face::Regular, 12.5, &meta_line, title_max);
        ui.text(
            Face::Regular,
            12.5,
            mx + 78.0,
            my + 41.0,
            my3_muted(),
            &fitted_meta,
        );

        // Divider under modal header
        ui.rounded(mx + 16.0, my + 74.0, mw - 32.0, 1.0, 0.0, my3_border());

        // Scrollable Body Area (Description + Gallery Screenshots + Versions List)
        let body_top = my + 82.0;
        let body_bottom = my + mh - 62.0;
        let inner_x = mx + 18.0;
        let inner_w = mw - 36.0;

        let desc_lines =
            wrap_text_lines(ui, Face::Regular, 13.0, &modal.description, inner_w - 8.0);
        let desc_h = (desc_lines.len() as f32 * 18.5).max(24.0);
        let gallery_h = if modal.screenshots.is_empty() {
            0.0
        } else {
            118.0
        };
        let versions_h = 28.0 + (modal.versions.len().min(6) as f32) * 38.0;
        let total_content_h = 24.0 + desc_h + gallery_h + versions_h + 16.0;
        let max_scroll = (total_content_h - (body_bottom - body_top)).max(0.0);
        modal.scroll = modal.scroll.clamp(0.0, max_scroll);
        self.list_view = Some([body_top, body_bottom, max_scroll]);

        ui.set_clip(Some([
            mx + 4.0,
            body_top,
            mw - 8.0,
            (body_bottom - body_top).max(1.0),
        ]));
        let mut cy = body_top - modal.scroll;

        // Section 1: About / Description
        if cy + 20.0 >= body_top && cy <= body_bottom {
            ui.text(
                Face::SemiBold,
                14.0,
                inner_x,
                cy,
                my3_accent(),
                "About Project",
            );
        }
        cy += 22.0;
        for line in &desc_lines {
            if cy + 18.0 >= body_top && cy <= body_bottom {
                ui.text(Face::Regular, 13.0, inner_x, cy, my3_text(), line);
            }
            cy += 18.5;
        }
        cy += 8.0;

        // Section 2: Screenshots (from Modrinth gallery)
        if !modal.screenshots.is_empty() {
            if cy + 20.0 >= body_top && cy <= body_bottom {
                ui.text(
                    Face::SemiBold,
                    14.0,
                    inner_x,
                    cy,
                    my3_accent(),
                    "Screenshots",
                );
            }
            cy += 22.0;
            let count = modal.screenshots.len().min(3) as f32;
            let gap = 10.0;
            let card_w = ((inner_w - gap * (count - 1.0)) / count).clamp(110.0, 200.0);
            let card_h = 84.0;
            if cy + card_h >= body_top && cy <= body_bottom {
                for (idx, (caption, sw, sh, rgba)) in modal.screenshots.iter().take(3).enumerate() {
                    let sx = inner_x + idx as f32 * (card_w + gap);
                    ui.rounded(sx, cy, card_w, card_h, 12.0, my3_card_bg());
                    ui.border(sx, cy, card_w, card_h, 12.0, my3_card_border());
                    let key = format!("shot:{}:{idx}", modal.project.id);
                    let _ = ui.image(
                        &key,
                        *sw,
                        *sh,
                        rgba,
                        sx + 3.0,
                        cy + 3.0,
                        card_w - 6.0,
                        card_h - 22.0,
                    );
                    let cap_fit = ui.fit_text(Face::Regular, 11.0, caption, card_w - 12.0);
                    ui.text(
                        Face::Regular,
                        11.0,
                        sx + 6.0,
                        cy + card_h - 17.0,
                        my3_muted(),
                        &cap_fit,
                    );
                }
            }
            cy += card_h + 12.0;
        }

        // Section 3: Selectable Versions to Download
        if cy + 20.0 >= body_top && cy <= body_bottom {
            ui.text(
                Face::SemiBold,
                14.0,
                inner_x,
                cy,
                my3_accent(),
                "Available Versions",
            );
        }
        cy += 24.0;
        for (v_idx, ver) in modal.versions.iter().take(6).enumerate() {
            let selected = modal.selected_version == v_idx;
            let row_rect = [inner_x, cy, inner_w, 34.0];
            self.list_targets
                .push((row_rect, Action::SelectModalVersion(v_idx)));
            if cy + 34.0 >= body_top && cy <= body_bottom {
                ui.rounded(
                    inner_x,
                    cy,
                    inner_w,
                    34.0,
                    10.0,
                    if selected {
                        my3_accent_soft()
                    } else {
                        my3_card_bg()
                    },
                );
                ui.border(
                    inner_x,
                    cy,
                    inner_w,
                    34.0,
                    10.0,
                    if selected {
                        my3_accent()
                    } else {
                        my3_card_border()
                    },
                );
                let ver_title = format!(
                    "{}v{}  •  MC {}  ({})",
                    if selected { "● " } else { "○ " },
                    ver.number,
                    ver.game_versions.first().map_or("26.3", String::as_str),
                    if ver.loaders.is_empty() {
                        modal.project.source.label().to_owned()
                    } else {
                        ver.loaders.join(", ")
                    }
                );
                let fitted_v = ui.fit_text(Face::Medium, 13.0, &ver_title, inner_w - 24.0);
                ui.text(
                    Face::Medium,
                    13.0,
                    inner_x + 12.0,
                    cy + 7.0,
                    if selected { my3_text() } else { my3_muted() },
                    &fitted_v,
                );
                if let Some(c) = ui.clip_rect(row_rect) {
                    self.targets.push((c, Action::SelectModalVersion(v_idx)));
                }
            }
            cy += 38.0;
        }

        ui.set_clip(None);
        // Modal Bottom Action Bar
        ui.rounded(mx + 16.0, my + mh - 56.0, mw - 32.0, 1.0, 0.0, my3_border());

        button(
            ui,
            &mut self.targets,
            [mx + 18.0, my + mh - 48.0, 108.0, 38.0],
            "Close",
            Action::CloseProjectModal,
            false,
        );
        button(
            ui,
            &mut self.targets,
            [mx + 136.0, my + mh - 48.0, mw - 154.0, 38.0],
            &install_label,
            Action::InstallModalProject,
            true,
        );
    }

    fn draw_diagnostics_summary_modal(&mut self, ui: &mut Ui, w: f32, h: f32) {
        self.targets.clear();
        self.list_targets.clear();
        self.list_view = None;

        let modal_t = self.anim.modal_anim.clamp(0.25, 1.0);
        ui.rounded(
            0.0,
            0.0,
            w,
            h,
            0.0,
            horizon_client::hud::rgba(4, 6, 14, (198.0 * modal_t) as u8),
        );

        let mw = (w - 28.0).clamp(320.0, 560.0);
        let mh = (h - 24.0).clamp(248.0, 356.0);
        let mx = (w - mw) * 0.5;
        let my = (h - mh) * 0.5 + (1.0 - modal_t) * 10.0;

        ui.rounded(
            mx + 2.0,
            my + 6.0,
            mw,
            mh,
            22.0,
            horizon_client::hud::rgba(0, 0, 0, 175),
        );
        ui.rounded(mx, my, mw, mh, 12.0, my3_surface());
        ui.border(mx, my, mw, mh, 12.0, my3_accent());

        // Header icon & title
        ui.rounded(mx + 18.0, my + 14.0, 36.0, 36.0, 11.0, my3_accent_soft());
        ui.icon(Icon::Terminal, mx + 27.0, my + 23.0, 18.0, my3_accent());
        ui.text(
            Face::SemiBold,
            17.0,
            mx + 64.0,
            my + 13.0,
            my3_text(),
            "Diagnostics & Runtime Health Summary",
        );
        ui.text(
            Face::Regular,
            11.5,
            mx + 64.0,
            my + 33.0,
            my3_muted(),
            "Live renderer pipeline, profile state, error log & telemetry status",
        );

        ui.rounded(mx + 16.0, my + 58.0, mw - 32.0, 1.0, 0.0, my3_border());

        // 2x2 Metric Grid Cards
        let c = &self.catalog.config;
        let gx = mx + 18.0;
        let gw = mw - 36.0;
        let half_w = (gw - 10.0) * 0.5;
        let card1_y = my + 68.0;
        let card2_y = card1_y + 52.0;

        let gpu_txt = format!(
            "{}  •  {}% Scale  •  {}",
            c.renderer.label(),
            c.render_scale.percent(),
            c.device.display_label()
        );
        let prof_txt = format!(
            "Profile {}  •  UI {}  •  {}",
            c.profile + 1,
            c.ui_scale_label(),
            c.input_host.label()
        );
        let anim_txt = format!(
            "Launcher Anim: {}  •  Game Anim: {}",
            if c.disable_launcher_animations {
                "OFF"
            } else {
                "ON"
            },
            if c.disable_game_animations {
                "OFF"
            } else {
                "ON"
            }
        );
        let perf_txt = format!(
            "SuperPerf: {}  •  FastUpload: {}  •  ADPF: {}",
            if c.super_perf_mode { "ON" } else { "OFF" },
            if c.fast_chunk_upload { "ON" } else { "OFF" },
            if c.device.performance_hints {
                "ON"
            } else {
                "OFF"
            }
        );

        for (r_idx, (title, val, x_off, y_pos)) in [
            ("GRAPHICS & DISPLAY PIPELINE", gpu_txt.as_str(), gx, card1_y),
            (
                "ACTIVE PROFILE & INPUT HOST",
                prof_txt.as_str(),
                gx + half_w + 10.0,
                card1_y,
            ),
            ("ANIMATION & MOTION STATE", anim_txt.as_str(), gx, card2_y),
            (
                "PERFORMANCE & UPLOAD BOOST",
                perf_txt.as_str(),
                gx + half_w + 10.0,
                card2_y,
            ),
        ]
        .into_iter()
        .enumerate()
        {
            let _ = r_idx;
            ui.rounded(x_off, y_pos, half_w, 46.0, 12.0, my3_card_bg());
            ui.border(x_off, y_pos, half_w, 46.0, 12.0, my3_card_border());
            ui.text(
                Face::SemiBold,
                9.5,
                x_off + 10.0,
                y_pos + 6.0,
                my3_accent(),
                title,
            );
            let fit_v = ui.fit_text(Face::Medium, 11.2, val, half_w - 20.0);
            ui.text(
                Face::Medium,
                11.2,
                x_off + 10.0,
                y_pos + 23.0,
                my3_text(),
                &fit_v,
            );
        }

        // Summary & Error Log Box
        let log_y = card2_y + 54.0;
        let log_h = (mh - (log_y - my) - 60.0).max(52.0);
        ui.rounded(gx, log_y, gw, log_h, 12.0, my3_card_bg());
        ui.border(gx, log_y, gw, log_h, 12.0, my3_card_border());

        let summary_str = crate::diagnostics::summary(&self.home);
        let err_str = std::fs::read_to_string(self.home.join("android-error.txt"))
            .ok()
            .map(|e| crate::diagnostics::redact(&e))
            .unwrap_or_else(|| {
                "None — last session exited cleanly with zero unhandled errors.".into()
            });

        ui.text(
            Face::SemiBold,
            10.0,
            gx + 12.0,
            log_y + 6.0,
            my3_accent(),
            "DIAGNOSTICS SUMMARY & LAST HANDLED ERROR",
        );
        let fit_sum = ui.fit_text(Face::Regular, 11.0, &summary_str, gw - 24.0);
        ui.text(
            Face::Regular,
            11.0,
            gx + 12.0,
            log_y + 22.0,
            my3_text(),
            &fit_sum,
        );
        let fit_err = ui.fit_text(
            Face::Mono,
            10.2,
            &format!("Last Error: {err_str}"),
            gw - 24.0,
        );
        ui.text(
            Face::Mono,
            10.2,
            gx + 12.0,
            log_y + 39.0,
            my3_muted(),
            &fit_err,
        );

        // Bottom Action Buttons: Close | Open Reports | Export ZIP
        let btn_y = my + mh - 46.0;
        let bw3 = (gw - 16.0) / 3.0;
        button(
            ui,
            &mut self.targets,
            [gx, btn_y, bw3, 36.0],
            "Close",
            Action::CloseDiagnosticsSummary,
            false,
        );
        button(
            ui,
            &mut self.targets,
            [gx + bw3 + 8.0, btn_y, bw3, 36.0],
            "Open Reports",
            Action::OpenReportsFolder,
            false,
        );
        button(
            ui,
            &mut self.targets,
            [gx + (bw3 + 8.0) * 2.0, btn_y, bw3, 36.0],
            "Export ZIP",
            Action::ExportDiagnostics,
            true,
        );
    }
}
