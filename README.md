# Horizon Android — clean rebuild

This repository is the new integration workspace.

## Source repositories
- `android-base/`: the existing Horizon Android Edition project, pinned to an exact commit.
- `references/goy-horizon-android/`: GoyDevv's Android port, kept as a reference for Android controls, Quick Settings, file management, and runtime integration.
- Engine upstream: https://github.com/horizon-rust/horizon

## Clone
```sh
git clone --recurse-submodules https://github.com/DreamingbullyYT/rustMC.git
```

## Rebuild rules
- Rework the Android Edition baseline; do not copy its old UI blindly.
- Design a new native Jetpack Compose / Material 3 launcher.
- Keep Horizon engine code as close to PC upstream as possible.
- Keep Android-only code in independent modules and overlay files.
- Minimize, document, and test every upstream patch.
- Audit and preserve all upstream and third-party licenses.
- Use GoyDevv/Horizon-Android as a feature reference, not as an unchecked engine replacement.

Git history before the clean reset is retained. The previous tracked working tree was removed intentionally.
