# How horizon/ is made

`horizon/` is NOT stored in this repo. CI builds it with `android/sync.sh`:

1. clone the original https://github.com/horizon-rust/horizon at the commit in `android/UPSTREAM_PIN`
2. apply `android/android.patch`: the few edits to upstream's own files (about 80 lines in 12 files)
3. copy `android/overlay/` over it: everything upstream does not have, as plain files you can read and diff
   (the Android game crate, the installer, `game/android.rs` with the touch and input code, build tools)

Where the Android code lives, from most to least merge-safe:
- `overlay/` files: upstream can never conflict with them.
- `crates/horizon-client/src/game/android.rs`: pulled into `game.rs` with one `include!`; `game.rs` only has
  one-line calls into it (`android_intercept`, `android_publish_stats`, `android_surface_*`, ...).
- `android.patch`: only what cannot be done from outside (a field, an import, a `cfg`).
- `core/` is ours and imports the launcher as a library. The launcher itself is not patched except one `cfg`
  line; its desktop-only crates (GUI, trash) are replaced by empty stand-ins in `core/stubs/`.

To change Horizon code: `bash android/sync.sh`, edit inside `horizon/`, then `bash android/save-patch.sh` writes the
patch and the overlay back. A new Android file goes into the overlay on its own; before editing an upstream file,
check whether a helper in `game/android.rs` can do it instead.

`.github/workflows/upstream-compatible.yml` builds the newest upstream Nightlies every 6 hours and moves the
pin to the first one that builds. How the app follows them: see `UPDATES.md`.
