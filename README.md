# Ferrite

Android launcher for the [Horizon](https://github.com/horizon-rust/horizon) Rust engine. MIT, written from scratch (no code from ZalithLauncher2 or PojavLauncher).

Done: instances with isolated game folders, touch-layout model (MCPE/Java presets, JSON import/export, overlap check),
world backup/restore, crash-log diagnosis, update check, Modrinth search, EN/RO UI.
Not done: account login, mod install/download, a drag-and-drop layout editor, Horizon engine hand-off beyond the
intent contract in `MainActivity.launch`, gamepad mapping.

`gradle :core:check` runs the 29 dependency-free core checks; `gradle :app:assembleDebug` builds the APK (CI: `.github/workflows/build.yml`).
