# Android port of the Nov 12 native-mod architecture

Status: **port investigation and SDK identity gate in progress**. This document is not a claim that native HMOD compilation works on Android yet.

## Non-negotiable architecture

- Keep the Android Kotlin launcher/UI and its JNI boundary. Do not try to run the desktop GPUI launcher on Android.
- Keep the current Horizon Android engine as a temporary fallback until the new engine has an Android game host.
- Do not rebuild the full game for every mod. The final Android native build must link mod outputs against an SDK for the exact game commit.
- Never accept a Linux/macOS/Windows SDK as an Android SDK. The target must be explicitly published as `aarch64-linux-android`.
- Keep the legacy `.hnmod` builder as a separately selectable fallback until the new path passes device tests.
- Native mod source, Cargo build scripts and procedural macros execute with the app UID. Require an explicit trust decision before building.

## What the upstream SDK currently assumes

The Nov 12 fork's SDK index names target slices as `runtime-<target>.tar.xz`, `api-<target>.tar.xz` and `engine-<target>.tar.xz`, alongside `surface.json`, `build-data.tar.xz` and `api-source.tar.xz`. A native mod pins:
- `[native].game`: game version;
- `[native].horizon`: exact 40-character game commit;
- `[native].mixins`: exact horizon-mixins commit.

The upstream `tools/prebuilt_game.py build` currently uses the build machine's `rustc_host()` as its target and packages a desktop `horizon-client` executable in the runtime slice. That is not an Android SDK. Merely passing `aarch64-linux-android` to the existing CLI or renaming a desktop archive would be incorrect.

## Android-specific port work

1. **Android game host in the new fork**
   - Add a dedicated Android `cdylib`/JNI entry point, using the existing Kotlin application as the launcher.
   - Port surface lifecycle, input/touch, audio, filesystem/resource lookup, logging, and Vulkan/GLES selection.
   - Do not bring GPUI desktop windowing into the Android APK.
   - Prove that the unmodded game starts and loads a world before enabling native mods.

2. **Android SDK production**
   - Extend the SDK builder with an explicit target and Android-specific runtime members; no desktop executable in the Android runtime slice.
   - Resolve the NDK/Clang sysroot and linker configuration deterministically.
   - Solve host proc-macro availability: proc macros must load in the player's Android Rust host, not in the Linux CI host. The current desktop SDK build's host/target assumptions do not solve this.
   - Publish the Android slices only after a clean SDK smoke build on Android ARM64.

3. **SDK acquisition and trust**
   - Fetch the SDK for the exact game commit and check the release signature before trusting its index.
   - Check each asset's SHA-256 and size before extraction; use atomic staging and cache by SDK identity.
   - Refuse missing Android slices, unknown schema/package format, unsupported Rust identity, or mismatched mixins pins. Never fall back to compiling the whole game.

4. **Native HMOD build**
   - Parse the real `horizon-mod.toml` format; preserve `[[native.mixin]]`, `[[native.loop]]`, bridge/state/module and updated `[[inject]]` semantics.
   - Resolve dependencies and compatibility before running Cargo.
   - Compile only the mod crates and generated mixin patch library against the exact Android SDK.
   - Link one Android native game library for the selected mod set and produce a deterministic build record.

5. **Installation and profiles**
   - Install to a versioned private app directory; validate hashes and native ABI before activation.
   - Maintain per-profile enabled mods, build records and rollback to the previous working library.
   - Never replace the current game library until the new one passes validation.

6. **Modpacks**
   - Implement native `.hpack` registry resolution, verified downloads, dependency closure and safe overrides.
   - Keep `.mrpack` import as a separate porting pipeline. Fabric/NeoForge Java JARs are not executable on Horizon; only compatible data/assets are carried over or mods are ported.
   - Do not call a read-only inspector an installer.

## Acceptance gates before an APK build

- [ ] Android SDK index explicitly contains all required `aarch64-linux-android` slices.
- [ ] SDK signature and every asset checksum are verified.
- [ ] Native toolchain/proc-macro smoke test runs on an actual ARM64 Android device.
- [ ] Plain new-fork Android game starts through the Kotlin/JNI host.
- [ ] One minimal native HMOD compiles and loads.
- [ ] Two native HMODs with a dependency compile together.
- [ ] `.hpack` installs a complete profile and can be rolled back.
- [ ] Legacy `.hnmod` fallback still works.
- [ ] Only after these gates: build one APK and run the full device test matrix.

## Current implementation in rustMC

- `hnmod-core/src/sdk.rs` reads the upstream SDK index shape, validates its identity fields and refuses target slices that are not explicitly present.
- `native_plan.rs` now binds HMOD pins to the SDK's exact game commit, mixins revision and target slices.
- This reader computes an index digest but **does not authenticate the SDK signature or download/install assets**. Those are still required before this index may be trusted.
- The Android SDK target is not currently published by the upstream fork's desktop builder. Therefore the new Android native compiler remains intentionally gated and cannot yet produce a native Android build.
