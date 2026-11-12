#!/usr/bin/env sh
set -eu

ENGINE=${1:?usage: install-android-winit.sh <upstream-checkout>}
PIN=ae4c85dc5b31da2078ee1e1009a53ced7d7ed1ba
REPOSITORY=https://github.com/GoyDevv/Horizon-Android.git
TMP=${TMPDIR:-/tmp}/horizon-android-winit-$PIN
DEST="$ENGINE/third_party/android-winit"

if [ ! -f "$ENGINE/Cargo.toml" ]; then
  echo "Upstream checkout is missing: $ENGINE" >&2
  exit 1
fi

rm -rf "$TMP" "$DEST"
git init -q "$TMP"
git -C "$TMP" remote add origin "$REPOSITORY"
git -C "$TMP" sparse-checkout init --cone
git -C "$TMP" sparse-checkout set third_party/winit
git -C "$TMP" fetch --quiet --depth 1 origin "$PIN"
git -C "$TMP" checkout --quiet --detach FETCH_HEAD

if [ ! -f "$TMP/third_party/winit/Cargo.toml" ]; then
  echo "Pinned Android Winit source is missing" >&2
  exit 1
fi
mkdir -p "$(dirname "$DEST")"
cp -a "$TMP/third_party/winit" "$DEST"

python3 - "$ENGINE/Cargo.toml" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
exclude_anchor = '"third_party/libmimalloc-sys"]'
if "third_party/android-winit" not in text:
    if text.count(exclude_anchor) != 1:
        raise SystemExit("upstream workspace exclude anchor changed")
    text = text.replace(
        exclude_anchor,
        '"third_party/libmimalloc-sys", "third_party/android-winit"]',
        1,
    )
if "[patch.crates-io]" not in text:
    raise SystemExit("upstream [patch.crates-io] table is missing")
if "winit = { path = \"third_party/android-winit\" }" not in text:
    text = text.replace(
        "[patch.crates-io]\n",
        '[patch.crates-io]\nwinit = { path = "third_party/android-winit" }\n',
        1,
    )
path.write_text(text, encoding="utf-8")
PY

echo "Android Winit pinned to $PIN and patched only in the transient build checkout"
