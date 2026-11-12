# Launcher core port

The Android Edition JNI API (`Native.kt`) and typed Kotlin core API
(`Core.kt`) are compiled in `:android-core`. The matching runtime updater
is also ported: it prepares verified native runtime updates, sets the active
library path before JNI loading, synchronizes the engine data archive, and
maintains launcher/game startup guards.

The app now initializes the core and engine data during startup. Home checks
the native installer's ready state, can install Minecraft through the native
installer, and starts the original Horizon NativeActivity when installation is
ready. Backup and existing Mojang folder import helpers are also ported.

The native launcher core and engine source remain from the pinned
`android-base` submodule; this does not introduce a separate Rust engine fork.
