#!/usr/bin/env python3
"""Fetch, authenticate, validate and atomically install one exact Horizon native-mod SDK.

The release is signed for sdk-<full game commit>. Nothing is extracted until the signature,
checksum list, sdk.json identity, target slices, sizes and hashes have all passed.
"""
from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.error
import urllib.request
from pathlib import Path, PurePosixPath

REPOSITORY = "arismoko/horizon"
TARGET = "aarch64-linux-android"
STATIC = {
    "surface.json": ("surface", "none"),
    "build-data.tar.xz": ("build-data", "xz"),
    "api-source.tar.xz": ("api-source", "xz"),
}
KINDS = ("runtime", "api", "engine")


def fail(message: str) -> None:
    raise RuntimeError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def request_json(url: str):
    request = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "rustMC-Android-Native-SDK/1",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def required_assets(target: str) -> set[str]:
    return {
        "sdk.json", "surface.json", "build-data.tar.xz", "api-source.tar.xz",
        *(f"{kind}-{target}.tar.xz" for kind in KINDS),
    }


def download_release(commit: str, target: str, destination: Path) -> None:
    tag = f"sdk-{commit}"
    release = request_json(f"https://api.github.com/repos/{REPOSITORY}/releases/tags/{tag}")
    if release.get("tag_name") != tag or release.get("draft") or release.get("prerelease") is not True:
        fail(f"{tag} is not a published native SDK release")
    assets = release.get("assets", [])
    by_name = {asset.get("name"): asset for asset in assets}
    if len(by_name) != len(assets):
        fail("GitHub SDK release contains duplicate asset names")
    required = required_assets(target) | {"SHA256SUMS", "SHA256SUMS.sig"}
    missing = required - set(by_name)
    if missing:
        fail(f"{tag} is missing Android SDK assets: {', '.join(sorted(missing))}")
    for name in required:
        if not re.fullmatch(r"[A-Za-z0-9._-]+", name):
            fail(f"unsafe SDK release asset name: {name!r}")
        url = by_name[name].get("browser_download_url")
        if not url:
            fail(f"SDK release asset has no download URL: {name}")
        request = urllib.request.Request(url, headers={"User-Agent": "rustMC-Android-Native-SDK/1"})
        with urllib.request.urlopen(request, timeout=180) as response, (destination / name).open("wb") as output:
            shutil.copyfileobj(response, output)


def verify_signature(release_dir: Path, commit: str, target: str, verifier: Path) -> None:
    required = sorted(required_assets(target))
    command = [
        "bash", "-c",
        'source "$1"; shift; horizon_sdk_verify_release "$@"',
        "_", str(verifier), str(release_dir), commit, *required,
    ]
    subprocess.run(command, check=True)


def parse_sums(path: Path) -> set[str]:
    names = set()
    for line in path.read_text(encoding="ascii").splitlines():
        match = re.fullmatch(r"[0-9a-f]{64}  ([A-Za-z0-9._-]+)", line)
        if not match or match.group(1) in names:
            fail(f"invalid or duplicate SHA256SUMS row: {line!r}")
        names.add(match.group(1))
    return names


def tree_hashes(root: Path) -> dict[str, str]:
    result = {}
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            fail(f"symlink found in installed SDK cache: {path}")
        if path.is_file():
            result[path.relative_to(root).as_posix()] = sha256(path)
    return result


def validate_cached(final: Path, release_dir: Path, index: dict, target: str) -> bool:
    try:
        if (final / "sdk.json").read_bytes() != (release_dir / "sdk.json").read_bytes():
            return False
        for name in required_assets(target) - {"sdk.json"}:
            asset = index["assets"][name]
            cached = final / name
            if not cached.is_file() or cached.stat().st_size != asset["size"] or sha256(cached) != asset["sha256"]:
                return False
        manifest = json.loads((final / ".install-manifest.json").read_text(encoding="utf-8"))
        slices = final / "slices"
        return (
            manifest.get("files") == tree_hashes(slices)
            and manifest.get("signature_verified") is True
            and manifest.get("tag") == f"sdk-{index['build']['commit']}"
            and manifest.get("index_sha256") == sha256(final / "sdk.json")
            and manifest.get("target") == target
            and manifest.get("game_commit") == index["build"]["commit"]
            and manifest.get("mixins_rev") == index["mixins"]["rev"]
        )
    except (OSError, ValueError, KeyError, TypeError):
        return False


