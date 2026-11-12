# Horizon Android source-port audit and integration plan

Audit date: 2026-11-12

Source: `GoyDevv/Horizon-Android`, branch `android`, snapshot
`ae4c85dc5b31da2078ee1e1009a53ced7d7ed1ba`.

Target engine: exact upstream revision recorded in `UPSTREAM.lock`,
`69ba5a7b63642555fdd3461defcb53c265967d59`.

## Non-negotiable architecture

**Keep the original Horizon-Android renderer and launcher UI. Do not replace,
redesign, or reimplement them.** The task is to adapt their engine-facing
integration so they run on the pinned upstream engine in this repository.

The renderer/UI are the Android presentation layer. The pinned upstream
Horizon engine is the gameplay/core layer. Add only the narrow compatibility
bridge needed between them.

- Preserve the original Android renderer, launcher, touch UI and visual
  behavior as the implementation baseline.
- Keep Android-only entrypoint, platform services and compatibility adapters
  in a separate Android crate/module.
- Keep the upstream engine pin unchanged.
- Make the smallest possible upstream changes, only when a clean external
  adapter cannot provide the needed seam.
- Never inject Android code into `game.rs` using `include!`; do not modify
  `game.rs` for that purpose.
- Do not wholesale merge the Android fork's general engine changes.
- Preserve the existing mod API and HNMod/HMod/HShader compatibility target.

## What the original Android project contains

- Gradle packaging under `packaging/android/`.
- A `NativeActivity` Java adapter; manifest loads
  `libhorizon_android.so` and calls `horizon_on_create`.
- A separate Cargo package `crates/horizon-android`, with a `cdylib`
  target pointing to `crates/horizon-client/src/android_lib.rs`.
- Android entry and UI code in `android_main.rs` and dedicated files under
  `crates/horizon-client/src/android/`.
- The original launcher renderer is Rust/wgpu code, principally
  `android/launcher.rs` and its page/catalog modules. Preserve this code and
  adapt its dependencies at the boundary; do not replace it with a Compose
  shell. The checked Gradle configuration uses `java.srcDirs = ['java']`
  and does not configure the Compose plugin/dependencies, so the Kotlin
  Compose file is not the active launcher implementation.
- Java platform adapters include NativeActivity/JNI and the Storage Access
  Framework document provider.

## Compatibility findings

The Android source branch is not a small Android-only patch on the pinned
engine. Its Android layer calls APIs absent from, or structurally different
in, the pinned upstream revision. Examples include:

- `horizon_client::android_runtime` and Android gamepad/input integration;
- `horizon_client::official_install` and `android_resources`;
- fork-specific game-launch APIs and launcher-facing renderer/UI helpers;
- additional workspace crates and changed feature/dependency arrangements.

This does **not** mean the renderer should be rewritten. It means we need to
identify the exact engine-facing calls made by the existing renderer and
supply equivalent behavior through a thin adapter, or make the smallest
upstream API seam necessary. Keep the renderer source intact wherever
possible; any unavoidable source changes should be limited to imports,
adapter calls and data contracts, and recorded separately.

## Integration order

1. Build the original `horizon-android` packaging and entrypoint as a
   separate Android target, without importing the fork's entire engine.
2. Bridge Android's `AndroidApp` to the pinned winit event loop. The pinned
   winit version must receive the app via
   `EventLoopBuilderExtAndroid::with_android_app` before event-loop build;
   creating a normal desktop event loop on Android is not sufficient.
3. Map the original renderer's engine dependencies to the pinned upstream
   APIs. Keep renderer logic, layout, draw path and UI behavior unchanged
   unless an API mismatch makes a narrowly scoped adaptation unavoidable.
4. Reconnect touch, mouse, keyboard, controller, lifecycle, audio and SAF
   services through Android-specific adapters.
5. Verify Vulkan 1.1 on ARM64, then test game launch and mod loading. GLES 2.0
   remains out of scope for this stage.
6. Compile and test the actual APK before claiming the port works.

## Change policy

Every proposed engine change must answer: why can't this be implemented in
the Android adapter? If it can, keep it out of upstream. Keep the existing
upstream commit pin and mod API stable. Do not merge this port into `main`
without explicit approval.

## Verified source/API inventory (2026-11-12)

The following points were checked against the exact pinned upstream commit and
the original Android source snapshot, rather than inferred from repository names:

- Upstream's `horizon-client` package declares a binary target at
  `src/main.rs`; its reusable public client API is a separate
  `horizon-client-lib` package whose library source is
  `crates/horizon-client/src/client_lib.rs`.
- The upstream executable's `main` calls `horizon_client::entry::main()`.
  Upstream `entry::main_with` performs engine hook registration and then
  calls the private game module's `game::run_with()`. That API reads process
  arguments and owns the normal game startup path; it is not currently an
  Android entry point accepting an `AndroidApp`.
- Upstream pins `winit = 0.30.13` in the client. The original Android
  entrypoint also uses winit 0.30 and stores an `AndroidApp`, then calls
  `EventLoop::builder().with_android_app(app).build()`. This is the correct
  Android event-loop contract to preserve.
- The original Android launcher source is
  `crates/horizon-client/src/android/launcher.rs` (46 KB at the audited
  snapshot). Its direct engine references include `android_runtime`,
  `official_install`, `android_resources`, `gamepad`, `touch`, `touch_ui`,
  `osk`, `ui_theme`, `gfx`, `graphics`, `audio` and `sound_events`. Several
  renderer/UI-facing modules are already public through upstream
  `horizon-client-lib` (including `gfx`, `hud`, `ui_theme`, `audio`,
  `gamepad` and `osk`), while the Android runtime, installer and touch-control
  helpers are not part of that upstream public surface.
- The source Android crate depends on the fork's `horizon-client`,
  `horizon-client-render`, `horizon-client-input`, and numerous fork-only
  crates. Its manifest cannot simply be pointed at the pinned upstream
  workspace without removing or adapting those dependencies.

### Consequence for the port

A standalone Android cdylib can reuse the upstream public client library for
renderer-facing systems, but it cannot currently invoke the complete upstream
gameplay loop through a supported public library API. The first engine seam
to investigate is therefore a small, public, Android-neutral game-start
interface that accepts already-created event-loop/platform context (or a
callback that supplies it). The Android crate should own construction of the
winit event loop with `AndroidApp`; upstream desktop startup must continue
to use its existing default path.

Do not expose or duplicate the entire private `game.rs` module just to make
Android compile. First trace where the upstream game loop creates its window
and event loop, then design the smallest API seam around that exact boundary.
If the only viable seam requires touching upstream, keep it as a tiny,
reviewable patch in a separate patch file and verify it does not change the
desktop path or mod API.

### Current implementation status

This branch currently contains the source-port audit and the upstream pin;
it does not yet contain the original Android renderer, native Android
entrypoint, compatibility adapters, or a working Android APK. The previous
Compose shell on `android-minimal-port` is an unrelated intermediate and is
not the port target.
