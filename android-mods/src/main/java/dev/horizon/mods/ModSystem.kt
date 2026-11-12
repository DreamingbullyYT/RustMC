package dev.horizon.mods

enum class ModSystem(val title: String, val description: String) {
    HNMOD("HNMod", "Build and integrate Android/native modifications with dependency and engine API checks."),
    HMOD("HMod", "Manage native packages supported by the Horizon runtime."),
    HSHADER("HShader", "Import and manage shader-pack archives for the selected instance.")
}
