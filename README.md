# Horizon Android — rebuild

This repository is being restarted from a clean baseline.

## Sources
- Android implementation reference: https://github.com/GoyDevv/Horizon-Android
- Android Edition architecture baseline: https://github.com/DreamingbullyYT/horizon-android-edition
- Engine upstream: https://github.com/horizon-rust/horizon

## Project rules
- Keep the Horizon engine as close to upstream as possible.
- Keep Android-specific code in separate modules and overlay files.
- Keep upstream patches minimal, documented, and automatically checked.
- Rebuild the Android launcher UI independently with Jetpack Compose Material 3.
- Preserve licenses and attribution for all reused code.

The previous tracked project tree was intentionally removed to begin this rebuild. Git history remains available.
