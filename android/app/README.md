# Android app shell

This is the isolated Android packaging layer for rustMC. It deliberately does not modify or vendor
Horizon engine sources.

## Current scope

- Android Gradle application module (AGP 8.11.1, compile/target SDK 35).
- NativeActivity entry point and a minimal Java lifecycle adapter.
- arm64-v8a packaging configuration.
- Optional Vulkan hardware feature declaration.

## Native library contract

The app expects the Rust Android target to produce:

`app/src/main/jniLibs/arm64-v8a/libhorizon_android.so`

That library is **not included yet**. The Rust JNI/event-loop bridge has to be ported against the
pinned Horizon revision before this APK can launch the game. This module is therefore a packaging
scaffold, not a claimed working APK.

## Build

From this directory, with Android SDK 35, JDK 17 and Gradle 8.13+ installed:

```sh
gradle assembleDebug
```

A successful Gradle packaging build alone does not prove the native library or renderer works on
device. Device testing requires the matching `libhorizon_android.so`.

## Porting constraints

- Do not add Android-specific code to `game.rs`.
- Keep JNI, Activity lifecycle, SAF, storage, launcher and toolchain code in this Android layer
  or dedicated Android crates.
- Do not import the Android source branch's modified `winit`, `wgpu` or `naga` trees wholesale.
- Only introduce a tiny upstream patch after proving a missing public extension point.
