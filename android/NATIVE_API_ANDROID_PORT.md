# Android port of the Nov 12 native-mod architecture

Status: **compiler and modpack infrastructure is being ported; native Android execution is not complete**. This document distinguishes working validation/install stages from the missing Android SDK and game host.

## Non-negotiable architecture

- Keep the Android Kotlin launcher/UI and its JNI boundary. Do not try to run the desktop GPUI launcher on Android.
- Keep the current Horizon Android engine as a temporary fallback until the new fork has an Android game host.
- Do not rebuild the full game for every mod. The final native build must link mod outputs against an SDK for the exact game commit.
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
   - Fetch the SDK for the exact game commit and verify the Ed25519 signature over the complete `SHA256SUMS` inventory before trusting `sdk.json`.
   - Download and hash only the shared assets plus the three selected Android ARM64 slices; desktop archives are not downloaded to the phone.
   - Check asset sizes and SHA-256 values, reject unsafe tar paths/links/special files, extract into staging, and atomically publish the cache.
   - Write an install receipt. The Rust resolver checks the receipt, raw selected-asset hashes and extracted-tree hashes before accepting the SDK.
   - Refuse missing Android slices, unknown schema/package format, unsupported Rust identity, or mismatched game/mixins pins. Never fall back to compiling the whole game.

4. **Native HMOD build**
   - Parse the Nov 12 `horizon-mod.toml` format, including `[[native.mixin]]`, `[[native.loop]]`, bridge/state/module and updated `[[inject]]` semantics.
   - Resolve dependencies and compatibility before running Cargo.
   - Compile only the mod crates and generated mixin patch library against the exact Android SDK.
   - Link one Android native game library for the selected mod set and produce a deterministic build record.

5. **Installation and profiles**
   - Install to a versioned private app directory; validate hashes and native ABI before activation.
   - Maintain per-profile enabled mods, build records and rollback to the previous working library.
   - Never replace the current game library until the new one passes validation.
   - Connect the installed profile to the launcher's profile selector only after the new native build path is usable.

6. **Modpacks**
   - Resolve each `.hpack` mod pin against an index snapshot: exact project ID, version, game version and SHA-256; reject revoked packages and untrusted download URLs.
   - Download packages over HTTPS, check exact size/SHA-256 and verify minisign when the project has a signing key. Unsigned projects require explicit opt-in.
   - Validate every downloaded `.hmod`, stage all mods and overrides, update the profile mod list, then commit with rollback. Remove stale pack-owned mods on updates; preserve user-edited overrides.
   - The Compile screen exposes `.hpack` import. By default the app fetches the official `horizon-rust.github.io/horizon-mods/index.json`, validates its schema, and caches it for 10 minutes; on refresh failure it may use a validated cache up to seven days old. A user-selected local index remains available for testing.
   - The installer resolves exact registry pins, checks SHA-256 and minisign signatures, refuses unsigned packages by default (with a separate explicit opt-in), and writes to an isolated private profile. It does not execute mod code during import.
   - The profile is not yet selectable/active in the launcher. The UI states this before installation; profile activation and wiring its game folder into the launcher's selected instance remain open work.
   - Keep `.mrpack` import as a separate porting pipeline. Fabric/NeoForge Java JARs are not executable on Horizon; only compatible data/assets are carried over or mods are ported.
   - Do not call a read-only inspector an installer.

## Acceptance gates before an APK build

- [ ] Upstream publishes all required `aarch64-linux-android` SDK slices.
- [x] Signed SDK inventory verification, selected-asset hash checks and safe atomic extraction are implemented and CI-tested against a generated signed fixture.
- [ ] Native toolchain/proc-macro smoke test runs on an actual ARM64 Android device.
- [ ] Plain new-fork Android game starts through the Kotlin/JNI host.
- [ ] One minimal native HMOD compiles and loads.
- [ ] Two native HMODs with a dependency compile together.
- [x] Hpack pin resolution, signed-package verification policy, transactional profile installation and stale-file cleanup are implemented and covered by CI fixtures.
- [ ] Hpack profile selection/activation is connected to the launcher.
- [ ] Legacy `.hnmod` fallback still works after the final integration.
- [ ] Only after these gates: build one APK and run the full device test matrix.

## Current implementation in rustMC

- `hnmod-core/src/hmod.rs` accepts the Nov 12 manifest metadata and native state rows; legacy `.hnmod` handling remains separate.
- `hnmod-core/src/sdk.rs` validates the upstream SDK index schema, exact game/mixins identity and Android target slices. It requires a signed-install receipt and rechecks selected raw assets plus the extracted SDK tree.
- `android/termux/native-sdk.sh` verifies the upstream Ed25519 signature. `install-native-sdk.py` fetches the exact `sdk-<commit>` release, downloads only the required target assets, verifies them, safely extracts and atomically caches the SDK. The upstream desktop builder currently does not publish Android slices, so this installer will correctly refuse a desktop-only release.
- `hnmod-core/src/native_plan.rs` resolves multiple HMODs, validates exact SDK/game/mixins pins and dependency order, and generates a deterministic build key. This is a planner, not the native compiler/linker.
- `hnmod-core/src/hpack.rs` securely inspects `.hpack` archives and resolves exact mod pins against a registry index, including revoked packages and trusted GitHub release URLs.
- `android/termux/install-hpack.py` downloads, hashes, verifies signatures, validates packages and applies profile changes transactionally. `install-hpack.sh` builds the resolver CLI and runs the installer. `HpackBuildAdapter` and `EmbeddedToolchain.installHpack` expose the flow to Kotlin.
- `.hpack` import still requires a local registry-index file and creates a private profile that the launcher does not yet activate. `.mrpack` remains inspection-only.
- No new native HMOD has been compiled or loaded on Android. The Android SDK producer, JNI game host, native mixin patch/compile/link stage and launcher profile activation remain the major blockers.
