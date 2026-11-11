# Architecture and change policy

## Dependency direction

```text
android/app          Android application, manifest and system bridges
       |
android/native      Rust Android entry point and JNI/native boundary
       |
android/modules     input, lifecycle, storage, graphics and mod integration
       |
upstream/horizon    pinned, unmodified upstream engine
```

The Android modules may depend on public upstream crates and documented extension
points. Upstream engine code must not depend on Android modules.

## Module ownership

- `android/app`: Gradle project, manifest, Activity and minimal Java/Kotlin bridges.
- `android/native`: Android `cdylib`, JNI boundary and startup/shutdown.
- `android/modules/input`: touch, virtual controls, keyboard, mouse and gamepad mapping.
- `android/modules/lifecycle`: surface creation/destruction, pause/resume and recovery.
- `android/modules/storage`: SAF/document-provider access and game data paths.
- `android/modules/graphics`: Android surface and renderer selection policy.
- `android/modules/mods`: discovery and compatibility checks; no unsupported mod format is
  claimed to work until tested against the pinned engine/API.

Modules are added only when they own real code. Avoid placeholder crates and
unnecessary abstraction layers.

## Upstream pin

`UPSTREAM.lock` is the human-readable source of truth. The Git submodule gitlink
must point to the same commit. Updating Horizon means changing both deliberately,
reviewing upstream API changes, and rerunning the architecture and Android tests.

## Minimal-change policy

1. Reproduce a bug with a focused test before changing code.
2. Change only the responsible Android module.
3. Never edit files in `upstream/horizon` as part of normal Android development.
4. If a missing extension point requires upstream work, first document the exact
   API gap and a minimal upstream proposal.
5. Do not report an APK or device test as successful unless it was actually built
   and exercised.
