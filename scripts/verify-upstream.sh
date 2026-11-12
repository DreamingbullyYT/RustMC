#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
"$ROOT/scripts/checkout-upstream.sh" "$TMP/horizon"
test -f "$TMP/horizon/Cargo.toml"
echo "Upstream pin and Android patch series verified."
