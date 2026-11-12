//! Loads a real cdylib plugin through the loader and dispatches an engine hook to it.
//! Run: HORIZON_TEST_PLUGIN=<path to libtest_plugin.so> cargo test -p mod-plugin_loader
use horizon_client::mod_api::{Hooks, dispatch_living_heal};
use mod_plugin_loader::load_dir;

#[test]
fn plugin_hook_runs_through_loader() {
    let so = std::env::var("HORIZON_TEST_PLUGIN").expect("HORIZON_TEST_PLUGIN");
    let dir = std::env::temp_dir().join(format!("hz-plugins-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::copy(&so, dir.join("a.so")).unwrap();
    std::fs::write(dir.join("broken.so"), b"not an elf").unwrap(); // skipped with a warning
    let mods: Vec<Box<dyn Hooks>> = vec![Box::new(load_dir(&dir))];
    let (mut amount, mut cancel) = (3.0_f32, false);
    dispatch_living_heal(&mods, 1, &mut amount, &mut cancel);
    assert_eq!((amount, cancel), (6.0, false));
    let (mut amount, mut cancel) = (80.0_f32, false);
    dispatch_living_heal(&mods, 1, &mut amount, &mut cancel);
    assert!(cancel, "plugin's Flow::Cancel must stop the hook");
    assert!(mods[0].render().is_some());
}

#[test]
fn empty_dir_is_a_noop() {
    let mods: Vec<Box<dyn Hooks>> = vec![Box::new(load_dir(std::path::Path::new("/nonexistent")))];
    let (mut amount, mut cancel) = (3.0_f32, false);
    dispatch_living_heal(&mods, 1, &mut amount, &mut cancel);
    assert_eq!((amount, cancel), (3.0, false));
}
