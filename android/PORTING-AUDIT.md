# Android port audit

Status: source inventory only; no Android source has been copied into the pinned engine.

## Fixed upstream baseline

- Repository: https://github.com/horizon-rust/horizon
- Commit: `69ba5a7b63642555fdd3461defcb53c265967d59`
- Tree: `61ea08849f2d4e6b400513f6392c9ed3eef9b4bd`

The `upstream/horizon` gitlink and `UPSTREAM.lock` remain pinned to this revision. Do not edit the submodule working tree as part of Android development.

## Android source snapshot

- Repository: https://github.com/GoyDevv/Horizon-Android
- Branch: `android`
- Commit: `ae4c85dc5b31da2078ee1e1009a53ced7d7ed1ba`

The source snapshot is a complete Horizon-derived tree, not a standalone Android app. Comparing it directly with the November 11 upstream pin is misleading because the snapshots have different bases and include unrelated upstream evolution. The meaningful source delta is measured against the common ancestor below.

## Provenance baseline and complete tree comparison

The Android branch has a real common ancestor with Horizon: merge commit
`da0b893b948740d4cdfc837b9e271386e046e553` explicitly merged published
Horizon commit `8c02c0b8534e11b7bb8be14238b140a8534280a0`. The Android branch
is 378 commits ahead of that base, with no commits behind it.

Comparing the full Git trees at that common base and the Android head (not the
unrelated current-upstream trees) gives:
- 998 added files, 18 deleted files and 415 modified files.
- The largest changed areas are `third_party/winit` (229 paths),
  `tools/native-hook-fragments` (225), `crates/horizon-client` (197),
  `crates/horizon-client-render` (99), and `crates/horizon-mod-sdk` (75).
- There are also 35 files under `packaging/android`.

This confirms the source branch is not a small Android overlay. It includes
substantial engine evolution and tooling beyond the Android platform layer.
The full tree comparison is the reliable inventory; the GitHub compare API's
file listing is capped, so its partial file list must not be treated as complete.

The current Android `crates/horizon-client/src/game.rs` is about 1,695 lines
longer than the common-base file and contains Android-specific declarations
and gameplay conditionals. It is explicitly excluded from bulk porting. The
existing upstream `game.rs` stays unchanged.

## High-risk findings

The Android source modifies broad core areas, including `crates/horizon-client/src/game.rs`, many `horizon-client` and `horizon-client-render` files, plus `third_party/winit`, `third_party/wgpu-hal`, and `third_party/naga`. It also carries substantial changes in world/core/protocol crates. Those need individual provenance and necessity checks.

The source has useful standalone Android areas:
- `packaging/android/` — Gradle, manifest, Activity, Compose UI and build scripts.
- `crates/horizon-android/` — Android support crate.
- `crates/horizon-client/src/android/` and `android_main.rs` — Android platform services and entrypoint candidates.
- `crates/horizon-client-render/src/touch/`, `touch.rs`, `android_gamepad.rs`, `android_runtime.rs` — input/control candidates.
- `tools/android_*` and `tests/test_android_*` — build, diagnostics and regression tooling.
- `crates/horizon-client/src/game/android_bundled/` — bundled mod payloads; inspect licenses and mod API compatibility before adopting.

Some Android code is currently nested under Horizon crates. It may be moved into dedicated workspace crates/modules where public APIs permit, rather than retaining broad core edits.

## Porting rules

1. Keep the engine pinned at the baseline above until a deliberate upstream update is approved.
2. Keep Android packaging, Java/Kotlin, JNI, lifecycle, storage, toolchain, input policy and launcher-specific services under `android/`.
3. Reuse upstream touch/gamepad/runtime APIs where available; do not fork existing implementations.
4. No `include!("game/android.rs")` or broad edits to `game.rs`. This is especially important because the mod/mixin pipeline rejects unsupported macro edits to that file.
5. An upstream patch is allowed only for a proven missing extension point. It must be small, isolated, documented and stored as an ordered patch under `patches/`.
6. Preserve HNMod/HMod/HShader compatibility by testing against the pinned engine and its actual mod APIs; do not infer compatibility from the Android source branch.
7. Do not import third-party renderer/windowing forks unless a minimal reproducible Android build failure proves they are required.

## Next extraction order

1. Read the source Android manifest, Gradle configuration, native entrypoint and crate manifests; map their dependencies.
2. Compare the Android event-loop integration against the pinned winit 0.30 API and implement the smallest context bridge outside `game.rs`.
3. Port aarch64 Android compile support and build a smoke-test APK before importing UI or bundled mods.
4. Port touch input by adapting `WindowEvent::Touch` into the existing input state machine, with pointer/lifecycle tests.
5. Port SAF storage, launcher UI, updater, mod/shader integration and compiler as independent modules, one verified slice at a time.
6. Record every unavoidable engine delta in `patches/`; run cross-builds and tests after each slice.

## Verification status

- Upstream pin: verified in repository metadata.
- Android source inventory: completed.
- Common ancestor and full source tree delta: established (998 added, 18 deleted, 415 modified paths).
- Android port: not yet applied.
- Android port: not yet applied.
- Cross-compilation/APK/device verification: not yet performed.