def validate_index(release_dir: Path, commit: str, target: str) -> dict:
    index_path = release_dir / "sdk.json"
    index = json.loads(index_path.read_text(encoding="utf-8"))
    if index.get("schema") != 1:
        fail(f"unsupported SDK schema: {index.get('schema')!r}")
    build = index.get("build") or {}
    if build.get("commit") != commit:
        fail("SDK index game commit does not match the requested release")
    if not build.get("game") or not (index.get("toolchain") or {}).get("rust"):
        fail("SDK index is missing game or Rust toolchain identity")
    if not isinstance(index.get("package_format"), int) or index["package_format"] < 1:
        fail("SDK index has an unsupported package format")
    if not isinstance(index.get("content_api"), str) or not index["content_api"]:
        fail("SDK index is missing content API identity")
    if not re.fullmatch(r"[0-9a-f]{64}", (index.get("surface") or {}).get("hash", "")):
        fail("SDK index is missing surface identity")
    mixins = index.get("mixins") or {}
    if not re.fullmatch(r"[0-9a-f]{40}", mixins.get("rev", "")):
        fail("SDK index has an invalid mixins revision")
    if not re.fullmatch(r"[0-9a-f]{64}", mixins.get("patch", "")):
        fail("SDK index has an invalid mixins patch hash")

    assets = index.get("assets")
    if not isinstance(assets, dict):
        fail("SDK index has no asset map")
    expected = set(STATIC)
    for name, (kind, codec) in STATIC.items():
        asset = assets.get(name)
        if not isinstance(asset, dict) or asset.get("kind") != kind or asset.get("codec") != codec:
            fail(f"SDK index has invalid metadata for {name}")
        expected.add(name)
    for kind in KINDS:
        name = f"{kind}-{target}.tar.xz"
        asset = assets.get(name)
        if not isinstance(asset, dict):
            fail(f"SDK has no {target} {kind} slice; Android ARM64 is not published for this build")
        if asset.get("kind") != kind or asset.get("codec") != "xz" or asset.get("target") != target or asset.get("side") != "client":
            fail(f"SDK slice metadata is inconsistent: {name}")
        expected.add(name)

    for name, asset in assets.items():
        if not re.fullmatch(r"[A-Za-z0-9._-]+", name):
            fail(f"unsafe SDK asset name: {name!r}")
        if not isinstance(asset, dict) or not re.fullmatch(r"[0-9a-f]{64}", asset.get("sha256", "")) or not isinstance(asset.get("size"), int) or asset["size"] <= 0:
            fail(f"invalid SDK asset checksum or size: {name}")
        if name not in STATIC and name not in ("build-data.tar.xz", "api-source.tar.xz"):
            parsed = re.fullmatch(r"(runtime|api|engine)-([a-z0-9_-]+)(-server)?\.tar\.xz", name)
            if not parsed:
                fail(f"unknown SDK asset name: {name}")
            expected_side = "server" if parsed.group(3) else "client"
            if asset.get("kind") != parsed.group(1) or asset.get("target") != parsed.group(2) or asset.get("side") != expected_side or asset.get("codec") != "xz":
                fail(f"SDK asset metadata does not match its name: {name}")
        if name in required_assets(target) - {"sdk.json"}:
            path = release_dir / name
            if not path.is_file():
                fail(f"signed SDK asset is missing: {name}")
            if path.stat().st_size != asset.get("size"):
                fail(f"SDK asset size mismatch: {name}")
            if sha256(path) != asset.get("sha256"):
                fail(f"SDK asset SHA-256 mismatch: {name}")
    if not expected.issubset(assets):
        fail("SDK index is missing required asset metadata")
    listed = parse_sums(release_dir / "SHA256SUMS")
    required_sums = {"sdk.json"} | set(assets)
    if listed != required_sums:
        fail("SHA256SUMS does not list exactly sdk.json and the indexed SDK assets")
    return index


