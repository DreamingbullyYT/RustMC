#!/usr/bin/env bash
# Install a registry-pinned Horizon .hpack as an isolated profile.
# Usage: install-hpack.sh PACK.hpack INDEX.json PROFILES_DIRECTORY [--allow-unsigned]
set -euo pipefail

REPO=$(cd "$(dirname "$0")/../.." && pwd)
PACK="${1:?Hpack file required}"
INDEX="${2:?Horizon registry index JSON required}"
PROFILES="${3:?Profiles directory required}"
shift 3
ALLOW_UNSIGNED=()
while [ $# -gt 0 ]; do
  case "$1" in
    --allow-unsigned) ALLOW_UNSIGNED+=(--allow-unsigned); shift;;
    -h|--help) sed -n '1,4p' "$0"; exit 0;;
    *) echo "unknown option: $1" >&2; exit 2;;
  esac
done

for tool in cargo python3 minisign; do
  command -v "$tool" >/dev/null || { echo "missing required tool: $tool" >&2; exit 1; }
done
[ -f "$PACK" ] || { echo "hpack file not found: $PACK" >&2; exit 1; }
[ -f "$INDEX" ] || { echo "registry index not found: $INDEX" >&2; exit 1; }

export CARGO_INCREMENTAL=0
cargo build --locked --manifest-path "$REPO/hnmod-core/Cargo.toml" --bin hnmod
CLI="$REPO/hnmod-core/target/debug/hnmod"
[ -x "$CLI" ] || { echo "HNMod resolver CLI was not built" >&2; exit 1; }
python3 "$REPO/android/termux/install-hpack.py" \
  --pack "$PACK" --index "$INDEX" --profiles-root "$PROFILES" --hnmod-cli "$CLI" "${ALLOW_UNSIGNED[@]}"
