#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
LOCK="$ROOT/UPSTREAM.lock"
DEST=${1:-"$ROOT/.local/upstream/horizon"}
eval "$(python3 - "$LOCK" <<'PY'
import json, shlex, sys
with open(sys.argv[1], encoding="utf-8") as f: lock = json.load(f)
for key in ("repository", "commit", "tree"):
    value = lock.get(key)
    if not isinstance(value, str) or not value: raise SystemExit(f"UPSTREAM.lock: missing {key}")
    print(f"{key.upper()}={shlex.quote(value)}")
PY
)"
mkdir -p "$(dirname "$DEST")"
rm -rf "$DEST"
git init -q "$DEST"
git -C "$DEST" remote add origin "$REPOSITORY"
git -C "$DEST" fetch --quiet --depth 1 origin "$COMMIT"
git -C "$DEST" checkout --quiet --detach FETCH_HEAD
ACTUAL_COMMIT=$(git -C "$DEST" rev-parse HEAD)
ACTUAL_TREE=$(git -C "$DEST" rev-parse 'HEAD^{tree}')
if [ "$ACTUAL_COMMIT" != "$COMMIT" ] || [ "$ACTUAL_TREE" != "$TREE" ]; then
  echo "Pinned upstream mismatch: expected $COMMIT ($TREE), got $ACTUAL_COMMIT ($ACTUAL_TREE)" >&2
  exit 1
fi
PATCH_DIR="$ROOT/patches"
if [ -d "$PATCH_DIR" ]; then
  find "$PATCH_DIR" -maxdepth 1 -type f -name '*.patch' -print | sort |
  while IFS= read -r patch; do
    echo "Applying Android integration patch: $(basename "$patch")"
    git -C "$DEST" apply --check "$patch"
    git -C "$DEST" apply "$patch"
  done
fi
if [ -d "$PATCH_DIR" ]; then
  find "$PATCH_DIR" -maxdepth 1 -type f -name '*.py' -print | sort |
  while IFS= read -r patcher; do
    echo "Applying Android integration patcher: $(basename "$patcher")"
    python3 "$patcher" "$DEST"
  done
fi

echo "Pinned upstream ready at $DEST ($COMMIT)"
