#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
MODULE="$ROOT/upstream/horizon"
LOCK="$ROOT/UPSTREAM.lock"
UPSTREAM_BRANCH="main"

if [ ! -d "$MODULE/.git" ] && [ ! -f "$MODULE/.git" ]; then
  echo "Horizon submodule is missing; initializing it." >&2
  git -C "$ROOT" submodule update --init --recursive upstream/horizon
fi

# Actions may check submodules out shallowly. Fetch full history before checking
# ancestry so that a normal upstream advance is not mistaken for a rewrite.
if [ "$(git -C "$MODULE" rev-parse --is-shallow-repository)" = "true" ]; then
  git -C "$MODULE" fetch --no-tags --unshallow origin "$UPSTREAM_BRANCH"
else
  git -C "$MODULE" fetch --no-tags origin "$UPSTREAM_BRANCH"
fi

TARGET=$(git -C "$MODULE" rev-parse FETCH_HEAD)
CURRENT=$(git -C "$MODULE" rev-parse HEAD)

if [ "$CURRENT" = "$TARGET" ]; then
  echo "Upstream is already current: $CURRENT"
else
  if ! git -C "$MODULE" merge-base --is-ancestor "$CURRENT" "$TARGET"; then
    echo "Upstream history is not a fast-forward; refusing automatic rewrite." >&2
    exit 1
  fi
  git -C "$MODULE" checkout --detach "$TARGET"
  echo "Updated Horizon: $CURRENT -> $TARGET"
fi

TREE=$(git -C "$MODULE" rev-parse 'HEAD^{tree}')
export TARGET TREE LOCK
python3 - <<'PY'
import json
import os

path = os.environ["LOCK"]
with open(path, encoding="utf-8") as stream:
    lock = json.load(stream)

lock["commit"] = os.environ["TARGET"]
lock["tree"] = os.environ["TREE"]

with open(path, "w", encoding="utf-8") as stream:
    json.dump(lock, stream, indent=2)
    stream.write("\n")
PY

# Apply the Android patch series only after the exact upstream revision is selected.
# A failed patch is fatal: never silently skip or rewrite an incompatible patch.
PATCH_DIR="$ROOT/patches"
if [ -d "$PATCH_DIR" ]; then
  find "$PATCH_DIR" -maxdepth 1 -type f -name '*.patch' -print | sort |
  while IFS= read -r patch; do
    echo "Checking Android patch: $(basename "$patch")"
    git -C "$MODULE" apply --check "$patch"
    git -C "$MODULE" apply "$patch"
  done
fi

echo "Upstream lock updated to $TARGET ($TREE)"
