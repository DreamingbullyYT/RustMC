# Touch controls port

The Android game module compiles the original Horizon Android Edition game
package directly from the pinned `android-base` submodule. This keeps the
Zalith overlay, layout editor, profiles, input routing and JNI-facing Kotlin
code tied to the source implementation instead of a re-created Compose mock.

The Zalith libraries are also consumed from the same pinned submodule:
`InputMap`, `LayerController`, `Guide`, `ColorPicker` and `ControlEditor`.

A small `dev.horizon.launcher.data.Updater` adapter supplies only the session
marker and optional updated-library path expected by the imported Activity.
It does not implement runtime updates. The bundled native libraries are the
fallback. The Play button remains disabled until instance installation and
launch are wired to the launcher core.
