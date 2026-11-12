# Warning this repository is planned to be shutdown,why? I'm working on a better launcher than this one,I'll make an announcement soon about the new launcher,untill then install the last release available, thank you for the patience


Horizon Android Edition

«Horizon — on Android. A native Android edition of "Horizon" (https://github.com/horizon-rust/horizon), bringing the Rust-based Minecraft: Java Edition client to ARM64 Android devices with a mobile-first launcher, touch controls, file import/backup tools, and an Android game runtime.»

""Android" (https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&logoColor=white)" (#requirements)
""ARM64" (https://img.shields.io/badge/ABI-arm64--v8a-blue)" (#requirements)
""Rust" (https://img.shields.io/badge/core-Rust-orange?logo=rust&logoColor=white)" (#architecture)
""License" (https://img.shields.io/badge/Horizon-MIT-informational)" (HORIZON-LICENSE.txt)

Horizon Android Edition is an unofficial community Android port/edition of Horizon. It is not affiliated with Mojang, Microsoft, or Zalith Launcher 2.

«Important: This project does not include Minecraft assets or game files. You must provide game files that you are licensed to use.»

---

What is Horizon?

"Horizon" (https://github.com/horizon-rust/horizon) is a Rust rewrite of Minecraft: Java Edition 26.3. The original project focuses on reproducing vanilla behaviour while providing a modern Rust engine, launcher, native mods, optional extras, and extensive differential testing against Java Edition.

Horizon Android Edition takes that foundation and adapts it for phones and tablets.

Instead of trying to make the desktop launcher UI work unchanged on a touchscreen, this project provides an Android-native launcher shell around the shared Horizon backend and an Android game activity with mobile input support.

The goal

- 📱 Make Horizon practical to run on Android ARM64 devices.
- 🎮 Provide comfortable touchscreen controls instead of relying on a physical keyboard and mouse.
- 📦 Make Minecraft file import, backups, and restores understandable on Android.
- 🔄 Keep the Android fork maintainable as upstream Horizon evolves.
- 🦀 Keep the game/runtime based on Horizon's Rust architecture.
- 🧩 Leave room for Android-specific features without changing the upstream project's core direction.

---

Features

🚀 Android-native launcher

The launcher uses Jetpack Compose for the Android UI while communicating with the Horizon Rust core through a small JNI/JSON bridge.

Current launcher areas include:

- Home — play, instances, accounts, Minecraft setup and user-data tools.
- Mods — Horizon mod and shader-pack importing/removal.
- Servers — saved server management and Android-side joining integration points.
- Updates — launcher/build information and Android-compatible release handling.
- Settings — launcher and Android-specific configuration.

The Android launcher is deliberately separate from Horizon's desktop GPUI launcher UI. The goal is a UI that behaves naturally on phones and tablets while still using the shared Rust backend.

🎮 Touch controls

The game runtime includes a mobile touch HUD based on the licensed Zalith Launcher 2 control components.

Available controls include:

- Virtual joystick
- Keyboard/mouse-style buttons
- Relative camera/look input
- Adjustable HUD layout
- Button/joystick positioning and resizing
- Touch control editing
- Camera sensitivity and inversion
- Mouse/action configuration
- Gesture configuration
- Gyroscope look support
- Gamepad mappings and deadzone settings
- Quick settings panel
- FPS and process-memory overlays
- Keep-awake and display/refresh-rate options

The Android layer also forwards appropriate touch/menu gestures into Horizon's existing Winit input path, so menus remain usable with touch rather than treating every screen as gameplay input.

📂 Minecraft import & file browser

Android does not give applications unrestricted access to the normal filesystem, so the launcher provides Android-compatible import flows.

The Home screen can handle Minecraft setup through a unified Browse / Import flow, including folder-based import and ZIP backup restoration.

The project is designed around Android's Storage Access Framework rather than assuming direct filesystem access.

💾 Backup & restore

The Android launcher includes Minecraft user-data backup and restore support.

The intent is to preserve player-created data while avoiding unnecessary generated/cache data. This makes it possible to move a Minecraft setup between Android installations without manually digging through protected app directories.

🧩 Mods & shaders

The launcher has Android import paths for Horizon content:

- Horizon ".hmod" packages
- Horizon ".hshader" packs
- Compatible shader ZIPs containing a "shaders/" directory

Java/Fabric/NeoForge mod JARs are not treated as native Horizon mods. Horizon's own mod system is different from the Java mod-loader ecosystem.

👤 Microsoft account support

The Android project contains native Microsoft device-code authentication support through a dedicated "horizon_msa" library, using Horizon's existing Rust account implementation.

Builds need the appropriate Horizon Microsoft client configuration for this functionality to be available. Authentication storage and Android security hardening remain an area of active work, so production-grade credential persistence should not be assumed from an early build.

🔄 Safer upstream updates

One of the most important differences from a simple Android fork is the compatibility pipeline.

Upstream Horizon changes quickly. An upstream commit that builds on desktop is not automatically guaranteed to build or run on Android.

The Android repository therefore has a compatibility workflow that can:

1. Check newer upstream commits.
2. Apply the Android port patch.
3. Regenerate Android engine data.
4. Build the Android project with Gradle.
5. Accept only candidates that pass the Android build gate.
6. Keep the Android fork on the last known-good upstream base when a candidate breaks compatibility.

This prevents an upstream change from silently becoming an Android release just because it exists on "main".

---

Architecture

┌──────────────────────────────────────────────┐
│              Android Launcher UI             │
│          Jetpack Compose / Kotlin            │
├──────────────────────────────────────────────┤
│        Android services & integrations        │
│  SAF • Backup • Import • Auth • Game Activity│
├──────────────────────────────────────────────┤
│              JNI / JSON bridge                │
├──────────────────────────────────────────────┤
│               Horizon Rust core               │
│       launcher • client • engine • mods       │
├──────────────────────────────────────────────┤
│             Android graphics/input            │
│           OpenGL ES • Winit • touch           │
└──────────────────────────────────────────────┘

Main Android pieces

Area| Purpose
"app/"| Android launcher UI, screens, game activity and Android integrations
"core/"| JNI bridge between Kotlin and the Rust launcher/game environment
"android-auth/"| Native Microsoft account authentication bridge
"android/"| Android-specific patching, synchronization and build helpers
"horizon/"| Generated/pinned upstream Horizon source tree
".github/workflows/"| Android builds and upstream compatibility checks

The "horizon/" tree is synchronized from upstream and should not be treated as a permanently hand-edited fork of every upstream source file. Android-specific changes are kept in the Android patch/integration layer wherever possible.

---

Requirements

Runtime

- Android 8.0 / API 26 or newer
- ARM64 ("arm64-v8a") device
- Sufficient free storage for Horizon, Minecraft resources, worlds and imported content
- Minecraft Java Edition files that you are licensed to use

The project is primarily targeting modern ARM64 Android phones and tablets. Device performance, GPU support, RAM and Android vendor behaviour can still affect compatibility.

Current graphics path

The Android runtime selects OpenGL ES on Android.

Vulkan is not currently treated as the default Android path because the tested Adreno 740 device encountered a Horizon sky-pipeline linking failure under Vulkan while GLES successfully created the renderer and presented a frame.

---

Installation

1. Download an Android build from the project's Releases page.
2. Install the APK on an ARM64 Android device.
3. Open Horizon Android Edition.
4. Import or prepare the Minecraft files you are licensed to use.
5. Create/select an instance or player profile as required.
6. Start the game.
7. Configure the touch HUD from the in-game controls/quick settings.

APK notes

Development/preview builds may be debug-signed. A debug APK is suitable for testing but should not be treated as a production release/update channel.

The APK itself does not contain Minecraft's copyrighted game assets.

---

Building from source

The Android project uses:

- Android Gradle Plugin 8.5.2
- Kotlin 2.0.21
- Rust for the native core/game libraries
- Android NDK for ARM64 native builds

The Rust JNI core and game "cdylib" are built as part of the Android build rather than relying on archived prebuilt native libraries as build inputs.

For the detailed build environment and commands, see [ANDROID_BUILD.md](ANDROID_BUILD.md).

The Android synchronization process is documented in the "android/" tooling and keeps a pinned upstream Horizon revision rather than silently following arbitrary upstream changes.

---

Upstream Horizon

This project is based on the upstream Horizon project:

"horizon-rust/horizon" (https://github.com/horizon-rust/horizon)

Horizon is an unofficial Rust implementation/rewrite of Minecraft: Java Edition. The upstream project provides the core game, launcher, native mod system, tooling, tests and documentation.

Horizon Android Edition adds the Android platform layer around that foundation.

For upstream architecture, parity work, mods, server support and developer documentation, see the original Horizon repository and its "docs/" directory.

---

Android-specific differences

Horizon Android Edition is not simply a recompiled desktop build.

Android introduces several constraints that do not exist on desktop:

- Application-scoped storage
- Activity lifecycle and pause/resume
- Touch-first input
- Virtual mouse/keyboard controls
- Android file pickers and Storage Access Framework permissions
- Mobile display and refresh-rate behaviour
- Android audio focus
- Foreground/background process limits
- ARM64-only native packaging
- GPU/API differences between Android devices

The Android layer exists to solve those problems while keeping the actual Horizon game/runtime as close to upstream as practical.

---

Current status

This is an active Android port/preview, not a finished replacement for the desktop Horizon experience.

The current source has reached the point where the Android launcher, native Rust game runtime, touch HUD, Minecraft import/backup flows and Android-specific integration can be built and exercised on ARM64 hardware.

Real Android-device testing has reached the Horizon 26.3 title/world screens and verified the touch HUD, quick settings panel and menu touch/scroll path.

Some hardware-specific features — especially physical gamepads, gyro behaviour and exhaustive audio validation — still need broader device testing.

Known limitations

- Android-specific download handling still needs a more complete foreground-service/resumable-download implementation before large installs should be considered fully polished.
- Android Keystore-backed credential storage is still needed for hardened persistent Microsoft authentication.
- Some Zalith-style functionality is not yet reproduced, including virtual cursor modes, advanced IME controls, custom resolution handling and the game log viewer.
- Android multiplayer joining and other platform integrations continue to evolve with upstream compatibility.
- Hardware behaviour can differ substantially between Android vendors and GPUs.


---

Roadmap

Near term

- [ ] Harden Microsoft account storage with Android Keystore.
- [ ] Finish Android foreground/resumable download service.
- [ ] Expand physical gamepad and gyro testing.
- [ ] Improve touch-layout editing and import/export.
- [ ] Add Android-specific resolution and virtual cursor options.
- [ ] Improve pause/resume, audio focus and process-lifecycle handling.
- [ ] Expand ARM64 Android CI coverage.

Longer term

- [ ] Keep the Android fork continuously compatible with upstream Horizon.
- [ ] Improve performance and memory behaviour on mobile GPUs.
- [ ] Expand Android-specific launcher polish for phones and tablets.
- [ ] Improve multiplayer and server integration on Android.
- [ ] Make Android releases easier to install, update and migrate between devices.

The roadmap will change as upstream Horizon changes and as real-device testing reveals new Android-specific problems.

---

Development philosophy

Upstream first, Android where necessary

When Horizon already provides the correct game behaviour, Android should use it rather than implementing a second version.

Android-specific code should exist where Android genuinely needs a different integration: UI, lifecycle, storage, input, graphics setup, packaging and platform services.

Test before shipping

A successful desktop build is not enough to call an Android commit compatible.

The Android compatibility workflow exists specifically to catch failures in:

- Android patch application
- Generated engine data
- Rust native compilation
- Gradle configuration
- ARM64 packaging
- Android-specific integration

No Minecraft redistribution

This project does not intend to redistribute Minecraft's proprietary game files, code or assets. Users are responsible for supplying files they are legally entitled to use.

---

Credits

Horizon

The Android edition is based on the work of the Horizon project and its contributors.

- Upstream: "horizon-rust/horizon" (https://github.com/horizon-rust/horizon)
- Horizon website: "playhorizon.dev" (https://playhorizon.dev/)

Zalith Launcher 2

The Android touch-control layer incorporates GPL-3.0 components derived from Zalith Launcher 2, with the applicable license and notices included in this repository.

- License notice: ""LICENSE-ZalithLauncher2-GPL-3.0.txt"" (LICENSE-ZalithLauncher2-GPL-3.0.txt)

Fonts and other third-party components

Third-party licenses and notices are kept in the repository alongside the components they cover.

---

Licensing

Horizon Android Edition contains multiple components with their own licensing terms.

- Original Horizon work: MIT, subject to the upstream project's license and third-party notices.
- Android touch-control components derived from Zalith Launcher 2: GPL-3.0, with the applicable additional terms/notices.
- Fonts and other dependencies retain their own licenses.

See ""HORIZON-LICENSE.txt"" (HORIZON-LICENSE.txt), ""LICENSE-ZalithLauncher2-GPL-3.0.txt"" (LICENSE-ZalithLauncher2-GPL-3.0.txt), and the other license files in the repository before redistributing builds or modified source.

---

Disclaimer

Horizon Android Edition is unofficial.

It is not affiliated with, endorsed by, or sponsored by Mojang Studios or Microsoft.

Minecraft is a trademark of Mojang Synergies AB. This project does not include or redistribute Minecraft's proprietary code or assets.

---

Project links

- Android repository: https://github.com/DreamingbullyYT/horizon-android-edition
- Upstream Horizon: https://github.com/horizon-rust/horizon
- Upstream documentation: https://github.com/horizon-rust/horizon/tree/main/docs
- Discord server: https://discord.gg/EbWKRES4kT

---

<p align="center">
  <b>Horizon Android Edition</b><br>
  Rust-powered Minecraft Java on Android — with a mobile-first launcher.
</p>
