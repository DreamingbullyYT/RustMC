plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.horizon.launcher"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "dev.horizon.launcher"
        minSdk = 26
        // Match Gemini-cli-for-Android: Android 10+ permits this embedded PRoot exec path below target 29.
        targetSdk = 28
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = System.getenv("HORIZON_VERSION_NAME") ?: "0.1.0-alpha"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildFeatures { compose = true }
    androidResources { noCompress += listOf("zip") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
    }

    val releaseKeystore = System.getenv("HORIZON_KEYSTORE_PATH")
    signingConfigs {
        // One fixed key for every CI build, so a new APK installs over the old one (in-app update).
        // CI runners would otherwise sign each build with a fresh random debug key.
        getByName("debug") {
            storeFile = file("horizon-debug.jks")
            storePassword = "android"
            keyAlias = "horizon"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("HORIZON_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HORIZON_KEY_ALIAS")
                keyPassword = System.getenv("HORIZON_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: System.getenv("HORIZON_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    sourceSets["main"].jniLibs.setSrcDirs(listOf(layout.buildDirectory.dir("generated/jniLibs")))
    sourceSets["main"].assets.srcDir("../horizon/.local/android")
}

val rustJniLibs = layout.buildDirectory.dir("generated/jniLibs")
val rustTargetDir = layout.buildDirectory.dir("rust-target")
val ndkHostTag = when {
    System.getProperty("os.name").lowercase().contains("win") -> "windows-x86_64"
    System.getProperty("os.name").lowercase().contains("mac") && System.getProperty("os.arch") == "aarch64" -> "darwin-arm64"
    System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val ndkBin = android.ndkDirectory.resolve("toolchains/llvm/prebuilt/$ndkHostTag/bin")

val rustAndroidTargets = listOf(Triple("arm64-v8a", "aarch64-linux-android", "aarch64-linux-android"))

val rustBuildTasks = rustAndroidTargets.map { (abi, target, compilerPrefix) ->
    val targetEnv = target.uppercase().replace('-', '_')
    tasks.register<Exec>("buildRust${abi.replace('-', '_')}") {
        group = "build"
        description = "Build Horizon's Rust JNI core for $abi"
        inputs.files(fileTree("../core/src"), fileTree("../horizon/launcher/src"), fileTree("../horizon/crates"))
        inputs.files("../core/Cargo.toml", "../core/Cargo.lock", "../horizon/Cargo.toml", "../horizon/Cargo.lock")
        inputs.property("rustflags", "-C link-arg=-Wl,-z,max-page-size=16384")
        outputs.file(rustJniLibs.map { it.file("$abi/libhorizon_core.so") })
        workingDir = rootProject.projectDir
        environment("CARGO_TARGET_DIR", rustTargetDir.get().asFile.absolutePath)
        environment("RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")
        environment("CARGO_TARGET_${targetEnv}_LINKER", ndkBin.resolve("${compilerPrefix}26-clang").absolutePath)
        environment("CC_${target}", ndkBin.resolve("${compilerPrefix}26-clang").absolutePath)
        environment("AR_${target}", ndkBin.resolve("llvm-ar").absolutePath)
        System.getenv("HORIZON_MSA_CLIENT_ID")?.takeIf { it.isNotBlank() }?.let { environment("HORIZON_MSA_CLIENT_ID", it) }
        commandLine("cargo", "build", "--manifest-path", "core/Cargo.toml", "--target", target, "--release", "--features", "audio")
        doLast {
            copy { from(rustTargetDir.get().file("$target/release/libhorizon_core.so")); into(rustJniLibs.get().dir(abi)) }
        }
    }
}

val rustAuthBuildTasks = rustAndroidTargets.map { (abi, target, compilerPrefix) ->
    val targetEnv = target.uppercase().replace('-', '_')
    tasks.register<Exec>("buildHorizonMicrosoftAuth${abi.replace('-', '_')}") {
        group = "build"
        description = "Build Horizon's Microsoft device-code JNI bridge for $abi"
        inputs.files(fileTree("../android-auth/src"), "../android-auth/Cargo.toml", "../horizon/launcher/src")
        outputs.file(rustJniLibs.map { it.file("$abi/libhorizon_msa.so") })
        workingDir = rootProject.projectDir
        environment("CARGO_TARGET_DIR", rustTargetDir.get().asFile.absolutePath)
        environment("RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")
        environment("CARGO_TARGET_${targetEnv}_LINKER", ndkBin.resolve("${compilerPrefix}26-clang").absolutePath)
        environment("CC_${target}", ndkBin.resolve("${compilerPrefix}26-clang").absolutePath)
        environment("AR_${target}", ndkBin.resolve("llvm-ar").absolutePath)
        System.getenv("HORIZON_MSA_CLIENT_ID")?.takeIf { it.isNotBlank() }?.let { environment("HORIZON_MSA_CLIENT_ID", it) }
        commandLine("cargo", "build", "--manifest-path", "android-auth/Cargo.toml", "--target", target, "--release")
        doLast {
            copy { from(rustTargetDir.get().file("$target/release/libhorizon_msa.so")); into(rustJniLibs.get().dir(abi)) }
        }
    }
}

val androidGameBuildTasks = rustAndroidTargets.map { (abi, target, compilerPrefix) ->
    val targetEnv = target.uppercase().replace('-', '_')
    tasks.register<Exec>("buildHorizonGame${abi.replace('-', '_')}") {
        group = "build"
        description = "Build Horizon's Android NativeActivity game runtime for $abi"
        inputs.files(fileTree("../horizon/crates"), fileTree("../horizon/third_party"))
        inputs.files("../horizon/Cargo.toml", "../horizon/Cargo.lock")
        inputs.property("rustflags", "-C link-arg=-Wl,-z,max-page-size=16384")
        outputs.file(rustJniLibs.map { it.file("$abi/libhorizon_android_game.so") })
        workingDir = rootProject.projectDir
        environment("CARGO_TARGET_DIR", rustTargetDir.get().asFile.absolutePath)
        environment("RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")
        environment("CARGO_TARGET_${targetEnv}_LINKER", ndkBin.resolve("${compilerPrefix}26-clang").absolutePath)
        environment("CC_${target}", ndkBin.resolve("${compilerPrefix}26-clang").absolutePath)
        environment("AR_${target}", ndkBin.resolve("llvm-ar").absolutePath)
        commandLine("cargo", "build", "--manifest-path", "horizon/Cargo.toml", "--package", "horizon-android-game", "--target", target, "--profile", "fast")
        doLast {
            copy { from(rustTargetDir.get().file("$target/fast/libhorizon_android_game.so")); into(rustJniLibs.get().dir(abi)) }
        }
    }
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
        dependsOn(rustBuildTasks + rustAuthBuildTasks + androidGameBuildTasks)
    }
}

dependencies {
    implementation(project(":zalith:LayerController"))
    implementation(project(":zalith:ControlEditor"))
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.savedstate:savedstate:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
