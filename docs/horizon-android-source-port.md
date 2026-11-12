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
