# GoyDevv touch-controls integration

## Source of truth

The touch implementation to reuse is pinned in the existing
`references/goy-horizon-android` submodule:

- Repository: https://github.com/GoyDevv/Horizon-Android
- Pinned revision: `ae4c85dc5b31da2078ee1e1009a53ced7d7ed1ba`

Relevant upstream files:

- `crates/horizon-client-render/src/touch.rs` — multitouch ownership, movement/look gestures, action dispatch, HUD drawing and touch state.
- `crates/horizon-client-render/src/touch/layout.rs` — MCPE/GoyControl settings, presets, placements, joystick style, visibility and serialization.
- `crates/horizon-client-render/src/touch/custom.rs` — custom buttons and multi-event key/action bindings.
- `crates/horizon-client-render/src/touch/editor.rs` — in-game visual editor, drag/resize, property inspector, event picker, undo/redo and preset save flow.
- `crates/horizon-client/src/touch_ui.rs` — Android touch menu/hub and auxiliary UI gestures.
- `packaging/android/compose/HorizonComposeLauncher.kt` — GoyDevv's Android Compose launcher integration points.

## Integration boundary

These files are not an independent Android UI library. The renderer touch system
uses its own `HudAtlas`, `HudBatch`, `PadState`, gamepad actions, renderer
state and client input lifecycle. The editor is driven by touch events from that
same runtime. Copying only the settings screen or inventing a Kotlin profile
format would not reproduce the actual editor or its input behavior.

The current `rustMC` app shell does not yet launch the Rust game runtime/JNI
bridge (the Play action is intentionally disabled). Therefore the GoyDevv touch
renderer cannot honestly be enabled in the app until that runtime boundary exists.

## Porting rules

1. Keep the pinned GoyDevv source as the behavioral reference.
2. Integrate the Rust touch renderer and editor with the Android runtime through
   a dedicated Android adapter/module; do not inject code into `game.rs`.
3. Keep the GoyDevv settings JSON compatible, including its `horizon-touch.json`
   format, MCPE/GoyControl presets, all 14 built-in control placements and
   custom-button events.
4. Forward Android multi-pointer events with stable pointer IDs and preserve
   ownership/release semantics. Do not translate look gestures into Compose
   scrolling.
5. Keep the renderer/engine changes limited to the minimum adapter required;
   put Android lifecycle, persistence paths and UI wiring in separate files.
6. Do not ship a second simplified touch system alongside GoyDevv's system.

## Current state

The earlier experimental `android-controls` profile model was removed because
its fields and behavior did not match GoyDevv's `Settings` schema or editor.
Touch controls remain an explicit integration task until the native runtime is
connected. No engine or submodule source was changed by this cleanup.
