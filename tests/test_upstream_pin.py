import json
import pathlib
import subprocess
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
EXPECTED = "687f3f6d3f2935f3198256de8f3820c5a6c94454"


class UpstreamPinTests(unittest.TestCase):
    def test_lock_uses_requested_commit_and_tree(self):
        lock = json.loads((ROOT / "UPSTREAM.lock").read_text(encoding="utf-8"))
        self.assertEqual(lock["commit"], EXPECTED)
        self.assertEqual(lock["tree"], "88661a7d632375deae594833fe5bff860a575e41")
        self.assertEqual(lock["submodule_path"], "upstream/horizon")

    def test_gitlink_points_to_locked_commit(self):
        entry = subprocess.check_output(
            ["git", "ls-tree", "HEAD", "upstream/horizon"],
            cwd=ROOT,
            text=True,
        ).strip()
        self.assertIn(f"160000 commit {EXPECTED}\tupstream/horizon", entry)

    def test_submodule_url_is_official_upstream(self):
        config = (ROOT / ".gitmodules").read_text(encoding="utf-8")
        self.assertIn("path = upstream/horizon", config)
        self.assertIn("https://github.com/horizon-rust/horizon.git", config)

    def test_engine_is_not_vendored_at_repository_root(self):
        self.assertFalse((ROOT / "crates").exists())
        self.assertFalse((ROOT / "Cargo.toml").exists())


if __name__ == "__main__":
    unittest.main()
