#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
MODULE="$ROOT/upstream/horizon"
LOCK="$ROOT/UPSTREAM.lock"
REPOSITORY="https://github.com/horizon-rust/horizon.git"
UPSTREAM_BRANCH="main"
TMP=$(mktemp -d)
STAGE="$ROOT/upstream/.horizon-vendor.$$"
BACKUP="$ROOT/upstream/.horizon-old.$$"
cleanup() {
  rm -rf "$TMP" "$STAGE"
}
trap cleanup EXIT HUP INT TERM

mkdir -p "$ROOT/upstream"
git clone --depth 1 --branch "$UPSTREAM_BRANCH" "$REPOSITORY" "$TMP/horizon"
TARGET=$(git -C "$TMP/horizon" rev-parse HEAD)
TREE=$(git -C "$TMP/horizon" rev-parse 'HEAD^{tree}')

# Apply the Android patch series against the exact upstream revision.
PATCH_DIR="$ROOT/patches"
if [ -d "$PATCH_DIR" ]; then
  find "$PATCH_DIR" -maxdepth 1 -type f -name '*.patch' -print | sort |
  while IFS= read -r patch; do
    echo "Checking Android patch: $(basename "$patch")"
    git -C "$TMP/horizon" apply --check "$patch"
    git -C "$TMP/horizon" apply "$patch"
  done
fi

# The vendor directory contains source files only, never a nested .git repository.
rm -rf "$TMP/horizon/.git"
rm -rf "$STAGE" "$BACKUP"
mv "$TMP/horizon" "$STAGE"
if [ -d "$MODULE" ]; then mv "$MODULE" "$BACKUP"; fi
mv "$STAGE" "$MODULE"
rm -rf "$BACKUP"

export TARGET TREE LOCK
python3 - <<'PY'
import json
import os

path = os.environ["LOCK"]
with open(path, encoding="utf-8") as stream:
    lock = json.load(stream)

lock["commit"] = os.environ["TARGET"]
lock["tree"] = os.environ["TREE"]
lock.pop("submodule_path", None)
lock["vendor_path"] = "upstream/horizon"

with open(path, "w", encoding="utf-8") as stream:
    json.dump(lock, stream, indent=2)
    stream.write("\n")
PY

echo "Vendored Horizon updated to $TARGET ($TREE)"
