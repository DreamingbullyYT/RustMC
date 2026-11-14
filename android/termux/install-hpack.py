#!/usr/bin/env python3
"""Install a registry-pinned Horizon .hpack into an isolated profile directory.

The Rust CLI resolves every exact registry pin first. This script downloads only those files,
checks SHA-256 and minisign before installation, validates each .hmod, and applies overrides
through a rollback-capable file transaction. It never executes mod code.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request
import uuid
import zipfile
from pathlib import Path, PurePosixPath

MAX_MOD = 64 * 1024 * 1024
MAX_TOTAL_MODS = 1024 * 1024 * 1024
MAX_OVERRIDE = 64 * 1024 * 1024
MAX_OVERRIDES = 256 * 1024 * 1024
ALLOWED_REDIRECT_HOSTS = {
    "github.com", "release-assets.githubusercontent.com",
    "objects.githubusercontent.com", "github-releases.githubusercontent.com",
}


def fail(message: str) -> None:
    raise RuntimeError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def safe_relative(value: str) -> Path:
    path = PurePosixPath(value)
    if path.is_absolute() or not path.parts or any(part in ("", ".", "..") for part in path.parts):
        fail(f"unsafe profile path: {value!r}")
    if "\\" in value or ":" in value:
        fail(f"unsafe profile path: {value!r}")
    return Path(*path.parts)


def allowed_override(value: str) -> bool:
    try:
        relative = safe_relative(value).as_posix()
    except RuntimeError:
        return False
    if relative in ("options.txt", "horizon-graphics.txt", "horizon-extras.txt"):
        return True
    parts = relative.split("/")
    if len(parts) == 2 and parts[0] == "resourcepacks":
        return not parts[1].startswith(".") and parts[1].endswith(".zip") and all(
            ch.isascii() and (ch.isalnum() or ch in ". _-+") for ch in parts[1]
        )
    if len(parts) >= 4 and parts[:2] == ["horizon-mods", ".horizon-mod-data"]:
        mod_id, tail = parts[2], parts[3:]
        valid_id = 1 <= len(mod_id) <= 64 and mod_id.isascii() and mod_id == mod_id.lower() and mod_id.replace("_", "").isalnum()
        valid_tail = all(
            part and not part.startswith(".") and all(
                ch.isascii() and (ch.isalnum() or ch in ". _-+") for ch in part
            ) for part in tail
        )
        return valid_id and valid_tail
    return False


def safe_target(root: Path, relative: str, create_parents: bool = False) -> Path:
    rel = safe_relative(relative)
    root = root.resolve()
    current = root
    for part in rel.parts[:-1]:
        current = current / part
        if current.is_symlink():
            fail(f"profile path contains a symlink: {current}")
        if current.exists() and not current.is_dir():
            fail(f"profile path parent is not a directory: {current}")
        if create_parents:
            current.mkdir(exist_ok=True)
        if current.exists():
            resolved = current.resolve()
            if resolved != root and root not in resolved.parents:
                fail(f"profile path escapes its root: {relative}")
    return root / rel


def download(item: dict, destination: Path) -> None:
    name, url = item["file_name"], item["url"]
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.hostname != "github.com" or parsed.query or parsed.fragment:
        fail(f"{name}: refusing an untrusted download URL")
    request = urllib.request.Request(url, headers={"User-Agent": "rustMC-Hpack-Installer/1"})
    digest = hashlib.sha256()
    total = 0
    with urllib.request.urlopen(request, timeout=180) as response, destination.open("xb") as output:
        final = urllib.parse.urlparse(response.geturl())
        if final.scheme != "https" or final.hostname not in ALLOWED_REDIRECT_HOSTS:
            fail(f"{name}: GitHub redirected to an untrusted host")
        while True:
            block = response.read(128 * 1024)
            if not block:
                break
            total += len(block)
            if total > MAX_MOD or total > item["size"]:
                fail(f"{name}: download exceeded its indexed size")
            digest.update(block)
            output.write(block)
    if total != item["size"] or digest.hexdigest() != item["sha256"]:
        fail(f"{name}: downloaded size or SHA-256 does not match the registry index")


def verify_signature(item: dict, archive: Path, temp: Path, minisign: str, allow_unsigned: bool) -> bool:
    key, signature = item.get("public_key"), item.get("signature")
    if not key:
        if not allow_unsigned:
            fail(f"{item['id']} is unsigned; explicit --allow-unsigned is required")
        return False
    if not signature:
        fail(f"{item['id']} is missing its required minisign signature")
    executable = shutil.which(minisign)
    if not executable:
        fail("minisign is required to verify signed mod files; install the Termux minisign package")
    signature_file = temp / f"{item['id']}.minisig"
    signature_file.write_text(signature, encoding="utf-8")
    result = subprocess.run(
        [executable, "-Vm", str(archive), "-P", key, "-x", str(signature_file)],
        capture_output=True, text=True,
    )
    if result.returncode:
        fail(f"{item['id']}: minisign verification failed: {(result.stderr or result.stdout).strip()}")
    return True


def inspect_hmod(cli: str, item: dict, archive: Path, expected_game: str) -> None:
    result = subprocess.run([cli, "inspect", str(archive)], capture_output=True, text=True)
    if result.returncode:
        fail(f"{item['id']}: package validation failed: {(result.stderr or result.stdout).strip()}")
    try:
        report = json.loads(result.stdout)
    except ValueError as error:
        fail(f"{item['id']}: package validator returned invalid JSON: {error}")
    if report.get("id") != item["id"] or report.get("version") != item["version"]:
        fail(f"{item['id']}: downloaded package identity differs from its registry pin")

    if item["file_name"].endswith(".hnmod"):
        if item["kind"] != "native-mod" or report.get("format") != "hnmod":
            fail(f"{item['id']}: legacy .hnmod is only valid for a native-mod registry project")
        if report.get("game") != expected_game:
            fail(f"{item['id']}: legacy native package targets a different game version")
        return

    if report.get("format") != "hmod":
        fail(f"{item['id']}: expected a current .hmod package")
    if report.get("api") != item["api"] or report.get("side") != item["side"]:
        fail(f"{item['id']}: HMOD API or side differs from the registry index")
    if set(report.get("capabilities", [])) != set(item.get("capabilities", [])):
        fail(f"{item['id']}: HMOD capabilities differ from the registry index")
    if report.get("game") is not None and report.get("game") != expected_game:
        fail(f"{item['id']}: native HMOD game version differs from the modpack target")
    expected_kind = "native" if item["kind"] == "native-mod" else None
    if expected_kind and report.get("kind") != expected_kind:
        fail(f"{item['id']}: registry lists a native mod but the package is {report.get('kind')}")
    if not expected_kind and report.get("kind") == "native":
        fail(f"{item['id']}: native source package is not listed as native-mod")


def extract_overrides(pack_path: Path, names: list[str], destination: Path) -> tuple[int, dict[str, str]]:
    total = 0
    hashes = {}
    with zipfile.ZipFile(pack_path) as archive:
        all_names = archive.namelist()
        if len(all_names) != len(set(all_names)):
            fail("hpack contains duplicate ZIP entries")
        for relative in names:
            path = safe_relative(relative)
            entry_name = f"overrides/{relative}"
            if entry_name not in all_names:
                fail(f"hpack override is missing: {relative}")
            info = archive.getinfo(entry_name)
            mode = (info.external_attr >> 16) & 0o170000
            if mode not in (0, 0o100000):
                fail(f"hpack override is a link or special file: {relative}")
            if info.file_size > MAX_OVERRIDE:
                fail(f"hpack override exceeds 64 MiB: {relative}")
            target = destination / path
            target.parent.mkdir(parents=True, exist_ok=True)
            count = 0
            with archive.open(info) as source, target.open("xb") as output:
                while True:
                    block = source.read(64 * 1024)
                    if not block:
                        break
                    count += len(block)
                    total += len(block)
                    if count > MAX_OVERRIDE or total > MAX_OVERRIDES:
                        fail("hpack overrides exceed their actual expanded size limits")
                    output.write(block)
            hashes[relative] = sha256(target)
    return total, hashes


def updated_mod_list(profile: Path, mods: list[dict], remove_ids: set[str]) -> bytes:
    path = profile / "horizon-mods.txt"
    if path.is_symlink():
        fail("horizon-mods.txt is a symlink; refusing to read it")
    existing = path.read_text(encoding="utf-8") if path.is_file() else ""
    changed_ids = remove_ids | {item["id"] for item in mods}
    changed = {f"mod.{mod_id}:" for mod_id in changed_ids}
    lines = [line for line in existing.splitlines() if not any(line.strip().startswith(prefix) for prefix in changed)]
    lines.extend(f"mod.{item['id']}:{'true' if item['on'] else 'false'}" for item in mods)
    return (("\n".join(lines).rstrip() + "\n") if lines else "").encode("utf-8")


def commit_files(profile: Path, staging: Path, relative_files: list[str], delete_files: list[str]) -> None:
    if len(relative_files) != len(set(relative_files)) or set(relative_files) & set(delete_files):
        fail("Hpack transaction contains duplicate or conflicting paths")
    backup = staging / "backup"
    backup.mkdir()
    installed: list[Path] = []
    saved: list[tuple[Path, Path]] = []
    root = profile.resolve()
    try:
        for relative in relative_files:
            rel = safe_relative(relative)
            source = staging / "new" / rel
            target = safe_target(root, relative, create_parents=True)
            if target.is_symlink() or (target.exists() and not target.is_file()):
                fail(f"refusing to replace a non-regular profile path: {target}")
            if target.exists():
                saved_path = backup / rel
                saved_path.parent.mkdir(parents=True, exist_ok=True)
                os.replace(target, saved_path)
                saved.append((saved_path, target))
            os.replace(source, target)
            installed.append(target)
        for relative in delete_files:
            target = safe_target(root, relative)
            if target.is_symlink() or (target.exists() and not target.is_file()):
                fail(f"refusing to delete a non-regular profile path: {target}")
            if not target.exists():
                continue
            saved_path = backup / safe_relative(relative)
            saved_path.parent.mkdir(parents=True, exist_ok=True)
            os.replace(target, saved_path)
            saved.append((saved_path, target))
    except Exception:
        for target in reversed(installed):
            if target.exists():
                target.unlink()
        for saved_path, target in reversed(saved):
            target.parent.mkdir(parents=True, exist_ok=True)
            if saved_path.exists():
                os.replace(saved_path, target)
        raise


def install(args) -> dict:
    profile_root = args.profiles_root.resolve()
    profile_root.mkdir(parents=True, exist_ok=True)
    # Resolve through the Rust reader each time: the plan is never accepted from an untrusted sidecar.
    result = subprocess.run(
        [args.hnmod_cli, "resolve-hpack", str(args.pack), str(args.index)],
        capture_output=True, text=True,
    )
    if result.returncode:
        fail(f"Hpack resolution failed: {(result.stderr or result.stdout).strip()}")
    try:
        plan = json.loads(result.stdout)
    except ValueError as error:
        fail(f"Hpack resolver returned invalid JSON: {error}")
    if not isinstance(plan.get("profile_id"), str) or not isinstance(plan.get("mods"), list):
        fail("Hpack resolver returned an invalid install plan")
    profile_component = safe_relative(plan["profile_id"])
    if len(profile_component.parts) != 1:
        fail("Hpack profile id is not a single safe path component")
    profile = profile_root / profile_component
    if profile.is_symlink():
        fail("profile directory is a symlink")
    profile.mkdir(parents=True, exist_ok=True)
    previous_receipt_path = profile / ".hpack-install.json"
    if previous_receipt_path.is_symlink():
        fail("previous Hpack receipt is a symlink")
    previous_receipt = {}
    if previous_receipt_path.is_file():
        try:
            previous_receipt = json.loads(previous_receipt_path.read_text(encoding="utf-8"))
        except ValueError as error:
            fail(f"previous Hpack receipt is invalid; refusing update: {error}")
        if not isinstance(previous_receipt, dict) or not isinstance(previous_receipt.get("mods", []), list):
            fail("previous Hpack receipt has an invalid mods list; refusing update")
    previous_mod_ids = {
        item.get("id") for item in previous_receipt.get("mods", [])
        if isinstance(item, dict) and isinstance(item.get("id"), str)
        and item.get("id").isascii() and item.get("id").replace("_", "").isalnum()
        and item.get("id") == item.get("id").lower() and 1 <= len(item.get("id")) <= 64
    }
    previous_mod_paths = set()
    for item in previous_receipt.get("mods", []):
        if not isinstance(item, dict) or item.get("kind") not in ("mod", "native-mod") or item.get("id") not in previous_mod_ids:
            continue
        folder = "horizon-native-mods" if item["kind"] == "native-mod" else "horizon-mods"
        extension = item.get("format", ".hmod")
        if extension not in (".hmod", ".hnmod"):
            continue
        previous_mod_paths.add(f"{folder}/{item['id']}{extension}")
    previous_override_hashes = previous_receipt.get("overrides", {})
    if not isinstance(previous_override_hashes, dict):
        previous_override_hashes = {}
    # A receipt is data, not authority: only remove paths that the Hpack format itself permits.
    previous_override_hashes = {
        path: digest for path, digest in previous_override_hashes.items()
        if isinstance(path, str) and allowed_override(path)
        and isinstance(digest, str) and len(digest) == 64
        and all(char in "0123456789abcdef" for char in digest)
    }
    staging = Path(tempfile.mkdtemp(prefix=".hpack-stage-", dir=profile))
    try:
        new_root = staging / "new"
        new_root.mkdir()
        downloaded = []
        signed_count = 0
        unsigned = []
        total_mod_bytes = sum(item.get("size", 0) for item in plan["mods"])
        if total_mod_bytes > MAX_TOTAL_MODS:
            fail("Hpack mod downloads exceed the 1 GiB profile limit")
        for item in plan["mods"]:
            filename = safe_relative(item["file_name"])
            valid_extension = item["file_name"].endswith(".hmod") or (
                item.get("kind") == "native-mod" and item["file_name"].endswith(".hnmod")
            )
            if len(filename.parts) != 1 or not valid_extension:
                fail(f"unsafe HMOD/HNMOD filename in install plan: {item['file_name']!r}")
            if item.get("kind") not in ("mod", "native-mod"):
                fail(f"unsupported registry mod kind: {item.get('kind')!r}")
            archive = staging / filename
            download(item, archive)
            if verify_signature(item, archive, staging, args.minisign, args.allow_unsigned):
                signed_count += 1
            else:
                unsigned.append(item["id"])
            inspect_hmod(args.hnmod_cli, item, archive, plan["game"])
            directory = "horizon-native-mods" if item["kind"] == "native-mod" else "horizon-mods"
            extension = ".hnmod" if item["file_name"].endswith(".hnmod") else ".hmod"
            relative = f"{directory}/{item['id']}{extension}"
            target = new_root / safe_relative(relative)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(archive, target)
            downloaded.append(relative)

        override_root = new_root / "overrides"
        override_root.mkdir()
        override_bytes, override_hashes = extract_overrides(args.pack, plan["overrides"], override_root)
        overrides = []
        for relative in plan["overrides"]:
            staged = override_root / safe_relative(relative)
            target = new_root / safe_relative(relative)
            target.parent.mkdir(parents=True, exist_ok=True)
            os.replace(staged, target)
            overrides.append(relative)
        shutil.rmtree(override_root)

        mod_list = updated_mod_list(profile, plan["mods"], previous_mod_ids)
        mod_list_path = new_root / "horizon-mods.txt"
        mod_list_path.write_bytes(mod_list)
        receipt = {
            "format": "hpack",
            "id": plan["profile_id"],
            "version": plan["profile_version"],
            "game": plan["game"],
            "content_fingerprint_sha256": plan["content_fingerprint_sha256"],
            "mods": [{"id": item["id"], "version": item["version"], "kind": item["kind"],
                      "format": ".hnmod" if item["file_name"].endswith(".hnmod") else ".hmod",
                      "sha256": item["sha256"], "on": item["on"]} for item in plan["mods"]],
            "overrides": override_hashes,
        }
        receipt_path = new_root / ".hpack-install.json"
        receipt_path.write_text(json.dumps(receipt, indent=2, sort_keys=True), encoding="utf-8")
        relative_files = downloaded + overrides + ["horizon-mods.txt", ".hpack-install.json"]
        new_mod_paths = set(downloaded)
        stale_mod_paths = previous_mod_paths - new_mod_paths
        new_override_paths = set(overrides)
        stale_override_paths = []
        for relative, old_hash in previous_override_hashes.items():
            if relative in new_override_paths or not isinstance(old_hash, str):
                continue
            target = safe_target(profile, relative)
            if target.is_file() and not target.is_symlink() and sha256(target) == old_hash:
                stale_override_paths.append(relative)
        delete_files = sorted(stale_mod_paths | set(stale_override_paths))
        commit_files(profile, staging, relative_files, delete_files)
        return {
            "profile": str(profile),
            "mods_installed": len(downloaded),
            "native_mods": sum(item["kind"] == "native-mod" for item in plan["mods"]),
            "signed_mods": signed_count,
            "unsigned_mods": unsigned,
            "overrides_installed": len(overrides),
            "override_bytes": override_bytes,
            "fingerprint": plan["content_fingerprint_sha256"],
        }
    finally:
        if staging.exists():
            shutil.rmtree(staging)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pack", type=Path, required=True)
    parser.add_argument("--index", type=Path, required=True, help="trusted Horizon registry index JSON")
    parser.add_argument("--profiles-root", type=Path, required=True)
    parser.add_argument("--hnmod-cli", required=True, help="built hnmod CLI executable")
    parser.add_argument("--minisign", default="minisign")
    parser.add_argument("--allow-unsigned", action="store_true", help="explicitly allow registry projects without a signing key")
    args = parser.parse_args()
    if not args.pack.is_file() or not args.index.is_file():
        fail("pack and registry index must be existing files")
    result = install(args)
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"hpack install refused: {error}", file=sys.stderr)
        raise SystemExit(1)
