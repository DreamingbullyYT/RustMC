#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ENGINE=${1:-"$ROOT/.local/upstream/horizon"}

if [ ! -f "$ENGINE/Cargo.toml" ]; then
  echo "Pinned upstream checkout is missing: $ENGINE" >&2
  exit 1
fi

copy_file() {
  source="$ROOT/$1"
  target="$ENGINE/$2"
  mkdir -p "$(dirname "$target")"
  cp "$source" "$target"
}

# The original Android cdylib and NativeActivity sources.
copy_file crates/horizon-android/Cargo.toml crates/horizon-android/Cargo.toml
copy_file crates/horizon-android/build.rs crates/horizon-android/build.rs
copy_file crates/horizon-client/src/android_lib.rs crates/horizon-client/src/android_lib.rs
copy_file crates/horizon-client/src/android_main.rs crates/horizon-client/src/android_main.rs
copy_file crates/horizon-client/src/android.rs crates/horizon-client/src/android.rs
copy_file crates/horizon-client/src/android_resources.rs crates/horizon-client/src/android_resources.rs
mkdir -p "$ENGINE/crates/horizon-client/src"
cp -a "$ROOT/crates/horizon-client/src/android" "$ENGINE/crates/horizon-client/src/"

# The adapter facade and the fork-derived Android services are kept outside engine crates.
mkdir -p "$ENGINE/android"
cp -a "$ROOT/android/adapters" "$ENGINE/android/"

# Original launcher support modules are included by android_main.rs via path attributes.
mkdir -p "$ENGINE/launcher/src"
for file in account.rs browser.rs proxy.rs skins.rs; do
  cp "$ROOT/launcher/src/$file" "$ENGINE/launcher/src/$file"
done

# Original Gradle app and its Android build tooling.
mkdir -p "$ENGINE/packaging"
cp -a "$ROOT/packaging/android" "$ENGINE/packaging/"
mkdir -p "$ENGINE/tools"
find "$ROOT/tools" -maxdepth 1 -type f \( -name 'android*.py' -o -name 'check_android_release.py' \) -exec cp {} "$ENGINE/tools/" \;

# Make the Android cdylib a transient workspace member; the upstream checkout itself stays clean.
python3 - "$ENGINE/Cargo.toml" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
anchor = '    "crates/horizon-client",\n'
if text.count(anchor) != 1:
    raise SystemExit("upstream workspace member anchor changed")
if '"crates/horizon-android"' not in text:
    text = text.replace(anchor, anchor + '    "crates/horizon-android",\n', 1)
path.write_text(text, encoding="utf-8")
PY

echo "Original Android project overlaid onto pinned upstream at $ENGINE"
