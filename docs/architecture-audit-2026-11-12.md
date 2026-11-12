# Android rebuild — source audit (2026-11-12)

## Scope and pinned inputs

This audit reads the exact submodule revisions pinned by rustMC at `63236e85b99070bea04ab0c22b3bbe774971d4f2`:

- Android Edition: `DreamingbullyYT/horizon-android-edition@25e4516e06e049e4c7c101338fd7f15f6874d033`
- GoyDevv reference: `GoyDevv/Horizon-Android@ae4c85dc5b31da2078ee1e1009a53ced7d7ed1ba`
- Android Edition's engine pin: `3b691ea10b5fbbc90e00cb24e96a1c841601bcfb`
- Upstream Horizon main observed during this audit: `f24ee1accf1c816736e6e067939d04f3853f474e`

The upstream comparison reports the Android Edition pin is 3,330 commits behind the observed upstream main (0 commits ahead). This is a commit-distance indicator, not a statement that all 3,330 commits are relevant to Android. The overlay must be rebased and compiled against the selected upstream commit before it is considered compatible.

## Android Edition baseline: reusable work

The pinned baseline already contains:

- Kotlin + Jetpack Compose launcher app, with a JNI boundary to Rust.
- Touch input routing and an in-game controls/editor stack, including swipe-look, hotbar/menu gestures, sensitivity tuning and hardware input support.
- HNMod build adapter/service and an embedded toolchain path.
- In-app runtime updater with version directories, SHA-256 checks, startup guards and rollback.
- Android installer/game crates and a readable `android/overlay/` tree.
- Android-specific build scripts and CI workflows.

Important source locations include:

- `app/src/main/java/dev/horizon/launcher/MainActivity.kt`
- `app/src/main/java/dev/horizon/launcher/ui/HorizonLauncherUi.kt`
- `app/src/main/java/dev/horizon/launcher/game/HorizonGameControls.kt`
- `app/src/main/java/dev/horizon/launcher/game/TouchInputRouter.kt`
- `app/src/main/java/dev/horizon/launcher/data/Updater.kt`
- `app/src/main/java/dev/horizon/launcher/data/HnmodBuildService.kt`
- `android/overlay/` and `android/mods/plugin-loader/`

The existing launcher UI is a 928-line Compose screen with a “World Atlas” visual identity. It is functional source to audit, not the final UI direction: rustMC's planned launcher UI remains a fresh, mobile-first Material 3 design.

## Engine patch audit

The baseline's `android/android-next.patch` contains 10 upstream file diffs / 15 hunks. It changes:

- workspace membership for Android crates;
- Android conditional compilation for clipboard and key-layout learning;
- renderer surface lifecycle and frame-latency behavior;
- Android process-exit behavior;
- IME and split-event-loop lifecycle behavior;
- the launcher crate's desktop-only module gating.

The overlay application script additionally edits `crates/horizon-client/src/game.rs`: it adds the Android module, exports bridge functions, adds Android touch state, and inserts calls into game-loop/lifecycle/input paths. The separate `android.rs` file is large (about 1,037 lines) and uses `use super::*`, so it relies on private game internals. Therefore the integration is not yet compliant with the strict requirement that HNMod-compatible Android work must not modify `game.rs`.

The newer overlay script also touches these refactored engine files in addition to `game.rs`: `game/window_handler.rs`, `game/pointer_capture.rs`, `game/resource_loader.rs`, `game/loading_screen.rs`, `horizon-client-render/src/gfx/surface_setup.rs` (or legacy `gfx.rs`), and `horizon-threads/src/jobs.rs`. Its `game.rs` edits add a module, bridge exports, touch state and game-loop calls. This is a broader seam than the small textual patch file alone suggests.

Do not copy this overlay unchanged into rustMC. In particular, do not use `include!` in `game.rs`, and do not claim the current overlay is mixin-safe.

## GoyDevv reference: reusable behavior, not architecture

The pinned GoyDevv tree provides useful feature references:

- four touch-control styles, custom button editor and floating Horizon menu;
- native keyboard/mouse/gamepad handling;
- SAF document-provider and file-management behavior;
- updater and display/performance controls;
- Android-specific diagnostics and input probes;
- touch layout/editor code under `crates/horizon-client-render/src/touch/`.

However, its implementation is heavily Rust/engine-side (for example `crates/horizon-client/src/android_main.rs`, `game/android_input.rs`, `crates/horizon-client-render/src/touch.rs`, and Android modules under `crates/horizon-client/src/android/`). Its README describes a native Rust/wgpu Android port. It is a feature/behavior reference, not a clean standalone Android UI module to transplant wholesale.

## Upstream architecture check (current main)

The current upstream's `docs/layers.md` explicitly says desktop-class ports, including Android, should build on the platform without editing engine files. It also says the engine exposes input hooks for platform features. This is the direction rustMC should follow rather than preserving the older invasive Android integration unchanged.

The same document describes the current input boundary more precisely:

- Mapping-based actions are the existing input abstraction; the game asks whether an `Action` is held rather than which physical key produced it.
- The remaining analog movement/look and menu-focus input-source interface is listed as M6 work. The document says those will become a registered input source implemented by platform code.
- The current `horizon-hooks::Hooks` trait is mainly lifecycle, frame, tick, player, camera, and service hooks; it is not yet a complete Android touch-input API.

This gives us a concrete integration route: keep Android UI, SAF, updater, touch-layout editor, toolchain and Android device services in rustMC-owned platform/app crates; use the existing action abstraction where possible; and avoid writing a parallel game input system. For analog look/movement and lifecycle/surface operations, first check whether the upstream M6 input-source and renderer seams have landed at the chosen pin. If not, propose the smallest upstream extension point and keep its patch isolated and reviewable. Do not silently fork or vendor the whole game loop.

## Constraints and next implementation steps

1. Keep both source submodules pinned as references; make all new rustMC work in rustMC-owned directories.
2. Select and record one current upstream Horizon commit. Rebase the Android overlay against it and run a compile check before building launcher features on top.
3. Split Kotlin app/UI, JNI bridge, Android runtime crates, toolchain, and engine compatibility patches into separate rustMC-owned areas.
4. Audit every required game-loop/input/lifecycle hook. First test whether an external Android bridge can use existing public engine APIs. If not, define the smallest explicit upstream seam possible, outside `game.rs` if technically feasible; if a `game.rs` seam proves unavoidable, isolate it as a tiny documented patch and explicitly mark the HNMod compatibility limitation rather than hiding it in an overlay.
5. Port GoyDevv features selectively only after identifying their Android API contracts and license/provenance. Do not copy its engine modifications wholesale.
6. Rebuild the launcher UI independently; preserve working services (updater, HNMod build pipeline, file access, touch router) behind stable Kotlin interfaces.
7. Add CI checks that fail if Android-only implementation leaks into unrelated upstream files, if an upstream patch anchor no longer matches, or if the engine pin changes without a compatibility build.

## Known caveats

- The Android Edition repository is archived. Its submodule is a read-only source reference for this project; rustMC changes must live in rustMC-owned files.
- The baseline Gradle application ID is `dev.horizon.launcher`. Verify package/application-ID expectations before carrying over its APK identity.
- The baseline engine pin is substantially older than upstream main at audit time. No claim of current-upstream compatibility or successful APK build is made by this audit.
- This document is an audit record, not a claim that the next-generation launcher or a new APK has already been implemented.
