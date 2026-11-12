#!/usr/bin/env bash
# Horizon's embedded Android HNMod build pipeline.
# Invoked by the launcher inside its private Termux-derived ARM64 runtime; no separate Termux app,
# RUN_COMMAND permission, shared-storage setup or manual package installation is required.
# The same native-mod flow as the PC launcher is used: horizon-mixins rewrites the engine source,
# then Cargo builds libhorizon_android_game.so for the exact pinned Horizon Android revision.
#
#   bash android/termux/build-with-mods.sh [options] mod1.hnmod [mod2.hnmod | folder-of-hnmod ...]
#
#   --name NAME      folder name of the result (default: mods-<date>)
#   --jobs N         cargo jobs (default: by free RAM, about 1 per 2 GB)
#   --profile P      fast (default) | release (slower, a bit faster game; much more RAM)
#   --no-mods        build the plain game (checks that the toolchain works)
#
# The first build needs at least 10 GB free and can take a long time.
# WARNING: native mod build scripts and procedural macros execute with Horizon's app UID. This local
# compiler is not sandboxed; only compile mods from sources you trust.
set -euo pipefail

REPO=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${HORIZON_BUILD_DIR:-$HOME/.horizon-build}
NAME=""; JOBS=""; PROFILE=fast; PLAIN=0; ALLOW_MISMATCH=0; INPUTS=()
while [ $# -gt 0 ]; do
  case $1 in
    --name) NAME=$2; shift 2;;
    --jobs) JOBS=$2; shift 2;;
    --profile) PROFILE=$2; shift 2;;
    --force) echo "--force was removed: incompatible mods must not bypass the compatibility resolver" >&2; exit 2;;
    --no-mods) PLAIN=1; shift;;
    --allow-version-mismatch) ALLOW_MISMATCH=1; shift;;
    -h|--help) sed -n 2,16p "$0"; exit 0;;
    *) INPUTS+=("$1"); shift;;
  esac
