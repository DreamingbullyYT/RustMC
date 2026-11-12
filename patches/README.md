# Minimal upstream patch series

The upstream engine is fetched at build time from the exact revision in
`UPSTREAM.lock`. This directory contains ordered, reviewable build-time
patches:

- `*.patch`: ordinary unified diffs, checked and applied with `git apply`.
- `*.py`: strict patchers that modify the temporary checkout using exact,
  asserted source anchors. They fail rather than silently applying to an
  unexpected upstream version.

Current seams:

- `0002-android-auth-clear.patch`: adds Android-only resettable auth state;
  desktop `OnceLock` behavior remains unchanged.
- `0003-android-port-pack-module.patch`: registers the separately stored
  `android_port.rs` adapter inside `horizon-port-pack`, where it can reuse
  crate-private pipeline helpers without copying or modifying them.

Keep Android application code in separate modules. A patch is allowed only
when the required extension point cannot be implemented outside upstream.
Every patch must apply cleanly to the pinned commit and must be tested against
the desktop path and Android target. The upstream checkout is disposable and
is never committed to this repository.
