#!/usr/bin/env python3
"""Make the Android Edition's engine-data preparation use Horizon's exact Mojang manifest pin.

Mojang's version_manifest_v2 can point at a republished 26.3 JSON whose JARs are
identical but whose assetIndex differs. Horizon intentionally accepts only the
manifest SHA-1 recorded in research-lock.json for reproducible engine data.
"""
from pathlib import Path
import sys

target = Path(sys.argv[1])
source = target.read_text(encoding="utf-8")

old = """    entry = next((v for v in fetch_json(MANIFEST)['versions'] if v['id'] == version), None)
    if entry is None:
        raise ValueError(f"Mojang does not list Minecraft {version}")
    # Mojang metadata may be republished: lab.download checks the catalog SHA-1,
    # and client/server jar hashes are checked against the engine pins below.
    versions = directory / 'versions' / version
    manifest_path = versions / f'{version}.json'
    lab.download(entry['url'], manifest_path, entry['sha1'])
"""
new = """    # Fetch the exact version JSON pinned by research-lock.json, not a
    # republished version_manifest_v2 entry with a different assetIndex.
    versions = directory / 'versions' / version
    manifest_path = versions / f'{version}.json'
    manifest_sha1 = pins['manifest_sha1']
    manifest_url = f'https://piston-meta.mojang.com/v1/packages/{manifest_sha1}/{version}.json'
    lab.download(manifest_url, manifest_path, manifest_sha1)
"""

if new in source:
    print("android_prepare.py already uses the pinned manifest")
elif old in source:
    target.write_text(source.replace(old, new, 1), encoding="utf-8")
    print("android_prepare.py now downloads the research-lock manifest")
else:
    raise SystemExit("Could not find the expected android_prepare.py download block; refusing a fuzzy patch.")