done
case $PROFILE in fast|release) ;; *) echo "profile must be fast or release" >&2; exit 2;; esac
[ "$PLAIN" = 1 ] || [ ${#INPUTS[@]} -gt 0 ] || { echo "Give at least one .hnmod (or --no-mods)." >&2; exit 2; }
NAME=${NAME:-mods-$(date +%Y%m%d-%H%M)}
mkdir -p "$WORK"
: > "$WORK/build.log"

say() { printf '\n== %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# crates.io's static.crates.io download host may fail DNS on some mobile networks.
# Keep crates.io as default, then retry failed Cargo operations via RsProxy.
# The fallback is process-local and never edits the user's global Cargo config.
CARGO_MIRROR_CONFIG=(
  --config 'source.crates-io.replace-with="horizon-rsproxy-sparse"'
  --config 'source.horizon-rsproxy-sparse.registry="sparse+https://rsproxy.cn/index/"'
)
CARGO_USE_MIRROR=0
cargo_retry() {
  local status
  if [ "$CARGO_USE_MIRROR" = 1 ]; then
    cargo "${CARGO_MIRROR_CONFIG[@]}" "$@"
    return $?
  fi
  if cargo "$@"; then
    return 0
  else
    status=$?
  fi
  say "Cargo registry request failed (exit $status); retrying through the RsProxy sparse mirror"
  CARGO_USE_MIRROR=1
  cargo "${CARGO_MIRROR_CONFIG[@]}" "$@"
}

# Android's long-lived partial clones can retain a stale commit-graph after an
# interrupted fetch or object cleanup. Git may then refuse to fetch a pinned
# revision because the graph references an object absent from the object DB.
# Disable that optional index and retry once with --refetch, which repairs the
# object store without deleting the engine checkout or its Cargo cache.
# Materialize an exact source snapshot without Git checkout/lazy blob fetching.
# Git metadata is retained for revision/date resolution; source files come from codeload.
restore_github_snapshot() {
  local repo_dir="$1" revision="$2" slug="$3"
  local archive="$WORK/source-${revision}.tar.gz"
  command -v curl >/dev/null || die "curl is required to download pinned source snapshots"
  command -v tar >/dev/null || die "tar is required to unpack pinned source snapshots"
  say "Downloading exact source snapshot $slug@$revision"
  curl -fL --retry 3 --retry-all-errors --connect-timeout 20 \
    "https://codeload.github.com/$slug/tar.gz/$revision" -o "$archive" \
    || die "could not download source snapshot $slug@$revision"
  find "$repo_dir" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
  tar -xzf "$archive" --strip-components=1 -C "$repo_dir" \
    || die "could not extract source snapshot $slug@$revision"
  rm -f "$archive"
}

git_fetch_revision() {
  local repo="$1" revision="$2"
  # Keep pack/index generation single-threaded. Android's private app filesystem can
  # otherwise lose temporary pack files during large partial-clone fetches.
  # core.createObject=rename: Git otherwise link()s each temporary pack into place and unlinks the
  # original. PRoot's --link2symlink turns that link into a symlink to the file being unlinked, so Git
  # reports "unable to write repeatedly vanishing file". A plain rename never creates a link.
  if git -c core.createObject=rename -c pack.threads=1 -c index.threads=1 -C "$repo" fetch -q --filter=blob:none origin "$revision"; then
    return 0
  fi
  say "Repairing Git object/commit-graph metadata and retrying $revision"
  git -C "$repo" config core.createObject rename
  git -C "$repo" config core.commitGraph false
  git -C "$repo" config pack.threads 1
  git -C "$repo" config index.threads 1
  rm -f "$repo/.git/objects/info/commit-graph"
  rm -rf "$repo/.git/objects/info/commit-graphs"
  git -c core.createObject=rename -c pack.threads=1 -c index.threads=1 -C "$repo" fetch -q --refetch --filter=blob:none origin "$revision"
}

# ---- 0. tools ---------------------------------------------------------------------------------
for t in cargo rustc clang git rsync sha256sum readelf python3 cmake make pkg-config curl tar; do
  command -v "$t" >/dev/null || die "missing '$t'. The Horizon embedded toolchain is incomplete; repair or reinstall it from Horizon."
done
case $(uname -m) in aarch64|arm64) ;; *) die "this script is for a 64-bit ARM phone (aarch64), not $(uname -m)";; esac
FREE_GB=$(df -k "$HOME" | awk 'NR==2{printf "%d", $4/1048576}')
[ "$FREE_GB" -ge 10 ] || die "only ${FREE_GB} GB free; the build needs about 10 GB."
if [ -z "$JOBS" ]; then
  MEM_GB=$(awk '/MemAvailable/{printf "%d", $2/1048576}' /proc/meminfo)
  JOBS=$(( MEM_GB / 2 )); [ "$JOBS" -ge 1 ] || JOBS=1
  CORES=$(nproc); [ "$JOBS" -le "$CORES" ] || JOBS=$CORES
fi
echo "cargo jobs: $JOBS (free RAM $(awk '/MemAvailable/{printf "%.1f", $2/1048576}' /proc/meminfo) GB)"
if [ "${HORIZON_EMBEDDED:-0}" != "1" ] && command -v termux-wake-lock >/dev/null; then
  termux-wake-lock
  trap 'termux-wake-unlock || true' EXIT
fi

# ---- 1. the engine: newest upstream that our Android patch applies to ---------------------------
# UPSTREAM_PIN is the newest upstream commit the compatibility bot built green. Newer upstream layouts
# use android-next.patch plus an overlay script; STABLE_ANDROID_PIN is the older commit android.patch fits.
# If the Android integration does not apply to the newest pin, fall back to the stable pin instead of failing.
PIN=$(tr -d '[:space:]' < "$REPO/android/UPSTREAM_PIN")
STABLE_PIN=$(tr -d '[:space:]' < "$REPO/android/STABLE_ANDROID_PIN")
INTEGRATION_SUM=$( { cat "$REPO/android/android.patch" "$REPO/android/android-next.patch" "$REPO/android/apply-next-overlay.py" "$REPO/android/apply-legacy-overlay.py"; \
  find "$REPO/android/overlay" -type f -print0 | sort -z | xargs -0 sha256sum; } | sha256sum | cut -c1-12)
[ -d "$REPO/android/overlay" ] || die "the Android overlay is missing from the embedded build assets"
PATCH_SUM=$(cat "$REPO/android/android.patch" "$REPO/android/android-next.patch" "$REPO/android/apply-next-overlay.py" "$REPO/android/apply-legacy-overlay.py" | sha256sum | cut -c1-12)
OVERLAY_SUM=$(find "$REPO/android/overlay" -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -c1-12)

# Applies our Android changes to an unpacked upstream tree for $2 (same rules as android/sync.sh).
apply_android_integration() {
  local dir="$1" ref="$2" patch layout=stable
  if [ "$ref" = "$STABLE_PIN" ]; then
    patch="$REPO/android/android.patch"
  else
    patch="$REPO/android/android-next.patch"
    if [ -f "$dir/crates/horizon-client/src/game/window_handler.rs" ]; then layout=modules; else layout=legacy; fi
  fi
  git -C "$dir" apply --whitespace=nowarn "$patch" || return 1
  cp -a "$REPO/android/overlay/." "$dir/"
  case $layout in
    modules) python3 "$REPO/android/apply-next-overlay.py" "$dir" || return 1;;
    legacy) python3 "$REPO/android/apply-legacy-overlay.py" "$dir" || return 1;;
  esac
  find "$dir" -type l -delete
  printf '%s' "$ref" > "$dir/.horizon-revision"
}

