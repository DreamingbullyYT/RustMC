# Stand-ins for desktop-only crates

The launcher's Cargo.toml asks for the GUI (`gpui-fast`, `gpui-fast-platform`) and the system trash (`trash`).
None of them can be built for Android, and the Android core uses only the launcher's library, which has no
GUI code. `[patch.crates-io]` in `core/Cargo.toml` points those names here, so the launcher stays exactly as
upstream wrote it (no patch to `launcher/Cargo.toml` or `desktop_seams.rs`).

The versions are high on purpose: they satisfy `^0.1.x` / `^5` whichever patch release upstream asks for.
If upstream moves to a new major/minor (`0.2`, `6`), raise the number here.
