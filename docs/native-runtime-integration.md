# Native Horizon runtime integration

The native runtime is built from the pinned `android-base` submodule. The
engine source is generated in that submodule's ignored `horizon/` directory
by the original Android Edition sync script; it is not vendored in rustMC.

Source reference:
- Android Edition submodule: `25e4516e06e049e4c7c101338fd7f15f6874d033`
- upstream engine pin: `3b691ea10b5fbbc90e00cb24e96a1c841601bcfb`
- Android patch base: `66f3981f522b24c250b21523a70080965b940424`

The module builds `libhorizon_core.so` (launcher JNI + NativeActivity
forwarder) and `libhorizon_android_game.so` (winit game runtime), then
generates and packages the pinned engine data archive. Only arm64-v8a is
enabled at this stage.

No engine files are committed into rustMC. Android changes remain in the
existing Android Edition overlay and its small patch against the pinned
upstream engine. The Play flow is not enabled until the Kotlin launcher core,
instance installation and NativeActivity launch are connected and tested.

## Mojang manifest pin

Before preparing engine tables, the build applies the small standalone helper
`android-runtime/tools/patch_android_prepare.py` to the generated Android
preparation script. It changes only the downloader to use the exact
`manifest_sha1` in the pinned engine's `research-lock.json`, instead of the
mutable version-manifest entry. Client/server jar hashes are still validated
against the engine pins. No Rust engine source is changed.
