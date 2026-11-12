# Android Edition baseline audit

Audited source: `DreamingbullyYT/horizon-android-edition` at
`25e4516e06e049e4c7c101338fd7f15f6874d033` (the `android-base` submodule pin).

This document records what can be reused and what must not be assumed when building
the rustMC-owned Android app.

## What the baseline actually contains

- A Kotlin/Jetpack Compose launcher UI in
  `app/src/main/java/dev/horizon/launcher/ui/HorizonLauncherUi.kt`.
- An activity/controller in `MainActivity.kt` that wires UI callbacks to install,
  accounts, instances, mods, shaders, settings, touch controls, logs and update services.
- A Kotlin JNI contract in `core/Native.kt` and game-input bridge in
  `game/GameInput.kt`.
- Android services for the embedded compiler/toolchain, updater, storage import/export,
  game activity and touch-control editor.
- Rust launcher-side code in `core/` and an Android auth crate in `android-auth/`.
- A generated `horizon/` engine checkout is intentionally absent from the repository.

## Build pipeline and its coupling

The baseline is not a self-contained Gradle app. Its documented build is:

1. `android/sync.sh` clones upstream Horizon at `android/UPSTREAM_PIN`.
2. It applies `android/android.patch` and copies `android/overlay/` into the generated
   `horizon/` checkout.
3. Gradle builds the Rust JNI launcher core, Microsoft-auth bridge and
   `horizon-android-game` NativeActivity library.
4. The app packages generated JNI libraries and engine data from `horizon/`.

The Gradle build therefore expects generated `core/` and `horizon/` paths. Pointing
a new root Gradle project at the pinned `android-base` submodule without reproducing
that pipeline would create a misleading, non-functional build.

The existing Kotlin activity is also not a small standalone shell: it references a
large set of baseline data/services and its JNI methods are implemented by the
baseline Rust core. Porting only `MainActivity.kt` or the Compose screen would not
produce a working launcher.

## Reuse strategy for rustMC

- Keep `android-base` pinned and read-only as a reference.
- Port Kotlin features in coherent rustMC-owned modules; preserve the behavior and
  service contracts, but do not copy the old generated-engine integration blindly.
- Keep the new app's JNI boundary explicit and versioned. Do not load a library or
  expose a UI action as functional until the corresponding native capability exists.
- Treat touch controls as a UI/settings feature until upstream exposes a public analog
  input-source seam. The old game bridge depends on the legacy Android game runtime.
- Keep the engine pin and Android patch separate from the app modules. Any required
  upstream edits must be individually justified, minimized and tested against the
  exact upstream SHA.
- Build CI in stages: Kotlin/Compose app validation first; Rust/JNI integration only
  after the rustMC-owned native bridge and upstream platform contract are ready.

## Current conclusion

The baseline is a valuable feature and implementation reference, not a drop-in
Gradle dependency. The next safe implementation unit is a rustMC-owned Android app
module with explicit interfaces for launcher services, storage, updater, controls
and native capabilities. It should not claim to launch Minecraft until the native
bridge and engine runtime are connected and tested.
