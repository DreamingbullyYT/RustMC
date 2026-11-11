use super::*;
use crate::catalog::SettingsCategory;
use horizon_client::{
    gfx::Renderer,
    osk::Output,
};
use crate::touch_compat::ModeExt;
use crate::ui_theme::{Icon, color};
use std::sync::atomic::Ordering;
use winit::{
    event::{KeyEvent, Touch},
    keyboard::{KeyCode, PhysicalKey},
};

thread_local! {
    static ACTIVE_SLIDER_DRAG: std::cell::Cell<Option<(Action, f32, f32)>> = const { std::cell::Cell::new(None) };
    static SLIDER_TRACK_TARGETS: std::cell::RefCell<Vec<([f32; 4], Action, f32, f32)>> = const { std::cell::RefCell::new(Vec::new()) };
}

/// Centralized dynamic theme accessors backed by `crate::ui_theme::active_theme()`.
#[inline]
fn my3_bg() -> u32 {
    crate::ui_theme::active_theme().bg_solid
}
#[inline]
fn my3_surface() -> u32 {
    crate::ui_theme::active_theme().surface
}
#[inline]
fn my3_card_bg() -> u32 {
    crate::ui_theme::active_theme().surface_dim
}
#[inline]
fn my3_card_border() -> u32 {
    color::with_alpha(crate::ui_theme::active_theme().border, 0.42)
}
#[inline]
fn my3_surface_high() -> u32 {
    crate::ui_theme::active_theme().surface_bright
}
#[inline]
fn my3_border() -> u32 {
    color::with_alpha(crate::ui_theme::active_theme().border, 0.52)
}
#[inline]
fn my3_nav_active() -> u32 {
    crate::ui_theme::active_theme().surface_selected
}
#[inline]
fn my3_accent() -> u32 {
    crate::ui_theme::active_theme().accent
}
#[inline]
fn my3_accent_soft() -> u32 {
    crate::ui_theme::active_theme().accent_soft
}
#[inline]
fn my3_on_accent() -> u32 {
    crate::ui_theme::active_theme().on_accent
}
#[inline]
fn my3_track() -> u32 {
    crate::ui_theme::active_theme().surface_dim
}
#[inline]
fn my3_pause_bg() -> u32 {
    crate::ui_theme::active_theme().surface_bright
}
#[inline]
fn my3_cancel_bg() -> u32 {
    color::with_alpha(crate::ui_theme::active_theme().danger, 0.28)
}
#[inline]
fn my3_text() -> u32 {
    crate::ui_theme::active_theme().text_primary
}
#[inline]
fn my3_muted() -> u32 {
    crate::ui_theme::active_theme().text_muted
}

/// Smooth 60fps UI animation state across navigation, pages, accordions, theme previews, progress bars, and modals.
/// Automatically respects `motion::reduced_motion()` on low-end Android hardware.
pub(crate) struct AnimState {
    pub last_tick: std::time::Instant,
    pub last_page: Page,
    pub last_category: SettingsCategory,
    pub last_has_modal: bool,
    pub nav_pill_y: f32,
    pub nav_pill_vel: f32,
    pub target_nav_y: f32,
    pub page_fade: f32,
    pub page_slide: f32,
    pub sub_tab_t: f32,
    pub toast_anim: f32,
    pub progress_smooth: f32,
    pub modal_anim: f32,
    pub shimmer_phase: f32,
    pub section_expand: [f32; 3],
    pub theme_pulse: f32,
    pub intro_start: Option<std::time::Instant>,
    pub intro_elapsed: f32,
    pub intro_done: bool,
    pub switches: std::collections::HashMap<String, f32>,
}

impl Default for AnimState {
    fn default() -> Self {
        Self {
            last_tick: std::time::Instant::now(),
            last_page: Page::Play,
            last_category: SettingsCategory::Overview,
            last_has_modal: false,
            nav_pill_y: 62.0,
            nav_pill_vel: 0.0,
            target_nav_y: 62.0,
            page_fade: 1.0,
            page_slide: 0.0,
            sub_tab_t: 1.0,
            toast_anim: 1.0,
            progress_smooth: 0.0,
            modal_anim: 0.0,
            shimmer_phase: 0.0,
            section_expand: [0.0; 3],
            theme_pulse: 1.0,
            intro_start: Some(std::time::Instant::now()),
            intro_elapsed: 0.0,
            intro_done: false,
            switches: std::collections::HashMap::new(),
        }
    }
}

