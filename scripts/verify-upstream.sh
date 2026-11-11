#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
LOCK="$ROOT/UPSTREAM.lock"
EXPECTED=$(sed -n 's/.*"commit": *"\([0-9a-f]*\)".*/\1/p' "$LOCK")
EXPECTED_TREE=$(sed -n 's/.*"tree": *"\([0-9a-f]*\)".*/\1/p' "$LOCK")
MODULE="$ROOT/upstream/horizon"

if [ -z "$EXPECTED" ] || [ -z "$EXPECTED_TREE" ]; then
  echo "UPSTREAM.lock is missing its commit or tree SHA" >&2
  exit 1
fi

if [ ! -d "$MODULE/.git" ] && [ ! -f "$MODULE/.git" ]; then
  echo "Horizon submodule is missing. Run: git submodule update --init --recursive" >&2
  exit 1
fi

ACTUAL=$(git -C "$MODULE" rev-parse HEAD)
ACTUAL_TREE=$(git -C "$MODULE" rev-parse 'HEAD^{tree}')
if [ "$ACTUAL" != "$EXPECTED" ] || [ "$ACTUAL_TREE" != "$EXPECTED_TREE" ]; then
  echo "Wrong Horizon checkout: expected $EXPECTED ($EXPECTED_TREE), got $ACTUAL ($ACTUAL_TREE)" >&2
  exit 1
fi

echo "Horizon upstream verified: $ACTUAL"
