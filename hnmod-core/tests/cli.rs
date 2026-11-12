use serde_json::{Value, json};
use std::{
    fs::{self, File},
    io::Write,
    process::Command,
};
use zip::{ZipWriter, write::SimpleFileOptions};

fn fixture(path: &std::path::Path) -> (String, String) {
    let horizon = "a".repeat(40);
    let mixins = "b".repeat(40);
    let manifest = json!({
        "schema": 1,
        "id": "demo_mod",
        "version": "1.0.0",
        "game": "26.3",
        "horizon": horizon,
        "mixins": mixins,
        "hooks": 3
    });
    let mixin = format!(
        "[mod]\nid = \"demo_mod\"\nversion = \"1.0.0\"\n[compatibility]\nhorizon_revision = \"{}\"\n",
        "a".repeat(40)
    );
    let mut zip = ZipWriter::new(File::create(path).unwrap());
    let options = SimpleFileOptions::default();
    zip.start_file("horizon-native.json", options).unwrap();
    zip.write_all(&serde_json::to_vec(&manifest).unwrap()).unwrap();
    zip.start_file("mixins/demo_mod/horizon-mixin.toml", options).unwrap();
    zip.write_all(mixin.as_bytes()).unwrap();
    zip.start_file("mixins/demo_mod/hook.rs", options).unwrap();
    zip.write_all(b"pub fn hook() {}").unwrap();
    zip.finish().unwrap();
    (horizon, mixins)
}

fn run(args: &[&str]) -> std::process::Output {
    Command::new(env!("CARGO_BIN_EXE_hnmod"))
        .args(args)
        .output()
        .unwrap()
}

#[test]
fn cli_inspects_resolves_and_extracts_only_validated_sources() {
    let root = std::env::temp_dir().join(format!("hnmod-cli-test-{}", std::process::id()));
    let _ = fs::remove_dir_all(&root);
    fs::create_dir_all(&root).unwrap();
    let archive = root.join("demo.hnmod");
    let (_horizon, mixins) = fixture(&archive);
    let archive_arg = archive.to_str().unwrap();

    let inspected = run(&["inspect", archive_arg]);
    assert!(inspected.status.success(), "{}", String::from_utf8_lossy(&inspected.stderr));
    let info: Value = serde_json::from_slice(&inspected.stdout).unwrap();
    assert_eq!(info["manifest"]["id"], "demo_mod");
    assert_eq!(info["manifest"]["hooks"], 3);
    assert_eq!(info["sha256"].as_str().unwrap().len(), 64);

    let resolved = run(&[
        "resolve",
        &"c".repeat(40),
        "4",
        &mixins,
        archive_arg,
    ]);
    assert!(resolved.status.success(), "{}", String::from_utf8_lossy(&resolved.stderr));
    let plan: Value = serde_json::from_slice(&resolved.stdout).unwrap();
    assert_eq!(plan["revision"], "c".repeat(40));
    assert_eq!(plan["mods"][0], "demo_mod");
    assert_eq!(plan["warnings"].as_array().unwrap().len(), 1);

    let destination = root.join("extracted");
    let destination_arg = destination.to_str().unwrap();
    let extracted = run(&["extract", archive_arg, destination_arg]);
    assert!(extracted.status.success(), "{}", String::from_utf8_lossy(&extracted.stderr));
    assert_eq!(
        fs::read_to_string(destination.join("horizon-mixin.toml")).unwrap(),
        format!(
            "[mod]\nid = \"demo_mod\"\nversion = \"1.0.0\"\n[compatibility]\nhorizon_revision = \"{}\"\n",
            "a".repeat(40)
        )
    );
    assert_eq!(
        fs::read_to_string(destination.join("hook.rs")).unwrap(),
        "pub fn hook() {}"
    );
    assert!(!destination.join("horizon-native.json").exists());
    fs::remove_dir_all(root).unwrap();
}
