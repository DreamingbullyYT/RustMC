import json
import pathlib
import re
import subprocess
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class UpstreamPinTests(unittest.TestCase):
    def test_lock_contains_a_valid_exact_revision(self):
        lock = json.loads((ROOT / "UPSTREAM.lock").read_text(encoding="utf-8"))
        self.assertRegex(lock["commit"], r"^[0-9a-f]{40}$")
        self.assertRegex(lock["tree"], r"^[0-9a-f]{40}$")
        self.assertEqual(lock["submodule_path"], "upstream/horizon")
        self.assertEqual(
            lock["repository"], "https://github.com/horizon-rust/horizon.git"
        )

    def test_gitlink_points_to_locked_commit(self):
        lock = json.loads((ROOT / "UPSTREAM.lock").read_text(encoding="utf-8"))
        entry = subprocess.check_output(
            ["git", "ls-tree", "HEAD", "upstream/horizon"],
            cwd=ROOT,
            text=True,
        ).strip()
        self.assertIn(
            f"160000 commit {lock['commit']}\tupstream/horizon", entry
        )

    def test_submodule_tracks_official_main_branch(self):
        config = (ROOT / ".gitmodules").read_text(encoding="utf-8")
        self.assertIn("path = upstream/horizon", config)
        self.assertIn("url = https://github.com/horizon-rust/horizon.git", config)
        self.assertIn("branch = main", config)

    def test_engine_is_not_vendored_at_repository_root(self):
        self.assertFalse((ROOT / "crates").exists())
        self.assertFalse((ROOT / "Cargo.toml").exists())


if __name__ == "__main__":
    unittest.main()
