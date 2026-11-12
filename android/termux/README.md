# Embedded Android HNMod build pipeline

This script is invoked by Horizon through its app-owned ARM64 runtime. The runtime uses Termux
package-build components, but the user does **not** install the Termux app, grant RUN_COMMAND,
configure shared storage, or install packages manually.

The launcher materializes the pinned script and HNMod validator sources in its private workspace,
copies selected archives into private storage, runs the build with the embedded Rust/Clang
toolchain, validates the resulting AArch64 ELF and imports it automatically through Horizon's
local-build store.

The build sequence remains:

1. Sync the pinned Horizon source and apply the Android patch.
2. Validate all HNMod archives with the shared Rust HNMod core.
3. Resolve exact engine revision, hooks API and horizon-mixins revision.
4. Apply horizon-mixins and build `libhorizon_android_game.so`.
5. Check Android shared-library dependencies and import the output.

The local build is **not sandboxed**. HNMod build scripts and Rust procedural macros execute with
Horizon app permissions. The launcher asks for explicit confirmation before starting.

The persistent Cargo target directory is kept between builds, so Cargo can reuse unchanged crate
artifacts when a later HNMod changes only part of the engine. Exact engine/mod/toolchain matches
also reuse the verified final Android game library. The validator CLI disables incremental
compilation only inside its own helper; the game build explicitly enables Cargo incremental
compilation to match Horizon's `fast` profile.

The bootstrap must be installed and pass its linker/compiler smoke tests before the build action
becomes available. Keep at least 10 GB free for the engine source, Rust caches and build output.