# Prepares the engine source for revision $1 into $SRC; returns 1 when the Android integration does not apply.
prepare_engine() {
  local ref="$1" src="$WORK/src/$1-$INTEGRATION_SUM-pc"
  if [ ! -f "$src/.horizon-revision" ]; then
    say "Engine $ref + Android patch (once)"
    rm -rf "$src.partial"; mkdir -p "$WORK/src"
    git clone -q --filter=blob:none --no-checkout https://github.com/horizon-rust/horizon.git "$src.partial"
    git_fetch_revision "$src.partial" "$ref" || die "could not fetch Horizon revision metadata $ref"
    restore_github_snapshot "$src.partial" "$ref" "horizon-rust/horizon"
    git -C "$src.partial" config core.fileMode false
    if ! apply_android_integration "$src.partial" "$ref"; then rm -rf "$src.partial"; return 1; fi
    mv "$src.partial" "$src"
  else
    # A previous build may have selected a newer mod-pinned revision in this shared
    # source directory. Always restore the app's base revision before resolving this build.
    local current
    current=$(tr -d '[:space:]' < "$src/.horizon-revision")
    if [ "$current" != "$ref" ]; then
      say "Restoring base engine revision $ref before resolving selected mods"
      git_fetch_revision "$src" "$ref" || die "could not fetch base engine revision metadata $ref"
      git -C "$src" cat-file -e "$ref^{commit}" 2>/dev/null || die "base engine revision $ref is missing from Git metadata"
      restore_github_snapshot "$src" "$ref" "horizon-rust/horizon"
      apply_android_integration "$src" "$ref" || return 1
    fi
  fi
  SRC="$src"; PIN="$ref"
}

if ! prepare_engine "$PIN"; then
  [ "$PIN" != "$STABLE_PIN" ] || die "the Android patch does not apply to $PIN"
  say "The Android patch does not apply to the newest engine $PIN; using the last compatible one, $STABLE_PIN"
  prepare_engine "$STABLE_PIN" || die "the Android patch does not apply to $STABLE_PIN either"
fi

