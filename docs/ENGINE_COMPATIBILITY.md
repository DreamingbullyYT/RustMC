# Engine compatibility status

Last inspected: 2026-11-12.

## Revisions

| Source | Commit |
|---|---|
| Android Edition reference | `25e4516e06e049e4c7c101338fd7f15f6874d033` |
| GoyDevv feature reference | `ae4c85dc5b31da2078ee1e1009a53ced7d7ed1ba` |
| Android Edition engine pin | `3b691ea10b5fbbc90e00cb24e96a1c841601bcfb` |
| Upstream PC main observed | `f24ee1accf1c816736e6e067939d04f3853f474e` |

GitHub compare reports the Android Edition pin 3,330 commits behind current upstream main. This is a distance measure, not a count of Android-relevant changes. The new upstream main must be treated as a candidate until the Android build and device tests pass.

## Existing Android Edition integration

The reference already separates much of the Android code into overlay files and two Android crates. However, its overlay script also edits:
- `crates/horizon-client/src/game.rs`
- `crates/horizon-client/src/game/window_handler.rs`
- `crates/horizon-client/src/game/pointer_capture.rs`
- `crates/horizon-client/src/game/resource_loader.rs`
- `crates/horizon-client/src/game/loading_screen.rs`
- renderer surface setup and thread/job integration files.

The large `game/android.rs` module imports parent-private state with `use super::*`. This is not a clean external platform boundary and must not be copied into the production port as-is. The integration does not use `include!`, but it still has multiple invasive game-loop seams.

## Current upstream APIs

At `f24ee1accf1c816736e6e067939d04f3853f474e`:
- `horizon-client-input::actions::Action` provides the common action vocabulary.
- `horizon-client-input::input_source` exposes `Feedback`, but does not yet expose a registered analog movement/look input source.
- `horizon-hooks::Hooks` exposes lifecycle, frame, tick, player, camera and service hooks; it is not a complete touch input injection API.
- `horizon-client-render::android_runtime` exists for Android gamepad/keypad state; it does not replace a touch-input API.

The upstream `docs/layers.md` says Android and other desktop-class ports should build on the platform without editing engine files. The port should follow that rule and wait for/use the planned M6 input-source seam for analog movement/look. Until then, the Android touch UI and editor can be developed independently, but full gameplay integration is not yet proven.

## GoyDevv feature reference

The pinned GoyDevv tree contains a substantial Rust touch system:
- `crates/horizon-client-render/src/touch.rs`
- `touch/custom.rs`
- `touch/editor.rs`
- `touch/layout.rs`

It includes multiple control styles, multitouch ownership, custom event slots, normalized layouts, camera sensitivity and a visual editor. These are useful behavior references, not code to merge wholesale. Port the data model, UX and tests selectively into Android-owned modules.

## Renderer caveat

The Android Edition's renderer patch adjusts surface lifecycle and frame latency. It does not establish that the previous Adreno 740 device-loss / invalid mapped buffer failure is fixed. The FIFO experiment did not change that failure. Do not patch the Stars vertex buffer speculatively; first isolate pipeline creation, validation output and the first failing GPU operation.

## Status

- Source audit: complete for the inspected commits.
- New upstream pin: not approved.
- HNMod compatibility against current upstream: not verified.
- Vulkan device-loss issue: unresolved.
- New Compose UI: not implemented yet.
- APK build against current upstream: not performed.
