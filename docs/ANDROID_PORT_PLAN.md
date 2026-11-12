# Android port plan

## Decision

Use the pinned Android Edition as a source reference and reuse its Kotlin/JNI/toolchain services selectively. Do not carry its current Rust game-loop overlay into production unchanged: the audit found that it reaches into private `Game` state and rewrites `game.rs` and several game-loop modules.

Use the current upstream architecture as the contract. Upstream's `docs/layers.md` says desktop-class ports such as Android should build on the platform without editing engine files. The port should therefore be a platform/app implementation, not a private engine fork.

## Target layout

```text
rustMC/
  android-base/                 # pinned, read-only Android Edition reference
  references/goy-horizon-android/ # pinned feature reference
  android-app/                  # rustMC-owned Kotlin/Compose app
    app/
    core-bridge/
    controls/
    file-manager/
    updater/
    toolchain/
  android-platform/              # Rust crate(s) owned by rustMC; public platform APIs only
  docs/
  tools/
```

The exact Gradle and Cargo workspace wiring will be added only after the app modules are copied/adapted and the engine API boundary is confirmed. Do not create a fake build that points at missing `core/` or generated engine folders.

## Input integration boundary

Available upstream pieces at `f24ee1accf1c816736e6e067939d04f3853f474e`:
- `horizon-client-input::actions::Action` is the shared mapping/action vocabulary.
- `horizon-client-input::input_source` currently contains `Feedback`, but no public registered analog input-source trait yet.
- `horizon-hooks::Hooks` provides lifecycle/frame/player/camera/service hooks, but not a full touch-input injection API.
- `horizon-client-render::android_runtime` is an Android input runtime for Android gamepad/keypad state; it is not the full touch control bridge we need.

Consequences:
- Build the touch-control settings model, editor, profiles, Android overlay UI and persistence independently in Kotlin.
- Map button presses to existing `Action` names where the public API allows it.
- Do not pretend swipe-look/analog movement is fully integrated until upstream exposes the input-source seam described as M6 in `docs/layers.md`.
- If that seam is not available at the selected engine pin, isolate the smallest required upstream extension in a reviewed patch or contribute it upstream. Do not inject a parallel game loop or copy private `Game` state.

## Feature port order

1. **Launcher shell:** new Compose/Material 3 UI; preserve working install, account and update services behind Kotlin interfaces.
2. **Storage:** SAF file manager, import/export, safe archive extraction, Minecraft folder mapping.
3. **Runtime services:** updater with signed/hash-verified artifacts and rollback; logs and diagnostics.
4. **Touch controls:** settings schema, layout profiles, editor, joystick/D-pad, floating menu and sensitivity controls. Keep renderer/game bridge behind an interface until the upstream input source API is ready.
5. **Mod systems:** HNMod compatibility checker first; HNMod build second; HMod and HShader after their APIs are audited. Compatibility is keyed to exact engine SHA, hook API and dependency graph.
6. **Renderer:** Vulkan first. Add device/driver diagnostics and surface lifecycle tests before changing GPU buffers. GLES is a later fallback, not a substitute for Vulkan correctness.

## Acceptance gates

- No copied PC engine tree in rustMC.
- No `include!` injection into `game.rs`.
- Android-owned code lives under rustMC-owned modules.
- Any upstream patch has a reason, narrow diff, exact base SHA, and automated apply/compile test.
- Physical-device tests cover Android pause/resume, surface recreation, multitouch, IME and Vulkan startup.
- A new engine pin is not accepted merely because a textual patch applies; Rust/NDK builds and runtime smoke tests must pass.
