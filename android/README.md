# Android port

The Android work is split from the pinned Horizon engine.

- `PORTING-AUDIT.md` records source provenance and the porting rules.
- `app/` contains the first isolated Android Gradle/NativeActivity packaging shell.
- The Rust Android runtime/JNI bridge is not ported yet; see `app/README.md`.

The engine baseline remains `horizon-rust/horizon@69ba5a7b63642555fdd3461defcb53c265967d59`.
Do not change the `upstream/horizon` gitlink to develop Android features.
