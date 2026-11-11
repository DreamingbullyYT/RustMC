# Android modules

Android-specific implementation belongs here, outside the Horizon engine.

Planned ownership:

- `app/`: Android Gradle application and system-facing bridge.
- `native/`: Rust `cdylib` entry point and JNI boundary.
- `modules/input/`: touch and physical input adapters.
- `modules/lifecycle/`: Android activity and surface lifecycle.
- `modules/storage/`: Storage Access Framework and data-directory adapter.
- `modules/graphics/`: Android renderer/surface configuration.
- `modules/mods/`: compatibility discovery for supported Horizon mod APIs.

Only create a module when its interface and tests are understood. This directory
is intentionally not populated with copied code from another fork.
