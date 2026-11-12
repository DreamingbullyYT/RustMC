# Horizon Android — clean rebuild

This repository is the integration workspace for the Android launcher and the upstream Horizon engine.

## Source repositories
- `android-base/`: pinned Android Edition source, used as a read-only reference for Kotlin/JNI, updater, storage and toolchain work.
- `references/goy-horizon-android/`: pinned feature reference for touch controls, file management and Android runtime behavior.
- Engine upstream: https://github.com/horizon-rust/horizon

## Current audit
- [Architecture audit](docs/architecture-audit-2026-11-12.md)
- [Android port plan](docs/ANDROID_PORT_PLAN.md)
- [Engine compatibility status](docs/ENGINE_COMPATIBILITY.md)

## Clone
```sh
git clone --recurse-submodules https://github.com/DreamingbullyYT/rustMC.git
```

## Rebuild rules
- Rebuild the launcher UI independently with native Jetpack Compose / Material 3.
- Keep the PC engine as the source of truth; do not vendor a fork of the engine.
- Keep Android UI, SAF, updater, toolchain and platform services in rustMC-owned modules.
- Do not inject Android code into `game.rs` with `include!` or copy the legacy private-state game-loop bridge unchanged.
- Use public platform APIs and the planned input-source seam; if an upstream extension is unavoidable, keep it tiny, documented and separately tested.
- Check HNMod/HMod/HShader against the exact engine SHA, hook API and dependencies before building.
- Preserve upstream and third-party licenses.

The previous working tree was intentionally reset. Git history and the pinned source references remain available.
