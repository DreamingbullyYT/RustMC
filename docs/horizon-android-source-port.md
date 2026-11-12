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

## Event-loop boundary traced to implementation

The upstream game startup path has now been traced one level deeper:

- `game::run_with()` performs the full resource and world startup, then calls
  `loading_screen::run_event_loop(game)` after the `Game` has been created.
- `loading_screen::run_event_loop()` handles headless mode itself. For a
  windowed game it creates `EventLoop::new()` locally and immediately calls
  `run_app(&mut split)`.
- `Loading` and `Game` implement winit 0.30's `ApplicationHandler` and
  create their windows from `resumed(&ActiveEventLoop)`. The event loop is
  therefore the correct narrow injection boundary; the renderer does not
  need to be replaced to support Android's `AndroidApp`.
- The gameplay crate is a library as well as a binary (`src/lib.rs` exports
  `pub mod entry` and `pub mod game`). However, `entry::main_with()` also
  owns one-time hook registration, allocator setup, logging/crash setup and
  shutdown. Calling `game::run_with()` directly from an Android cdylib would
  skip required initialization and is not an acceptable integration.

### Required minimal upstream seam (not yet applied)

The clean seam is a paired entry/startup API, not a renderer rewrite:

1. Preserve `entry::main()` and `entry::main_with()` as the desktop defaults.
2. Factor the common initialization in `entry.rs` so a platform entry can
   execute the same hook/logging/allocator setup and then call the game with
   a supplied event loop, without duplicating initialization or calling
   `process::exit` from Android's lifecycle callback.
3. Add a `game::run_with_event_loop(EventLoop<()>)` path which shares the
   existing `run_with` resource/game startup and passes the supplied loop
   down to `loading_screen::run_event_loop`; keep the existing
   `run_with()` path creating `EventLoop::new()` exactly as before.
4. In the Android cdylib, construct the event loop with winit's Android
   extension and the `AndroidApp` supplied by NativeActivity, then call the
   Android entry API. Keep Android-specific glue outside `game.rs` wherever
   the existing module boundary allows.

This seam needs coordinated edits in `entry.rs`, `game.rs`, and
`game/loading_screen.rs`. It must be tested against desktop builds and the
Android target before being considered complete. No upstream source patch
has been applied yet; this section records the verified design so a patch
does not accidentally bypass engine initialization or alter desktop startup.

### Renderer preservation check

The original Android launcher renderer is still the target. Its fork-only
runtime services (Android document access, installer/catalog, touch and
device integration) must be supplied by the Android platform crate or
narrow adapters. Do not port the fork's entire `horizon-client` package over
the pinned upstream package, and do not replace the wgpu launcher with a new
UI.

### Original Android launcher/game lifecycle traced

The original Android frontend's NativeActivity entrypoint was inspected directly
at the source snapshot above:

- `android_main(AndroidApp)` stores the `AndroidApp`, configures Android
  services and then creates its Winit loop with
  `EventLoop::builder().with_android_app(app.clone()).build()`.
- That outer loop runs the original `launcher::Launcher` via
  `run_app(&mut launcher)`. The launcher renderer is therefore a real native
  Winit/wgpu application, not a Compose screen.
- The launch action is separate: `android/launcher.rs` creates a
  `game_launch::LaunchRequest`, then `android/game_launch.rs` configures
  fork-only Android runtime graphics settings and calls
  `crate::game::android_game(request.arguments())`. This is not the same
  entry path as the launcher event loop.
- The fork's `game_launch::start` returns an
  `ApplicationHandler` for the game to be hosted by the Android frontend.
  Its `android_game` API and runtime graphics hooks are absent from the
  pinned upstream revision, so the game-launch bridge must be mapped
  independently from the launcher renderer.

### Updated integration consequence

There are two distinct integration responsibilities, and they must not be
collapsed into a replacement UI:

1. Keep the existing Android launcher `ApplicationHandler`, renderer and
   launcher event loop intact, adapting only its platform-service dependencies.
2. Replace the fork-only `android_game` handoff with an upstream-compatible
   game application handler/startup path. The upstream currently exposes
   `entry::main_with` and `game::run_with`, which own a process-style
   executable startup; the Android fork instead expects a game handler that
   can be hosted by the existing Android event loop. A minimal bridge must
   preserve the upstream hook/allocator/logging setup and avoid a second
   Winit event loop, because Winit permits only one event loop per process.

The earlier proposed "pass a prebuilt EventLoop into run_with" is therefore
not yet proven to be the correct final seam: the Android frontend already
owns an event loop and expects an `ApplicationHandler`, while upstream's
current startup API internally constructs and runs its own loop. Before
writing the patch, the next step is to trace the upstream `Loading` /
`Split` application handler ownership and determine whether it can be
exposed as a hosted handler without changing game/render behavior. Do not
implement a second event loop or start the engine in a detached process.

No source code or APK was changed by this audit update.


### Confirmed original single-loop handoff (launcher and fork implementation)

A further trace of the original Android `Launcher` resolves how its game handler
is hosted:

- On Play, `Launcher::activate` calls `game_launch::start(request)`, drops
  its launcher renderer/window/gui references, stores the returned handler in
  `self.game`, and calls `game.resumed(events)` using the existing
  `ActiveEventLoop`.
- While `self.game` is set, the same `Launcher::ApplicationHandler`
  forwards `resumed`, `suspended`, `window_event`, `device_event` and
  `about_to_wait` to that handler. When the game ends, it restores the
  launcher. There is no nested event loop.
- The fork's `game::android_game(arguments)` parses explicit arguments,
  prepares Android settings/runtime, and returns
  `Box<dyn ApplicationHandler>` containing `loading_app(...)`.
- The fork's `loading_app` constructs the existing
  `loading_screen::App::Loading` without running an event loop. Desktop
  `run_loading` calls the same factory and runs it in its own event loop.

This is the proven integration pattern to preserve. The upstream equivalent
should expose a narrow factory that returns its existing loading/game
`ApplicationHandler` for the Android launcher to host. It should not expose
or recreate an `EventLoop` for Android.

### Corrected upstream port plan

The exact fork factory pattern is now known, but upstream currently keeps
`loading_app`, `App`, `Loading`, and `Split` private and constructs the
loading app inside `resource_loader::run_loading`. A minimal port should:

1. Factor the construction portion of upstream `resource_loader::run_loading`
   into a crate-visible factory returning the existing app handler, while
   keeping the desktop function responsible for creating/running its own loop
   and doing its existing post-loop report/error handling.
2. Add an explicit-arguments Android/game launch entry in upstream
   `game` that prepares the same upstream options/settings/resources and
   returns that handler, rather than reading process arguments and running a
   loop. Preserve the upstream resource loading, startup screen, renderer,
   game handoff and `Split` unchanged.
3. Factor the shared hook/allocator/logging/engine initialization in
   `entry::main_with` into an idempotent or explicitly one-shot setup API
   that Android calls before constructing the game handler. Desktop
   `main_with` retains its existing CLI, error and process-exit behavior.
4. Let the original Android `Launcher` continue forwarding lifecycle and
   input events to the returned handler, as it already does. Android-only
   platform adapters remain in the Android crate; do not add Android
   conditionals to the renderer/game simulation.

The upstream changes should be limited to these reusable API seams. No
Android-specific code is to be injected into `game.rs` via macros/includes,
and no second Winit event loop is to be created.

This remains an audit/design update only; no upstream engine source or APK
has been changed.
