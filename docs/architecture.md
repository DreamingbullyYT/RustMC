# Architecture and change policy

## Source ownership

- `packaging/android/`: original Android Gradle project, manifest, resources and Java adapters.
- `crates/horizon-android/`: original NativeActivity `cdylib` package.
- `crates/horizon-client/src/android*/`: imported Android entry, launcher, platform and lifecycle source.
- `android/modules/`: new compatibility adapters where code can be separated cleanly.
- `patches/*.patch`: the only tracked modifications to upstream engine source.
- `.local/upstream/horizon/`: disposable build-time checkout of the exact upstream source; never committed.

## Build-time upstream

Horizon is not a submodule and is not vendored in this repository. The
`UPSTREAM.lock` file pins repository, commit and tree. `scripts/checkout-upstream.sh`
fetches that exact revision into `.local/upstream/horizon`, verifies both
hashes, and applies the ordered patch series. GitHub Actions runs the same
script before integration checks and, once enabled, APK builds.

## Dependency direction

```text
Original Android launcher / renderer
             |
Separate Android compatibility adapters
             |
Pinned upstream Horizon public APIs
             |
Pinned upstream engine source (build-time only)
```

The Android frontend may depend on upstream public crates and documented
extension points. The upstream engine must not depend on Android modules.

## Minimal-change policy

1. Preserve the original launcher renderer, pages, touch UI and file manager.
2. Reproduce a compatibility gap before changing code.
3. Implement it in the Android adapter unless that is genuinely impossible.
4. If upstream needs an extension point, add the smallest Android-neutral API
   seam and record it as a patch in `patches/`.
5. Never replace upstream source with the Android fork's general engine.
6. Never use `include!` to inject Android code into `game.rs`.
7. Keep the original single Winit event loop; the launcher hosts the game's
   `ApplicationHandler`.
8. Do not report an APK or device test as successful unless it was built and
   exercised on the target.
