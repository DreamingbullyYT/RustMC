impl Launcher {
    /// Material Design 3 Library & Content Manager with two top sub-views:
    /// View 1: `InstalledSubTab::Library` (Search, Category Filter Pills, Sort, Rich M3 Cards)
    /// View 2: `InstalledSubTab::FileManager` (Real filesystem manager with Back/Forward history,
    /// Breadcrumbs, Multi-select, Copy/Cut/Paste, Import, Export, Rename, New Folder, Delete, Details)
    /// Material 3 Expressive `horizon-port-pack` Native Porting & Compiler Workbench (`Page::Compiler`).
    fn draw_compiler_page(
        &mut self,
        ui: &mut Ui,
        content_x: f32,
        content_w: f32,
        h: f32,
        compact: bool,
        slide_y: f32,
    ) {
        use crate::catalog::CompilerMode;
        use crate::content_files::Kind;

        let header_y = 60.0 + slide_y;
        ui.text(
            Face::SemiBold,
            18.5,
            content_x,
            header_y + 2.0,
            my3_text(),
            if compact {
                "Port Pack Compiler"
            } else {
                "horizon-port-pack & Native Compiler"
            },
        );

        // Top-right 4-segment Material 2026 Connected Button Group for CompilerMode
        let mode_count = CompilerMode::ALL.len();
        let seg_w = if compact { 76.0 } else { 98.0 };
        let group_x =
            content_x + content_w - seg_w * (mode_count as f32) - (mode_count as f32 - 1.0) * 3.0;
        for (i, mode) in CompilerMode::ALL.into_iter().enumerate() {
            let rx = group_x + i as f32 * (seg_w + 3.0);
            let rect = [rx, header_y - 1.0, seg_w, 30.0];
            let active = self.catalog.compiler_mode == mode;
            ui.m3_connected_segment(
                rect,
                i,
                mode_count,
                mode.label(),
                active,
                Some(Icon::Terminal),
            );
            self.targets.push((rect, Action::SetCompilerMode(mode)));
        }

        // Row 1: Modrinth Slug / URL / File Path Input + "Port & Compile" / "Build Native .so" CTA + "+ Pick .jar"
        let row1_y = 96.0 + slide_y;
        let pick_w = if compact { 88.0 } else { 108.0 };
        let run_w = if compact { 98.0 } else { 122.0 };
        let field_w = (content_w - pick_w - run_w - 14.0).max(100.0);
        let hint = match self.catalog.compiler_mode {
            CompilerMode::JarMod => "Modrinth mod slug/URL (e.g. appleskin) or .jar path...",
            CompilerMode::Modpack => "Modrinth modpack slug/URL or local .mrpack path...",
            CompilerMode::DataPackUpgrade => {
                "Enter 1.21 / 26.2 data pack path to upgrade to 26.3..."
            }
            CompilerMode::NativeSoBuild => {
                "Tap 'Build Native .so' to bundle all active .hnmod/.hmod mods..."
            }
        };
        let query_r = ui.text_field(
            content_x,
            row1_y,
            field_w,
            34.0,
            "",
            &self.catalog.compiler_query,
            hint,
            self.catalog.editing == Some("compiler_query"),
        );
        self.targets
            .push((query_r, Action::Field("compiler_query")));

        button(
            ui,
            &mut self.targets,
            [content_x + field_w + 7.0, row1_y, run_w, 34.0],
            if self.catalog.compiler_mode == CompilerMode::NativeSoBuild {
                "Build Native .so"
            } else {
                "Port & Compile"
            },
            Action::CompilerRunQuery,
            true,
        );
        button(
            ui,
            &mut self.targets,
            [content_x + field_w + run_w + 14.0, row1_y, pick_w, 34.0],
            "+ Pick .jar",
            Action::Import(Kind::Mods),
            false,
        );

        // Row 2: Live `horizon-port-pack` Diagnostic & Coverage Card
        let diag_y = 136.0 + slide_y;
        let diag_h = 96.0;
        ui.rounded(content_x, diag_y, content_w, diag_h, 12.0, my3_surface());
        ui.border(content_x, diag_y, content_w, diag_h, 12.0, my3_accent());

        if let Some(ref res) = self.catalog.last_port_result {
            let verdict_str = format!("{} • {}", res.loader.to_uppercase(), res.verdict.label());
            let vw = (ui.measure(Face::SemiBold, 10.5, &verdict_str) + 18.0).clamp(72.0, 168.0);
            let vx = content_x + content_w - vw - 12.0;
            ui.rounded(vx, diag_y + 10.0, vw, 22.0, 6.0, my3_accent_soft());
            ui.border(vx, diag_y + 10.0, vw, 22.0, 6.0, my3_accent());
            ui.centered_fit(
                Face::SemiBold,
                10.0,
                [vx + 4.0, diag_y + 10.0, vw - 8.0, 22.0],
                my3_accent(),
                &verdict_str,
            );

            let hdr = format!("{} ({} v{})", res.name, res.mod_id, res.version);
            let fit_hdr = ui.fit_text(
                Face::SemiBold,
                14.5,
                &hdr,
                (content_w - vw - 36.0).max(80.0),
            );
            ui.text(
                Face::SemiBold,
                14.5,
                content_x + 14.0,
                diag_y + 8.0,
                my3_text(),
                &fit_hdr,
            );

            let fit_sum = ui.fit_text(Face::Regular, 11.5, &res.summary, content_w - 28.0);
            ui.text(
                Face::Regular,
                11.5,
                content_x + 14.0,
                diag_y + 30.0,
                my3_muted(),
                &fit_sum,
            );

            // 4 Metric Chips (Blocks, Items, 26.3 Recipes, Hooks/Classes)
            let metrics = [
                format!("Blocks: {}", res.blocks_ported),
                format!("Items: {}", res.items_ported),
                format!("26.3 Recipes: {}", res.recipes_upgraded),
                format!(
                    "Hooks: {} • Classes: {}",
                    res.mapped_hooks.len(),
                    res.classes_scanned
                ),
            ];
            let chip_gap = 6.0;
            let chip_w = ((content_w - 28.0 - chip_gap * 3.0) / 4.0).max(54.0);
            let chip_y = diag_y + 52.0;
            for (idx, m_lbl) in metrics.iter().enumerate() {
                let cx = content_x + 14.0 + idx as f32 * (chip_w + chip_gap);
                ui.rounded(cx, chip_y, chip_w, 20.0, 6.0, my3_card_bg());
                ui.border(cx, chip_y, chip_w, 20.0, 6.0, my3_card_border());
                ui.centered_fit(
                    Face::Medium,
                    10.0,
                    [cx + 3.0, chip_y, chip_w - 6.0, 20.0],
                    my3_accent(),
                    m_lbl,
                );
            }

            let log_line = self
                .catalog
                .compiler_logs
                .last()
                .map(String::as_str)
                .unwrap_or("Carried over into horizon-mods/ & horizon-native-mods/");
            let fit_log = ui.fit_text(Face::Mono, 10.2, log_line, content_w - 28.0);
            ui.text(
                Face::Mono,
                10.2,
                content_x + 14.0,
                diag_y + 76.0,
                my3_text(),
                &fit_log,
            );
        } else {
            ui.rounded(
                content_x + 14.0,
                diag_y + 12.0,
                38.0,
                38.0,
                10.0,
                my3_accent_soft(),
            );
            ui.icon(
                Icon::Terminal,
                content_x + 23.0,
                diag_y + 21.0,
                20.0,
                my3_accent(),
            );
            ui.text(
                Face::SemiBold,
                14.5,
                content_x + 62.0,
                diag_y + 10.0,
                my3_text(),
                "Native horizon-port-pack Pipeline (In-Process Android)",
            );
            let sub = "Carries over 1.21+ blocks, items, fluids, 26.3 recipes, loot tables & worldgen + synthesizes Hook API 415.";
            let fit_sub = ui.fit_text(Face::Regular, 11.5, sub, content_w - 76.0);
            ui.text(
                Face::Regular,
                11.5,
                content_x + 62.0,
                diag_y + 31.0,
                my3_muted(),
                &fit_sub,
            );

            for (i, line) in self.catalog.compiler_logs.iter().take(2).enumerate() {
                let fit_l = ui.fit_text(Face::Mono, 10.5, line, content_w - 28.0);
                ui.text(
                    Face::Mono,
                    10.5,
                    content_x + 14.0,
                    diag_y + 54.0 + i as f32 * 18.0,
                    my3_accent(),
                    &fit_l,
                );
            }
        }

        // Row 3: Scrollable Local Profile Mods & Port Queue
        let list_top = diag_y + diag_h + 8.0;
        let list_bottom = h - 42.0;
        let mod_items: Vec<(usize, crate::catalog::LocalItem)> = self
            .catalog
            .local
            .iter()
            .cloned()
            .enumerate()
            .filter(|(_, item)| item.kind == Kind::Mods)
            .collect();

        let row_h = 58.0;
        let total_h = mod_items.len() as f32 * row_h;
        let max_scroll = (total_h - (list_bottom - list_top)).max(0.0);
        self.catalog.scroll = self.catalog.scroll.clamp(0.0, max_scroll);
        self.list_view = Some([list_top, list_bottom, max_scroll]);

        ui.set_clip(Some([
            content_x - 2.0,
            list_top,
            content_w + 4.0,
            (list_bottom - list_top).max(1.0),
        ]));
        for (row_idx, (orig_idx, item)) in mod_items.into_iter().enumerate() {
            let y = list_top + row_idx as f32 * row_h - self.catalog.scroll;
            let card_h = 52.0;
            let port_btn_r = [content_x + content_w - 118.0, y + 9.0, 106.0, 34.0];
            self.list_targets
                .push((port_btn_r, Action::CompilerPortLocal(orig_idx)));
            if y + card_h < list_top || y > list_bottom {
                continue;
            }
            ui.rounded(content_x, y, content_w, card_h, 12.0, my3_card_bg());
            ui.border(content_x, y, content_w, card_h, 12.0, my3_card_border());
            ui.rounded(
                content_x + 10.0,
                y + 8.0,
                36.0,
                36.0,
                9.0,
                my3_accent_soft(),
            );
            ui.icon(Icon::Mods, content_x + 19.0, y + 17.0, 18.0, my3_accent());

            let max_tw = (content_w - 190.0).max(70.0);
            let fit_t = ui.fit_text(Face::SemiBold, 13.8, &item.title, max_tw);
            ui.text(
                Face::SemiBold,
                13.8,
                content_x + 56.0,
                y + 7.0,
                my3_text(),
                &fit_t,
            );
            let fit_s = ui.fit_text(Face::Regular, 11.0, &item.subtitle, max_tw);
            ui.text(
                Face::Regular,
                11.0,
                content_x + 56.0,
                y + 27.0,
                my3_muted(),
                &fit_s,
            );

            button(
                ui,
                &mut self.targets,
                port_btn_r,
                "Port / Inspect",
                Action::CompilerPortLocal(orig_idx),
                true,
            );
        }
        ui.set_clip(None);
    }

    fn draw_installed(
        &mut self,
        ui: &mut Ui,
        content_x: f32,
        content_w: f32,
        h: f32,
        compact: bool,
        slide_y: f32,
    ) {
        use crate::catalog::{InstalledFilter, InstalledSubTab};
        use crate::content_files::Kind;

        let header_y = 60.0 + slide_y;
        ui.text(
            Face::SemiBold,
            19.0,
            content_x,
            header_y + 2.0,
            my3_text(),
            if self.catalog.installed_sub_tab == InstalledSubTab::Library {
                "Content Library"
            } else {
                "Profile File Manager"
            },
        );

        // Top right Material 2026 Connected Button Group (Library vs File Manager)
        let tab_w = if compact { 106.0 } else { 128.0 };
        let lib_rect = [
            content_x + content_w - tab_w * 2.0 - 3.0,
            header_y - 1.0,
            tab_w,
            32.0,
        ];
        let fm_rect = [content_x + content_w - tab_w, header_y - 1.0, tab_w, 32.0];
        ui.m3_connected_segment(
            lib_rect,
            0,
            2,
            "Library",
            self.catalog.installed_sub_tab == InstalledSubTab::Library,
            Some(Icon::Installed),
        );
        self.targets.push((
            lib_rect,
            Action::SetInstalledSubTab(InstalledSubTab::Library),
        ));
        ui.m3_connected_segment(
            fm_rect,
            1,
            2,
            "File Manager",
            self.catalog.installed_sub_tab == InstalledSubTab::FileManager,
            Some(Icon::Folder),
        );
        self.targets.push((
            fm_rect,
            Action::SetInstalledSubTab(InstalledSubTab::FileManager),
        ));

        if self.catalog.installed_sub_tab == InstalledSubTab::Library {
            // Row 1: Search filter input + Sort selector + Quick Import CTA
            let row1_y = 96.0 + slide_y;
            let sort_w = if compact { 96.0 } else { 114.0 };
            let imp_w = if compact { 78.0 } else { 94.0 };
            let search_w = (content_w - sort_w - imp_w - 14.0).max(100.0);
            let search_r = ui.text_field(
                content_x,
                row1_y,
                search_w,
                34.0,
                "",
                &self.catalog.installed_query,
                "Search installed mods, packs & shaders...",
                self.catalog.editing == Some("installed_search"),
            );
            self.targets
                .push((search_r, Action::Field("installed_search")));

            let sort_r = [content_x + search_w + 7.0, row1_y, sort_w, 34.0];
            button(
                ui,
                &mut self.targets,
                sort_r,
                self.catalog.installed_sort.label(),
                Action::CycleInstalledSort,
                false,
            );

            let import_kind = match self.catalog.installed_filter {
                InstalledFilter::Mods | InstalledFilter::All => Kind::Mods,
                InstalledFilter::Packs => Kind::Packs,
                InstalledFilter::Shaders => Kind::Shaders,
            };
            let imp_r = [content_x + search_w + sort_w + 14.0, row1_y, imp_w, 34.0];
            button(
                ui,
                &mut self.targets,
                imp_r,
                "+ Import",
                Action::Import(import_kind),
                true,
            );

            // Row 2: Category filter pills (All, Mods, Resource Packs, Shaders)
            let row2_y = 136.0 + slide_y;
            let pill_gap = 6.0;
            let pill_w = ((content_w - pill_gap * 3.0) / 4.0).max(64.0);
            for (i, filter) in InstalledFilter::ALL.into_iter().enumerate() {
                let count = self
                    .catalog
                    .local
                    .iter()
                    .filter(|item| filter.matches(item.kind))
                    .count();
                let lbl = if compact && filter == InstalledFilter::Packs {
                    format!("Packs ({count})")
                } else {
                    format!("{} ({count})", filter.label())
                };
                let pr = [
                    content_x + i as f32 * (pill_w + pill_gap),
                    row2_y,
                    pill_w,
                    28.0,
                ];
                let f_icon = match filter {
                    InstalledFilter::All => Some(Icon::Installed),
                    InstalledFilter::Mods => Some(Icon::Mods),
                    InstalledFilter::Packs => Some(Icon::Packs),
                    InstalledFilter::Shaders => Some(Icon::Shaders),
                };
                ui.m3_filter_chip(pr, &lbl, self.catalog.installed_filter == filter, f_icon);
                self.targets.push((pr, Action::SetInstalledFilter(filter)));
            }

            // Filtered items list
            let q = self.catalog.installed_query.trim().to_lowercase();
            let filtered: Vec<(usize, crate::catalog::LocalItem)> = self
                .catalog
                .local
                .iter()
                .cloned()
                .enumerate()
                .filter(|(_, item)| {
                    self.catalog.installed_filter.matches(item.kind)
                        && (q.is_empty()
                            || item.title.to_lowercase().contains(&q)
                            || item.file.to_lowercase().contains(&q)
                            || item.subtitle.to_lowercase().contains(&q))
                })
                .collect();

            let top = 172.0 + slide_y;
            let bottom = h - 42.0;
            if filtered.is_empty() {
                self.list_view = None;
                let card_h = (bottom - top - 8.0).clamp(110.0, 176.0);
                ui.rounded(content_x, top, content_w, card_h, 18.0, my3_surface());
                ui.border(content_x, top, content_w, card_h, 18.0, my3_border());
                ui.rounded(
                    content_x + 20.0,
                    top + 18.0,
                    42.0,
                    42.0,
                    12.0,
                    my3_accent_soft(),
                );
                ui.icon(
                    Icon::Installed,
                    content_x + 30.0,
                    top + 28.0,
                    22.0,
                    my3_accent(),
                );
                ui.text(
                    Face::SemiBold,
                    16.0,
                    content_x + 74.0,
                    top + 18.0,
                    my3_text(),
                    if self.catalog.local.is_empty() {
                        "No local add-ons installed yet"
                    } else {
                        "No installed add-ons match your filter"
                    },
                );
                ui.hint_line(
                    content_x + 74.0,
                    top + 42.0,
                    (content_w - 92.0).max(80.0),
                    "Import .hmod/.jar mods, .zip resource packs, or shader packs — or browse files in File Manager.",
                    my3_muted(),
                );
                let cta_y = top + card_h - 46.0;
                let cta_w = ((content_w - 56.0) / 3.0).clamp(88.0, 168.0);
                button(
                    ui,
                    &mut self.targets,
                    [content_x + 20.0, cta_y, cta_w, 34.0],
                    "Import Mod",
                    Action::Import(Kind::Mods),
                    true,
                );
                button(
                    ui,
                    &mut self.targets,
                    [content_x + 28.0 + cta_w, cta_y, cta_w, 34.0],
                    "Import Pack",
                    Action::Import(Kind::Packs),
                    false,
                );
                button(
                    ui,
                    &mut self.targets,
                    [content_x + 36.0 + cta_w * 2.0, cta_y, cta_w, 34.0],
                    "File Manager",
                    Action::SetInstalledSubTab(InstalledSubTab::FileManager),
                    false,
                );
                return;
            }

            let row_h = 68.0;
            let total_h = filtered.len() as f32 * row_h;
            let max_scroll = (total_h - (bottom - top)).max(0.0);
            self.catalog.scroll = self.catalog.scroll.clamp(0.0, max_scroll);
            self.list_view = Some([top, bottom, max_scroll]);

            ui.set_clip(Some([
                content_x - 2.0,
                top,
                content_w + 4.0,
                (bottom - top).max(1.0),
            ]));
            for (row_idx, (orig_idx, item)) in filtered.into_iter().enumerate() {
                let y = top + row_idx as f32 * row_h - self.catalog.scroll;
                let card_h = 62.0;
                let toggle_r = [content_x + content_w - 196.0, y + 12.0, 56.0, 38.0];
                let export_r = [content_x + content_w - 132.0, y + 14.0, 58.0, 34.0];
                let delete_r = [content_x + content_w - 68.0, y + 14.0, 58.0, 34.0];
                self.list_targets
                    .push((toggle_r, Action::ToggleLocal(orig_idx)));
                self.list_targets
                    .push((export_r, Action::ExportLocal(orig_idx)));
                self.list_targets
                    .push((delete_r, Action::DeleteLocal(orig_idx)));

                if y + card_h < top || y > bottom {
                    continue;
                }
                ui.rounded(content_x, y, content_w, card_h, 14.0, my3_card_bg());
                ui.border(
                    content_x,
                    y,
                    content_w,
                    card_h,
                    14.0,
                    if item.enabled {
                        my3_accent()
                    } else {
                        my3_card_border()
                    },
                );

                // Left project/mod icon or centered vector icon inside 44x44dp box
                let kind_icon = match item.kind {
                    Kind::Mods => Icon::Mods,
                    Kind::Packs => Icon::Packs,
                    Kind::Shaders => Icon::Shaders,
                };
                let raw_stem = item
                    .file
                    .strip_prefix("horizon-native-mods/")
                    .or_else(|| item.file.strip_prefix("horizon-mods/"))
                    .or_else(|| item.file.strip_prefix("builtin:"))
                    .unwrap_or(&item.file);
                let raw_stem = raw_stem
                    .strip_suffix(".hnmod")
                    .or_else(|| raw_stem.strip_suffix(".hmod"))
                    .or_else(|| raw_stem.strip_suffix(".jar"))
                    .or_else(|| raw_stem.strip_suffix(".hshader"))
                    .or_else(|| raw_stem.strip_suffix(".zip"))
                    .unwrap_or(raw_stem);
                let canon_icon_id = crate::jar_transpiler::sanitize_mod_id(raw_stem);
                let icon_entry = self
                    .catalog
                    .icons
                    .get(&item.file)
                    .map(|v| (item.file.as_str(), v))
                    .or_else(|| self.catalog.icons.get(raw_stem).map(|v| (raw_stem, v)))
                    .or_else(|| {
                        self.catalog
                            .icons
                            .get(&canon_icon_id)
                            .map(|v| (canon_icon_id.as_str(), v))
                    });
                let drawn_img = if let Some((ik, (iw, ih, rgba))) = icon_entry {
                    ui.image(ik, *iw, *ih, rgba, content_x + 10.0, y + 9.0, 44.0, 44.0)
                } else {
                    false
                };
                if !drawn_img {
                    ui.rounded(
                        content_x + 10.0,
                        y + 9.0,
                        44.0,
                        44.0,
                        12.0,
                        if item.enabled {
                            my3_accent_soft()
                        } else {
                            my3_surface_high()
                        },
                    );
                    ui.icon(
                        kind_icon,
                        content_x + 21.0,
                        y + 20.0,
                        22.0,
                        if item.enabled {
                            my3_accent()
                        } else {
                            my3_muted()
                        },
                    );
                }

                // Status badge pill ("ACTIVE" vs "OFF")
                let status_str = if item.enabled { "ACTIVE" } else { "OFF" };
                let status_w = if item.enabled { 48.0 } else { 34.0 };
                let text_x = content_x + 64.0;
                let max_title_w = (content_w - 280.0 - status_w).max(60.0);
                let fitted_title = ui.fit_text(Face::SemiBold, 14.5, &item.title, max_title_w);
                let title_w = ui.text(
                    Face::SemiBold,
                    14.5,
                    text_x,
                    y + 9.0,
                    my3_text(),
                    &fitted_title,
                );
                let badge_x = text_x + title_w + 8.0;
                ui.rounded(
                    badge_x,
                    y + 11.0,
                    status_w,
                    18.0,
                    9.0,
                    if item.enabled {
                        my3_accent_soft()
                    } else {
                        my3_surface_high()
                    },
                );
                ui.centered_fit(
                    Face::SemiBold,
                    8.5,
                    [badge_x, y + 11.0, status_w, 18.0],
                    if item.enabled {
                        my3_accent()
                    } else {
                        my3_muted()
                    },
                    status_str,
                );

                ui.text_clipped(
                    Face::Regular,
                    11.5,
                    text_x,
                    y + 34.0,
                    (content_w - 270.0).max(70.0),
                    my3_muted(),
                    &item.subtitle,
                );

                // Enable/Disable M3 switch + Export & Delete buttons
                let switch_t =
                    self.anim
                        .step_switch(&format!("local_{}", item.file), item.enabled, 0.016);
                ui.m3_switch_animated(toggle_r[0] + 2.0, y + 17.0, switch_t);
                if let Some(c) = ui.clip_rect(toggle_r) {
                    self.targets.push((c, Action::ToggleLocal(orig_idx)));
                }
                button(
                    ui,
                    &mut self.targets,
                    export_r,
                    "Export",
                    Action::ExportLocal(orig_idx),
                    false,
                );
                button(
                    ui,
                    &mut self.targets,
                    delete_r,
                    "Delete",
                    Action::DeleteLocal(orig_idx),
                    false,
                );
            }
            ui.set_clip(None);
            return;
        }

        // ---- Sub-View 2: Integrated Mini File Manager (`InstalledSubTab::FileManager`) ----
        // Row 1: Back, Forward, Up + Clickable Breadcrumb Trail + Import & New Folder CTAs
        let nav_y = 94.0 + slide_y;
        let nav_btn_w = 32.0;
        let has_back = !self.catalog.fm_back_stack.is_empty();
        let has_fwd = !self.catalog.fm_forward_stack.is_empty();
        let has_up = !self.catalog.fm_current_dir.is_empty();
        button(
            ui,
            &mut self.targets,
            [content_x, nav_y, nav_btn_w, 30.0],
            "←",
            Action::FmBack,
            has_back,
        );
        button(
            ui,
            &mut self.targets,
            [content_x + 36.0, nav_y, nav_btn_w, 30.0],
            "→",
            Action::FmForward,
            has_fwd,
        );
        button(
            ui,
            &mut self.targets,
            [content_x + 72.0, nav_y, nav_btn_w, 30.0],
            "↑",
            Action::FmUp,
            has_up,
        );

        let right_cta_w = if compact { 256.0 } else { 296.0 };
        let imp_btn_w = (right_cta_w - 8.0) * 0.26;
        let imp_fld_w = (right_cta_w - 8.0) * 0.46;
        let new_folder_w = right_cta_w - 8.0 - imp_btn_w - imp_fld_w;
        let btn1_x = content_x + content_w - right_cta_w;
        let btn2_x = btn1_x + imp_btn_w + 4.0;
        let btn3_x = btn2_x + imp_fld_w + 4.0;
        button(
            ui,
            &mut self.targets,
            [btn1_x, nav_y, imp_btn_w, 30.0],
            "Import",
            Action::FmImportHere,
            false,
        );
        button(
            ui,
            &mut self.targets,
            [btn2_x, nav_y, imp_fld_w, 30.0],
            "Import Folder",
            Action::FmImportFolderHere,
            true,
        );
        button(
            ui,
            &mut self.targets,
            [btn3_x, nav_y, new_folder_w, 30.0],
            "+ Folder",
            Action::FmOpenNewFolder,
            false,
        );

        // Clickable Breadcrumb Bar between Up button and Right CTAs
        let bc_x = content_x + 110.0;
        let bc_w = (content_x + content_w - right_cta_w - 8.0 - bc_x).max(60.0);
        ui.rounded(bc_x, nav_y, bc_w, 30.0, 10.0, my3_surface());
        ui.border(bc_x, nav_y, bc_w, 30.0, 10.0, my3_border());
        let mut cur_bx = bc_x + 6.0;
        let root_lbl = format!("P{}", self.catalog.config.profile + 1);
        let root_bw = (ui.measure(Face::SemiBold, 11.5, &root_lbl) + 12.0).min(bc_w - 10.0);
        let root_br = [cur_bx, nav_y + 4.0, root_bw, 22.0];
        ui.rounded(
            root_br[0],
            root_br[1],
            root_br[2],
            root_br[3],
            6.0,
            if self.catalog.fm_current_dir.is_empty() {
                my3_accent_soft()
            } else {
                my3_surface_high()
            },
        );
        ui.centered_fit(Face::SemiBold, 11.0, root_br, my3_accent(), &root_lbl);
        self.targets.push((root_br, Action::FmBreadcrumb(0)));
        cur_bx += root_bw + 4.0;

        if !self.catalog.fm_current_dir.is_empty() {
            let parts: Vec<&str> = self
                .catalog
                .fm_current_dir
                .split('/')
                .filter(|s| !s.is_empty())
                .collect();
            for (idx, seg) in parts.iter().enumerate() {
                if cur_bx + 28.0 > bc_x + bc_w {
                    break;
                }
                ui.text(Face::Regular, 11.0, cur_bx, nav_y + 7.0, my3_muted(), "/");
                cur_bx += 8.0;
                let avail = (bc_x + bc_w - 6.0 - cur_bx).max(24.0);
                let fit_seg = ui.fit_text(Face::Medium, 11.0, seg, (avail - 10.0).max(16.0));
                let seg_w = (ui.measure(Face::Medium, 11.0, &fit_seg) + 10.0).min(avail);
                let seg_r = [cur_bx, nav_y + 4.0, seg_w, 22.0];
                let is_last = idx + 1 == parts.len();
                ui.rounded(
                    seg_r[0],
                    seg_r[1],
                    seg_r[2],
                    seg_r[3],
                    6.0,
                    if is_last {
                        my3_accent_soft()
                    } else {
                        my3_surface_high()
                    },
                );
                ui.centered_fit(
                    Face::Medium,
                    11.0,
                    seg_r,
                    if is_last { my3_text() } else { my3_muted() },
                    &fit_seg,
                );
                self.targets.push((seg_r, Action::FmBreadcrumb(idx + 1)));
                cur_bx += seg_w + 4.0;
            }
        }

        // Row 2: Quick Root Folder Pills (Root, Worlds, Mods, Packs, Shaders, Controls, Shots, Reports)
        let root_y = 128.0 + slide_y;
        let root_dirs = crate::document_store::FM_ROOT_DIRS;
        let r_gap = 4.0;
        let r_w = ((content_w - r_gap * (root_dirs.len() as f32 - 1.0)) / root_dirs.len() as f32)
            .max(38.0);
        for (i, (rel, label)) in root_dirs.iter().enumerate() {
            let rx = content_x + i as f32 * (r_w + r_gap);
            let active = self.catalog.fm_current_dir == *rel
                || (!rel.is_empty()
                    && self
                        .catalog
                        .fm_current_dir
                        .strip_prefix(*rel)
                        .is_some_and(|rest| rest.starts_with('/')));
            button(
                ui,
                &mut self.targets,
                [rx, root_y, r_w, 26.0],
                label,
                Action::FmNavigateRoot(rel),
                active,
            );
        }

        // Row 3: Multi-Select & Clipboard Action Toolbar
        let tool_y = 158.0 + slide_y;
        let sel_count = self.catalog.fm_selected.len();
        let clip_count = self
            .catalog
            .fm_clipboard
            .as_ref()
            .map_or(0, |(v, _)| v.len());
        let paste_label = if clip_count > 0 {
            format!("Paste ({clip_count})")
        } else {
            "Paste".into()
        };
        let del_label = if sel_count > 0 {
            format!("Delete ({sel_count})")
        } else {
            "Delete".into()
        };
        let has_dir_sel = self.catalog.fm_selected.iter().any(|rel| {
            self.catalog
                .fm_entries
                .iter()
                .any(|e| e.is_dir && &e.rel_path == rel)
        });
        let export_lbl = if has_dir_sel {
            "Export Folder".to_string()
        } else {
            "Export".to_string()
        };
        let tools: [(&str, Action, bool); 8] = [
            ("All", Action::FmSelectAll, false),
            ("Clear", Action::FmClearSelection, false),
            ("Copy", Action::FmCopy, sel_count > 0),
            ("Cut", Action::FmCut, sel_count > 0),
            (&paste_label, Action::FmPaste, clip_count > 0),
            ("Rename", Action::FmOpenRenameSelected, sel_count == 1),
            (&export_lbl, Action::FmExportSelected, sel_count > 0),
            (&del_label, Action::FmRequestDeleteSelected, sel_count > 0),
        ];
        let t_gap = 4.0;
        let t_w = ((content_w - t_gap * (tools.len() as f32 - 1.0)) / tools.len() as f32).max(38.0);
        for (i, (lbl, act, hi)) in tools.into_iter().enumerate() {
            let tx = content_x + i as f32 * (t_w + t_gap);
            button(ui, &mut self.targets, [tx, tool_y, t_w, 26.0], lbl, act, hi);
        }

        // Scrollable File & Directory Rows
        let top = 190.0 + slide_y;
        let bottom = h - 42.0;
        if self.catalog.fm_entries.is_empty() {
            self.list_view = None;
            let empty_h = (bottom - top - 8.0).clamp(96.0, 152.0);
            ui.rounded(content_x, top, content_w, empty_h, 16.0, my3_surface());
            ui.border(content_x, top, content_w, empty_h, 16.0, my3_border());
            ui.icon(
                Icon::Folder,
                content_x + 20.0,
                top + 20.0,
                24.0,
                my3_accent(),
            );
            ui.text(
                Face::SemiBold,
                15.0,
                content_x + 54.0,
                top + 18.0,
                my3_text(),
                "This folder is empty",
            );
            ui.hint_line(
                content_x + 54.0,
                top + 40.0,
                (content_w - 72.0).max(80.0),
                "Use Import to copy files from Android storage, + Folder to create a directory, or Paste from clipboard.",
                my3_muted(),
            );
            return;
        }

        let row_h = 52.0;
        let total_h = self.catalog.fm_entries.len() as f32 * row_h;
        let max_scroll = (total_h - (bottom - top)).max(0.0);
        self.catalog.scroll = self.catalog.scroll.clamp(0.0, max_scroll);
        self.list_view = Some([top, bottom, max_scroll]);

        ui.set_clip(Some([
            content_x - 2.0,
            top,
            content_w + 4.0,
            (bottom - top).max(1.0),
        ]));
        let entries_snapshot = self.catalog.fm_entries.clone();
        for (idx, entry) in entries_snapshot.iter().enumerate() {
            let y = top + idx as f32 * row_h - self.catalog.scroll;
            let card_h = 46.0;
            let selected = self.catalog.fm_selected.contains(&entry.rel_path);
            let check_r = [content_x + 6.0, y + 7.0, 32.0, 32.0];
            let actions_w = if compact { 256.0 } else { 304.0 };
            let open_r = [
                content_x + 40.0,
                y,
                (content_w - 44.0 - actions_w).max(60.0),
                card_h,
            ];
            let btn_w = (actions_w - 12.0) / 4.0;
            let exp_r = [content_x + content_w - actions_w, y + 8.0, btn_w, 30.0];
            let info_r = [exp_r[0] + btn_w + 4.0, y + 8.0, btn_w, 30.0];
            let ren_r = [info_r[0] + btn_w + 4.0, y + 8.0, btn_w, 30.0];
            let del_r = [ren_r[0] + btn_w + 4.0, y + 8.0, btn_w, 30.0];

            self.list_targets.push((open_r, Action::FmOpenEntry(idx)));
            self.list_targets
                .push((check_r, Action::FmToggleSelect(idx)));
            self.list_targets.push((exp_r, Action::FmExportSingle(idx)));
            self.list_targets.push((info_r, Action::FmOpenDetails(idx)));
            self.list_targets.push((ren_r, Action::FmOpenRename(idx)));
            self.list_targets
                .push((del_r, Action::FmRequestDeleteSingle(idx)));

            if y + card_h < top || y > bottom {
                continue;
            }
            ui.rounded(
                content_x,
                y,
                content_w,
                card_h,
                12.0,
                if selected {
                    my3_nav_active()
                } else {
                    my3_card_bg()
                },
            );
            ui.border(
                content_x,
                y,
                content_w,
                card_h,
                12.0,
                if selected {
                    my3_accent()
                } else {
                    my3_card_border()
                },
            );

            ui.m3_checkbox(content_x + 11.0, y + 13.0, 20.0, selected, "");
            if let Some(c) = ui.clip_rect(check_r) {
                self.targets.push((c, Action::FmToggleSelect(idx)));
            }

            let entry_icon = if entry.is_dir {
                Icon::Folder
            } else if entry.name.ends_with(".hmod")
                || entry.name.ends_with(".hnmod")
                || entry.name.ends_with(".jar")
                || entry.name.ends_with(".mod.json")
            {
                Icon::Mods
            } else if entry.name.ends_with(".hshader") {
                Icon::Shaders
            } else if entry.name.ends_with(".zip") {
                Icon::Packs
            } else {
                Icon::FileText
            };
            ui.rounded(
                content_x + 38.0,
                y + 7.0,
                32.0,
                32.0,
                9.0,
                if entry.is_dir {
                    my3_accent_soft()
                } else {
                    my3_surface_high()
                },
            );
            ui.icon(
                entry_icon,
                content_x + 46.0,
                y + 15.0,
                16.0,
                if entry.is_dir {
                    my3_accent()
                } else {
                    my3_text()
                },
            );

            let tx = content_x + 78.0;
            let max_tw = (content_w - 86.0 - actions_w).max(50.0);
            ui.text_clipped(
                Face::SemiBold,
                13.5,
                tx,
                y + 5.0,
                max_tw,
                my3_text(),
                &entry.name,
            );
            let meta_line = if entry.is_dir {
                format!("Folder  •  {} item(s)  •  Tap to open", entry.size_bytes)
            } else {
                format!(
                    "{}  •  {}  •  {}",
                    entry.kind_label,
                    crate::catalog::format_file_size(entry.size_bytes),
                    crate::document_store::format_timestamp_utc(entry.modified_secs)
                )
            };
            ui.text_clipped(
                Face::Regular,
                10.5,
                tx,
                y + 24.0,
                max_tw,
                my3_muted(),
                &meta_line,
            );
            if let Some(c) = ui.clip_rect(open_r) {
                self.targets.push((c, Action::FmOpenEntry(idx)));
            }

            button(
                ui,
                &mut self.targets,
                exp_r,
                "Export",
                Action::FmExportSingle(idx),
                false,
            );
            button(
                ui,
                &mut self.targets,
                info_r,
                "Details",
                Action::FmOpenDetails(idx),
                false,
            );
            button(
                ui,
                &mut self.targets,
                ren_r,
                "Rename",
                Action::FmOpenRename(idx),
                false,
            );
            button(
                ui,
                &mut self.targets,
                del_r,
                "Delete",
                Action::FmRequestDeleteSingle(idx),
                false,
            );
        }
        ui.set_clip(None);
    }

    /// Modal dialogs for the Integrated Mini File Manager:
    /// - Mandatory Delete Confirmation (`FmModal::ConfirmDelete`)
    /// - Rename with `Ui::text_field` & path-traversal validation (`FmModal::Rename`)
    /// - Create New Folder with `Ui::text_field` (`FmModal::NewFolder`)
    /// - View File Details with metadata & read-only text preview (`FmModal::Details`)
    fn draw_fm_modal(&mut self, ui: &mut Ui, w: f32, h: f32, ime_top_dp: Option<f32>) {
        let Some(modal) = self.catalog.fm_modal.clone() else {
            return;
        };
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
            horizon_client::hud::rgba(6, 5, 12, (185.0 * modal_t) as u8),
        );

        match modal {
            crate::catalog::FmModal::ConfirmDelete { paths } => {
                let mw = (w - 32.0).min(420.0);
                let mh = 190.0;
                let mx = (w - mw) * 0.5;
                let my = (h - mh) * 0.5;
                ui.rounded(mx, my, mw, mh, 12.0, my3_surface());
                ui.border(mx, my, mw, mh, 12.0, my3_border());
                ui.icon(Icon::Trash, mx + 20.0, my + 20.0, 22.0, my3_accent());
                let title = if paths.len() == 1 {
                    "Delete selected item?".to_owned()
                } else {
                    format!("Delete {} selected items?", paths.len())
                };
                ui.text(
                    Face::SemiBold,
                    18.0,
                    mx + 50.0,
                    my + 18.0,
                    my3_text(),
                    &title,
                );
                let preview_names = paths.iter().take(3).cloned().collect::<Vec<_>>().join(", ");
                ui.text_clipped(
                    Face::Medium,
                    12.5,
                    mx + 20.0,
                    my + 54.0,
                    mw - 40.0,
                    my3_accent(),
                    &preview_names,
                );
                ui.hint_line(
                    mx + 20.0,
                    my + 80.0,
                    mw - 40.0,
                    "This permanently deletes the selected file(s) from this profile and cannot be undone.",
                    my3_muted(),
                );
                let bw = (mw - 52.0) * 0.5;
                button(
                    ui,
                    &mut self.targets,
                    [mx + 20.0, my + mh - 54.0, bw, 38.0],
                    "Cancel",
                    Action::FmCloseModal,
                    false,
                );
                button(
                    ui,
                    &mut self.targets,
                    [mx + 32.0 + bw, my + mh - 54.0, bw, 38.0],
                    "Confirm Delete",
                    Action::FmConfirmDelete,
                    true,
                );
            }
            crate::catalog::FmModal::Rename { target_rel } => {
                let mw = (w - 32.0).min(430.0);
                let mh = 196.0;
                let mx = (w - mw) * 0.5;
                let mut my = (h - mh) * 0.5;
                if let Some(ime_y) = ime_top_dp {
                    my = my.min((ime_y - mh - 8.0).max(6.0));
                }
                ui.rounded(mx, my, mw, mh, 12.0, my3_surface());
                ui.border(mx, my, mw, mh, 12.0, my3_border());
                ui.text(
                    Face::SemiBold,
                    18.0,
                    mx + 20.0,
                    my + 16.0,
                    my3_text(),
                    "Rename File or Folder",
                );
                ui.hint_line(
                    mx + 20.0,
                    my + 42.0,
                    mw - 40.0,
                    &format!("Target: /{target_rel} (Slashes and '..' are rejected)"),
                    my3_muted(),
                );
                let field_r = ui.text_field(
                    mx + 20.0,
                    my + 68.0,
                    mw - 40.0,
                    48.0,
                    "NEW NAME",
                    &self.catalog.fm_input_text,
                    "Enter new filename",
                    self.catalog.editing == Some("fm_rename"),
                );
                self.targets.push((field_r, Action::Field("fm_rename")));
                let bw = (mw - 52.0) * 0.5;
                button(
                    ui,
                    &mut self.targets,
                    [mx + 20.0, my + mh - 52.0, bw, 38.0],
                    "Cancel",
                    Action::FmCloseModal,
                    false,
                );
                button(
                    ui,
                    &mut self.targets,
                    [mx + 32.0 + bw, my + mh - 52.0, bw, 38.0],
                    "Save Name",
                    Action::FmSubmitRename,
                    true,
                );
            }
            crate::catalog::FmModal::NewFolder => {
                let mw = (w - 32.0).min(430.0);
                let mh = 196.0;
                let mx = (w - mw) * 0.5;
                let mut my = (h - mh) * 0.5;
                if let Some(ime_y) = ime_top_dp {
                    my = my.min((ime_y - mh - 8.0).max(6.0));
                }
                ui.rounded(mx, my, mw, mh, 12.0, my3_surface());
                ui.border(mx, my, mw, mh, 12.0, my3_border());
                ui.text(
                    Face::SemiBold,
                    18.0,
                    mx + 20.0,
                    my + 16.0,
                    my3_text(),
                    "Create New Folder",
                );
                let loc = if self.catalog.fm_current_dir.is_empty() {
                    "/".to_owned()
                } else {
                    format!("/{}", self.catalog.fm_current_dir)
                };
                ui.hint_line(
                    mx + 20.0,
                    my + 42.0,
                    mw - 40.0,
                    &format!("Location: {loc}"),
                    my3_muted(),
                );
                let field_r = ui.text_field(
                    mx + 20.0,
                    my + 68.0,
                    mw - 40.0,
                    48.0,
                    "FOLDER NAME",
                    &self.catalog.fm_input_text,
                    "Enter folder name",
                    self.catalog.editing == Some("fm_new_folder"),
                );
                self.targets.push((field_r, Action::Field("fm_new_folder")));
                let bw = (mw - 52.0) * 0.5;
                button(
                    ui,
                    &mut self.targets,
                    [mx + 20.0, my + mh - 52.0, bw, 38.0],
                    "Cancel",
                    Action::FmCloseModal,
                    false,
                );
                button(
                    ui,
                    &mut self.targets,
                    [mx + 32.0 + bw, my + mh - 52.0, bw, 38.0],
                    "Create Folder",
                    Action::FmSubmitNewFolder,
                    true,
                );
            }
            crate::catalog::FmModal::Details(details) => {
                let mw = (w - 36.0).min(560.0);
                let mh = (h - 32.0).min(340.0);
                let mx = (w - mw) * 0.5;
                let my = (h - mh) * 0.5;
                ui.rounded(mx, my, mw, mh, 12.0, my3_surface());
                ui.border(mx, my, mw, mh, 12.0, my3_border());
                ui.text_clipped(
                    Face::SemiBold,
                    17.5,
                    mx + 20.0,
                    my + 14.0,
                    mw - 80.0,
                    my3_text(),
                    &details.name,
                );
                let close_r = [mx + mw - 50.0, my + 12.0, 34.0, 34.0];
                icon_action_button(
                    ui,
                    &mut self.targets,
                    close_r,
                    my3_surface_high(),
                    Icon::Close,
                    Action::FmCloseModal,
                );

                let meta_summary = format!(
                    "Path: /{}  •  Kind: {}  •  Size: {} ({} bytes)  •  Modified: {}",
                    details.rel_path,
                    details.kind_label,
                    crate::catalog::format_file_size(details.size_bytes),
                    details.size_bytes,
                    details.modified_label
                );
                ui.hint_line(mx + 20.0, my + 40.0, mw - 40.0, &meta_summary, my3_accent());
                ui.rounded(mx + 18.0, my + 62.0, mw - 36.0, 1.0, 0.0, my3_border());

                let box_y = my + 70.0;
                let box_h = (mh - 126.0).max(60.0);
                ui.rounded(mx + 18.0, box_y, mw - 36.0, box_h, 12.0, my3_card_bg());
                ui.border(mx + 18.0, box_y, mw - 36.0, box_h, 12.0, my3_card_border());
                ui.set_clip(Some([mx + 22.0, box_y + 4.0, mw - 44.0, box_h - 8.0]));
                for (i, line) in details.preview_lines.iter().enumerate() {
                    let ly = box_y + 8.0 + i as f32 * 16.0;
                    if ly + 14.0 > box_y + box_h {
                        break;
                    }
                    ui.text_clipped(Face::Mono, 10.5, mx + 26.0, ly, mw - 52.0, my3_text(), line);
                }
                ui.set_clip(None);

                let btn_y = my + mh - 46.0;
                if let Some(entry_idx) = self
                    .catalog
                    .fm_entries
                    .iter()
                    .position(|e| e.rel_path == details.rel_path)
                {
                    let bw = (mw - 56.0) / 4.0;
                    button(
                        ui,
                        &mut self.targets,
                        [mx + 18.0, btn_y, bw, 34.0],
                        "Rename",
                        Action::FmOpenRename(entry_idx),
                        false,
                    );
                    button(
                        ui,
                        &mut self.targets,
                        [mx + 24.0 + bw, btn_y, bw, 34.0],
                        "Export",
                        Action::FmExportSingle(entry_idx),
                        false,
                    );
                    button(
                        ui,
                        &mut self.targets,
                        [mx + 30.0 + bw * 2.0, btn_y, bw, 34.0],
                        "Delete",
                        Action::FmRequestDeleteSingle(entry_idx),
                        false,
                    );
                    button(
                        ui,
                        &mut self.targets,
                        [mx + 36.0 + bw * 3.0, btn_y, bw, 34.0],
                        "Done",
                        Action::FmCloseModal,
                        true,
                    );
                } else {
                    button(
                        ui,
                        &mut self.targets,
                        [mx + mw - 128.0, btn_y, 110.0, 34.0],
                        "Close",
                        Action::FmCloseModal,
                        true,
                    );
                }
            }
        }
    }
}