# ---- 2. validate and extract .hnmod bundles through the shared Rust core ------------------------
MODS="$WORK/mods"; IDS=(); FILES=()
rm -rf "$MODS" "$WORK/unpack"; mkdir -p "$MODS" "$WORK/unpack"
if [ "$PLAIN" = 0 ]; then
  say "Collecting .hnmod archives"
  for i in "${INPUTS[@]}"; do
    if [ -d "$i" ]; then
      while IFS= read -r f; do FILES+=("$f"); done < <(find "$i" -maxdepth 1 -type f -name '*.hnmod' | sort)
    else
      FILES+=("$i")
    fi
  done
  [ ${#FILES[@]} -gt 0 ] || die "no .hnmod found in the given paths"
  for f in "${FILES[@]}"; do
    [ -f "$f" ] || die "not a file: $f"
    case "$f" in *.hnmod) ;; *) die "expected a .hnmod archive: $f";; esac
  done

  # Match the PC launcher's revision selection: pinned mods build on the newest
  # pinned Horizon revision or the installed base, determined by Git commit dates.
  # A commit hash is never treated as a timestamp.
  # Keep the small validator CLI cheap without changing the environment of the much larger game build.
  hnmod_cli() {
    local CARGO_INCREMENTAL=0
    export CARGO_INCREMENTAL
    cargo_retry run --quiet --locked --manifest-path "$REPO/hnmod-core/Cargo.toml" --bin hnmod -- "$@"
  }
  PINS=(); MOD_PINS=(); MIXIN_PINS=()
  for f in "${FILES[@]}"; do
    info=$(hnmod_cli inspect "$f") || die "validation failed: $f"
    pin=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["manifest"].get("horizon") or "")' <<< "$info")
    hooks=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["manifest"].get("hooks") or "")' <<< "$info")
    if [ -n "$pin" ]; then
      [[ "$pin" =~ ^[0-9a-f]{40}$ ]] || die "invalid Horizon revision in $f: $pin"
      # PC hook-API mods do not pin the engine revision; their hooks requirement is
      # checked against the selected engine's NATIVE_HOOKS_API instead.
      if [ -z "$hooks" ]; then PINS+=("$pin"); MOD_PINS+=("$pin"); fi
    fi
    mixpin=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["manifest"].get("mixins") or "")' <<< "$info")
    if [ -n "$mixpin" ]; then
      [[ "$mixpin" =~ ^[0-9a-f]{40}$ ]] || die "invalid horizon-mixins revision in $f: $mixpin"
      MIXIN_PINS+=("$mixpin")
    fi
  done
  PINS+=("$PIN")
  mapfile -t PINS < <(printf '%s\n' "${PINS[@]}" | sort -u)
  BEST_PIN="$PIN"; BEST_DATE=""
  for candidate in "${PINS[@]}"; do
    git_fetch_revision "$SRC" "$candidate" \
      || die "cannot fetch mod-pinned Horizon revision $candidate (Git object database repair failed)"
    committed=$(git -C "$SRC" show -s --format=%cI "$candidate") \
      || die "cannot read commit date for Horizon $candidate"
    if [ -z "$BEST_DATE" ] || [[ "$committed" > "$BEST_DATE" ]]; then
      BEST_DATE="$committed"; BEST_PIN="$candidate"
    fi
  done
  HAS_MOD_MISMATCH=0
  for mod_pin in "${MOD_PINS[@]}"; do
    [ "$mod_pin" = "$BEST_PIN" ] || HAS_MOD_MISMATCH=1
  done
  if [ "$HAS_MOD_MISMATCH" = 1 ] && [ "$ALLOW_MISMATCH" != "1" ]; then
    die "PC-compatible resolver selected Horizon $BEST_PIN, but some mods target other revisions. The PC launcher asks before attempting this combination; Android must explicitly confirm it."
  fi
  if [ "$BEST_PIN" != "$PIN" ]; then
    say "Selecting newest mod-compatible Horizon revision $BEST_PIN (by Git commit date)"
    restore_github_snapshot "$SRC" "$BEST_PIN" "horizon-rust/horizon"
    apply_android_integration "$SRC" "$BEST_PIN" \
      || die "the Android patch does not apply to selected mod revision $BEST_PIN"
    PIN="$BEST_PIN"
  fi

  HOOKS_API=$(sed -n 's/^pub(crate) const NATIVE_HOOKS_API: u32 = \([0-9]*\);.*/\1/p' \
    "$SRC/crates/horizon-client/src/game/native_hooks.rs" | head -1)
  REV=$(sed -n 's/^pub const MIXINS_REV: &str = "\([0-9a-f]*\)";.*/\1/p' \
    "$SRC/launcher/src/native_mods.rs" | head -1)
  [ -n "$HOOKS_API" ] || die "could not read NATIVE_HOOKS_API from the engine"
  [ -n "$REV" ] || die "could not read MIXINS_REV from the engine"

  # Match PC behavior for the mixin compiler pin: one mod pin wins; with
  # several pins, compare their actual commit dates plus the engine default.
  if [ "${#MIXIN_PINS[@]}" -eq 1 ]; then
    REV="${MIXIN_PINS[0]}"
  elif [ "${#MIXIN_PINS[@]}" -gt 1 ]; then
    MIXIN_PINS+=("$REV")
    MIXIN_REPO="$WORK/mixins-git"
    if [ ! -d "$MIXIN_REPO/.git" ]; then
      mkdir -p "$WORK"
      git clone -q --filter=blob:none https://github.com/horizon-rust/horizon-mixins.git "$MIXIN_REPO"
    fi
    mapfile -t MIXIN_PINS < <(printf '%s\n' "${MIXIN_PINS[@]}" | sort -u)
    BEST_MIXIN="$REV"; BEST_MIXIN_DATE=""
    for candidate in "${MIXIN_PINS[@]}"; do
      git_fetch_revision "$MIXIN_REPO" "$candidate" \
        || die "cannot fetch horizon-mixins revision $candidate (Git object database repair failed)"
      committed=$(git -C "$MIXIN_REPO" show -s --format=%cI "$candidate") \
        || die "cannot read commit date for horizon-mixins $candidate"
      if [ -z "$BEST_MIXIN_DATE" ] || [[ "$committed" > "$BEST_MIXIN_DATE" ]]; then
        BEST_MIXIN_DATE="$committed"; BEST_MIXIN="$candidate"
      fi
    done
    REV="$BEST_MIXIN"
  fi

  say "Checking all mods against Horizon $PIN (hooks API $HOOKS_API; mixins $REV)"
  hnmod_cli resolve "$PIN" "$HOOKS_API" "$REV" "${FILES[@]}" \
    || die "the selected mods are not compatible with this engine/mixins toolchain"

  say "Extracting validated source bundles"
  for f in "${FILES[@]}"; do
    info=$(hnmod_cli inspect "$f") || die "validation failed: $f"
    id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["manifest"]["id"])' <<< "$info")
    hooks=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["manifest"].get("hooks") or "")' <<< "$info")
    mod_pin=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["manifest"].get("horizon") or "")' <<< "$info")
    hnmod_cli extract "$f" "$MODS/$id" || die "could not extract validated bundle: $f"
    toml="$MODS/$id/horizon-mixin.toml"
    if [ -n "$hooks" ] || { [ -n "$mod_pin" ] && [ "$mod_pin" != "$PIN" ]; }; then
      python3 - "$toml" "$PIN" <<'PY'
