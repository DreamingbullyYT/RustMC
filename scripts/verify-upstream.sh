#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
LOCK="$ROOT/UPSTREAM.lock"
MODULE="$ROOT/upstream/horizon"
EXPECTED=$(sed -n 's/.*"commit": *"\([0-9a-f]*\)".*/\1/p' "$LOCK")
EXPECTED_TREE=$(sed -n 's/.*"tree": *"\([0-9a-f]*\)".*/\1/p' "$LOCK")
REPOSITORY=$(sed -n 's/.*"repository": *"\([^"]*\)".*/\1/p' "$LOCK")
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM

if [ -z "$EXPECTED" ] || [ -z "$EXPECTED_TREE" ] || [ -z "$REPOSITORY" ]; then
  echo "UPSTREAM.lock is missing repository, commit, or tree SHA" >&2
  exit 1
fi
if [ ! -d "$MODULE" ] || [ -d "$MODULE/.git" ] || [ -f "$MODULE/.git" ]; then
  echo "Vendored Horizon source is missing or contains nested Git metadata" >&2
  exit 1
fi

git clone --filter=blob:none --no-checkout "$REPOSITORY" "$TMP/horizon"
git -C "$TMP/horizon" checkout --detach "$EXPECTED"
ACTUAL_TREE=$(git -C "$TMP/horizon" rev-parse 'HEAD^{tree}')
if [ "$ACTUAL_TREE" != "$EXPECTED_TREE" ]; then
  echo "Upstream tree mismatch: expected $EXPECTED_TREE, got $ACTUAL_TREE" >&2
  exit 1
fi

PATCH_DIR="$ROOT/patches"
if [ -d "$PATCH_DIR" ]; then
  find "$PATCH_DIR" -maxdepth 1 -type f -name '*.patch' -print | sort |
  while IFS= read -r patch; do
    git -C "$TMP/horizon" apply --check "$patch"
    git -C "$TMP/horizon" apply "$patch"
  done
fi
rm -rf "$TMP/horizon/.git"

if ! diff -qr "$TMP/horizon" "$MODULE"; then
  echo "Vendored Horizon files do not match the locked upstream revision plus Android patches" >&2
  exit 1
fi

echo "Vendored Horizon verified: $EXPECTED ($EXPECTED_TREE)"