impl AnimState {
    pub fn step(
        &mut self,
        page: Page,
        category: SettingsCategory,
        target_nav_y: f32,
        has_modal: bool,
        sections: [bool; 3],
    ) {
        let now = std::time::Instant::now();
        let intro_t0 = *self.intro_start.get_or_insert(now);
        self.intro_elapsed = now.duration_since(intro_t0).as_secs_f32();
        let raw_dt = now
            .duration_since(self.last_tick)
            .as_secs_f32()
            .clamp(0.001, 0.08);
        self.last_tick = now;
        self.target_nav_y = target_nav_y;

        if crate::ui_theme::motion::reduced_motion() {
            self.intro_done = true;
            self.last_page = page;
            self.last_category = category;
            self.last_has_modal = has_modal;
            self.nav_pill_y = target_nav_y;
            self.page_fade = 1.0;
            self.page_slide = 0.0;
            self.modal_anim = if has_modal { 1.0 } else { 0.0 };
            self.theme_pulse = 1.0;
            for i in 0..3 {
                self.section_expand[i] = if sections[i] { 1.0 } else { 0.0 };
            }
            return;
        }

        let page_changed =
            page != self.last_page || (page == Page::Settings && category != self.last_category);
        let modal_changed = has_modal != self.last_has_modal;
        self.last_has_modal = has_modal;

        if page_changed {
            self.last_page = page;
            self.last_category = category;
            self.page_fade = 0.0;
            self.page_slide = 18.0;
        }
        if modal_changed && has_modal {
            self.modal_anim = 0.0;
        }

        // Clamp first transition frame dt to 16ms so an idle gap before a tap never
        // consumes most of the entrance animation before the first frame renders.
        let dt = if page_changed || modal_changed {
            raw_dt.min(0.016)
        } else {
            raw_dt
        };

        let speed = (dt * 14.0).min(1.0);
        crate::ui_theme::motion::step_spring_damper(
            &mut self.nav_pill_y,
            &mut self.nav_pill_vel,
            target_nav_y,
            380.0,
            26.0,
            dt,
        );
        self.page_fade = (self.page_fade + dt * 7.5).min(1.0);
        self.page_slide *= (1.0 - speed).max(0.0);
        self.sub_tab_t = (self.sub_tab_t + dt * 7.0).min(1.0);
        self.toast_anim = (self.toast_anim + dt * 6.5).min(1.0);
        let modal_target = if has_modal { 1.0 } else { 0.0 };
        self.modal_anim += (modal_target - self.modal_anim) * (dt * 14.0).min(1.0);
        self.theme_pulse = (self.theme_pulse + dt * 6.5).min(1.0);
        for i in 0..3 {
            let target = if sections[i] { 1.0 } else { 0.0 };
            self.section_expand[i] += (target - self.section_expand[i]) * (dt * 14.0).min(1.0);
        }
        self.shimmer_phase = (self.shimmer_phase + dt * 0.95) % 1.0;
    }

    pub fn trigger_page_transition(&mut self) {
        if !crate::ui_theme::motion::reduced_motion() {
            self.last_tick = std::time::Instant::now();
            self.page_fade = 0.0;
            self.page_slide = 18.0;
        }
    }

    pub fn trigger_theme_pulse(&mut self) {
        if !crate::ui_theme::motion::reduced_motion() {
            self.last_tick = std::time::Instant::now();
            self.theme_pulse = 0.0;
        }
    }

    pub fn step_switch(&mut self, key: &str, on: bool, dt: f32) -> f32 {
        if crate::ui_theme::motion::reduced_motion() {
            return if on { 1.0 } else { 0.0 };
        }
        let target = if on { 1.0 } else { 0.0 };
        let entry = self.switches.entry(key.to_string()).or_insert(target);
        if (*entry - target).abs() > 0.001 {
            *entry += (target - *entry) * (dt * 16.0).min(1.0);
            if (*entry - target).abs() < 0.005 {
                *entry = target;
            }
        }
        *entry
    }

    pub fn is_active(&self) -> bool {
        if !self.intro_done {
            return true;
        }
        if crate::ui_theme::motion::reduced_motion() {
            return false;
        }
        self.page_fade < 0.99
            || self.page_slide.abs() > 0.35
            || (self.nav_pill_y - self.target_nav_y).abs() > 0.25
            || self.nav_pill_vel.abs() > 0.5
            || self.sub_tab_t < 0.99
            || self.toast_anim < 0.99
            || (self.modal_anim > 0.01 && self.modal_anim < 0.99)
            || self.theme_pulse < 0.99
            || self.section_expand.iter().any(|v| *v > 0.01 && *v < 0.99)
            || self.switches.values().any(|v| *v > 0.005 && *v < 0.995)
    }
}

