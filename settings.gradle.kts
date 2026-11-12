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

rootProject.name = "HorizonAndroid"
include(":app")
include(":zalith:InputMap")
project(":zalith:InputMap").projectDir = file("app/third_party/zalith/InputMap")
include(":zalith:LayerController")
project(":zalith:LayerController").projectDir = file("app/third_party/zalith/LayerController")

include(":zalith:Guide")
project(":zalith:Guide").projectDir = file("app/third_party/zalith/Guide")
include(":zalith:ColorPicker")
project(":zalith:ColorPicker").projectDir = file("app/third_party/zalith/ColorPicker")
include(":zalith:ControlEditor")
project(":zalith:ControlEditor").projectDir = file("app/third_party/zalith/ControlEditor")
