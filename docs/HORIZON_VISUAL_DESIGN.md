# Horizon Android Edition — Horizon Command Center

## Visual identity
Horizon is a field console for Minecraft, not a generic launcher dashboard. The interface uses a near-black graphite canvas, electric-lime signal color, a restrained coral warning color, instrument-style dividers, monospaced micro-labels and oversized editorial headlines. Avoid repeating the standard rounded-card grid pattern.

## Navigation
A compact five-destination dock: Home, Library, Mods, Servers and System. The current destination is marked with a thin signal bar, not a large selected pill.

## Home — Command Center
- Large, typographic game-entry composition with a custom geometric Horizon orb.
- Live native state: core readiness, game readiness, HNMod toolchain readiness, player profile, selected instance, saved worlds, mod count and server count.
- Instance selector backed by the native instance API.
- Primary action remains the existing GameSetup launch/install route; do not change the native game-input bridge.

## Library
- Real native instances, saved worlds and installed versions.
- Native instance selection and creation.
- Existing install, folder import, backup restore and backup export operations.
- Never synthesize a version, world or instance when the native core returns none.

## Mod Workshop
- Existing embedded HNMod compiler, explicit trust confirmation, compiled-library import and streamed build log.
- HMod inventory from the native core, package import and removal.
- HShader package import. The current bridge does not expose a shader inventory, so the UI must disclose that limitation instead of inventing shader rows.
- Compatibility and compiler state are sourced from the existing toolchain.

## Multiplayer
- Server list read from the native core.
- Add and remove actions call the existing server APIs.
- Do not display ping or online status unless a real measurement is implemented.

## System
- Existing engine policy settings are read from and saved to the native core.
- HNMod toolchain state and build diagnostics.
- Graphics backend, FPS, memory and render-distance controls must remain absent until an Android-native backend exists.

## Implementation constraints
- Native Kotlin + Jetpack Compose; no WebView.
- Existing GameSetup, Updater, EmbeddedToolchain and native APIs remain the source of truth.
- Keep engine and launcher-core source untouched unless an Android-only adapter is insufficient.
- Keep the existing native input bridge unchanged.
- Do not display actions as working until they are wired to real operations.
- Support narrow phones, landscape gameplay, accessibility contrast and low-memory devices.

## Follow-on work
- Replace the temporary player identity text with a real skin texture importer and interactive 3D skin renderer.
- Surface the existing touch-control editor through an Android-native route without replacing or changing GameInput bridge semantics.
- Add performance controls only after their device/backend contracts are implemented and tested.
