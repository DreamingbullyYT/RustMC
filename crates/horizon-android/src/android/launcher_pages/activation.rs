impl Launcher {
    pub(super) fn activate(&mut self, events: &ActiveEventLoop) -> Result<()> {
        if self.session_worker.is_some() && self.chooser.is_none() && !self.exit_confirmation {
            return Ok(());
        }
        let (Some(r), Some(gui)) = (&self.renderer, &self.gui) else {
            return Ok(());
        };
        let mut b = HudBatch::new([r.config.width, r.config.height]);
        let ui = Ui::mobile(&gui.atlas, &mut b, (0.0, 0.0));
        let p = self.focus.and_then(|index| self.targets.get(index)).map_or(
            [
                self.cursor.0 as f32 / ui.density,
                self.cursor.1 as f32 / ui.density,
            ],
            |([x, y, w, h], _)| [x + w / 2.0, y + h / 2.0],
        );
        let action = self
            .targets
            .iter()
            .rev()
            .find(|([x, y, w, h], _)| {
                (*x..*x + *w).contains(&p[0]) && (*y..*y + *h).contains(&p[1])
            })
            .map(|(_, a)| *a);
        if crate::documents::busy() {
            return Ok(());
        }
        if self.catalog.busy()
            && !matches!(
                action,
                Some(
                    Action::PauseCatalogDownload
                        | Action::CancelCatalogDownload
                        | Action::Page(_)
                        | Action::Account
                )
            )
        {
            return Ok(());
        }
        self.feedback = action.map(|action| (action, std::time::Instant::now()));
        self.focus = None;
        if action.is_some() && crate::official_install::ready(&self.home) {
            let audio = self.audio.get_or_insert_with(|| {
                let mut audio = horizon_client::audio::Audio::new(
                    &self.home.join(".local/horizon-client/26.3"),
                );
                let settings = horizon_client::settings::Settings::load(
                    &self
                        .catalog
                        .config
                        .game_home(&self.home)
                        .join("options.txt"),
                );
                audio.master = settings.volumes[0];
                let mut volumes = settings.volumes;
                volumes[0] = 1.0;
                horizon_client::settings::set_source_volumes(volumes);
                audio
            });
            audio.play_button_click();
        }
        match action {
            Some(Action::PauseInstall) => {
                let next = !self.install_paused.load(Ordering::Relaxed);
                self.install_paused.store(next, Ordering::Relaxed);
            }
            Some(Action::CancelInstall) => {
                self.install_cancelled.store(true, Ordering::Relaxed);
                self.install_paused.store(false, Ordering::Relaxed);
                self.worker = None;
                self.catalog.status = "Game preparation cancelled.".into();
            }
            Some(Action::PauseCatalogDownload) => {
                self.catalog.toggle_pause();
            }
            Some(Action::CancelCatalogDownload) => {
                self.catalog.cancel_download();
            }
            Some(Action::ConfirmExit) => {
                events.exit();
            }
            Some(Action::SettingsCat(cat)) => {
                self.catalog.settings_category = cat;
                self.catalog.scroll = 0.0;
                self.anim.trigger_page_transition();
            }
            Some(Action::OpenProject(index)) => {
                if let Some(project) = self.catalog.results.get(index).cloned() {
                    self.catalog
                        .open_project_modal(&self.home, project, None, Some(index));
                }
            }
            Some(Action::OpenBundledProject(id)) => {
                if let Some((_, title, desc)) = crate::ANDROID_BUNDLED
                    .iter()
                    .find(|(bid, _, _)| *bid == id)
                {
                    let project = crate::browser::Project {
                        source: crate::browser::SourceId::Horizon,
                        id: (*id).to_owned(),
                        slug: (*id).to_owned(),
                        title: (*title).to_owned(),
                        summary: (*desc).to_owned(),
                        author: "Horizon Built-in".into(),
                        downloads: 0,
                        icon: None,
                        page: String::new(),
                        kind: crate::browser::Kind::Mod,
                        license: "MIT / Apache-2.0".into(),
                        game_versions: vec![crate::browser::GAME.into()],
                        loaders: vec!["horizon".into()],
                    };
                    self.catalog
                        .open_project_modal(&self.home, project, Some(id), None);
                }
            }
            Some(Action::CloseProjectModal) => {
                self.catalog.project_modal = None;
                self.catalog.modal_worker = None;
            }
            Some(Action::SelectModalVersion(v_idx)) => {
                if let Some(modal) = &mut self.catalog.project_modal {
                    modal.selected_version = v_idx;
                }
            }
            Some(Action::InstallModalProject) => {
                if let Some(modal) = self.catalog.project_modal.take() {
                    self.catalog.modal_worker = None;
                    if let Some(id) = modal.bundled_id {
                        self.toggle_bundled_mod(id)?;
                    } else if let Some(idx) = modal.catalog_index {
                        self.catalog.install(&self.home, idx);
                    }
                }
            }
            Some(Action::UiScale) => self.open_chooser(chooser::Kind::UiScale),
            Some(Action::ChooseUiScale(pct)) => {
                self.catalog.config.ui_scale_percent = pct;
                crate::ui_theme::set_ui_scale_percent(pct);
                self.catalog.config.save(&self.home)?;
                self.chooser = None;
                self.catalog.status = format!(
                    "Launcher UI scale set to {}.",
                    self.catalog.config.ui_scale_label()
                );
            }
            // Video settings mutations
            Some(Action::RenderScaleDelta(delta)) => {
                let next_pct = (self.catalog.config.render_scale.percent() as i32 + delta)
                    .clamp(25, 100) as u32;
                let scale = crate::display::RenderScale::new(next_pct);
                self.catalog.config.render_scale = scale;
                let _ = self.catalog.config.save(&self.home);
                crate::display::set_scale(scale);
                self.modify_game_settings(|s| {
                    s.horizon.resolution_scale = next_pct;
                });
                self.modify_touch_settings(|t| {
                    t.resolution_scale_percent = next_pct;
                });
            }
            Some(Action::RenderDistDelta(delta)) => {
                self.modify_game_settings(|s| {
                    s.render_distance = (s.render_distance + delta).clamp(2, 32);
                });
            }
            Some(Action::SimDistDelta(delta)) => {
                self.modify_game_settings(|s| {
                    s.simulation_distance = (s.simulation_distance + delta).clamp(5, 32);
                });
            }
            Some(Action::FovDelta(delta)) => {
                self.modify_game_settings(|s| {
                    s.fov = (s.fov + delta).clamp(30.0, 110.0);
                });
            }
            Some(Action::CycleMaxFps) => {
                let mut now_uncapped = false;
                self.modify_game_settings(|s| {
                    s.max_fps = match s.max_fps {
                        0..=45 => 60,
                        46..=75 => 90,
                        76..=105 => 120,
                        106..=200 => 260,
                        _ => 30,
                    };
                    if s.max_fps >= 260 {
                        s.vsync = false;
                        s.horizon.present_mode = horizon_client::graphics::PresentMode::Immediate;
                        s.inactivity_fps_limit = "minimized".into();
                        now_uncapped = true;
                    }
                });
                self.catalog.config.max_fps_mode = now_uncapped;
                if now_uncapped {
                    self.catalog.config.battery_perf_mode = false;
                }
                let _ = self.catalog.config.save(&self.home);
            }
            Some(Action::CycleGraphicsPreset) => {
                self.modify_game_settings(|s| {
                    s.graphics_preset = match s.graphics_preset.as_str() {
                        "fast" => "fancy".into(),
                        "fancy" => "fabulous".into(),
                        _ => "fast".into(),
                    };
                });
            }
            Some(Action::ToggleVsync) => {
                let mut vsync_now = false;
                let mut fps_now = 120;
                self.modify_game_settings(|s| {
                    s.vsync = !s.vsync;
                    vsync_now = s.vsync;
                    fps_now = s.max_fps;
                    s.horizon.present_mode = if s.vsync {
                        horizon_client::graphics::PresentMode::Fifo
                    } else {
                        horizon_client::graphics::PresentMode::Immediate
                    };
                });
                if vsync_now {
                    self.catalog.config.max_fps_mode = false;
                } else if fps_now >= 260 {
                    self.catalog.config.max_fps_mode = true;
                    self.catalog.config.battery_perf_mode = false;
                }
                let _ = self.catalog.config.save(&self.home);
            }
            Some(Action::ToggleSmoothLighting) => {
                self.modify_game_settings(|s| s.smooth_lighting = !s.smooth_lighting);
            }
            Some(Action::CycleClouds) => {
                self.modify_game_settings(|s| {
                    s.clouds = match s.clouds.as_str() {
                        "false" => "fast".into(),
                        "fast" => "true".into(),
                        _ => "false".into(),
                    };
                });
            }
            Some(Action::ToggleMenuBlur) => {
                self.modify_game_settings(|s| {
                    s.menu_blur = if s.menu_blur > 0 { 0 } else { 4 };
                });
            }
            // Experimental settings mutations
            Some(Action::ToggleSuperPerf) => {
                self.catalog.config.super_perf_mode = !self.catalog.config.super_perf_mode;
                if self.catalog.config.super_perf_mode {
                    self.catalog.config.battery_perf_mode = false;
                    self.catalog.config.renderer = crate::catalog::RendererMode::Vulkan;
                }
                crate::ui_theme::motion::set_reduced_motion(
                    self.catalog.config.super_perf_mode || self.catalog.config.battery_perf_mode,
                );
                self.catalog.config.save(&self.home)?;
                self.catalog.config.apply_experimental_to_game(&self.home);
                self.catalog.status = if self.catalog.config.super_perf_mode {
                    "Super Performance Mode enabled: fast chunk culling, minimal particles & Vulkan batching."
                } else {
                    "Super Performance Mode disabled."
                }
                .into();
            }
            Some(Action::ToggleBatteryPerf) => {
                self.catalog.config.battery_perf_mode = !self.catalog.config.battery_perf_mode;
                if self.catalog.config.battery_perf_mode {
                    self.catalog.config.super_perf_mode = false;
                    self.catalog.config.max_fps_mode = false;
                    self.catalog.config.device.display_hz = 60;
                } else if self.catalog.config.device.display_hz == 60 {
                    self.catalog.config.device.display_hz = 0;
                }
                crate::device_runtime::configure(self.catalog.config.device);
                crate::ui_theme::motion::set_reduced_motion(
                    self.catalog.config.super_perf_mode || self.catalog.config.battery_perf_mode,
                );
                self.catalog.config.save(&self.home)?;
                self.catalog.config.apply_experimental_to_game(&self.home);
                self.catalog.status = if self.catalog.config.battery_perf_mode {
                    "Battery Performance Mode enabled: 60 FPS cap with VSync and balanced workers."
                } else {
                    "Battery Performance Mode disabled."
                }
                .into();
            }
            Some(Action::ToggleMaxFpsMode) => {
                self.catalog.config.max_fps_mode = !self.catalog.config.max_fps_mode;
                if self.catalog.config.max_fps_mode {
                    self.catalog.config.battery_perf_mode = false;
                    if self.catalog.config.device.display_hz < 120 {
                        self.catalog.config.device.display_hz = 120;
                    }
                } else {
                    if self.catalog.config.device.display_hz == 120 {
                        self.catalog.config.device.display_hz = 0;
                    }
                    self.modify_game_settings(|s| {
                        s.max_fps = 120;
                        s.vsync = true;
                        s.horizon.present_mode = horizon_client::graphics::PresentMode::Auto;
                    });
                }
                crate::device_runtime::configure(self.catalog.config.device);
                self.catalog.config.save(&self.home)?;
                self.catalog.config.apply_experimental_to_game(&self.home);
                self.catalog.status = if self.catalog.config.max_fps_mode {
                    "Max FPS Mode enabled: 260 FPS uncapped with VSync disabled and high-refresh requested."
                } else {
                    "Max FPS Mode disabled (balanced 120 FPS default restored)."
                }
                .into();
            }
            Some(Action::ToggleZeroLatencyTouch) => {
                self.catalog.config.zero_latency_touch = !self.catalog.config.zero_latency_touch;
                self.catalog.config.save(&self.home)?;
                self.catalog.config.apply_experimental_to_game(&self.home);
                self.catalog.status = if self.catalog.config.zero_latency_touch {
                    "Zero-Latency Touch Pipeline active: zero deadzone on look and virtual mouse."
                } else {
                    "Standard touch pipeline active."
                }
                .into();
            }
            Some(Action::ToggleFastChunkUpload) => {
                self.catalog.config.fast_chunk_upload = !self.catalog.config.fast_chunk_upload;
                self.catalog.config.save(&self.home)?;
                self.catalog.config.apply_experimental_to_game(&self.home);
                self.catalog.status = if self.catalog.config.fast_chunk_upload {
                    "Fast Chunk Mesh Upload Boost active."
                } else {
                    "Standard chunk mesh upload active."
                }
                .into();
            }
            // Controls & Control Editor actions
            Some(Action::SelectControlMode(style)) => {
                let game_home = self.catalog.config.game_home(&self.home);
                let mut touch = crate::touch_compat::Settings::load(
                    &game_home.join("horizon-touch.json"),
                );
                touch.switch_control_system(&game_home, style);
                self.catalog.status = format!(
                    "Control Mode set to {} (Active preset: '{}').",
                    style.label(),
                    touch.preset_name
                );
            }
            Some(Action::OpenControlEditor) => {
                // The visual HUD editor came from the old touch UI, which upstream does not
                // have. Presets, import/export and the sliders below still work.
                self.catalog.status =
                    "The on-screen layout editor is not available in this build yet. Pick, copy or import a preset instead."
                        .into();
            }
            Some(Action::CloseControlEditor) => {}
            Some(Action::ControlProfileChooser) => {
                self.open_chooser(chooser::Kind::ControlProfile);
            }
            Some(Action::ChooseControlProfile(idx)) => {
                let game_home = self.catalog.config.game_home(&self.home);
                let mut touch = crate::touch_compat::Settings::load(
                    &game_home.join("horizon-touch.json"),
                );
                let style = touch.control_style;
                let presets = crate::touch_compat::Settings::list_presets(&game_home, style);
                if let Some(meta) = presets.get(idx as usize) {
                    touch.select_preset(&game_home, style, &meta.id);
                    self.catalog.status = format!(
                        "Selected {} preset '{}' ({}).",
                        style.label(),
                        meta.name,
                        meta.description
                    );
                }
                self.chooser = None;
            }
            Some(Action::CopyActivePreset) => {
                let game_home = self.catalog.config.game_home(&self.home);
                let touch = crate::touch_compat::Settings::load(
                    &game_home.join("horizon-touch.json"),
                );
                self.catalog.config.preset_edit_mode = "copy".into();
                self.catalog.config.custom_profile_name = format!("{} Copy", touch.preset_name);
                self.catalog.config.custom_profile_desc = touch.preset_description.clone();
                self.catalog.editing = Some("profile_name");
                crate::platform::show(&self.catalog.config.custom_profile_name);
                self.catalog.status = format!(
                    "Step 1/2: Enter new {} preset name and press Enter.",
                    touch.control_style.label()
                );
            }
            Some(Action::EditActivePresetMeta) => {
                let game_home = self.catalog.config.game_home(&self.home);
                let touch = crate::touch_compat::Settings::load(
                    &game_home.join("horizon-touch.json"),
                );
                if touch.is_default_preset() {
                    self.catalog.status = format!(
                        "{} Default preset is protected and cannot be renamed or overwritten. Use 'Copy Preset' to create an editable copy.",
                        touch.control_style.label()
                    );
                } else {
                    self.catalog.config.preset_edit_mode = "edit".into();
                    self.catalog.config.custom_profile_name = touch.preset_name.clone();
                    self.catalog.config.custom_profile_desc = touch.preset_description.clone();
                    self.catalog.editing = Some("profile_name");
                    crate::platform::show(&self.catalog.config.custom_profile_name);
                    self.catalog.status = format!(
                        "Step 1/2: Edit {} preset name and press Enter.",
                        touch.control_style.label()
                    );
                }
            }
            Some(Action::SaveControlProfileAsNew) => {
                let game_home = self.catalog.config.game_home(&self.home);
                let touch = crate::touch_compat::Settings::load(
                    &game_home.join("horizon-touch.json"),
                );
                self.catalog.config.preset_edit_mode = "copy".into();
                self.catalog.config.custom_profile_name = format!("{} Copy", touch.preset_name);
                self.catalog.config.custom_profile_desc = touch.preset_description.clone();
                self.catalog.editing = Some("profile_name");
                crate::platform::show(&self.catalog.config.custom_profile_name);
                self.catalog.status = format!(
                    "Step 1/2: Enter new {} preset name and press Enter.",
                    touch.control_style.label()
                );
            }
            Some(Action::ConfirmReplaceDuplicatePreset) => {
                if let Some((_, name, desc)) = self.catalog.duplicate_preset_confirm.take() {
                    let game_home = self.catalog.config.game_home(&self.home);
                    let mut touch = crate::touch_compat::Settings::load(
                        &game_home.join("horizon-touch.json"),
                    );
                    if let Ok(meta) = touch.save_or_replace_preset_by_name(&game_home, &name, &desc)
                    {
                        self.catalog.status = format!(
                            "Replaced {} preset '{}' (controls/{}/{}.json).",
                            meta.system.label(),
                            meta.name,
                            meta.system.storage_subdir(),
                            meta.id
                        );
                    }
                }
            }
            Some(Action::CancelReplaceDuplicatePreset) => {
                self.catalog.duplicate_preset_confirm = None;
                self.catalog.status = "Cancelled replacing existing control preset.".into();
            }
            Some(Action::ExportControlProfile) => {
                let game_home = self.catalog.config.game_home(&self.home);
                let touch = crate::touch_compat::Settings::load(
                    &game_home.join("horizon-touch.json"),
                );
                let clean =
                    crate::touch_compat::Settings::sanitize_profile_name(&touch.preset_id);
                let suggested_name = format!("horizon-controls-{clean}.json");
                if let Ok(saved_path) =
                    touch.export_profile(&self.home, &game_home, &touch.preset_id)
                {
                    self.catalog.fm_clipboard = Some((vec![saved_path], false));
                }
                match serde_json::to_vec_pretty(&touch) {
                    Ok(bytes) => {
                        match crate::documents::begin_control_export(
                            game_home.clone(),
                            suggested_name.clone(),
                            bytes,
                        ) {
                            Ok(()) => {
                                self.catalog.status = format!(
                                    "Select where to save '{}' (also saved in controls/{}/ and copied to clipboard).",
                                    suggested_name,
                                    touch.control_style.storage_subdir()
                                );
                            }
                            Err(_) => {
                                self.catalog.status = format!(
                                    "Saved '{}' in controls/{}/{}.json and copied to File Manager clipboard.",
                                    suggested_name,
                                    touch.control_style.storage_subdir(),
                                    touch.preset_id
                                );
                            }
                        }
                    }
                    Err(e) => self.catalog.status = e.to_string(),
                }
            }
            Some(Action::ImportControlProfile) => {
                let game_home = self.catalog.config.game_home(&self.home);
                crate::touch_compat::Settings::ensure_system_storage(
                    &game_home,
                    crate::touch_compat::ControlStyle::Mcpe,
                );
                crate::touch_compat::Settings::ensure_system_storage(
                    &game_home,
                    crate::touch_compat::ControlStyle::GoyControl,
                );
                match crate::documents::begin_control_import(game_home) {
                    Ok(()) => {
                        self.catalog.status =
                            "Pick a control .json file — Horizon will auto-detect MCPE or GoyControl and save it in the right folder."
                                .into();
                    }
                    Err(err) => {
                        self.catalog.status = err;
                    }
                }
            }
            Some(Action::MouseSensDelta(delta)) => {
                self.modify_game_settings(|s| {
                    s.sensitivity = (s.sensitivity + delta).clamp(0.05, 1.0);
                });
            }
            Some(Action::TouchSensDelta(delta)) => {
                self.modify_touch_settings(|t| {
                    t.sensitivity = (t.sensitivity + delta).clamp(0.10, 3.0);
                });
            }
            Some(Action::VirtualMouseSpeedDelta(delta)) => {
                self.modify_touch_settings(|t| {
                    t.virtual_mouse_sensitivity =
                        (t.virtual_mouse_sensitivity + delta).clamp(0.25, 3.0);
                });
            }
            Some(Action::ButtonScaleDelta(delta)) => {
                self.modify_touch_settings(|t| {
                    t.button_scale = (t.button_scale + delta).clamp(0.60, 1.60);
                });
            }
            Some(Action::ToggleControlScheme) => {
                self.modify_touch_settings(|t| {
                    t.mode = match t.mode {
                        horizon_client::touch::Mode::Crosshair => horizon_client::touch::Mode::Tap,
                        horizon_client::touch::Mode::Tap => horizon_client::touch::Mode::Dpad,
                        horizon_client::touch::Mode::Dpad => horizon_client::touch::Mode::Crosshair,
                    };
                });
            }
            Some(Action::ToggleSplitControls) => {
                self.modify_touch_settings(|t| t.toggle_split_touch());
            }
            Some(Action::ToggleSprintLatch) => {
                self.modify_touch_settings(|t| t.sprint_toggle = !t.sprint_toggle);
            }
            Some(Action::ToggleLeftHanded) => {
                self.modify_touch_settings(|t| t.left_handed = !t.left_handed);
            }
            Some(Action::ToggleInvertY) => {
                let mut next = false;
                self.modify_game_settings(|s| {
                    s.invert_y = !s.invert_y;
                    next = s.invert_y;
                });
                self.modify_touch_settings(|t| t.invert_y = next);
            }
            Some(Action::ToggleGyro) => {
                self.modify_touch_settings(|t| t.gyroscope = !t.gyroscope);
            }
            Some(Action::GyroSensDelta(delta)) => {
                self.modify_touch_settings(|t| {
                    t.gyro_sensitivity = (t.gyro_sensitivity + delta).clamp(0.10, 3.0);
                });
            }
            Some(Action::ToggleVirtualMouseEnabled) => {
                self.modify_touch_settings(|t| {
                    t.virtual_mouse_enabled = !t.virtual_mouse_enabled;
                });
            }
            Some(Action::PointerSizeDelta(delta)) => {
                self.modify_touch_settings(|t| {
                    t.virtual_mouse_pointer_scale =
                        (t.virtual_mouse_pointer_scale + delta).clamp(0.50, 2.00);
                });
            }
            Some(Action::CycleJoystickZone) => {
                self.modify_touch_settings(|t| {
                    t.joystick_style.move_area = t.joystick_style.move_area.next();
                });
            }
            Some(Action::ToggleTouchVibrate) => {
                self.modify_touch_settings(|t| t.vibrate = !t.vibrate);
            }
            Some(Action::ToggleAutoJump) => {
                self.modify_game_settings(|s| s.auto_jump = !s.auto_jump);
            }
            Some(Action::ToggleInventoryControls) => {
                self.modify_touch_settings(|t| t.inventory_controls = !t.inventory_controls);
            }
            Some(Action::Immersive) => {
                self.catalog.config.screen.immersive ^= true;
                self.catalog.config.save(&self.home)?;
                self.configure_screen();
            }
            Some(Action::Cutout) => {
                self.catalog.config.screen.avoid_cutout ^= true;
                self.catalog.config.save(&self.home)?;
                self.configure_screen();
            }
            Some(Action::Aspect) => self.open_chooser(chooser::Kind::Aspect),
            Some(Action::ChooseAspect(aspect)) => {
                self.catalog.config.screen.aspect = aspect;
                self.catalog.config.save(&self.home)?;
                self.configure_screen();
                self.chooser = None;
            }
            Some(Action::CacheBudget) => self.open_chooser(chooser::Kind::CacheBudget),
            Some(Action::ChooseCacheBudget(mib)) => {
                self.catalog.config.cache_budget_mib = mib.min(1024);
                self.catalog.config.save(&self.home)?;
                self.chooser = None;
                self.catalog.status = "Disposable cache budget saved. Applies on next Play; not a total RAM or VRAM limit.".into();
            }
            Some(Action::ToggleBundled(id)) => {
                self.toggle_bundled_mod(id)?;
            }
            Some(Action::GameFiles) => {
                self.catalog.status = if crate::platform::open_documents(crate::document_store::ROOT) {
                    "Game files opened. Worlds, mods, packs, shaders and reports are available here."
                } else { "Open system Files > Horizon Android." }.into();
            }
            Some(Action::WorldFiles) => {
                let id = crate::document_store::folder_id(self.catalog.config.profile, "worlds");
                self.catalog.status = if crate::platform::open_documents(&id) {
                    "Copy a complete world folder to back up or import. Finish copying before Play; never replace an existing world silently."
                } else { "Open system Files > Horizon Android > Worlds. Copy complete world folders while the game is closed." }.into();
            }
            Some(Action::WorkerLimit) => self.open_chooser(chooser::Kind::WorkerLimit),
            Some(Action::ChooseWorkerLimit(limit)) => {
                self.catalog.config.worker_limit = limit.min(64);
                self.catalog.config.save(&self.home)?;
                self.chooser = None;
                self.catalog.status = "Chunk worker ceiling saved. Applies on the next Play; other engine threads are unchanged.".into();
            }
            Some(Action::DisplayHz) => self.open_chooser(chooser::Kind::DisplayHz),
            Some(Action::ChooseDisplayHz(hz)) => {
                self.catalog.config.device.display_hz = hz;
                self.catalog.config.device.sanitize();
                self.catalog.config.save(&self.home)?;
                crate::device_runtime::configure(self.catalog.config.device);
                self.chooser = None;
            }
            Some(Action::PerformanceHints) => {
                self.catalog.config.device.performance_hints ^= true;
                self.catalog.status="ADPF gives Android CPU frame-work hints on supported devices. It does not cap FPS or guarantee a speedup.".into();
                self.catalog.config.save(&self.home)?;
                crate::device_runtime::configure(self.catalog.config.device);
            }
            Some(Action::GpuProfile) => {
                self.catalog.config.device.gpu_profile ^= true;
                self.catalog.status="Detailed GPU profiling records diagnostics and has overhead. Reports remain on this device.".into();
                self.catalog.config.save(&self.home)?;
                crate::device_runtime::configure(self.catalog.config.device);
            }

            Some(Action::ShaderSource(origin)) => {
                self.catalog.config.shader_source = origin;
                self.catalog.config.save(&self.home)?;
                self.catalog.offset = 0;
                self.catalog.scroll = 0.0;
                self.catalog.browse(&self.home);
            }
            Some(Action::ModSource(origin)) => {
                self.catalog.config.mod_source = origin;
                self.catalog.config.save(&self.home)?;
                self.catalog.offset = 0;
                self.catalog.scroll = 0.0;
                self.catalog.browse(&self.home);
            }
            Some(Action::SetCompilerMode(mode)) => {
                self.catalog.compiler_mode = mode;
                self.anim.trigger_page_transition();
            }
            Some(Action::CompilerRunQuery) => {
                crate::platform::hide();
                self.catalog.run_compiler_from_query(&self.home);
            }
            Some(Action::CompilerPortLocal(idx)) => {
                self.catalog.run_compiler_on_local(&self.home, idx);
            }
            Some(Action::OpenFolder(kind)) => {
                crate::platform::hide();
                let rel_dir = match kind {
                    crate::content_files::Kind::Mods => "horizon-mods",
                    crate::content_files::Kind::Packs => "resourcepacks",
                    crate::content_files::Kind::Shaders => "shaderpacks",
                };
                self.catalog.page = crate::catalog::Page::Installed;
                self.catalog.installed_sub_tab = crate::catalog::InstalledSubTab::FileManager;
                self.catalog.fm_navigate(&self.home, rel_dir.to_owned());
                self.anim.trigger_page_transition();
                self.catalog.status = format!("Opened '{rel_dir}' in Profile File Manager.");
            }
            Some(Action::Import(kind)) => {
                crate::platform::hide();
                let home = self.catalog.config.game_home(&self.home);
                if let Err(e) = crate::documents::begin(kind, home) {
                    self.catalog.status = e;
                }
            }
            Some(Action::ToggleSection(i)) => {
                self.catalog.sections[i] = !self.catalog.sections[i];
                self.catalog.scroll = 0.0;
            }
            Some(Action::ToggleLocal(i)) => self.catalog.toggle_local(&self.home, i),
            Some(Action::DeleteLocal(i)) => {
                self.catalog.pending_delete = self.catalog.local.get(i).cloned();
                if let Some(item) = &self.catalog.pending_delete {
                    self.chooser = Some(super::chooser::Chooser::confirm_delete(item.file.clone()));
                }
            }
            Some(Action::ConfirmDeleteLocal) => {
                self.catalog.remove_local(&self.home);
                self.chooser = None;
            }
            Some(Action::Repository) => {
                self.catalog.status = if crate::platform::open_repository() {
                    "Repository opened in browser."
                } else {
                    "No browser available to open the repository."
                }
                .into();
            }
            Some(Action::Discord) => {
                self.catalog.status = if crate::platform::open_url("https://discord.gg/CjyWBu2mC7")
                {
                    "Discord opened in browser."
                } else {
                    "No browser available to open Discord."
                }
                .into();
            }
            Some(Action::Account) => {
                self.catalog.editing = None;
                crate::platform::hide();
                self.login.refresh_status();
                self.login.visible = true;
                self.login.offline = false;
                self.login.ely = false;
                if self.login.signed_in {
                    // Already signed in: open the selector, with the current
                    // Microsoft/Ely.by account called out, instead of asking
                    // the player to choose a provider again.
                    self.login.providers = false;
                    self.login.choosing = true;
                    self.login.account_offset = 0;
                } else {
                    self.login.providers = true;
                    self.login.choosing = false;
                }
            }
            Some(Action::AddAccount) => {
                self.login.choosing = false;
                self.login.providers = true;
                self.login.offline = false;
                self.login.ely = false;
                self.login.visible = true;
            }
            Some(Action::OfflineAccount | Action::MicrosoftAccount) => {
                self.login.providers = false;
                self.login.offline = matches!(action, Some(Action::OfflineAccount));
                self.login.ely = false;
                self.login.choosing = false;
                self.login.visible = true;
            }
            Some(Action::SavedAccounts) => {
                self.login.providers = false;
                self.login.refresh_status();
                self.login.choosing = true;
                self.login.ely = false;
            }
            Some(Action::SavedPage(next)) => {
                if next && self.login.account_offset + 3 < self.login.saved_accounts.len() {
                    self.login.account_offset += 3;
                } else if !next {
                    self.login.account_offset = self.login.account_offset.saturating_sub(3);
                }
            }
            Some(Action::SelectAccount(index)) => {
                let mut accounts = crate::account::Accounts::load();
                if let Some(account) = self.login.saved_accounts.get(index)
                    && accounts.accounts.iter().any(|a| a.key() == account.key())
                {
                    accounts.selected = Some(account.key());
                    accounts.save()?;
                }
                self.session_ready = false;
                self.prepared_session = None;
                self.session_worker = None;
                self.login.refresh_status();
                self.refresh_wardrobe_account();
            }
            Some(Action::SelectOffline) => {
                self.catalog.config.save(&self.home)?;
                self.catalog.editing = None;
                crate::platform::hide();
                self.login.hide();
                let mut accounts = crate::account::Accounts::load();
                accounts.selected = None;
                accounts.save()?;
                self.session_ready = false;
                self.prepared_session = None;
                self.session_worker = None;
                self.login.choosing = false;
                self.login.refresh_status();
                self.refresh_wardrobe_account();
            }
            Some(Action::ElySignIn) => {
                self.login.providers = false;
                self.login.close();
                self.login.ely = true;
                self.login.visible = true;
            }
            Some(Action::SubmitEly) => {
                self.catalog.editing = None;
                crate::platform::hide();
                self.session_ready = false;
                self.prepared_session = None;
                self.session_worker = None;
                if !self.login.pending() {
                    self.login.start_ely();
                }
            }
            Some(Action::CloseLogin) => {
                self.login.hide();
                crate::platform::hide();
                self.catalog.editing = None;
                self.refresh_wardrobe_account();
            }
            Some(Action::DeviceSignIn) => {
                self.session_ready = false;
                self.prepared_session = None;
                self.session_worker = None;
                self.login
                    .start_device(&self.catalog.config.microsoft_client_id);
            }
            Some(Action::CopyLoginCode) => self.login.copy_code(),
            Some(Action::CancelLogin) => {
                self.login.close();
                self.login.visible = true;
            }
            Some(Action::ReviewApp) => self.login.review_app(),
            Some(Action::SignIn) => {
                self.session_ready = false;
                self.prepared_session = None;
                self.session_worker = None;
                self.login.start(&self.catalog.config.microsoft_client_id);
            }
            Some(Action::SignOut) => {
                self.session_ready = false;
                self.prepared_session = None;
                self.session_worker = None;
                self.login.sign_out();
                self.refresh_wardrobe_account();
            }
            Some(Action::LoginLink) => self.login.open_link(),
            Some(Action::Page(page)) => {
                if page == Page::Skins {
                    self.wardrobe.open(
                        &self.catalog.config.name,
                        &self.catalog.config.microsoft_client_id,
                    );
                }
                self.catalog.editing = None;
                self.catalog.project_modal = None;
                self.catalog.modal_worker = None;
                if crate::platform::active() {
                    crate::platform::hide();
                }
                if page == Page::Settings && self.catalog.page == Page::Settings {
                    self.catalog.settings_category = SettingsCategory::Overview;
                }
                self.catalog.page = page;
                self.catalog.scroll = 0.0;
                self.error = None;
                self.catalog.offset = 0;
                self.catalog.browse(&self.home);
            }
            Some(Action::Play) => self.action(events)?,
            Some(Action::WardrobeKind(cape)) => {
                self.wardrobe.set_tab(cape);
                self.catalog.scroll = 0.0;
            }
            Some(Action::WardrobeSelect(index)) => self.wardrobe.select(index),
            Some(Action::WardrobeImport) => self.wardrobe.import(),
            Some(Action::WardrobeCloud) => self
                .wardrobe
                .cloud(&self.catalog.config.microsoft_client_id, None),
            Some(Action::WardrobeUpload) => self.wardrobe.cloud(
                &self.catalog.config.microsoft_client_id,
                Some(wardrobe::CloudAction::Upload),
            ),
            Some(Action::WardrobeActivateCape(index)) => self.wardrobe.cloud(
                &self.catalog.config.microsoft_client_id,
                Some(wardrobe::CloudAction::Cape(index)),
            ),
            Some(Action::WardrobeUseCloud) => self.wardrobe.use_cloud(),
            Some(Action::WardrobeModel) => self.wardrobe.model(),
            Some(Action::WardrobeRotate(direction)) => {
                self.wardrobe.camera.rotate(-direction as f32 * 25.0, 0.0);
                self.wardrobe.preview();
            }
            Some(Action::WardrobeZoom(inward)) => {
                self.wardrobe
                    .camera
                    .zoom(if inward { 1.15 } else { 1.0 / 1.15 });
                self.wardrobe.preview();
            }
            Some(Action::WardrobeHideCape) => self.wardrobe.hide_cape(),
            Some(Action::Search) => {
                crate::platform::hide();
                self.catalog.offset = 0;
                self.catalog.scroll = 0.0;
                self.catalog.search(&self.home);
            }
            Some(Action::FinishNameEdit) => {
                if self.catalog.editing == Some("name") {
                    self.refresh_wardrobe_account();
                }
                self.catalog.editing = None;
                crate::platform::hide();
                self.catalog.config.touch_name_changed();
                self.catalog.config.save(&self.home)?;
            }
            Some(Action::Field(name)) => {
                if let Some(value) = self.login.field(name) {
                    self.catalog.editing = Some(name);
                    if name == "ely_password" || name == "ely_otp" {
                        crate::platform::show_secret(value);
                    } else {
                        crate::platform::show(value);
                    }
                    return Ok(());
                }
                if name == "render_scale" {
                    self.catalog.render_scale_text =
                        self.catalog.config.render_scale.percent().to_string();
                    self.chooser = None;
                }
                self.catalog.editing = Some(name);
                let initial = match name {
                    "name" => &self.catalog.config.name,
                    "profile_name" => &self.catalog.config.custom_profile_name,
                    "profile_desc" => &self.catalog.config.custom_profile_desc,
                    "server" => &self.catalog.config.server,
                    "render_scale" => &self.catalog.render_scale_text,
                    "client_id" => &self.catalog.config.microsoft_client_id,
                    "theme_bg" => &self.catalog.config.theme.custom_bg_hex,
                    "theme_surface" => &self.catalog.config.theme.custom_surface_hex,
                    "theme_accent" => &self.catalog.config.theme.custom_accent_hex,
                    "theme_grad_start" => &self.catalog.config.theme.gradient_start_hex,
                    "theme_grad_mid" => &self.catalog.config.theme.gradient_mid_hex,
                    "theme_grad_end" => &self.catalog.config.theme.gradient_end_hex,
                    "installed_search" => &self.catalog.installed_query,
                    "compiler_query" => &self.catalog.compiler_query,
                    "fm_rename" | "fm_new_folder" => &self.catalog.fm_input_text,
                    _ => &self.catalog.query,
                };
                crate::platform::show(initial);
            }
            Some(Action::Install(i)) => self.catalog.install(&self.home, i),
            Some(Action::Next) => {
                let is_horizon_paged = (self.catalog.page == Page::Mods
                    && self.catalog.config.mod_source == crate::shader_catalog::Origin::Horizon)
                    || (self.catalog.page == Page::Shaders
                        && self.catalog.config.shader_source
                            == crate::shader_catalog::Origin::Horizon);
                if is_horizon_paged {
                    let bundled_count = if self.catalog.page == Page::Mods {
                        let query = self.catalog.query.trim().to_lowercase();
                        crate::ANDROID_BUNDLED
                            .iter()
                            .filter(|(id, title, _)| {
                                query.is_empty()
                                    || id.contains(&query)
                                    || title.to_lowercase().contains(&query)
                            })
                            .count()
                    } else {
                        0
                    };
                    let cat_count = self
                        .catalog
                        .results
                        .iter()
                        .filter(|p| {
                            !(self.catalog.page == Page::Mods
                                && p.source == crate::browser::SourceId::Horizon
                                && crate::ANDROID_BUNDLED
                                    .iter()
                                    .any(|(id, _, _)| *id == p.id))
                        })
                        .count();
                    let total = bundled_count + cat_count;
                    if self.catalog.offset + crate::catalog::CATALOG_PAGE_SIZE < total {
                        self.catalog.offset += crate::catalog::CATALOG_PAGE_SIZE;
                        self.catalog.scroll = 0.0;
                    }
                } else if self.catalog.results.len() >= crate::catalog::CATALOG_PAGE_SIZE {
                    self.catalog.offset += crate::catalog::CATALOG_PAGE_SIZE;
                    self.catalog.scroll = 0.0;
                    self.catalog.search(&self.home);
                }
            }
            Some(Action::Previous) => {
                if self.catalog.offset >= crate::catalog::CATALOG_PAGE_SIZE {
                    self.catalog.offset -= crate::catalog::CATALOG_PAGE_SIZE;
                    self.catalog.scroll = 0.0;
                    let is_horizon_paged = (self.catalog.page == Page::Mods
                        && self.catalog.config.mod_source
                            == crate::shader_catalog::Origin::Horizon)
                        || (self.catalog.page == Page::Shaders
                            && self.catalog.config.shader_source
                                == crate::shader_catalog::Origin::Horizon);
                    if !is_horizon_paged {
                        self.catalog.search(&self.home);
                    }
                }
            }
            Some(Action::Profile) => self.open_chooser(chooser::Kind::Profile),
            Some(Action::Renderer) => self.open_chooser(chooser::Kind::Renderer),
            Some(Action::RenderScale) => self.open_chooser(chooser::Kind::RenderScale),
            Some(Action::ChooseRenderScale(scale)) => {
                self.catalog.config.render_scale = scale;
                self.catalog.config.save(&self.home)?;
                self.chooser = None;
                crate::display::set_scale(scale);
            }
            Some(Action::Orientation) => self.open_chooser(chooser::Kind::Orientation),
            Some(Action::InputHost) => self.open_chooser(chooser::Kind::InputHost),
            Some(Action::ChooseInputHost(mode)) => {
                self.catalog.config.input_host = mode;
                self.catalog.config.save(&self.home)?;
                self.chooser = None;
                crate::configure_input_mode(mode);
            }
            Some(Action::ChooseOrientation(mode)) => {
                self.catalog.config.orientation = mode;
                self.catalog.config.save(&self.home)?;
                self.chooser = None;
                crate::platform::set_orientation(mode);
            }
            Some(Action::ChooseRenderer(mode)) => {
                self.catalog.config.renderer = mode;
                self.catalog.config.save(&self.home)?;
                self.chooser = None;
            }
            Some(Action::ChooseProfile(profile)) => {
                std::fs::create_dir_all(if profile == 0 {
                    self.home.clone()
                } else {
                    self.home
                        .join("profiles")
                        .join(format!("profile-{profile}"))
                })?;
                self.catalog.config.profile = profile;
                self.catalog.config.save(&self.home)?;
                self.catalog.refresh(&self.home);
                self.chooser = None;
            }
            Some(Action::Dismiss) => {
                self.exit_confirmation = false;
                self.chooser = None;
                if self.session_worker.is_none() {
                    self.session_ready = false;
                    self.prepared_session = None;
                }
            }
            Some(Action::Logs) => {
                self.catalog.status = crate::diagnostics::summary(&self.home);
                if let Ok(error) = std::fs::read_to_string(self.home.join("android-error.txt")) {
                    self.catalog.status = format!(
                        "Last handled error: {}. {}",
                        crate::diagnostics::redact(&error),
                        self.catalog.status
                    );
                }
                self.diagnostics_modal_open = true;
            }
            Some(Action::CloseDiagnosticsSummary) => {
                self.diagnostics_modal_open = false;
            }
            Some(Action::OpenReportsFolder) => {
                self.diagnostics_modal_open = false;
                self.catalog.status = if crate::platform::open_documents("reports") {
                    "Opened Reports folder in system Files.".into()
                } else {
                    "Reports are saved under Horizon Android > Reports.".into()
                };
            }
            Some(Action::ToggleLauncherAnimations) => {
                self.catalog.config.disable_launcher_animations =
                    !self.catalog.config.disable_launcher_animations;
                crate::ui_theme::motion::set_launcher_animations_disabled(
                    self.catalog.config.disable_launcher_animations,
                );
                self.catalog.config.save(&self.home)?;
                self.catalog.status = if self.catalog.config.disable_launcher_animations {
                    "Launcher animations disabled (instant page & modal transitions)."
                } else {
                    "Launcher animations enabled."
                }
                .into();
            }
            Some(Action::ToggleGameAnimations) => {
                let next = !self.catalog.config.disable_game_animations;
                self.catalog.config.disable_game_animations = next;
                crate::ui_theme::motion::set_game_animations_disabled(next);
                self.catalog.config.save(&self.home)?;
                self.catalog.config.apply_experimental_to_game(&self.home);
                self.modify_touch_settings(|t| t.disable_game_animations = next);
                self.catalog.status = if next {
                    "In-game animations disabled (static textures, instant FOV/menus, no view bobbing)."
                } else {
                    "In-game animations enabled."
                }
                .into();
            }
            Some(Action::ExportDiagnostics) if self.diagnostic_export.is_none() => {
                let home = self.home.clone();
                let (sender, receiver) = mpsc::channel();
                self.diagnostic_export = Some(receiver);
                self.catalog.status =
                    "Preparing local diagnostic ZIP. No upload; review before sharing.".into();
                std::thread::spawn(move || {
                    let result = crate::diagnostics::export(&home).map(|p| {
                        p.file_name()
                            .unwrap_or_default()
                            .to_string_lossy()
                            .into_owned()
                    });
                    let _ = sender.send(result);
                });
            }
            Some(Action::ExportDiagnostics) => {}
            Some(Action::RecordPerformance) => {
                self.catalog.config.record_performance = !self.catalog.config.record_performance;
                if !self.catalog.config.record_performance {
                    crate::performance::stop();
                }
                self.catalog.config.save(&self.home)?;
                self.catalog.status = if self.catalog.config.record_performance {
                    "Recording starts on Play. Detailed timings add overhead. Return here to save the report."
                } else { "Performance recording off." }.into();
            }
            Some(Action::ExportPerformance) => {
                self.catalog.status = match crate::performance::export(&self.home) {
                    Ok(()) => "Reports opened in Files. Copy or share a report from there.".into(),
                    Err(e) => e,
                };
            }
            Some(Action::SelectThemePreset(preset)) => {
                self.catalog.config.theme.apply_preset(preset);
                crate::ui_theme::set_active_theme(self.catalog.config.theme.resolve());
                self.anim.trigger_theme_pulse();
                self.catalog.config.save(&self.home)?;
                self.catalog.status = format!("Applied '{}' theme.", preset.label());
            }
            Some(Action::SetThemeAppearance(appearance)) => {
                self.catalog.config.theme.appearance = appearance;
                if self.catalog.config.theme.preset
                    == crate::ui_theme::ThemePreset::DaylightFrost
                    && appearance == crate::ui_theme::ThemeAppearance::Dark
                {
                    self.catalog.config.theme.preset =
                        crate::ui_theme::ThemePreset::EmeraldForest;
                }
                crate::ui_theme::set_active_theme(self.catalog.config.theme.resolve());
                self.anim.trigger_theme_pulse();
                self.catalog.config.save(&self.home)?;
                self.catalog.status = format!("Switched to {}.", appearance.label());
            }
            Some(Action::SetThemeBackgroundMode(mode)) => {
                self.catalog.config.theme.background_mode = mode;
                crate::ui_theme::set_active_theme(self.catalog.config.theme.resolve());
                self.anim.trigger_theme_pulse();
                self.catalog.config.save(&self.home)?;
                self.catalog.status = format!("Background style set to {}.", mode.label());
            }
            Some(Action::SetGradientDirection(dir)) => {
                self.catalog.config.theme.gradient_direction = dir;
                self.catalog.config.theme.background_mode =
                    crate::ui_theme::BackgroundMode::Gradient;
                crate::ui_theme::set_active_theme(self.catalog.config.theme.resolve());
                self.anim.trigger_theme_pulse();
                self.catalog.config.save(&self.home)?;
                self.catalog.status = format!("Gradient direction set to {}.", dir.label());
            }
            Some(Action::ToggleGradientThreeStops) => {
                self.catalog.config.theme.use_three_stops =
                    !self.catalog.config.theme.use_three_stops;
                self.catalog.config.theme.background_mode =
                    crate::ui_theme::BackgroundMode::Gradient;
                crate::ui_theme::set_active_theme(self.catalog.config.theme.resolve());
                self.anim.trigger_theme_pulse();
                self.catalog.config.save(&self.home)?;
            }
            Some(Action::StepGradientMidPos(dir)) => {
                let cur = self.catalog.config.theme.gradient_mid_pos;
                let next = if dir >= 0 {
                    if cur < 0.35 {
                        0.50
                    } else if cur < 0.65 {
                        0.75
                    } else {
                        0.25
                    }
                } else if cur > 0.65 {
                    0.50
                } else {
                    0.25
                };
                self.catalog.config.theme.gradient_mid_pos = next;
                self.catalog.config.theme.background_mode =
                    crate::ui_theme::BackgroundMode::Gradient;
                crate::ui_theme::set_active_theme(self.catalog.config.theme.resolve());
                self.anim.trigger_theme_pulse();
                self.catalog.config.save(&self.home)?;
            }
            Some(Action::SelectAccentSwatch(rgb)) => {
                self.catalog.config.theme.custom_accent_hex =
                    crate::ui_theme::format_hex_color(rgb);
                self.catalog.config.theme.preset = crate::ui_theme::ThemePreset::Custom;
                crate::ui_theme::set_active_theme(self.catalog.config.theme.resolve());
                self.anim.trigger_theme_pulse();
                self.catalog.config.save(&self.home)?;
            }
            Some(Action::SelectBgSwatch(rgb)) => {
                self.catalog.config.theme.custom_bg_hex =
                    crate::ui_theme::format_hex_color(rgb);
                self.catalog.config.theme.preset = crate::ui_theme::ThemePreset::Custom;
                self.catalog.config.theme.background_mode =
                    crate::ui_theme::BackgroundMode::Solid;
                crate::ui_theme::set_active_theme(self.catalog.config.theme.resolve());
                self.anim.trigger_theme_pulse();
                self.catalog.config.save(&self.home)?;
            }
            Some(Action::SelectGradStartSwatch(rgb)) => {
                self.catalog.config.theme.gradient_start_hex =
                    crate::ui_theme::format_hex_color(rgb);
                self.catalog.config.theme.preset = crate::ui_theme::ThemePreset::Custom;
                self.catalog.config.theme.background_mode =
                    crate::ui_theme::BackgroundMode::Gradient;
                crate::ui_theme::set_active_theme(self.catalog.config.theme.resolve());
                self.anim.trigger_theme_pulse();
                self.catalog.config.save(&self.home)?;
            }
            Some(Action::SelectGradEndSwatch(rgb)) => {
                self.catalog.config.theme.gradient_end_hex =
                    crate::ui_theme::format_hex_color(rgb);
                self.catalog.config.theme.preset = crate::ui_theme::ThemePreset::Custom;
                self.catalog.config.theme.background_mode =
                    crate::ui_theme::BackgroundMode::Gradient;
                crate::ui_theme::set_active_theme(self.catalog.config.theme.resolve());
                self.anim.trigger_theme_pulse();
                self.catalog.config.save(&self.home)?;
            }
            Some(Action::ResetTheme) => {
                self.catalog.config.theme =
                    crate::ui_theme::LauncherThemeConfig::default();
                crate::ui_theme::set_active_theme(self.catalog.config.theme.resolve());
                self.anim.trigger_theme_pulse();
                self.catalog.config.save(&self.home)?;
                self.catalog.status = "Theme reset to Horizon default (Obsidian Violet).".into();
            }
            Some(Action::SkipIntro) => {
                self.anim.intro_done = true;
            }
            Some(Action::SetInstalledSubTab(tab)) => {
                self.catalog.installed_sub_tab = tab;
                self.catalog.editing = None;
                crate::platform::hide();
                self.catalog.scroll = 0.0;
                if tab == crate::catalog::InstalledSubTab::FileManager {
                    self.catalog.refresh_fm(&self.home);
                }
                self.anim.trigger_page_transition();
            }
            Some(Action::SetInstalledFilter(filter)) => {
                self.catalog.installed_filter = filter;
                self.catalog.scroll = 0.0;
                self.anim.trigger_page_transition();
            }
            Some(Action::CycleInstalledSort) => {
                self.catalog.installed_sort = self.catalog.installed_sort.next();
                self.catalog.sort_local_items();
                self.catalog.scroll = 0.0;
            }
            Some(Action::ExportLocal(idx)) => {
                if let Some(item) = self.catalog.local.get(idx).cloned() {
                    let game_home = self.catalog.config.game_home(&self.home);
                    crate::document_store::sync_mods_folder(&game_home);
                    let path = if let Some(bid) = item.file.strip_prefix("builtin:") {
                        game_home
                            .join("horizon-mods")
                            .join(format!("{bid}.mod.json"))
                    } else {
                        let direct = game_home.join(&item.file);
                        if direct.exists() {
                            direct
                        } else {
                            game_home.join(item.kind.folder()).join(&item.file)
                        }
                    };
                    if path.is_dir() {
                        match crate::documents::export_folder_prepared(
                            &self.home,
                            self.catalog.config.profile,
                            &path,
                        ) {
                            Ok((zip_path, _, msg)) => {
                                self.catalog.fm_clipboard = Some((vec![zip_path], false));
                                self.catalog.status = msg;
                            }
                            Err(e) => self.catalog.status = e,
                        }
                    } else {
                        self.catalog.fm_clipboard = Some((vec![path.clone()], false));
                        self.catalog.status = match crate::documents::export_file(
                            &self.home,
                            self.catalog.config.profile,
                            &path,
                        ) {
                            Ok(msg) => msg,
                            Err(e) => e,
                        };
                    }
                }
            }
            Some(Action::FmNavigateRoot(rel)) => {
                self.catalog.fm_navigate(&self.home, (*rel).to_owned());
                self.anim.trigger_page_transition();
            }
            Some(Action::FmBreadcrumb(depth)) => {
                if depth == 0 {
                    self.catalog.fm_navigate(&self.home, String::new());
                } else {
                    let target = self
                        .catalog
                        .fm_current_dir
                        .split('/')
                        .filter(|s| !s.is_empty())
                        .take(depth)
                        .collect::<Vec<_>>()
                        .join("/");
                    self.catalog.fm_navigate(&self.home, target);
                }
            }
            Some(Action::FmBack) => {
                let _ = self.catalog.fm_back(&self.home);
            }
            Some(Action::FmForward) => {
                let _ = self.catalog.fm_forward(&self.home);
            }
            Some(Action::FmUp) => {
                self.catalog.fm_up(&self.home);
            }
            Some(Action::FmOpenEntry(idx)) => {
                if let Some(entry) = self.catalog.fm_entries.get(idx).cloned() {
                    if entry.is_dir {
                        self.catalog.fm_navigate(&self.home, entry.rel_path);
                    } else {
                        let game_home = self.catalog.config.game_home(&self.home);
                        match crate::document_store::fm_details(
                            &self.home,
                            &game_home,
                            &entry.rel_path,
                        ) {
                            Ok(details) => {
                                self.catalog.fm_modal =
                                    Some(crate::catalog::FmModal::Details(details));
                            }
                            Err(e) => self.catalog.status = e,
                        }
                    }
                }
            }
            Some(Action::FmToggleSelect(idx)) => {
                if let Some(entry) = self.catalog.fm_entries.get(idx) {
                    let rel = entry.rel_path.clone();
                    if !self.catalog.fm_selected.remove(&rel) {
                        self.catalog.fm_selected.insert(rel);
                    }
                }
            }
            Some(Action::FmSelectAll) => {
                for entry in &self.catalog.fm_entries {
                    self.catalog.fm_selected.insert(entry.rel_path.clone());
                }
            }
            Some(Action::FmClearSelection) => {
                self.catalog.fm_selected.clear();
            }
            Some(Action::FmCopy) => {
                self.catalog.fm_copy_or_cut(&self.home, false);
            }
            Some(Action::FmCut) => {
                self.catalog.fm_copy_or_cut(&self.home, true);
            }
            Some(Action::FmPaste) => {
                self.catalog.fm_paste_here(&self.home);
            }
            Some(Action::FmImportHere) => {
                let game_home = self.catalog.config.game_home(&self.home);
                match crate::document_store::resolve_fm_path(
                    &self.home,
                    &game_home,
                    &self.catalog.fm_current_dir,
                ) {
                    Ok(target_dir) => {
                        if let Err(e) = crate::documents::begin_into_dir(target_dir) {
                            self.catalog.status = e;
                        }
                    }
                    Err(e) => self.catalog.status = e,
                }
            }
            Some(Action::FmImportFolderHere) => {
                let game_home = self.catalog.config.game_home(&self.home);
                match crate::document_store::resolve_fm_path(
                    &self.home,
                    &game_home,
                    &self.catalog.fm_current_dir,
                ) {
                    Ok(target_dir) => {
                        if let Err(e) = crate::documents::begin_folder_into_dir(target_dir) {
                            self.catalog.status = e;
                        }
                    }
                    Err(e) => self.catalog.status = e,
                }
            }
            Some(Action::FmOpenNewFolder) => {
                self.catalog.fm_input_text = "New Folder".into();
                self.catalog.fm_modal = Some(crate::catalog::FmModal::NewFolder);
                self.catalog.editing = Some("fm_new_folder");
                crate::platform::show(&self.catalog.fm_input_text);
            }
            Some(Action::FmSubmitNewFolder) => {
                self.catalog.fm_submit_new_folder(&self.home);
            }
            Some(Action::FmOpenRename(idx)) => {
                if let Some(entry) = self.catalog.fm_entries.get(idx).cloned() {
                    if crate::document_store::is_protected_root_folder(&entry.rel_path) {
                        self.catalog.status =
                            "Built-in profile root folders cannot be renamed.".into();
                    } else {
                        self.catalog.fm_input_text = entry.name;
                        self.catalog.fm_modal = Some(crate::catalog::FmModal::Rename {
                            target_rel: entry.rel_path,
                        });
                        self.catalog.editing = Some("fm_rename");
                        crate::platform::show(&self.catalog.fm_input_text);
                    }
                }
            }
            Some(Action::FmOpenRenameSelected) => {
                if let Some(rel) = self.catalog.fm_selected.iter().next().cloned() {
                    if crate::document_store::is_protected_root_folder(&rel) {
                        self.catalog.status =
                            "Built-in profile root folders cannot be renamed.".into();
                    } else {
                        let cur_name = rel.rsplit_once('/').map_or(rel.as_str(), |(_, n)| n);
                        self.catalog.fm_input_text = cur_name.to_owned();
                        self.catalog.fm_modal =
                            Some(crate::catalog::FmModal::Rename { target_rel: rel });
                        self.catalog.editing = Some("fm_rename");
                        crate::platform::show(&self.catalog.fm_input_text);
                    }
                } else {
                    self.catalog.status = "Select a single file or folder to rename.".into();
                }
            }
            Some(Action::FmSubmitRename) => {
                self.catalog.fm_submit_rename(&self.home);
            }
            Some(Action::FmRequestDeleteSingle(idx)) => {
                if let Some(entry) = self.catalog.fm_entries.get(idx).cloned() {
                    if crate::document_store::is_protected_root_folder(&entry.rel_path) {
                        self.catalog.status =
                            "Built-in profile root folders cannot be deleted.".into();
                    } else {
                        self.catalog.fm_modal = Some(crate::catalog::FmModal::ConfirmDelete {
                            paths: vec![entry.rel_path],
                        });
                    }
                }
            }
            Some(Action::FmRequestDeleteSelected) => {
                if self.catalog.fm_selected.is_empty() {
                    self.catalog.status = "Select one or more items to delete.".into();
                } else {
                    let paths: Vec<String> = self
                        .catalog
                        .fm_selected
                        .iter()
                        .filter(|p| !crate::document_store::is_protected_root_folder(p))
                        .cloned()
                        .collect();
                    if paths.is_empty() {
                        self.catalog.status =
                            "Built-in profile root folders cannot be deleted.".into();
                    } else {
                        self.catalog.fm_modal =
                            Some(crate::catalog::FmModal::ConfirmDelete { paths });
                    }
                }
            }
            Some(Action::FmConfirmDelete) => {
                self.catalog.fm_confirm_delete(&self.home);
            }
            Some(Action::FmOpenDetails(idx)) => {
                if let Some(entry) = self.catalog.fm_entries.get(idx).cloned() {
                    let game_home = self.catalog.config.game_home(&self.home);
                    match crate::document_store::fm_details(&self.home, &game_home, &entry.rel_path)
                    {
                        Ok(details) => {
                            self.catalog.fm_modal = Some(crate::catalog::FmModal::Details(details));
                        }
                        Err(e) => self.catalog.status = e,
                    }
                }
            }
            Some(Action::FmExportSingle(idx)) => {
                self.catalog.fm_modal = None;
                if let Some(entry) = self.catalog.fm_entries.get(idx).cloned() {
                    let game_home = self.catalog.config.game_home(&self.home);
                    match crate::document_store::resolve_fm_path(
                        &self.home,
                        &game_home,
                        &entry.rel_path,
                    ) {
                        Ok(path) => {
                            if path.is_dir() {
                                match crate::documents::export_folder_prepared(
                                    &self.home,
                                    self.catalog.config.profile,
                                    &path,
                                ) {
                                    Ok((zip_path, _, msg)) => {
                                        self.catalog.fm_clipboard = Some((vec![zip_path], false));
                                        self.catalog.status = msg;
                                    }
                                    Err(e) => self.catalog.status = e,
                                }
                            } else {
                                self.catalog.fm_clipboard = Some((vec![path.clone()], false));
                                self.catalog.status = match crate::documents::export_file(
                                    &self.home,
                                    self.catalog.config.profile,
                                    &path,
                                ) {
                                    Ok(msg) => msg,
                                    Err(e) => e,
                                };
                            }
                        }
                        Err(e) => self.catalog.status = e,
                    }
                }
            }
            Some(Action::FmExportSelected) => {
                let target_rel = self.catalog.fm_selected.iter().next().cloned().or_else(|| {
                    (!self.catalog.fm_current_dir.is_empty())
                        .then(|| self.catalog.fm_current_dir.clone())
                });
                if let Some(rel) = target_rel {
                    let game_home = self.catalog.config.game_home(&self.home);
                    match crate::document_store::resolve_fm_path(&self.home, &game_home, &rel) {
                        Ok(path) => {
                            if path.is_dir() {
                                match crate::documents::export_folder_prepared(
                                    &self.home,
                                    self.catalog.config.profile,
                                    &path,
                                ) {
                                    Ok((zip_path, _, msg)) => {
                                        self.catalog.fm_clipboard = Some((vec![zip_path], false));
                                        self.catalog.status = msg;
                                    }
                                    Err(e) => self.catalog.status = e,
                                }
                            } else {
                                self.catalog.fm_clipboard = Some((vec![path.clone()], false));
                                self.catalog.status = match crate::documents::export_file(
                                    &self.home,
                                    self.catalog.config.profile,
                                    &path,
                                ) {
                                    Ok(msg) => msg,
                                    Err(e) => e,
                                };
                            }
                        }
                        Err(e) => self.catalog.status = e,
                    }
                } else {
                    self.catalog.status =
                        "Select a file or folder (or tap Export on any row) to export.".into();
                }
            }
            Some(Action::OpenUpdateScreen) => {
                self.catalog.update_ui_open = true;
                self.catalog.update_anim_time = 0.0;
            }
            Some(Action::CloseUpdateScreen) => {
                self.catalog.update_ui_open = false;
                crate::updater::dismiss();
            }
            Some(Action::TriggerUpdateInstall) => {
                let status = crate::updater::get_status();
                match status {
                    crate::updater::UpdateStatus::Available(info) => {
                        crate::updater::download_and_install_in_background(info, self.home.clone());
                    }
                    crate::updater::UpdateStatus::Downloaded { path, .. } => {
                        crate::updater::trigger_install(&path);
                    }
                    crate::updater::UpdateStatus::Error(_) => {
                        if let Some(info) = self.catalog.update_info.clone() {
                            crate::updater::download_and_install_in_background(
                                info,
                                self.home.clone(),
                            );
                        }
                    }
                    _ => {}
                }
            }
            Some(Action::CheckForUpdates) => {
                self.catalog.status = "Checking for updates...".into();
                crate::updater::check_in_background();
            }
            Some(Action::FmCloseModal) => {
                self.catalog.fm_modal = None;
                self.catalog.editing = None;
                crate::platform::hide();
            }
            None => {}
        }
        Ok(())
    }
}
