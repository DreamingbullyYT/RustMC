#!/usr/bin/env bash
# Verify a downloaded Nov 12 fork SDK release before any archive is extracted.
# Usage: source this file; horizon_sdk_verify_release SDK_DIRECTORY FULL_GAME_COMMIT
set -euo pipefail

# Public Ed25519 release key from arismoko/horizon. Override only in isolated tests.
HORIZON_FORK_RELEASE_KEY_B64=${HORIZON_FORK_RELEASE_KEY_B64:-Iql+MN7pjxis0Xm+fRhjoW0aIwO1tV/gQLonX1tK35o=}

horizon_sdk_verify_release() {
  local dir=${1:?SDK directory required}
  local build=${2:?full game commit required}
  local tag="sdk-$build"
  local sums="$dir/SHA256SUMS"
  local signatures="$dir/SHA256SUMS.sig"
  local temp raw der pem message signature signed=0
  [[ "$build" =~ ^[0-9a-f]{40}$ ]] || { echo "SDK build must be a full lowercase 40-character commit" >&2; return 2; }
  [[ -d "$dir" && -s "$sums" && -s "$signatures" ]] || { echo "SDK release is missing SHA256SUMS or SHA256SUMS.sig" >&2; return 1; }
  for tool in openssl base64 sha256sum python3; do
    command -v "$tool" >/dev/null || { echo "Missing SDK verification tool: $tool" >&2; return 1; }
  done

  # SDK release assets are flat files. Refuse unsafe names and duplicates before sha256sum -c.
  python3 - "$sums" "$dir" <<'PY'
import pathlib, re, sys
listing, root = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
seen = set()
for line in listing.read_text().splitlines():
    match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9._-]+)", line)
    if not match:
        raise SystemExit(f"Invalid SDK checksum row: {line!r}")
    name = match.group(2)
    if name in seen:
        raise SystemExit(f"Duplicate SDK checksum entry: {name}")
    seen.add(name)
    path = root / name
    if not path.is_file() or path.stat().st_size == 0:
        raise SystemExit(f"SDK asset is missing or empty: {name}")
if "sdk.json" not in seen:
    raise SystemExit("Signed SDK checksums do not include sdk.json")
PY

  temp=$(mktemp -d "${TMPDIR:-/tmp}/horizon-sdk-verify.XXXXXX")
  trap 'rm -rf "$temp"' RETURN
  raw="$temp/key.raw"; der="$temp/key.der"; pem="$temp/key.pem"; message="$temp/message"; signature="$temp/signature"
  printf '%s' "$HORIZON_FORK_RELEASE_KEY_B64" | base64 -d > "$raw"
  [[ $(wc -c < "$raw" | tr -d ' ') == 32 ]] || { echo "Invalid built-in Ed25519 release key" >&2; return 1; }
  # Ed25519 SubjectPublicKeyInfo DER prefix followed by the raw 32-byte public key.
  { printf '\060\\052\060\\005\\006\\003\\053\\145\\160\\003\\041\\000'; cat "$raw"; } > "$der"
  {
    printf '%s\n' '-----BEGIN PUBLIC KEY-----'
    base64 < "$der"
    printf '%s\n' '-----END PUBLIC KEY-----'
  } > "$pem"

  { printf 'horizon release %s\n' "$tag"; cat "$sums"; } > "$message"
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -n "$line" ]] || continue
    if printf '%s' "$line" | base64 -d > "$signature" 2>/dev/null &&
       openssl pkeyutl -verify -pubin -inkey "$pem" -rawin -in "$message" -sigfile "$signature" >/dev/null 2>&1; then
      signed=1
      break
    fi
  done < "$signatures"
  [[ "$signed" == 1 ]] || { echo "SDK release signature does not match $tag" >&2; return 1; }

  (cd "$dir" && sha256sum -c SHA256SUMS >/dev/null) || {
    echo "One or more SDK assets do not match the signed checksums" >&2
    return 1
  }
  echo "Verified signed SDK release $tag"
}