import pathlib, re, sys
path, revision = pathlib.Path(sys.argv[1]), sys.argv[2]
source = path.read_text()
updated, count = re.subn(r'(?m)^(\s*horizon_revision\s*=\s*)"[^"]*"',
                         lambda match: match.group(1) + '"' + revision + '"',
                         source, count=1)
if count != 1:
    raise SystemExit(f"{path}: expected exactly one horizon_revision")
path.write_text(updated)
PY
    fi
    IDS+=("$id")
    echo "validated mod: $id"
  done
  API_PLAN="$WORK/api-plan.json"
  hnmod_cli prepare "$MODS" "$API_PLAN" || die "PC trait API/dependency preparation failed"
fi

# ---- 3. horizon-mixins (the same tool and commit the PC launcher uses) --------------------------
STAGE="$WORK/stage"
if [ ${#IDS[@]} -gt 0 ]; then
  # REV was read and checked by the compatibility resolver above.
  TOOL="$WORK/tools/$REV"
  if [ ! -x "$TOOL/bin/horizon-mixins" ]; then
    say "Preparing local horizon-mixins source $REV"
    MIXIN_SOURCE="$WORK/mixins-src"
    if [ ! -d "$MIXIN_SOURCE/.git" ]; then
      rm -rf "$MIXIN_SOURCE"
      git clone -q --filter=blob:none --no-checkout https://github.com/horizon-rust/horizon-mixins.git "$MIXIN_SOURCE" \
        || die "could not clone horizon-mixins metadata"
    fi
    git_fetch_revision "$MIXIN_SOURCE" "$REV" \
      || die "could not fetch horizon-mixins source revision $REV"
    git -C "$MIXIN_SOURCE" cat-file -e "$REV^{commit}" 2>/dev/null \
      || die "horizon-mixins revision metadata $REV is missing"
    restore_github_snapshot "$MIXIN_SOURCE" "$REV" "horizon-rust/horizon-mixins"
    say "Building horizon-mixins $REV from the local checkout (once)"
    cargo_retry install --locked --path "$MIXIN_SOURCE/crates/horizon-mixins-cli" --root "$TOOL" \
      || die "horizon-mixins failed to build from its local checkout"
  fi
  say "Applying the mixins"
  rm -rf "$WORK/applied"
  "$TOOL/bin/horizon-mixins" apply --workspace "$SRC" --mods "$MODS" --out "$WORK/applied" \
    || die "horizon-mixins refused the mods (see the message above)"
  mkdir -p "$STAGE"
  # Only changed files are written, so cargo (which goes by file times) redoes only what the mods touch.
  rsync -rc --delete --exclude target "$WORK/applied/" "$STAGE/"
  API_RESULT=$(hnmod_cli stage-api "$MODS" "$STAGE" "$API_PLAN") || die "could not stage PC trait API crates into the Android game"
  HAS_API=$(python3 -c 'import json,sys; print("1" if json.load(sys.stdin).get("has_api") else "0")' <<< "$API_RESULT")
  say "Resolving staged native crates and trait APIs"
  # Resolve the clean Android-patched workspace first. Its Cargo.lock includes
  # legitimate Android-only packages from horizon-android-game/installer (e.g.
  # android_logger and zip), none of which are present in upstream's desktop lock.
  # Save this baseline before inspecting STAGE: STAGE is where HNMods are applied,
  # so comparing against this lock rejects only crates introduced by staged mods.
  cargo_retry fetch --manifest-path "$SRC/Cargo.toml" >/dev/null \
    || die "could not fetch Horizon's pinned Cargo packages"
  cp "$SRC/Cargo.lock" "$WORK/horizon-pinned-Cargo.lock" \
    || die "Horizon Android workspace Cargo.lock is missing; refusing an unpinned native build"
  cargo metadata --offline --format-version 1 --manifest-path "$STAGE/Cargo.toml" >/dev/null \
    || die "native mod dependency resolution failed (only packages available to Horizon can be used)"
  python3 - "$WORK/horizon-pinned-Cargo.lock" "$STAGE/Cargo.lock" <<'PYLOCK'
import sys, tomllib
from pathlib import Path
base, staged = (tomllib.loads(Path(p).read_text()) for p in sys.argv[1:])
def packages(lock):
    return {(p.get("name"), p.get("version"), p.get("source"), p.get("checksum"))
            for p in lock.get("package", []) if p.get("source")}
allowed = packages(base)
extra = packages(staged) - allowed
if extra:
    name, version, source, _ = sorted(extra)[0]
    raise SystemExit(f"Native mod dependency {name} {version} ({source}) is not in Horizon's original Cargo.lock")
PYLOCK
  [ "$HAS_API" = "0" ] || say "PC trait API crates attached to horizon-android-game"
else
  mkdir -p "$STAGE"; rsync -rc --delete --exclude target "$SRC/" "$STAGE/"
fi

# ---- 4. build identity and content-addressed library cache -------------------------------------
export CARGO_TARGET_DIR="$WORK/target"
export CARGO_BUILD_JOBS="$JOBS"
export CARGO_TERM_PROGRESS_WHEN=always CARGO_TERM_PROGRESS_WIDTH=100
ACTUAL_PIN=$(tr -d '[:space:]' < "$SRC/.horizon-revision")
CORE_SUM=$(find "$REPO/hnmod-core" -type f -not -path '*/target/*' -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -c1-12)
TOOLCHAIN_SUM=$({ rustc --version; cargo --version; clang --version | head -1; } | sha256sum | cut -c1-12)
BUILD_KEY=$(
  {
    printf 'horizon=%s\nmixins=%s\nprofile=%s\ntarget=aarch64-linux-android\npatch=%s\noverlay=%s\ncore=%s\ntoolchain=%s\nscript=%s\n' \
      "$ACTUAL_PIN" "${REV:-none}" "$PROFILE" "$PATCH_SUM" "$OVERLAY_SUM" "$CORE_SUM" "$TOOLCHAIN_SUM" \
      "$(sha256sum "$0" | cut -c1-12)"
    for f in "${FILES[@]}"; do sha256sum "$f"; done
  } | sha256sum | cut -c1-32
)
if [ "${HORIZON_EMBEDDED:-0}" = "1" ]; then
  OUTROOT="$HOME"
else
  OUTROOT="$HOME/storage/downloads"; [ -d "$OUTROOT" ] || OUTROOT="$HOME"
fi
OUT="$OUTROOT/Horizon-builds/$NAME"
CACHE_BUILD="$WORK/build-cache/$BUILD_KEY"
rm -rf "$OUT"; mkdir -p "$OUT"
CACHE_HIT=0
if [ -s "$CACHE_BUILD/libhorizon_android_game.so" ] && [ -s "$CACHE_BUILD/build.json" ]; then
  CACHED_SUM=$(sha256sum "$CACHE_BUILD/libhorizon_android_game.so" | cut -d' ' -f1)
  RECORDED_SUM=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("sha256",""))' "$CACHE_BUILD/build.json" 2>/dev/null || true)
  if [ "$CACHED_SUM" = "$RECORDED_SUM" ]; then
    cp "$CACHE_BUILD/libhorizon_android_game.so" "$OUT/libhorizon_android_game.so"
    CACHE_HIT=1
    say "Reusing verified Android game library from build cache $BUILD_KEY"
  fi
fi

if [ "$CACHE_HIT" = "0" ]; then
  # Horizon's fast profile already opts into incremental compilation. The HNMod CLI's
  # validator-only setting is scoped to hnmod_cli(); enable incremental Rust builds here.
  export CARGO_INCREMENTAL=1
  say "Compiling (profile $PROFILE). The first time this takes long; keep the phone charging."
  cargo_retry build --locked --manifest-path "$STAGE/Cargo.toml" \
    -p horizon-android-game --profile "$PROFILE" 2>&1 | tee "$WORK/build.log" \
    || die "the build failed; the end of $WORK/build.log says why"
  LIB="$CARGO_TARGET_DIR/$PROFILE/libhorizon_android_game.so"
  [ -f "$LIB" ] || die "no library produced at $LIB"
  cp "$LIB" "$OUT/libhorizon_android_game.so"
fi

# ---- 5. make sure the library can load in the Android app ----------------------------------------
LIB="$OUT/libhorizon_android_game.so"
say "Checking the library"
command -v patchelf >/dev/null && patchelf --remove-rpath "$LIB" || true
SYSTEM='libc.so|libm.so|libdl.so|liblog.so|libandroid.so|libvulkan.so|libEGL.so|libGLESv2.so|libGLESv3.so|libz.so|libOpenSLES.so|libaaudio.so|libjnigraphics.so|libnativewindow.so|libstdc\+\+.so|libc\+\+_shared.so'
BAD=$(readelf -d "$LIB" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | grep -Ev "^($SYSTEM)$" || true)
if [ -n "$BAD" ]; then
  echo "The library needs libraries the app does not have:"; echo "$BAD"
  rm -rf "$OUT"
  die "libraries from Termux (not Android) are linked in. Send this list to the developer."
fi
if readelf -d "$LIB" | grep -q 'libc++_shared.so'; then
  echo "note: needs libc++_shared.so; the app must ship it (it does when built with the NDK's)." >&2
fi

# ---- 6. import bundle data before post-build programs -------------------------------------------
# horizon-mixins stages each mod's archive data at native-data/<mod-id>. The launcher imports
# OUT/native-data into the selected game version, so copy that payload beside the library first.
# Post-build programs then write into the same per-version directories without losing bundle files.
if [ -d "$STAGE/native-data" ]; then
  say "Copying native mod bundle data into the imported version"
  mkdir -p "$OUT/native-data"
  rsync -a "$STAGE/native-data/" "$OUT/native-data/"
fi

# ---- 6. PC-compatible post-build programs -------------------------------------------------------
POST="$STAGE/native-post-build.json"
if [ -f "$POST" ]; then
  say "Building and running native mod post-build steps"
  if ! python3 - "$POST" "$STAGE" "$OUT" "$WORK/post-build-cache" "$CARGO_TARGET_DIR" "$PROFILE" "$WORK/build.log" <<'PYPOST'
import json, os, pathlib, re, subprocess, sys
post, stage, version, cache, target, profile, log = map(pathlib.Path, sys.argv[1:8])
steps = json.loads(post.read_text())
if not isinstance(steps, list):
    raise SystemExit("native-post-build.json must contain an array")
valid = re.compile(r"^[A-Za-z0-9_-]{1,100}$")
with log.open("ab") as output:
    for index, step in enumerate(steps):
        if not isinstance(step, dict):
            raise SystemExit(f"post-build step #{index} is not an object")
        mod, package, binary = step.get("mod"), step.get("package"), step.get("bin")
        args = step.get("args", [])
        if not all(isinstance(x, str) and valid.fullmatch(x) for x in (mod, package, binary)):
            raise SystemExit(f"post-build step #{index} has an invalid mod, package or bin name")
        if not isinstance(args, list) or not all(isinstance(x, str) for x in args):
            raise SystemExit(f"{mod}: post-build args must be an array of strings")
        command = ["cargo", "build", "--locked", "--manifest-path", str(stage / "Cargo.toml"),
                   "-p", package, "--bin", binary, "--profile", profile]
        env = os.environ.copy()
        env["CARGO_TARGET_DIR"] = str(target)
        output.write(f"\n== Building {mod}'s post-build program {binary}\n".encode())
        built = subprocess.run(command, cwd=stage, env=env, stdout=output, stderr=subprocess.STDOUT,
                               preexec_fn=lambda: os.nice(10))
        if built.returncode:
            raise SystemExit(f"{mod}: post-build program compilation failed (exit {built.returncode})")
        program = target / profile / binary
        if not program.is_file():
            raise SystemExit(f"{mod}: Cargo did not produce post-build binary {program}")
        data_dir = version / "native-data" / mod
        cache_dir = cache / mod
        bundle_data = stage / "native-data" / mod
        for directory in (data_dir, cache_dir, bundle_data):
            directory.mkdir(parents=True, exist_ok=True)
        env.update({
            "HORIZON_NATIVE_MOD": mod,
            "HORIZON_NATIVE_VERSION_DIR": str(version),
            "HORIZON_NATIVE_DATA_DIR": str(data_dir),
            "HORIZON_NATIVE_CACHE_DIR": str(cache_dir),
            "HORIZON_NATIVE_BUNDLE_DATA_DIR": str(bundle_data),
        })
        output.write(f"\n== Running {mod}'s post-build program\n".encode())
        ran = subprocess.run([str(program), *args], cwd=version, env=env, stdout=output,
                             stderr=subprocess.STDOUT, preexec_fn=lambda: os.nice(10))
        if ran.returncode:
            raise SystemExit(f"{mod}: post-build program failed (exit {ran.returncode})")
PYPOST
  then
    rm -rf "$OUT"
    die "a native mod post-build step failed; see $WORK/build.log"
  fi
fi

SUM=$(sha256sum "$LIB" | cut -d' ' -f1)
{
  printf '{\n  "name": "%s",\n  "build_key": "%s",\n  "upstream": "%s",\n  "android_patch": "%s",\n  "android_overlay": "%s",\n  "profile": "%s",\n' "$NAME" "$BUILD_KEY" "$ACTUAL_PIN" "$PATCH_SUM" "$OVERLAY_SUM" "$PROFILE"
  printf '  "mods": [%s],\n  "sha256": "%s",\n  "built": "%s"\n}\n' \
    "$(printf '"%s",' "${IDS[@]:-}" | sed 's/,$//; s/^"",*$//')" "$SUM" "$(date -u +%FT%TZ)"
} > "$OUT/build.json"

# Cache only the verified library; post-build programs run again into this build's own version
# directory so HORIZON_NATIVE_VERSION_DIR and generated data never point at a previous build.
CACHE_PART="$CACHE_BUILD.partial"
rm -rf "$CACHE_PART"; mkdir -p "$CACHE_PART"
cp "$LIB" "$CACHE_PART/libhorizon_android_game.so"
cp "$OUT/build.json" "$CACHE_PART/build.json"
mkdir -p "$WORK/build-cache"
rm -rf "$CACHE_BUILD"
mv "$CACHE_PART" "$CACHE_BUILD"
touch "$CACHE_BUILD"
python3 - "$WORK/build-cache" "$CACHE_BUILD" <<'PYPRUNE'
import pathlib, shutil, sys
root, current = map(pathlib.Path, sys.argv[1:3])
entries = [p for p in root.iterdir() if p.is_dir() and not p.name.endswith(".partial")]
entries.sort(key=lambda p: p.stat().st_mtime, reverse=True)
for old in entries[5:]:
    if old != current:
        shutil.rmtree(old, ignore_errors=True)
PYPRUNE
say "Done: $OUT"
echo "sha256 ${SUM:0:16}…   mods: ${IDS[*]:-none}"
echo "Horizon will validate and import this local build automatically."
[ "$OUTROOT" = "$HOME" ] && echo "Build remains in Horizon's private app storage; the launcher imports it automatically."
