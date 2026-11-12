#!/usr/bin/env bash
# Bundle the same official Termux bootstrap + PRoot runtime layout used by Gemini-cli-for-Android.
# Preserve SONAME symlink names (e.g. libtalloc.so.2) by copying each link target to its link name.
set -euo pipefail

ASSETS="app/src/main/assets"
PROOT_ASSETS="$ASSETS/proot"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$PROOT_ASSETS/lib"

BOOTSTRAP_URL="https://github.com/termux/termux-packages/releases/download/bootstrap-2026.11.01-r1%2Bapt.android-7/bootstrap-aarch64.zip"
# Use the GitHub asset API first; it bypasses the release-page redirect path that can return
# transient 502/504 responses on CI runners. Keep the browser URL as a fallback.
BOOTSTRAP_ASSET_API="https://api.github.com/repos/termux/termux-packages/releases/assets/696854476"
BOOTSTRAP_SHA256="5a1429b6c68ee3bdc17b5fd6e47ba42933ec924cd7283fe74992574e1d5badae"

echo "Fetching pinned official Termux ARM64 bootstrap"
BOOTSTRAP_TMP="$WORK/bootstrap-aarch64.zip"
downloaded=false
for url in "$BOOTSTRAP_ASSET_API" "$BOOTSTRAP_URL"; do
  rm -f "$BOOTSTRAP_TMP"
  echo "Trying official Termux asset endpoint: $url"
  if curl --connect-timeout 20 --max-time 300 --retry 3 --retry-all-errors \
      --retry-delay 2 -H "Accept: application/octet-stream" -fsSL "$url" -o "$BOOTSTRAP_TMP" &&
      echo "$BOOTSTRAP_SHA256  $BOOTSTRAP_TMP" | sha256sum -c -; then
    downloaded=true
    break
  fi
  echo "Endpoint failed or returned an unexpected checksum; trying the next official URL." >&2
done
if [ "$downloaded" != true ]; then
  echo "::error::Could not fetch the pinned official Termux bootstrap from either verified GitHub endpoint." >&2
  exit 1
fi
install -m 0644 "$BOOTSTRAP_TMP" "$ASSETS/bootstrap-aarch64.zip"

REPO="https://packages-cf.termux.dev/apt/termux-main"
curl --retry 5 --retry-all-errors -fsSL "$REPO/dists/stable/main/binary-aarch64/Packages" -o "$WORK/Packages"

python3 - "$WORK/Packages" "$WORK/packages.tsv" <<'PY'
import sys
from pathlib import Path
wanted = {"proot", "libtalloc", "libandroid-shmem"}
found = {}
for record in Path(sys.argv[1]).read_text().split("\n\n"):
    fields = {}
    for line in record.splitlines():
        if ": " in line and not line.startswith(" "):
            key, value = line.split(": ", 1)
            fields[key] = value
    name = fields.get("Package")
    if name in wanted:
        found[name] = fields.get("Filename", "")
missing = wanted - found.keys()
if missing:
    raise SystemExit("Missing official Termux packages: " + ", ".join(sorted(missing)))
Path(sys.argv[2]).write_text("".join(f"{name}\t{found[name]}\n" for name in sorted(wanted)))
PY

while IFS=$'\t' read -r package path; do
  test -n "$path"
  curl --retry 5 --retry-all-errors -fsSL "$REPO/$path" -o "$WORK/$package.deb"
  mkdir -p "$WORK/$package"
  dpkg-deb -x "$WORK/$package.deb" "$WORK/$package"
done < "$WORK/packages.tsv"

PROOT_BIN="$(find "$WORK/proot" -type f -path '*/usr/bin/proot' -print -quit)"
PROOT_LOADER="$(find "$WORK/proot" -type f -path '*/usr/libexec/proot/loader' -print -quit)"
test -s "$PROOT_BIN"
test -s "$PROOT_LOADER"
install -m 0755 "$PROOT_BIN" "$PROOT_ASSETS/proot"
install -m 0755 "$PROOT_LOADER" "$PROOT_ASSETS/loader"

for package in libtalloc libandroid-shmem; do
  find "$WORK/$package" -path '*/usr/lib/*' -name '*.so*' -print |
    while IFS= read -r library; do
      cp -L "$library" "$PROOT_ASSETS/lib/$(basename "$library")"
    done
done

test -s "$PROOT_ASSETS/lib/libtalloc.so.2"
test -s "$PROOT_ASSETS/lib/libandroid-shmem.so"
readelf -d "$PROOT_ASSETS/proot" | grep NEEDED || true
echo "Prepared Gemini-style embedded Termux runtime:"
ls -lh "$ASSETS/bootstrap-aarch64.zip" "$PROOT_ASSETS" "$PROOT_ASSETS/lib"
