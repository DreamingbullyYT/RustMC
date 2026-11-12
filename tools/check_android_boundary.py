#!/usr/bin/env python3
"""Guard the rustMC source boundary: no vendored engine tree or include! injection."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
errors = []

def tracked(path: str) -> bool:
    result = subprocess.run(
        ["git", "ls-tree", "-d", "HEAD", "--", path],
        cwd=root, text=True, capture_output=True, check=False
    )
    return bool(result.stdout.strip())

for forbidden in ("horizon", "crates/horizon-client", "engine"):
    if tracked(forbidden):
        errors.append(f"unexpected vendored engine path: {forbidden}")

# Android production sources belong to rustMC-owned directories, never inside reference submodules.
for base in (root / "android-app", root / "android-platform"):
    if not base.exists():
        continue
    for path in base.rglob("*.rs"):
        source = path.read_text(encoding="utf-8", errors="replace")
        if "include!" in source:
            errors.append(f"include! macro is forbidden in Android-owned Rust source: {path.relative_to(root)}")

if errors:
    print("\n".join(errors), file=sys.stderr)
    raise SystemExit(1)

print("Source-boundary check passed: no vendored engine tree and no include! in Android-owned Rust sources.")