#[derive(Clone, Copy, PartialEq)]
pub(super) enum Action {
    Page(Page),
    SettingsCat(SettingsCategory),
    Play,
    PauseInstall,
    CancelInstall,
    PauseCatalogDownload,
    CancelCatalogDownload,
    Search,
    ShaderSource(crate::shader_catalog::Origin),
    ModSource(crate::shader_catalog::Origin),
    SetCompilerMode(crate::catalog::CompilerMode),
    CompilerRunQuery,
    CompilerPortLocal(usize),
    Field(&'static str),
    FinishNameEdit,
    Install(usize),
    OpenProject(usize),
    OpenBundledProject(&'static str),
    CloseProjectModal,
    SelectModalVersion(usize),
    InstallModalProject,
    Import(crate::content_files::Kind),
    OpenFolder(crate::content_files::Kind),
    ToggleSection(usize),
    ToggleLocal(usize),
    DeleteLocal(usize),
    ConfirmDeleteLocal,
    ConfirmExit,
    Next,
    Previous,
    Profile,
    Logs,
    RecordPerformance,
    ExportPerformance,
    ExportDiagnostics,
    Account,
    AddAccount,
    SignIn,
    ElySignIn,
    MicrosoftAccount,
    OfflineAccount,
    SubmitEly,
    SavedAccounts,
    SavedPage(bool),
    SelectAccount(usize),
    SelectOffline,
    WardrobeKind(bool),
    WardrobeSelect(usize),
    WardrobeImport,
    WardrobeCloud,
    WardrobeUpload,
    WardrobeActivateCape(usize),
    WardrobeUseCloud,
    WardrobeModel,
    WardrobeRotate(i8),
    WardrobeZoom(bool),
    WardrobeHideCape,
    DeviceSignIn,
    CopyLoginCode,
    CancelLogin,
    ReviewApp,
    Repository,
    Discord,
    SignOut,
    LoginLink,
    CloseLogin,
    Renderer,
    RenderScale,
    ChooseRenderScale(crate::display::RenderScale),
    UiScale,
    ChooseUiScale(u32),
    Orientation,
    InputHost,
    ChooseInputHost(crate::catalog::InputHost),
    ChooseOrientation(crate::catalog::Orientation),
    ChooseRenderer(crate::catalog::RendererMode),
    ChooseProfile(u32),
    Dismiss,
    Immersive,
    Cutout,
    Aspect,
    ChooseAspect(crate::display::Aspect),
    DisplayHz,
    ChooseDisplayHz(u16),
    CacheBudget,
    ChooseCacheBudget(u32),
    ToggleBundled(&'static str),
    WorldFiles,
    GameFiles,
    WorkerLimit,
    ChooseWorkerLimit(u32),
    PerformanceHints,
    GpuProfile,
    // Video settings actions (options.txt)
    RenderScaleDelta(i32),
    RenderDistDelta(i32),
    SimDistDelta(i32),
    CycleMaxFps,
    CycleGraphicsPreset,
    ToggleVsync,
    ToggleSmoothLighting,
    CycleClouds,
    ToggleMenuBlur,
    FovDelta(f32),
    // Animation & Diagnostics Modal actions
    ToggleLauncherAnimations,
    ToggleGameAnimations,
    CloseDiagnosticsSummary,
    OpenReportsFolder,
    // Experimental settings actions
    ToggleSuperPerf,
    ToggleBatteryPerf,
    ToggleMaxFpsMode,
    ToggleZeroLatencyTouch,
    ToggleFastChunkUpload,
    // Controls & Control Editor actions
    OpenControlEditor,
    CloseControlEditor,
    ControlProfileChooser,
    ChooseControlProfile(u32),
    SaveControlProfileAsNew,
    ExportControlProfile,
    ImportControlProfile,
    MouseSensDelta(f32),
    TouchSensDelta(f32),
    VirtualMouseSpeedDelta(f32),
    ButtonScaleDelta(f32),
    ToggleControlScheme,
    SelectControlMode(crate::touch_compat::ControlStyle),
    CopyActivePreset,
    EditActivePresetMeta,
    ConfirmReplaceDuplicatePreset,
    CancelReplaceDuplicatePreset,
    ToggleSplitControls,
    ToggleSprintLatch,
    ToggleLeftHanded,
    ToggleInvertY,
    ToggleGyro,
    GyroSensDelta(f32),
    ToggleVirtualMouseEnabled,
    PointerSizeDelta(f32),
    CycleJoystickZone,
    ToggleTouchVibrate,
    ToggleAutoJump,
    ToggleInventoryControls,
    // Theme customization actions (Settings -> Launcher Settings -> Theme)
    SelectThemePreset(crate::ui_theme::ThemePreset),
    SetThemeAppearance(crate::ui_theme::ThemeAppearance),
    SetThemeBackgroundMode(crate::ui_theme::BackgroundMode),
    SetGradientDirection(crate::ui_theme::GradientDirection),
    ToggleGradientThreeStops,
    StepGradientMidPos(i8),
    SelectAccentSwatch(u32),
    SelectBgSwatch(u32),
    SelectGradStartSwatch(u32),
    SelectGradEndSwatch(u32),
    ResetTheme,
    // Intro & Installed / File Manager actions
    SkipIntro,
    SetInstalledSubTab(crate::catalog::InstalledSubTab),
    SetInstalledFilter(crate::catalog::InstalledFilter),
    CycleInstalledSort,
    ExportLocal(usize),
    FmNavigateRoot(&'static str),
    FmBreadcrumb(usize),
    FmBack,
    FmForward,
    FmUp,
    FmOpenEntry(usize),
    FmToggleSelect(usize),
    FmSelectAll,
    FmClearSelection,
    FmCopy,
    FmCut,
    FmPaste,
    FmImportHere,
    FmImportFolderHere,
    FmOpenNewFolder,
    FmSubmitNewFolder,
    FmOpenRename(usize),
    FmOpenRenameSelected,
    FmSubmitRename,
    FmRequestDeleteSingle(usize),
    FmRequestDeleteSelected,
    FmConfirmDelete,
    FmOpenDetails(usize),
    FmExportSingle(usize),
    FmExportSelected,
    FmCloseModal,
    OpenUpdateScreen,
    CloseUpdateScreen,
    TriggerUpdateInstall,
    CheckForUpdates,
}

pub(super) fn button(
    ui: &mut Ui,
    targets: &mut Vec<([f32; 4], Action)>,
    rect: [f32; 4],
    label: &str,
    action: Action,
    accent: bool,
) {
    let [x, y, w, h] = rect;
    let radius = (h * 0.22).clamp(6.0, 10.0);
    ui.rounded(
        x,
        y,
        w,
        h,
        radius,
        if accent {
            my3_accent()
        } else {
            my3_surface_high()
        },
    );
    if !accent {
        ui.border(x, y, w, h, radius, my3_border());
    }
    let padding = (w * 0.055).clamp(4.0, 9.0);
    let avail_w = (w - 2.0 * padding).max(16.0);
    let mut font = if w < 90.0 {
        12.5
    } else if h <= 36.0 {
        13.5
    } else {
        14.5
    };
    while font > 9.5 && ui.measure(Face::Medium, font, label) > avail_w {
        font -= 0.5;
    }
    let fitted = ui.fit_text(Face::Medium, font, label, avail_w);
    let text_w = ui.measure(Face::Medium, font, &fitted);
    let left_align = matches!(action, Action::Field(_)) || label.ends_with(" v");
    let tx = if left_align {
        x + padding
    } else {
        x + ((w - text_w) * 0.5).max(padding)
    };
    let ty = y + ((h - font * 1.45) * 0.5).max(2.0);
    ui.text(
        Face::Medium,
        font,
        tx,
        ty,
        if accent { my3_on_accent() } else { my3_text() },
        &fitted,
    );
    if let Some(c) = ui.clip_rect(rect) {
        targets.push((c, action));
    }
}

fn toggle(
    ui: &mut Ui,
    targets: &mut Vec<([f32; 4], Action)>,
    rect: [f32; 4],
    on: bool,
    action: Action,
    switch_t: f32,
) {
    let [x, y, w, h] = rect;
    ui.text(
        Face::Medium,
        13.5,
        x,
        y + (h - 20.0) * 0.5,
        if on { my3_accent() } else { my3_muted() },
        if on { "Enabled" } else { "Off" },
    );
    let track_x = x + w - 52.0;
    let track_y = y + (h - 28.0) * 0.5;
    ui.m3_switch_animated(track_x, track_y, switch_t);
    if let Some(c) = ui.clip_rect([x, y, w, h]) {
        targets.push((c, action));
    }
}

#[allow(clippy::too_many_arguments)]
fn draw_setting_card(
    ui: &mut Ui,
    targets: &mut Vec<([f32; 4], Action)>,
    list_targets: &mut Vec<([f32; 4], Action)>,
    anim: &mut AnimState,
    x: f32,
    y: f32,
    w: f32,
    h: f32,
    title: &str,
    subtitle: &str,
    right_control: SettingControl<'_>,
) {
    if let SettingControl::SectionHeader(badge, icon) = right_control {
        ui.rounded(x, y + 6.0, w, h - 10.0, 10.0, my3_surface());
        ui.border(x, y + 6.0, w, h - 10.0, 10.0, my3_border());
        ui.rounded(x + 12.0, y + 13.0, 34.0, 34.0, 8.0, my3_accent_soft());
        ui.icon(icon, x + 20.0, y + 21.0, 18.0, my3_accent());
        let text_max = (w - 170.0).max(90.0);
        let fitted_title = ui.fit_text(Face::SemiBold, 13.5, title, text_max);
        ui.text(
            Face::SemiBold,
            13.5,
            x + 56.0,
            y + 12.0,
            my3_accent(),
            &fitted_title,
        );
        let fitted_sub = ui.fit_text(Face::Regular, 11.5, subtitle, text_max);
        ui.text(
            Face::Regular,
            11.5,
            x + 56.0,
            y + 31.0,
            my3_muted(),
            &fitted_sub,
        );
        let chip_w = (ui.measure(Face::SemiBold, 10.5, badge) + 22.0).clamp(64.0, 124.0);
        let chip_x = x + w - chip_w - 14.0;
        let chip_y = y + (h - 24.0) * 0.5 + 1.0;
        ui.rounded(chip_x, chip_y, chip_w, 24.0, 6.0, my3_accent_soft());
        ui.border(chip_x, chip_y, chip_w, 24.0, 6.0, my3_accent());
        ui.centered_fit(
            Face::SemiBold,
            10.0,
            [chip_x + 4.0, chip_y, chip_w - 8.0, 24.0],
            my3_accent(),
            badge,
        );
        return;
    }

    let is_active_card = matches!(
        right_control,
        SettingControl::Toggle(true, _)
            | SettingControl::Button(_, _, true)
            | SettingControl::TextField { active: true, .. }
    );
    ui.rounded(
        x,
        y + 1.0,
        w,
        (h - 2.0).max(24.0),
        10.0,
        if is_active_card {
            my3_surface_high()
        } else {
            my3_card_bg()
        },
    );
    ui.border(
        x,
        y + 1.0,
        w,
        (h - 2.0).max(24.0),
        10.0,
        if is_active_card {
            my3_accent()
        } else {
            my3_card_border()
        },
    );
    if is_active_card {
        ui.rounded(
            x + 4.0,
            y + 12.0,
            3.5,
            (h - 24.0).max(12.0),
            1.8,
            my3_accent(),
        );
    }

    let text_max = (w - 236.0).max(90.0);
    let fitted_title = ui.fit_text(Face::SemiBold, 15.0, title, text_max);
    ui.text(
        Face::SemiBold,
        15.0,
        x + 16.0,
        y + 10.0,
        my3_text(),
        &fitted_title,
    );
    let fitted_sub = ui.fit_text(Face::Regular, 12.5, subtitle, text_max);
    ui.text(
        Face::Regular,
        12.5,
        x + 16.0,
        y + 32.0,
        my3_muted(),
        &fitted_sub,
    );

    match right_control {
        SettingControl::SectionHeader(..) => {}
        SettingControl::Toggle(on, action) => {
            let rect = [x + w - 122.0, y + (h - 38.0) * 0.5, 108.0, 38.0];
            list_targets.push((rect, action));
            let switch_t = anim.step_switch(title, on, 0.016);
            toggle(ui, targets, rect, on, action, switch_t);
        }
        SettingControl::Button(label, action, accent) => {
            let bw = 176.0f32.min(w * 0.44);
            let rect = [x + w - bw - 14.0, y + (h - 38.0) * 0.5, bw, 38.0];
            list_targets.push((rect, action));
            button(ui, targets, rect, label, action, accent);
        }
        SettingControl::TextField {
            label,
            value,
            hint,
            active,
            action,
        } => {
            let bw = 186.0f32.min(w * 0.46);
            let fh = if label.is_empty() { 36.0 } else { 42.0 };
            let rect = ui.text_field(
                x + w - bw - 14.0,
                y + (h - fh) * 0.5,
                bw,
                fh,
                label,
                value,
                hint,
                active,
            );
            list_targets.push((rect, action));
            if let Some(c) = ui.clip_rect(rect) {
                targets.push((c, action));
            }
        }
        SettingControl::Stepper(value_label, minus_action, plus_action) => {
            let right_x = x + w - 14.0;
            let plus_rect = [right_x - 36.0, y + (h - 36.0) * 0.5, 36.0, 36.0];
            let val_w = 88.0;
            let val_x = plus_rect[0] - val_w - 6.0;
            let minus_rect = [val_x - 36.0 - 6.0, y + (h - 36.0) * 0.5, 36.0, 36.0];

            ui.rounded(
                val_x,
                y + (h - 36.0) * 0.5,
                val_w,
                36.0,
                12.0,
                my3_surface(),
            );
            ui.border(val_x, y + (h - 36.0) * 0.5, val_w, 36.0, 12.0, my3_border());
            let fitted_v = ui.fit_text(Face::SemiBold, 13.5, value_label, val_w - 10.0);
            let tw = ui.measure(Face::SemiBold, 13.5, &fitted_v);
            ui.text(
                Face::SemiBold,
                13.5,
                val_x + (val_w - tw) * 0.5,
                y + (h - 20.0) * 0.5,
                my3_accent(),
                &fitted_v,
            );
            list_targets.push((minus_rect, minus_action));
            list_targets.push((plus_rect, plus_action));
            button(ui, targets, minus_rect, "-", minus_action, false);
            button(ui, targets, plus_rect, "+", plus_action, true);
        }
        SettingControl::Slider {
            value_label,
            fraction,
            minus_action,
            plus_action,
        } => {
            let right_x = x + w - 14.0;
            let plus_rect = [right_x - 34.0, y + (h - 34.0) * 0.5, 34.0, 34.0];
            let track_w = 112.0f32.min((w * 0.26).max(76.0));
            let track_x = plus_rect[0] - track_w - 6.0;
            let minus_rect = [track_x - 34.0 - 6.0, y + (h - 34.0) * 0.5, 34.0, 34.0];

            let pill_y = y + (h - 36.0) * 0.5;
            ui.rounded(track_x, pill_y, track_w, 36.0, 12.0, my3_surface());
            ui.border(track_x, pill_y, track_w, 36.0, 12.0, my3_border());

            // M3 Expressive Value Label + Linear Track Bar with Active Thumb
            let fitted_v = ui.fit_text(Face::SemiBold, 12.0, value_label, track_w - 12.0);
            let tw = ui.measure(Face::SemiBold, 12.0, &fitted_v);
            ui.text(
                Face::SemiBold,
                12.0,
                track_x + (track_w - tw) * 0.5,
                pill_y + 4.0,
                my3_accent(),
                &fitted_v,
            );

            let bar_x = track_x + 10.0;
            let bar_w = (track_w - 20.0).max(24.0);
            let bar_y = pill_y + 23.0;
            let t = fraction.clamp(0.0, 1.0);
            let is_dragging = ACTIVE_SLIDER_DRAG
                .with(|c| c.get().is_some_and(|(_, bx, _)| (bx - bar_x).abs() < 1.0));
            ui.m3_expressive_slider(bar_x, bar_y, bar_w, 6.0, t, is_dragging);

            let left_half = [track_x, pill_y, track_w * 0.5, 36.0];
            let right_half = [track_x + track_w * 0.5, pill_y, track_w * 0.5, 36.0];
            if let Some(c) = ui.clip_rect([track_x, pill_y, track_w, 36.0]) {
                SLIDER_TRACK_TARGETS.with(|t| t.borrow_mut().push((c, minus_action, bar_x, bar_w)));
            }
            if let Some(c) = ui.clip_rect(left_half) {
                targets.push((c, minus_action));
            }
            if let Some(c) = ui.clip_rect(right_half) {
                targets.push((c, plus_action));
            }
            list_targets.push((left_half, minus_action));
            list_targets.push((right_half, plus_action));
            list_targets.push((minus_rect, minus_action));
            list_targets.push((plus_rect, plus_action));
            button(ui, targets, minus_rect, "-", minus_action, false);
            button(ui, targets, plus_rect, "+", plus_action, true);
        }
    }
}

enum SettingControl<'a> {
    SectionHeader(&'a str, Icon),
    Toggle(bool, Action),
    Button(&'a str, Action, bool),
    TextField {
        label: &'a str,
        value: &'a str,
        hint: &'a str,
        active: bool,
        action: Action,
    },
    Stepper(&'a str, Action, Action),
    Slider {
        value_label: &'a str,
        fraction: f32,
        minus_action: Action,
        plus_action: Action,
    },
}

fn page_icon(page: Page) -> Icon {
    match page {
        Page::Play => Icon::Play,
        Page::Mods => Icon::Mods,
        Page::Packs => Icon::Packs,
        Page::Shaders => Icon::Shaders,
        Page::Compiler => Icon::Terminal,
        Page::Installed => Icon::Installed,
        Page::Settings => Icon::Settings,
        Page::Skins => Icon::Skins,
    }
}

fn icon_action_button(
    ui: &mut Ui,
    targets: &mut Vec<([f32; 4], Action)>,
    rect: [f32; 4],
    bg: u32,
    icon: Icon,
    action: Action,
) {
    let [x, y, w, h] = rect;
    ui.rounded(x, y, w, h, 8.0, bg);
    ui.border(x, y, w, h, 8.0, my3_border());
    let icon_size = 18.0;
    ui.icon(
        icon,
        x + (w - icon_size) * 0.5,
        y + (h - icon_size) * 0.5,
        icon_size,
        my3_text(),
    );
    targets.push((rect, action));
}

#[allow(clippy::too_many_arguments)]
fn draw_my3_progress_view(
    ui: &mut Ui,
    targets: &mut Vec<([f32; 4], Action)>,
    content_x: f32,
    content_w: f32,
    h: f32,
    title: &str,
    subtitle_left: &str,
    subtitle_right: &str,
    speed_label: &str,
    eta_label: &str,
    step_index: usize,
    steps: &[&str],
    fraction: f32,
    shimmer_phase: f32,
    stage_text: &str,
    paused: bool,
    pause_action: Action,
    cancel_action: Action,
) {
    let clamped = fraction.clamp(0.0, 1.0);
    let pulse = (shimmer_phase * std::f32::consts::TAU).sin() * 0.5 + 0.5;

    // Material 3 Expressive Elevated Surface Container
    let card_y = (h * 0.14).clamp(58.0, 88.0);
    let card_h = (h - card_y - 20.0).clamp(218.0, 326.0);
    ui.rounded(content_x, card_y, content_w, card_h, 12.0, my3_surface());
    ui.border(content_x, card_y, content_w, card_h, 12.0, my3_border());

    let inner_x = content_x + 18.0;
    let inner_w = (content_w - 36.0).max(120.0);
    let head_y = card_y + 16.0;

    // 1. Animated Header Icon Badge + Title & Live Percentage Pill
    let badge_sz = 44.0;
    if !paused {
        let halo_pad = 2.0 + pulse * 2.5;
        ui.rounded(
            inner_x - halo_pad,
            head_y - halo_pad,
            badge_sz + halo_pad * 2.0,
            badge_sz + halo_pad * 2.0,
            10.0,
            color::with_alpha(my3_accent(), 0.12 + 0.10 * pulse),
        );
    }
    ui.rounded(inner_x, head_y, badge_sz, badge_sz, 9.0, my3_accent_soft());
    ui.border(inner_x, head_y, badge_sz, badge_sz, 9.0, my3_accent());
    let header_icon = if paused {
        Icon::Pause
    } else if clamped >= 0.99 {
        Icon::Check
    } else {
        Icon::Packs
    };
    ui.icon(
        header_icon,
        inner_x + 11.0,
        head_y + 11.0,
        22.0,
        my3_accent(),
    );

    let pct_str = format!("{:.0}%", clamped * 100.0);
    let pct_pill_w = (ui.measure(Face::SemiBold, 13.0, &pct_str) + 20.0).max(54.0);
    let pct_pill_x = inner_x + inner_w - pct_pill_w;
    ui.rounded(pct_pill_x, head_y, pct_pill_w, 26.0, 7.0, my3_accent());
    ui.centered_fit(
        Face::SemiBold,
        12.5,
        [pct_pill_x + 4.0, head_y, pct_pill_w - 8.0, 26.0],
        my3_on_accent(),
        &pct_str,
    );

    let right_w = ui.measure(Face::Medium, 12.0, subtitle_right);
    ui.text(
        Face::Medium,
        12.0,
        inner_x + inner_w - right_w,
        head_y + 30.0,
        my3_muted(),
        subtitle_right,
    );

    let title_x = inner_x + badge_sz + 14.0;
    let title_max = (pct_pill_x - title_x - 12.0).max(80.0);
    let title_fitted = ui.fit_text(Face::SemiBold, 19.0, title, title_max);
    ui.text(
        Face::SemiBold,
        19.0,
        title_x,
        head_y - 1.0,
        my3_text(),
        &title_fitted,
    );

    let step_badge = format!(
        "Step {} of {}",
        (step_index + 1).min(steps.len().max(1)),
        steps.len().max(1)
    );
    let step_badge_w = (ui.measure(Face::SemiBold, 10.5, &step_badge) + 14.0).max(68.0);
    ui.rounded(
        title_x,
        head_y + 25.0,
        step_badge_w,
        20.0,
        6.0,
        my3_surface_high(),
    );
    ui.border(
        title_x,
        head_y + 25.0,
        step_badge_w,
        20.0,
        6.0,
        my3_accent(),
    );
    ui.centered_fit(
        Face::SemiBold,
        10.5,
        [title_x + 4.0, head_y + 25.0, step_badge_w - 8.0, 20.0],
        my3_accent(),
        &step_badge,
    );

    let left_x = title_x + step_badge_w + 10.0;
    let left_max = (inner_x + inner_w - right_w - left_x - 12.0).max(50.0);
    let left_fitted = ui.fit_text(Face::Regular, 12.0, subtitle_left, left_max);
    ui.text(
        Face::Regular,
        12.0,
        left_x,
        head_y + 27.0,
        my3_muted(),
        &left_fitted,
    );

    // 2. Material 2026 (M3 Expressive) Wavy Linear Progress Indicator & Morphing Spinner
    let bar_y = head_y + 56.0;
    let bar_h = 14.0;
    ui.m3_wavy_progress(
        inner_x,
        bar_y,
        inner_w,
        bar_h,
        clamped,
        shimmer_phase,
        paused,
    );
    if !paused {
        ui.m3_wavy_circular_spinner(pct_pill_x - 16.0, head_y + 13.0, 8.5, shimmer_phase);
    }

    // 3. Telemetry Sub-Card (Active Stage + Transfer Speed & ETA)
    let telem_y = bar_y + 22.0;
    let telem_h = 30.0;
    ui.rounded(inner_x, telem_y, inner_w, telem_h, 8.0, my3_card_bg());
    ui.border(inner_x, telem_y, inner_w, telem_h, 8.0, my3_card_border());
    ui.icon(
        if paused { Icon::Pause } else { Icon::Terminal },
        inner_x + 10.0,
        telem_y + 8.0,
        14.0,
        my3_accent(),
    );
    let metrics_line = format!("{speed_label}  •  {eta_label}");
    let metrics_w = ui.measure(Face::Medium, 12.0, &metrics_line);
    let stage_line = if paused {
        format!("Paused • {stage_text}")
    } else {
        stage_text.to_owned()
    };
    let stage_max = (inner_w - metrics_w - 44.0).max(70.0);
    let stage_fitted = ui.fit_text(Face::Regular, 12.0, &stage_line, stage_max);
    ui.text(
        Face::Regular,
        12.0,
        inner_x + 30.0,
        telem_y + 6.0,
        my3_text(),
        &stage_fitted,
    );
    ui.text(
        Face::Medium,
        12.0,
        inner_x + inner_w - metrics_w - 10.0,
        telem_y + 6.0,
        my3_accent(),
        &metrics_line,
    );

    // 4. Multi-Step Pipeline Cards with Dedicated Vector Icons
    let step_y = telem_y + 40.0;
    if !steps.is_empty() {
        let gap = 8.0;
        let n = steps.len() as f32;
        let pill_w = ((inner_w - gap * (n - 1.0)) / n).max(40.0);
        let pill_h = 32.0;
        for (idx, name) in steps.iter().enumerate() {
            let px = inner_x + idx as f32 * (pill_w + gap);
            let done = idx < step_index;
            let active = idx == step_index;
            let bg = if active {
                my3_accent_soft()
            } else if done {
                my3_surface_high()
            } else {
                my3_card_bg()
            };
            let border_col = if active || done {
                my3_accent()
            } else {
                my3_card_border()
            };
            ui.rounded(px, step_y, pill_w, pill_h, 8.0, bg);
            ui.border(px, step_y, pill_w, pill_h, 8.0, border_col);

            let step_icon = if done {
                Icon::Check
            } else {
                match *name {
                    "Manifest" | "Resolve" => Icon::FileText,
                    "Metadata" => Icon::Terminal,
                    "Client JAR" | "Download" => Icon::Packs,
                    "Verify" => Icon::Search,
                    "Extract" => Icon::Folder,
                    _ => Icon::Play,
                }
            };
            let fg = if active || done {
                my3_text()
            } else {
                my3_muted()
            };
            let ic_col = if active || done {
                my3_accent()
            } else {
                my3_muted()
            };
            ui.icon(step_icon, px + 7.0, step_y + 9.0, 14.0, ic_col);
            let fitted = ui.fit_text(Face::Medium, 11.0, name, (pill_w - 26.0).max(16.0));
            ui.text(Face::Medium, 11.0, px + 24.0, step_y + 8.0, fg, &fitted);

            if active && !paused {
                let sub_w = ((pill_w - 12.0) * (0.25 + 0.75 * pulse)).max(8.0);
                ui.rounded(
                    px + 6.0,
                    step_y + pill_h - 3.5,
                    sub_w,
                    2.0,
                    1.0,
                    my3_accent(),
                );
            }
        }
    }

    // 5. Bottom-Right Material 3 Action Buttons (Pause/Resume & Cancel with Vector Icons)
    let btn_y = (card_y + card_h - 48.0).max(step_y + 40.0);
    let btn_h = 36.0;
    let cancel_w = 96.0;
    let pause_w = 104.0;
    let cancel_x = inner_x + inner_w - cancel_w;
    let pause_x = cancel_x - pause_w - 10.0;

    let pause_rect = [pause_x, btn_y, pause_w, btn_h];
    ui.rounded(
        pause_rect[0],
        pause_rect[1],
        pause_rect[2],
        pause_rect[3],
        8.0,
        my3_pause_bg(),
    );
    ui.border(
        pause_rect[0],
        pause_rect[1],
        pause_rect[2],
        pause_rect[3],
        8.0,
        my3_accent(),
    );
    ui.icon(
        if paused { Icon::Play } else { Icon::Pause },
        pause_rect[0] + 12.0,
        pause_rect[1] + 10.0,
        16.0,
        my3_accent(),
    );
    ui.text(
        Face::SemiBold,
        12.5,
        pause_rect[0] + 34.0,
        pause_rect[1] + 9.0,
        my3_text(),
        if paused { "Resume" } else { "Pause" },
    );
    targets.push((pause_rect, pause_action));

    let cancel_rect = [cancel_x, btn_y, cancel_w, btn_h];
    ui.rounded(
        cancel_rect[0],
        cancel_rect[1],
        cancel_rect[2],
        cancel_rect[3],
        8.0,
        my3_cancel_bg(),
    );
    ui.border(
        cancel_rect[0],
        cancel_rect[1],
        cancel_rect[2],
        cancel_rect[3],
        8.0,
        crate::ui_theme::active_theme().danger,
    );
    ui.icon(
        Icon::Close,
        cancel_rect[0] + 12.0,
        cancel_rect[1] + 10.0,
        16.0,
        my3_text(),
    );
    ui.text(
        Face::SemiBold,
        12.5,
        cancel_rect[0] + 34.0,
        cancel_rect[1] + 9.0,
        my3_text(),
        "Cancel",
    );
    targets.push((cancel_rect, cancel_action));
}
