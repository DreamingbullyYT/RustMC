#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
EXPECTED=687f3f6d3f2935f3198256de8f3820c5a6c94454
MODULE="$ROOT/upstream/horizon"

if [ ! -d "$MODULE/.git" ] && [ ! -f "$MODULE/.git" ]; then
  echo "Horizon submodule is missing. Run: git submodule update --init --recursive" >&2
  exit 1
fi

ACTUAL=$(git -C "$MODULE" rev-parse HEAD)
if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "Wrong Horizon revision: expected $EXPECTED, got $ACTUAL" >&2
  exit 1
fi

LOCK_COMMIT=$(sed -n 's/.*"commit": *"\([0-9a-f]*\)".*/\1/p' "$ROOT/UPSTREAM.lock")
if [ "$LOCK_COMMIT" != "$EXPECTED" ]; then
  echo "UPSTREAM.lock does not match the pinned engine revision" >&2
  exit 1
fi

echo "Horizon upstream verified: $ACTUAL"
