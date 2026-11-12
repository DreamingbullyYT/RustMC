# Ferrite

Android launcher for the [Horizon](https://github.com/horizon-rust/horizon) Rust engine. MIT, written from scratch (no code from ZalithLauncher2 or PojavLauncher).

Done (core, covered by 94 dependency-free checks, `gradle :core:check`):
- Instances: isolated game folders, clone, export/import, pinned engine version per instance.
- Worlds: list with version/size/last played (reads `level.dat`), per-world backup/restore, rename, delete, import.
  Restore never overwrites and refuses another game version unless accepted. Full-instance backup too.
- Mods: `.hmod`, `.hnmod`, `.hshader`; enable/disable by renaming (files kept), install/uninstall, compatibility,
  dependency and conflict checks (HNMod manifest `horizon-native.json`).
- Servers: saved list, join history, Server List Ping.
- Diagnostics: crash history with cause detection, log redaction and support export, file integrity check.
- Offline accounts, graphics-API capability check (Vulkan 1.0/1.1, GLES 2.0/3.0), HNMod build-job state machine
  (shared by the Discord bot and the launcher), component updater with SHA-256 verification.
- Controls and quick settings: edit the engine's own `horizon-touch.json` per instance (sensitivity, resolution scale,
  virtual mouse, button size, schemes, per-control position/size/opacity/visibility for all 3 modes). Unknown keys in the
  file are preserved. Settings and ranges follow Horizon (MIT, Horizon contributors) as used in GoyDevv/Horizon-Android.
- Modrinth search, update check, EN/RO UI (Play, Worlds, Servers, Mods, Controls, Logs tabs).

Not done: Microsoft login, real mod/engine download wiring, the layout editor, resource-pack validation, file explorer,
skin viewer, Diagnostics and Settings screens, the Discord bot side of build jobs, and Android lifecycle handling
(that lives in the engine's Android host, not in the launcher). The Compose UI has not been compiled yet: CI does it.

Also: Files-app access to the instances folder (`FerriteDocumentsProvider`, path rules tested in `SafePaths`), Material You theme with an optional
animated background, and a CI build that signs with a repository key when `FERRITE_KEYSTORE_BASE64`, `FERRITE_KEYSTORE_PASSWORD` and
`FERRITE_KEY_ALIAS` secrets exist (otherwise the debug key), numbers each build and publishes SHA256SUMS.
