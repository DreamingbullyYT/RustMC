# Horizon HNMod Core

Standalone, UI-independent Rust library for inspecting and planning builds from Horizon native mods.

## Current scope

- Reads the PC launcher's `.hnmod` ZIP layout: `horizon-native.json` plus
  `mixins/<id>/horizon-mixin.toml`.
- Accepts PC bundle metadata fields without requiring Android-only `schema`,
  `game`, or duplicated version fields. The mod version comes from
  `[mod].version`; the Horizon pin may come from the native manifest or
  `[compatibility].horizon_revision`.
- Keeps ZIP traversal, duplicate-entry, symlink/special-file, entry-count and
  expanded-size protections while accepting complete safe payload trees (API
  crates, helper sources, data and other files) under the mod's own folder.
- Resolves stable hook API requirements and reports pinned-revision differences.
  The Termux script selects Horizon and horizon-mixins commits by their actual
  Git commit dates, matching the PC launcher's newest-revision policy.
- Extracts only validated `mixins/<id>/` payloads and computes deterministic
  source digests.

The Android build runs the upstream `horizon-mixins` CLI against a staged
workspace, then builds the Android ARM64 game library. PC binary outputs are
never copied to Android: the source bundle format is shared, while Cargo
recompiles for Android.

**Android parity implemented:** the embedded pipeline now ports the PC launcher's
trait-API staging and dependency graph: `[api]`, semver `[dependencies]`,
optional dependency features, deterministic topological hook order, and the
generated hook list attached to the existing `horizon-android-game` JNI
entry point. It also validates mod-crate dependencies against Horizon's own
`Cargo.lock`, stages the upstream `data/` payloads through
`horizon-mixins`, copies those bundle files into the imported Android game
version, and builds/runs `[[post_build]]` programs with the PC environment
variables and per-mod data/cache directories.

The Android script now has a content-addressed ARM64 library cache keyed by
the selected engine and mixins revisions, mod archive hashes, profile,
toolchain, Android patch/overlay and compiler sources. Post-build programs
still run for each imported version so their output is written under that
version's own data directory. The remaining differences are the PC
launcher's richer build records, injection-conflict diagnostics and per-mod
failure isolation; Android's cache is not yet a full replacement for the
PC launcher's installed-version/build-record system. A source mod that uses Windows-only APIs, unsupported Android
dependencies, or desktop-only entry points can still fail the ARM64 build.
Native mod and post-build code runs with the launcher's app UID; only install
mods whose source you trust.
## Android compiler integration

Horizon now packages this crate and the Android build script as app assets. The launcher materializes
them in private storage, validates selected archives, runs the build script inside Horizon's own
Termux-derived ARM64 runtime, then imports the resulting AArch64 game library automatically.

The former external Termux RUN_COMMAND integration is removed. Users do not install a separate
Termux app, grant its permissions, or configure shared storage. The script path
`android/termux/build-with-mods.sh` is invoked by Horizon's embedded process runner.

The runtime installer and Android linker-exec bridge are gated by checksum, bootstrap second-stage
and compiler smoke tests. Real-device validation is still required before claiming the full local
compiler flow is production-ready.

## Test

Run: cargo test --manifest-path hnmod-core/Cargo.toml

## Attribution

The archive format and compatibility concepts are based on Starling's MIT-licensed implementation. The original license is preserved in THIRD_PARTY_LICENSES/starling-MIT.txt.
