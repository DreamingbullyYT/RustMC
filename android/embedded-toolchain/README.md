# Horizon embedded HNMod runtime

Horizon follows the working embedded Termux/PRoot arrangement from
[Gemini-cli-for-Android](https://github.com/sniffy-furry/Gemini-cli-for-Android), adapted for
background HNMod compilation. It does not install the separate Termux application, request
Termux's RUN_COMMAND permission, or create a Horizon package repository.

## Runtime layout

1. CI bundles the pinned official Termux ARM64 bootstrap in the APK assets and verifies its SHA-256.
2. On first HNMod compilation, Horizon extracts it into its private fake
   `/data/data/com.termux` tree and recreates the bootstrap symlinks.
3. CI also bundles the official Termux PRoot binary, loader, and dependency libraries. The
   dependency SONAME names (including `libtalloc.so.2`) are preserved when copied.
4. Horizon places those PRoot libraries in the fake Termux `$PREFIX/lib`, sets
   `LD_LIBRARY_PATH` for the Android host linker, and starts PRoot with the same Termux identity
   variables and guest command arrangement used by Gemini-cli-for-Android.
5. The guest root is `/`; the fake Termux data directory is bind-mounted at
   `/data/data/com.termux`, and Horizon's private build workspace is exposed at `/horizon-host`.
6. The launcher configures the official Termux main repository and installs Rust/Cargo, Clang,
   Git, rsync, binutils, Python, CMake, Make, pkg-config and coreutils.
7. Shell, Rust and native compiler smoke tests must pass before the runtime is marked ready.
   The existing Horizon HNMod validation/build/import pipeline then runs in this environment.

## Android target compatibility

The embedded PRoot execution arrangement follows Gemini-cli-for-Android's `targetSdk = 28`
setting. Android 10+ restricts direct execution of app-private files for apps targeting API 29
or newer; the lower target is therefore deliberate for this runtime approach. Horizon still
compiles against SDK 34 and retains its own minimum supported Android version. This is a
compatibility trade-off for sideloaded builds and should be revisited before any Play Store
distribution.

## Pinned bootstrap

- Release: `bootstrap-2026.11.01-r1+apt.android-7`
- ABI: `aarch64`
- SHA-256: `5a1429b6c68ee3bdc17b5fd6e47ba42933ec924cd7283fe74992574e1d5badae`
- Package source: `https://packages-cf.termux.dev/apt/termux-main`

The package index is refreshed on first setup and packages are installed from the official Termux
repository. No additional Horizon package repository is created.

## Security

HNMod Rust build scripts and procedural macros execute code with Horizon's app permissions.
Compilation is not a sandbox. The existing explicit trust confirmation remains required.
The bootstrap and each package retain their upstream licenses; preserve upstream Termux GPL-3.0
and package-specific notices when redistributing binaries.
