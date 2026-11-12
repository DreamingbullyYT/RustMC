

## Upstream architecture check (current main)

The current upstream's `docs/layers.md` explicitly says desktop-class ports, including Android, should build on the platform without editing engine files. It also says the engine exposes input hooks for platform features. This is the direction rustMC should follow rather than preserving the older invasive Android integration unchanged.

The same document describes the current input boundary more precisely:

- Mapping-based actions are the existing input abstraction; the game asks whether an `Action` is held rather than which physical key produced it.
- The remaining analog movement/look and menu-focus input-source interface is listed as M6 work. The document says those will become a registered input source implemented by platform code.
- The current `horizon-hooks::Hooks` trait is mainly lifecycle, frame, tick, player, camera, HUD-adjacent and service hooks; it is not yet a complete Android touch-input API.

This gives us a concrete integration route: keep Android UI, SAF, updater, touch-layout editor, toolchain and Android device services in rustMC-owned platform/app crates; use the existing action abstraction where possible; and avoid writing a parallel game input system. For analog look/movement and lifecycle/surface operations, first check whether the upstream M6 input-source and renderer seams have landed at the chosen pin. If not, propose the smallest upstream extension point and keep its patch isolated and reviewable. Do not silently fork or vendor the whole game loop.

The newer overlay script also touches these refactored engine files in addition to `game.rs`: `game/window_handler.rs`, `game/pointer_capture.rs`, `game/resource_loader.rs`, `game/loading_screen.rs`, `horizon-client-render/src/gfx/surface_setup.rs` (or legacy `gfx.rs`), and `horizon-threads/src/jobs.rs`. Its `game.rs` edits add a module, bridge exports, touch state and game-loop calls. This is a broader seam than the small textual patch file alone suggests.
