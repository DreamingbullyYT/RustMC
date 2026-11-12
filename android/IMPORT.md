# Original Horizon Android source import

Imported from `GoyDevv/Horizon-Android`, branch `android`, snapshot commit
`ae4c85dc5b31da2078ee1e1009a53ced7d7ed1ba` (2026-11-12).

The Android-specific application sources, launcher, renderer-facing Android
helpers, Gradle packaging, Java adapters, build tools, tests, documentation,
and license notices are preserved at their original paths in this repository.

The fork's general Horizon engine/workspace is intentionally **not** copied.
The engine is to be fetched at build time from the exact revision in
`UPSTREAM.lock`. Fork-only API dependencies are being moved behind separate
Android compatibility modules; only unavoidable, minimal upstream integration
seams may be patched.

This is a source import, not a claim that an upstream-compatible APK already
builds. Compilation and real-device verification remain required.
