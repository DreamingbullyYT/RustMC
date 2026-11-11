# rustMC: Horizon for Android

Upstream engine: [horizon-rust/horizon](https://github.com/horizon-rust/horizon) at
`687f3f6d3f2935f3198256de8f3820c5a6c94454` (Minecraft 26.3).

Android code lives in `crates/horizon-android` and `packaging/android`. Engine changes are kept
minimal: 13 patched engine files (see `git diff` against upstream) plus `third_party/winit`
(Android input patch, Apache-2.0). Status and known gaps: `PORTING_STATUS.txt`.
