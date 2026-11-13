<div align="center">

# Horizon Android Edition

**Minecraft: Java Edition 26.3, rewritten in Rust, now in your pocket.**

[![Android 8+](https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=white)](#requirements)
[![ARM64](https://img.shields.io/badge/ABI-arm64--v8a-blue)](#requirements)
[![Rust core](https://img.shields.io/badge/core-Rust-orange?logo=rust&logoColor=white)](#how-its-built)
[![Horizon: MIT](https://img.shields.io/badge/Horizon-MIT-informational)](HORIZON-LICENSE.txt)

</div>

> **Status:** this repository may be retired. A newer launcher is in the works and will be announced.
> Until then, install the latest release.

An unofficial Android edition of [Horizon](https://github.com/horizon-rust/horizon), the Rust rewrite of
Minecraft: Java Edition. A phone-first launcher, touch controls and an Android game activity wrapped around
the shared Horizon engine. Not affiliated with Mojang, Microsoft or Zalith Launcher 2.
No Minecraft files are included: bring game files you are licensed to use.

## Highlights

| | |
|---|---|
| 🎮 **Touch controls** | Joystick, buttons, look area, gyro look, gamepad support, a layout editor, presets and an in-game quick settings panel. |
| 🎨 **Compose launcher** | A glass-style Jetpack Compose UI with an animated backdrop, built for phones and tablets rather than ported from desktop. |
| 🧩 **Mods and shaders** | Import `.hmod` packages and `.hshader` packs. Native mods can be compiled on the device. |
| 💾 **Backups** | Export and restore your worlds and settings, using Android's Storage Access Framework. |
| 🔄 **Tracks upstream** | A small patch plus separate overlay files keep the engine close to Horizon's `main`, and a workflow only accepts upstream commits that still build for Android. |

## Install

1. Download the newest APK from [Releases](../../releases) or from the latest successful run in **Actions**.
2. Install it and open the app.
3. Follow the Home screen to set up your Minecraft files (folder import or a ZIP backup).
4. Tap **Play**.

### Requirements

- Android 8.0 (API 26) or newer
- An ARM64 (`arm64-v8a`) phone or tablet
- Enough free storage for the engine, game resources and your worlds

## How it's built

```
Compose launcher  ──JNI/JSON──▶  Rust core  ──▶  Horizon engine (upstream + small patch)
      │                                                     ▲
      └── Game activity (NativeActivity, touch layer) ──────┘
```

| Path | What lives there |
|---|---|
| `app/` | The Android app: Compose launcher, game activity, touch controls |
| `core/` | Rust JNI library around Horizon's launcher backend |
| `android-auth/` | Microsoft device-code sign-in library |
| `hnmod-core/` | Reader and planner for Horizon mod packages (`.hmod`, plus legacy `.hnmod`) |
| `android/` | The upstream pin, `android.patch` files, overlay files and sync scripts |

The engine is not forked. `android/sync.sh` clones upstream at the pinned commit, applies the small
patch and copies in the overlay files. A scheduled workflow tries newer upstream commits and moves the pin
only when the Android build still passes.

### Build it yourself

```bash
android/sync.sh          # fetch upstream Horizon and apply the Android changes
gradle assembleDebug     # needs JDK 17, the Android SDK/NDK and a Rust toolchain with the Android target
```

More detail in [ANDROID_BUILD.md](ANDROID_BUILD.md). Pushes to `main` build a debug APK in GitHub Actions.

## Mods

Horizon has its own mod system, so Java mod JARs (Fabric, NeoForge, Forge) do not load. Use `.hmod` packages,
`.hshader` packs, or shader ZIPs that contain a `shaders/` folder.

## Contributing

Issues and pull requests are welcome. Keep changes small, put Android-only code in separate files rather
than in the engine, and say how you tested it. For engine bugs, report them to
[upstream Horizon](https://github.com/horizon-rust/horizon).

## License

- Horizon: MIT, see [HORIZON-LICENSE.txt](HORIZON-LICENSE.txt)
- Touch-control components derived from Zalith Launcher 2: GPL-3.0, see
  [LICENSE-ZalithLauncher2-GPL-3.0.txt](LICENSE-ZalithLauncher2-GPL-3.0.txt)

Minecraft is a trademark of Mojang Studios. This project does not distribute Minecraft assets.
