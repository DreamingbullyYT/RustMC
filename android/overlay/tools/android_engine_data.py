#!/usr/bin/env python3
"""Package the prepared engine tables as .local/android/engine-data.zip (what the APK bundles).

Run tools/android_prepare.py first. Same file selection as Vasysik/horizon-android's android_build.py
(developer build): the .json/.nbt tables plus worldgen/ and biome-parameters/.
"""
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    version = json.loads((ROOT / 'research-lock.json').read_text())['minecraft']['version']
    resources = ROOT / '.local/horizon-client' / version
    if not (resources / 'horizon-resources.json').is_file():
        raise SystemExit('Prepared resources missing: run tools/android_prepare.py first')
    files = [p for p in resources.iterdir()
             if p.is_file() and p.suffix in ('.json', '.nbt') and p.name != 'asset-index.json']
    for name in ('worldgen', 'biome-parameters'):
        files.extend(p for p in (resources / name).rglob('*') if p.is_file())
    files.sort()
    output = ROOT / '.local/android/engine-data.zip'
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for file in files:
            archive.write(file, file.relative_to(resources).as_posix())
    print(f'{output}: {len(files)} files, {output.stat().st_size // 1024} KiB')


if __name__ == '__main__':
    main()