def safe_extract(archive: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    root = destination.resolve()
    with tarfile.open(archive, mode="r:xz") as tar:
        members = tar.getmembers()
        if len(members) > 100_000:
            fail(f"SDK archive has too many entries: {archive.name}")
        total = 0
        for member in members:
            if member.isdir() and member.name in (".", "./"):
                continue
            relative = PurePosixPath(member.name)
            if relative.is_absolute() or not relative.parts or any(part in ("", ".", "..") for part in relative.parts):
                fail(f"unsafe path in SDK archive {archive.name}: {member.name!r}")
            if not (member.isfile() or member.isdir()):
                fail(f"unsupported link or special file in SDK archive {archive.name}: {member.name!r}")
            total += max(member.size, 0)
            if total > 8 * 1024 * 1024 * 1024:
                fail(f"SDK archive expands beyond 8 GiB: {archive.name}")
            target = (destination / Path(*relative.parts)).resolve()
            if target != root and root not in target.parents:
                fail(f"SDK archive path escapes its destination: {member.name!r}")
        for member in members:
            if member.isdir() and member.name in (".", "./"):
                continue
            target = destination / Path(*PurePosixPath(member.name).parts)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            source = tar.extractfile(member)
            if source is None:
                fail(f"cannot read SDK archive member: {member.name}")
            with source, target.open("xb") as output:
                shutil.copyfileobj(source, output)
            target.chmod(0o755 if member.mode & 0o111 else 0o644)


def install(release_dir: Path, cache_root: Path, commit: str, target: str, verifier: Path) -> Path:
    verify_signature(release_dir, commit, target, verifier)
    index = validate_index(release_dir, commit, target)
    cache_root.mkdir(parents=True, exist_ok=True)
    final = cache_root / f"{commit}-{target}"
    lock_path = cache_root / ".install.lock"
    with lock_path.open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if (final / "sdk.json").is_file() and validate_cached(final, release_dir, index, target):
            return final
        staging = Path(tempfile.mkdtemp(prefix=".sdk-staging-", dir=cache_root))
        backup = cache_root / f".{final.name}.previous"
        try:
            for name in ("sdk.json", "SHA256SUMS", "SHA256SUMS.sig"):
                shutil.copy2(release_dir / name, staging / name)
            selected_assets = required_assets(target) - {"sdk.json"}
            for name in selected_assets:
                shutil.copy2(release_dir / name, staging / name)
            extracted = staging / "slices"
            for name in selected_assets:
                if not name.endswith(".tar.xz"):
                    continue
                folder = name[:-len(".tar.xz")]
                safe_extract(staging / name, extracted / folder)
            (staging / ".install-manifest.json").write_text(
                json.dumps({
                    "files": tree_hashes(extracted),
                    "signature_verified": True,
                    "tag": f"sdk-{commit}",
                    "index_sha256": sha256(staging / "sdk.json"),
                    "target": target,
                    "game_commit": commit,
                    "mixins_rev": index["mixins"]["rev"],
                }, sort_keys=True), encoding="utf-8"
            )
            if backup.exists():
                shutil.rmtree(backup)
            if final.exists():
                os.replace(final, backup)
            os.replace(staging, final)
            if backup.exists():
                shutil.rmtree(backup)
        except Exception:
            if final.exists() and backup.exists():
                shutil.rmtree(final)
                os.replace(backup, final)
            raise
        finally:
            if staging.exists():
                shutil.rmtree(staging)
    return final


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--commit", required=True, help="full 40-character Horizon game commit")
    parser.add_argument("--target", default=TARGET)
    parser.add_argument("--release-dir", type=Path, help="use an already downloaded SDK release folder")
    parser.add_argument("--cache-dir", type=Path, required=True)
    parser.add_argument("--verifier", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.commit):
        fail("--commit must be a full lowercase 40-character commit")
    if args.release_dir:
        install(args.release_dir, args.cache_dir, args.commit, args.target, args.verifier)
    else:
        with tempfile.TemporaryDirectory(prefix="horizon-sdk-download-") as temporary:
            release_dir = Path(temporary)
            download_release(args.commit, args.target, release_dir)
            install(release_dir, args.cache_dir, args.commit, args.target, args.verifier)
    print(args.cache_dir / f"{args.commit}-{args.target}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, OSError, ValueError, KeyError, json.JSONDecodeError, subprocess.CalledProcessError) as error:
        print(f"native SDK install refused: {error}", file=sys.stderr)
        raise SystemExit(1)
