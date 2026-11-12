import json
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class UpstreamPinTests(unittest.TestCase):
    def test_lock_contains_exact_upstream_revision(self):
        lock = json.loads((ROOT / "UPSTREAM.lock").read_text(encoding="utf-8"))
        self.assertEqual(lock["repository"], "https://github.com/horizon-rust/horizon.git")
        self.assertRegex(lock["commit"], r"^[0-9a-f]{40}$")
        self.assertRegex(lock["tree"], r"^[0-9a-f]{40}$")
        self.assertEqual(lock["checkout_path"], ".local/upstream/horizon")
        self.assertNotIn("submodule_path", lock)
        self.assertEqual(lock["commit"], "69ba5a7b63642555fdd3461defcb53c265967d59")

    def test_engine_is_not_a_submodule_or_committed_checkout(self):
        self.assertFalse((ROOT / ".gitmodules").exists())
        self.assertFalse((ROOT / "upstream" / "horizon").exists())

    def test_build_time_checkout_verifies_both_hashes_and_applies_patches(self):
        script = (ROOT / "scripts/checkout-upstream.sh").read_text(encoding="utf-8")
        self.assertIn('git -C "$DEST" fetch --quiet --depth 1 origin "$COMMIT"', script)
        self.assertIn("HEAD^{tree}", script)
        self.assertIn("UPSTREAM.lock", script)
        self.assertIn("name '*.py'", script)
        self.assertIn('python3 "$patcher" "$DEST"', script)
        patcher = ROOT / "patches/0001-hosted-android-application-handler.py"
        self.assertTrue(patcher.is_file())
        self.assertIn("create_application_handler", patcher.read_text(encoding="utf-8"))

    def test_original_android_source_is_present(self):
        for path in (
            "packaging/android/AndroidManifest.xml",
            "packaging/android/build.gradle",
            "crates/horizon-android/Cargo.toml",
            "crates/horizon-client/src/android_main.rs",
            "crates/horizon-client/src/android/launcher.rs",
            "crates/horizon-client/src/android/game_launch.rs",
        ):
            self.assertTrue((ROOT / path).is_file(), path)


if __name__ == "__main__":
    unittest.main()
