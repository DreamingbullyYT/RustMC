#!/usr/bin/env bash
# Packs this folder into plugin_loader.hnmod (horizon-native.json + mixins/plugin_loader/...).
# Usage: pack.sh <horizon-mixins commit, 40 hex> [out.hnmod]
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
mix=${1:?horizon-mixins commit required}
out=${2:-$here/plugin_loader.hnmod}
[[ $mix =~ ^[0-9a-f]{40}$ ]] || { echo "invalid horizon-mixins commit" >&2; exit 1; }
rev=$(sed -n 's/^horizon_revision = "\(.*\)"/\1/p' "$here/horizon-mixin.toml")
ver=$(sed -n 's/^version = "\(.*\)"/\1/p' "$here/horizon-mixin.toml" | head -1)
python3 - "$here" "$out" "$rev" "$ver" "$mix" <<'PY'
import json, os, sys, zipfile
here, out, rev, ver, mix = sys.argv[1:]
manifest = {"schema": 1, "id": "plugin_loader", "version": ver, "game": "26.3", "horizon": rev, "mixins": mix}
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("horizon-native.json", json.dumps(manifest, indent=2))
    z.write(os.path.join(here, "horizon-mixin.toml"), "mixins/plugin_loader/horizon-mixin.toml")
    for f in ("Cargo.toml", "src/lib.rs", "src/forward.rs"):
        z.write(os.path.join(here, "api", f), "mixins/plugin_loader/api/" + f)
print(out)
PY
