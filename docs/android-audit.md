# Android upstream audit

Audit target: Horizon upstream `main` at
`69ba5a7b63642555fdd3461defcb53c265967d59` (2026-11-11).

This document records source-level findings before the first Android implementation.
It is not a claim that an Android APK has compiled or run.

## Findings

### Existing reusable Android-related code

- `crates/horizon-client-render/src/touch.rs` contains a reusable multi-touch
  control state machine. It maps fingers to movement, camera look, buttons,
  taps and holds, and produces the existing `PadState` used by gameplay.
- `crates/horizon-client-render/src/android_gamepad.rs` decodes Android
  controller key codes and motion axes into the same gamepad state.
- `crates/horizon-client-render/src/android_runtime.rs` stores input-mode,
  controller and keypad state. Its own module documentation describes it as
  pure state that does not talk to Android OS APIs.
- `horizon-client-render` already depends on `winit 0.30.13` and `wgpu
  30.0.1`; its graphics dependencies include Vulkan and GLES backends.

These are useful building blocks, not a complete Android application port.
The client event path still needs an explicit integration audit and bridge:
the current `game/window_handler.rs` handles keyboard, mouse and focus
windows events, but has no `WindowEvent::Touch` arm. The Android touch
state machine is not currently wired into that event handler.

### Required Android entry-point bridge

The client starts its main event loop in
`crates/horizon-client/src/game/loading_screen.rs`:

```rust
EventLoop::new()?.run_app(&mut split)
```

For winit 0.30 on Android, the application receives an `AndroidApp` in
`android_main` and must pass that exact app to
`EventLoopBuilderExtAndroid::with_android_app` before building the event loop.
It is not global state. Winit 0.30 uses `android-activity 0.6`; the app and
winit must use the same glue version.

Therefore an Android `android_main` in a separate crate alone is insufficient.
The smallest likely upstream patch is to provide a narrowly scoped way for the
Android app context to reach the event-loop builder. Keep this out of
`game.rs`; prefer a dedicated Android module/entry bridge plus a small
platform-gated change at event-loop construction.

### Packaging and platform gaps

The upstream repository currently has no Android Gradle application,
AndroidManifest, NativeActivity entry point or APK packaging pipeline.

The workspace declares Rust `rust-version = 1.95` at this audited revision.
Use an Android NDK toolchain and begin with `aarch64-linux-android` for the
first device build. Rust officially supports this Android target.

The client input crate has an unconditional `arboard` dependency. Its
Android-target compilation and clipboard behavior must be tested before
choosing whether a small platform-specific clipboard adapter is required.
Do not add a workaround until the cross-build produces a reproducible error.

## Proposed implementation boundary

Keep the Android app and its packaging under `android/`. Keep platform bridge
code in a dedicated Android module. Keep unavoidable upstream changes as
ordered, reviewable patches under `patches/`; never commit edits made directly
inside the submodule as though they were upstream source.

### Ordered milestones

1. Add a minimal Android app shell and NativeActivity entry point, with the
   winit Android glue version pinned to the version used by winit 0.30.
2. Add the smallest event-loop context bridge and cross-compile the client for
   `aarch64-linux-android`. This is a compile smoke test, not yet an APK success.
3. Wire existing `WindowEvent::Touch` events into `TouchControls`, then route
   its output into the existing game input path. Add tests for pointer identity,
   multi-finger movement/look, press/release and lifecycle cancellation.
4. Verify Vulkan and GLES device/surface creation, resize, pause/resume and
   surface recreation on a real Android device.
5. Add Android storage access (SAF), user-owned Minecraft resource import,
   app-private save/config paths and APK packaging.
6. Add optional Android audio/controller support only after the base client
   starts reliably.

## Explicit non-goals for the first milestone

- No rewrite of the renderer.
- No copy of the whole Horizon engine into the Android app.
- No broad edits to `game.rs` or gameplay logic.
- No assumption that the desktop client can be made Android-compatible by
  changing the target triple alone.
- No APK/build-success claim until the actual workflow and device test pass.
