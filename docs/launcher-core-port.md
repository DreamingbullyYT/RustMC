# Launcher core port

The Kotlin JNI surface (`Native.kt`) and typed asynchronous API (`Core.kt`)
are ported from the pinned Android Edition submodule. They call the native
`libhorizon_core.so` packaged by `:android-runtime`.

The application initializes the core in its private `filesDir/launcher`
directory during startup and reports the actual native core version/status.
The current Compose profile/instance modules remain separate until a safe,
data-preserving migration to Horizon's native instance API is implemented.

Source revision: `android-base@25e4516e06e049e4c7c101338fd7f15f6874d033`.
