# Android app module

This is the isolated Android application layer for rustMC. The launcher UI, Android Activity
lifecycle and game native surface live here; the pinned Horizon engine remains untouched.

## Current scope

- Jetpack Compose launcher with Play, Files and Settings destinations.
- A separate `NativeActivity` for the game; the launcher starts it explicitly.
- Internal game-data file browser rooted at the app's private files directory.
- Persisted "keep screen on while playing" preference, applied by the game Activity.
- arm64-v8a packaging configuration and optional Vulkan hardware declaration.

The update control is intentionally absent until an updater is actually connected. Mod, shader,
touch-layout and renderer screens are not represented as functional controls yet; they need the
native/runtime bridge and compatibility work first.

## Native library contract

The game Activity expects the Rust Android target to produce:

`app/src/main/jniLibs/arm64-v8a/libhorizon_android.so`

That library is **not included yet**. The Compose launcher can be packaged independently, but
launching the game requires the matching native library. This is not yet a runnable game APK.

## Build

From this directory, with Android SDK 35, JDK 17 and Gradle 8.13+ installed:

```sh
gradle assembleDebug
```

A successful Gradle packaging build alone does not prove the native library or renderer works on
device. Device testing requires the matching `libhorizon_android.so`.

## Porting constraints

- Do not add Android-specific code to `game.rs`.
- Keep JNI, Activity lifecycle, storage, launcher and toolchain code in this Android layer
  or dedicated Android crates.
- Do not import the Android source branch's modified `winit`, `wgpu` or `naga` trees wholesale.
- Only introduce a tiny upstream patch after proving a missing public extension point.
