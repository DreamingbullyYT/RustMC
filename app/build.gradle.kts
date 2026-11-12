plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.ferrite.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.ferrite.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "0.1.0"
    }
    // A fixed key (CI secret) lets each build update the last one in place; without it, builds use the debug key.
    val keystore = System.getenv("FERRITE_KEYSTORE")
    signingConfigs {
        if (keystore != null) create("ci") {
            storeFile = file(keystore); storePassword = System.getenv("FERRITE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("FERRITE_KEY_ALIAS"); keyPassword = System.getenv("FERRITE_KEYSTORE_PASSWORD")
        }
    }
    buildTypes {
        release { signingConfig = if (keystore != null) signingConfigs.getByName("ci") else signingConfigs.getByName("debug"); isMinifyEnabled = false }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
}
