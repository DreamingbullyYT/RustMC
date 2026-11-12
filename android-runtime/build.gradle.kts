plugins {
    id("com.android.library")
}

android {
    namespace = "dev.horizon.runtime"
    compileSdk = 35
    ndkVersion = "26.3.11579264"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    sourceSets["main"].jniLibs.setSrcDirs(listOf(layout.buildDirectory.dir("generated/jniLibs")))
    sourceSets["main"].assets.srcDir(rootProject.file("android-base/horizon/.local/android"))
    androidResources { noCompress += listOf("zip") }
    packaging { jniLibs.useLegacyPackaging = true }
}

val baseDir = rootProject.file("android-base")
val engineDir = baseDir.resolve("horizon")
val rustTargetDir = layout.buildDirectory.dir("rust-target")
val generatedJni = layout.buildDirectory.dir("generated/jniLibs")
val target = "aarch64-linux-android"
val targetEnv = target.uppercase().replace('-', '_')
val ndkHostTag = when {
    System.getProperty("os.name").lowercase().contains("win") -> "windows-x86_64"
    System.getProperty("os.name").lowercase().contains("mac") && System.getProperty("os.arch") == "aarch64" -> "darwin-arm64"
    System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val ndkBin = android.ndkDirectory.resolve("toolchains/llvm/prebuilt/$ndkHostTag/bin")
val linker = ndkBin.resolve("aarch64-linux-android26-clang").absolutePath

val syncAndroidEngine = tasks.register<Exec>("syncHorizonAndroidEngine") {
    group = "build"
    description = "Prepare pinned upstream Horizon source with the Android Edition overlay."
    workingDir = rootProject.projectDir
    commandLine("bash", "android-base/android/sync.sh")
    inputs.files(
        baseDir.resolve("android/UPSTREAM_PIN"),
        baseDir.resolve("android/STABLE_ANDROID_PIN"),
        baseDir.resolve("android/android.patch"),
        baseDir.resolve("android/android-next.patch"),
        fileTree(baseDir.resolve("android/overlay")),
        baseDir.resolve("android/sync.sh")
    )
    outputs.dir(engineDir)
}

val prepareEngineData = tasks.register<Exec>("prepareHorizonAndroidEngineData") {
    group = "build"
    description = "Generate the pinned Minecraft engine data bundled in the APK."
    dependsOn(syncAndroidEngine)
    workingDir = engineDir
    commandLine(
        "bash", "-lc",
        "python3 ../../android-runtime/tools/patch_android_prepare.py tools/android_prepare.py && python3 tools/android_prepare.py && python3 tools/android_engine_data.py && rm -rf .local/android/minecraft .local/android/jdk .local/horizon-client"
    )
    inputs.file(rootProject.file("android-runtime/tools/patch_android_prepare.py"))
    outputs.file(engineDir.resolve(".local/android/engine-data.zip"))
}

val buildHorizonCore = tasks.register<Exec>("buildHorizonCoreArm64") {
    group = "build"
    description = "Build Horizon launcher JNI core for arm64-v8a."
    dependsOn(syncAndroidEngine)
    workingDir = rootProject.projectDir
    environment("CARGO_TARGET_DIR", rustTargetDir.get().asFile.absolutePath)
    environment("RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")
    environment("CARGO_TARGET_${targetEnv}_LINKER", linker)
    environment("CC_${target}", linker)
    environment("AR_${target}", ndkBin.resolve("llvm-ar").absolutePath)
    commandLine("cargo", "build", "--manifest-path", "android-base/core/Cargo.toml", "--target", target, "--release", "--features", "audio")
    inputs.files(fileTree(baseDir.resolve("core/src")), baseDir.resolve("core/Cargo.toml"), baseDir.resolve("core/Cargo.lock"))
    outputs.file(generatedJni.map { it.file("arm64-v8a/libhorizon_core.so") })
    doLast {
        copy {
            from(rustTargetDir.get().file("$target/release/libhorizon_core.so"))
            into(generatedJni.get().dir("arm64-v8a"))
        }
    }
}

val buildHorizonGame = tasks.register<Exec>("buildHorizonGameArm64") {
    group = "build"
    description = "Build Horizon NativeActivity game runtime for arm64-v8a."
    dependsOn(syncAndroidEngine)
    workingDir = rootProject.projectDir
    environment("CARGO_TARGET_DIR", rustTargetDir.get().asFile.absolutePath)
    environment("RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")
    environment("CARGO_TARGET_${targetEnv}_LINKER", linker)
    environment("CC_${target}", linker)
    environment("AR_${target}", ndkBin.resolve("llvm-ar").absolutePath)
    commandLine("cargo", "build", "--manifest-path", "android-base/horizon/Cargo.toml", "--package", "horizon-android-game", "--target", target, "--profile", "fast")
    inputs.files(fileTree(engineDir.resolve("crates")), fileTree(engineDir.resolve("third_party")), engineDir.resolve("Cargo.toml"), engineDir.resolve("Cargo.lock"))
    outputs.file(generatedJni.map { it.file("arm64-v8a/libhorizon_android_game.so") })
    doLast {
        copy {
            from(rustTargetDir.get().file("$target/fast/libhorizon_android_game.so"))
            into(generatedJni.get().dir("arm64-v8a"))
        }
    }
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
        dependsOn(buildHorizonCore, buildHorizonGame)
    }
    // The assets source directory is created by sync and populated by prepareEngineData.
    // Package tasks need the explicit edge too (not only mergeAssets).
    if (name.endsWith("Assets") && (name.startsWith("merge") || name.startsWith("package"))) {
        dependsOn(prepareEngineData)
    }
}
