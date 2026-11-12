# Horizon Android source-port audit

Audit date: 2026-11-12

Source: `GoyDevv/Horizon-Android`, branch `android`, commit
`ae4c85dc5b31da2078ee1e1009a53ced7d7ed1ba`.

Target engine: the exact upstream revision recorded in `UPSTREAM.lock`,
`69ba5a7b63642555fdd3461defcb53c265967d59`. Do not move this pin as part
of the port.

## What the original Android project actually contains

- The Android application is packaged from `packaging/android/` using Gradle.
- `HorizonActivity` is an Android `NativeActivity`; the manifest loads
  `libhorizon_android.so` and calls `horizon_on_create`.
- The native library is a separate Cargo package, `crates/horizon-android`,
  whose `cdylib` target points at `crates/horizon-client/src/android_lib.rs`.
- The Android entry code is in `android_main.rs` and dedicated files under
  `crates/horizon-client/src/android/`. This is a useful architectural
  precedent: Android code is in a separate crate/module, not injected into
  `game.rs`.
- The launcher UI is primarily Rust/wgpu code in
  `crates/horizon-client/src/android/launcher.rs` and its page/catalog
  modules. `HorizonComposeLauncher.kt` is not wired into the shown Gradle
  source set: the build config sets `java.srcDirs = ['java']` and does not
  configure the Compose plugin/dependencies. Do not mistake that Kotlin file
  for the active launcher implementation.
- The Java files are narrow Android adapters: NativeActivity/JNI and the
  Storage Access Framework document provider.

## Compatibility findings

The source branch is not a small Android-only patch on the pinned engine.
Its Android crate and launcher call APIs that are absent from, or structurally
different in, the pinned upstream revision. Examples include:

- `horizon_client::android_runtime`, `android_gamepad`, and the Android
  renderer/runtime integration used by its launcher;
- `horizon_client::official_install` and `android_resources`;
- the fork's `game::android_game` launch API;
- launcher/browser/account modules and UI/theme APIs that have evolved since
  the pinned revision;
- additional workspace crates and dependency/feature arrangements.

The original Android branch also contains extensive unrelated engine changes.
Copying its whole engine or moving the submodule pin to that branch would
violate this repository's minimal-patch policy and risk the existing
Horizon/HNMod compatibility target.

## Porting rules

1. Keep `upstream/horizon` at the commit in `UPSTREAM.lock`.
2. Keep all Android packaging and Android-only Rust code under `android/`.
3. Build a separate Android `cdylib` crate; do not insert Android code into
   `game.rs` with `include!` or modify `game.rs` for that purpose.
4. First prove a minimal NativeActivity + winit Android event-loop bridge
   against the pinned engine. Use the exact winit/android-activity versions
   already selected by the pinned workspace.
5. Only then adapt the original launcher screens and storage adapters. Port
   their behavior and visual design selectively; do not copy modules that
   depend on unavailable fork-only APIs without replacing those dependencies.
6. Wire touch events into the existing input path with tests for multi-touch,
   pointer identity, press/release and lifecycle cancellation.
7. Do not claim the launcher or game works until an ARM64 APK builds and the
   native activity is tested on an Android device.

## First implementation boundary

The first code milestone is not a replacement launcher shell. It is a minimal
native library entry point and event-loop context bridge using the pinned
engine. The next milestone is the original Rust launcher UI adapted to the
pinned client APIs, followed by SAF file management and the original
touch-control editor.
