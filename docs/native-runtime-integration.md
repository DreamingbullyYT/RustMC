# Native Horizon runtime integration

The Android runtime is built from the pinned `android-base` submodule rather
than vendoring or editing a second engine tree in this repository.

Source reference:
- `android-base`: `DreamingbullyYT/horizon-android-edition`
- submodule revision: `25e4516e06e049e4c7c101338fd7f15f6874d033`
- upstream engine pin used by that revision: `3b691ea10b5fbbc90e00cb24e96a1c841601bcfb`
- Android patch base: `66f3981f522b24c250b21523a70080965b940424`

`android-runtime` runs the source repository's `android/sync.sh`, which
checks out the pinned PC Horizon source, applies the Android patch and copies
the standalone Android overlay. It then builds two arm64 libraries:
- `libhorizon_core.so`: launcher JNI/core and NativeActivity forwarding stub.
- `libhorizon_android_game.so`: native winit game runtime.

It also generates and packages the engine data archive. The engine itself is
not copied into the rustMC Git tree and no engine source is modified here.

This is the native build foundation only. The Kotlin NativeActivity, JNI input
adapter, game launch flow and Zalith controls are the next integration layer;
the Play action must remain disabled until those are connected and verified.
