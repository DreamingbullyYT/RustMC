#!/usr/bin/env bash
# The other way round from sync.sh: after editing inside ./horizon (made by sync.sh), writes the changes back
#   android/android.patch   edits to files upstream has (keep these few and small)
#   android/overlay/        files upstream does not have (the Android crates, game/android.rs, tools)
# Usage: android/save-patch.sh        (compares with the commit in android/UPSTREAM_PIN)
set -euo pipefail
cd "$(dirname "$0")/.."
[ -d horizon ] || { echo "no ./horizon: run android/sync.sh first" >&2; exit 1; }
pin=$(tr -d '[:space:]' < android/UPSTREAM_PIN)
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
git clone -q --filter=blob:none https://github.com/horizon-rust/horizon.git "$work/up"
git -C "$work/up" checkout -q "$pin"
git -C "$work/up" config core.fileMode false
# horizon/ over the clean checkout, without build output and the lock file cargo rewrites
(cd horizon && tar --exclude=./.git --exclude=./.local --exclude=./target --exclude=./Cargo.lock -cf - .) | tar -C "$work/up" -xf -
git -C "$work/up" diff > android/android.patch
rm -rf android/overlay && mkdir -p android/overlay
git -C "$work/up" ls-files --others --exclude-standard | while IFS= read -r f; do
  mkdir -p "android/overlay/$(dirname "$f")"
  cp "$work/up/$f" "android/overlay/$f"
done
echo "android.patch: $(git -C "$work/up" diff --shortstat)"
echo "overlay: $(find android/overlay -type f | wc -l) files"
