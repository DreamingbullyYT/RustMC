# Ferrite

Android launcher for the [Horizon](https://github.com/horizon-rust/horizon) Rust engine. MIT, written from scratch (no code from ZalithLauncher2 or PojavLauncher).

Done (core, covered by 81 dependency-free checks, `gradle :core:check`):
- Instances: isolated game folders, clone, export/import, pinned engine version per instance.
- Worlds: list with version/size/last played (reads `level.dat`), per-world backup/restore, rename, delete, import.
  Restore never overwrites and refuses another game version unless accepted. Full-instance backup too.
- Mods: `.hmod`, `.hnmod`, `.hshader`; enable/disable by renaming (files kept), install/uninstall, compatibility,
  dependency and conflict checks (HNMod manifest `horizon-native.json`).
- Servers: saved list, join history, Server List Ping.
- Diagnostics: crash history with cause detection, log redaction and support export, file integrity check.
- Offline accounts, graphics-API capability check (Vulkan 1.0/1.1, GLES 2.0/3.0), HNMod build-job state machine
  (shared by the Discord bot and the launcher), component updater with SHA-256 verification.
- Touch-layout model, Modrinth search, update check, EN/RO UI (Play, Worlds, Servers, Mods, Controls, Logs tabs).

Not done: Microsoft login, real mod/engine download wiring, the layout editor, resource-pack validation, file explorer,
skin viewer, Diagnostics and Settings screens, the Discord bot side of build jobs, and Android lifecycle handling
(that lives in the engine's Android host, not in the launcher). The Compose UI has not been compiled yet: CI does it.
