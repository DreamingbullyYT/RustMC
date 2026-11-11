#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

git submodule sync -- upstream/horizon
git submodule update --init --recursive upstream/horizon
"$ROOT/scripts/verify-upstream.sh"
