# rustMC — Horizon Android integration

This repository contains the **original Horizon Android application source**
and the Android-specific integration needed to run it on the upstream PC
engine.

- Original Android project: imported from [GoyDevv/Horizon-Android](https://github.com/GoyDevv/Horizon-Android), branch `android`.
- Exact upstream engine revision: `UPSTREAM.lock`.
- Android application sources: `packaging/android/`, `crates/horizon-android/`,
  `crates/horizon-client/src/android*/`, and the Android-specific support files.
- Engine policy: do not copy or track the fork's general engine workspace here.

## Engine checkout at build time

Horizon is **not a Git submodule** and its source is not committed into this
repository. GitHub Actions (and local developers) fetch the exact commit in
`UPSTREAM.lock` into a temporary build directory:

```sh
./scripts/checkout-upstream.sh
./scripts/verify-upstream.sh
```

The checkout verifies both the commit SHA and tree SHA, then applies only the
reviewed patches in `patches/*.patch`. The upstream pin is not advanced by a
scheduled sync job; changing it requires an explicit, reviewed update.

## Architecture rules

1. Preserve the imported Android launcher, renderer, touch UI and file manager.
2. Put Android platform services and compatibility code in separate Android
   modules wherever possible.
3. Keep the upstream engine identical to the pinned PC revision by default.
4. Patch upstream only when a required integration point cannot be implemented
   outside it. Each patch must be small, justified and tested.
5. Do not inject Android code into `game.rs` with `include!`; this breaks the
   Horizon mixin tooling.
6. Keep the original single-Winit-event-loop lifecycle: the launcher hosts the
   game's `ApplicationHandler`; never start a nested event loop.
7. Keep APKs, game resources, signing keys and generated build files out of Git.

## Current status

The original Android-specific source set has been imported. The compatibility
port to the pinned upstream engine is in progress. **No upstream-compatible APK
is claimed to build or run yet.** GitHub Actions currently verifies the pinned
upstream checkout and source baseline; the APK job will be enabled after the
Android adapter and build overlay are complete.
