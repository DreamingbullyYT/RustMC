pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "rustMC"
include(":android-app", ":android-storage", ":android-updater", ":android-instances", ":android-mods", ":android-runtime")

include(":android-game")
include(":zalith:InputMap")
project(":zalith:InputMap").projectDir = file("android-base/app/third_party/zalith/InputMap")
include(":zalith:LayerController")
project(":zalith:LayerController").projectDir = file("android-base/app/third_party/zalith/LayerController")
include(":zalith:Guide")
project(":zalith:Guide").projectDir = file("android-base/app/third_party/zalith/Guide")
include(":zalith:ColorPicker")
project(":zalith:ColorPicker").projectDir = file("android-base/app/third_party/zalith/ColorPicker")
include(":zalith:ControlEditor")
project(":zalith:ControlEditor").projectDir = file("android-base/app/third_party/zalith/ControlEditor")

include(":android-core")
