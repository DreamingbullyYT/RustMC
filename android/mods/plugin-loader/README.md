# plugin_loader

A native trait mod (`[api]` package). It loads `horizon-plugins/*.so` (or `$HORIZON_PLUGIN_DIR`) at start and forwards
every `Hooks` (448) and `RenderHooks` (3, incl. `wgpu` drawing) call to them. The engine stays vanilla. The loader is
compiled once with the game; plugins are compiled separately, with no engine rebuild.

Forwarding rules are upstream's: first `Cancel` stops, first `Some` wins, other hooks reach every plugin (file-name order).

## Plugin
`plugin-template/`: a cdylib exporting `horizon_plugin_abi()` (must equal the loader's `ABI`, the Horizon commit) and
`horizon_plugin_hooks() -> Box<dyn Hooks>`. Build it with the same rustc and Horizon commit as the game.
A wrong or broken plugin is skipped with a log warning.

## Build the loader
`./pack.sh <horizon-mixins commit> [out.hnmod]`, then compile it like any `.hnmod`.

## Regenerate `api/src/forward.rs`
When the Horizon pin changes the trait changes. Run Horizon's `horizon-mod-api-gen` (`generate(root, out, Target::Client|Render)`)
and: `tools/gen-forward.py mod_api_generated.rs native_render_generated.rs api/src/forward.rs`; update the commit in
`horizon-mixin.toml`, `api/Cargo.toml`, `ABI` in `api/src/lib.rs` and the plugin templates.

## Tested (host, Horizon 41f822b)
`cargo check` against the real engine; `test/loader_test.rs` loads a real cdylib (`test/plugin`) and dispatches
`living_heal` through it (doubling, Cancel, a broken `.so` skipped).

## Limits
- Rust ABI: same rustc + same commit only. Each plugin links its own copy of `horizon-client`, so statics inside engine
  helpers a plugin calls directly are separate from the game's; use `Host` / event arguments for engine access.
- A plugin stays loaded until exit. Not tested on Android yet.
