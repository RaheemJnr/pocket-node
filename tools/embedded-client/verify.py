#!/usr/bin/env python3
"""Verify the pinned upstream tree, explicit mobile patch, and checked-in vendor tree."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
VENDOR = ROOT / 'external/ckb-light-client'

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def tree(path):
    return {p.relative_to(path).as_posix(): digest(p) for p in path.rglob('*') if p.is_file()}

def compare(expected, actual, label):
    differences = sorted(k for k in expected.keys() | actual.keys() if expected.get(k) != actual.get(k))
    if differences:
        raise SystemExit(f'{label} differs: ' + ', '.join(differences))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--archive', type=Path)
    source.add_argument('--upstream-tree', type=Path)
    args = parser.parse_args()
    manifest = json.loads((HERE / 'upstream.json').read_text())
    patch = HERE / 'mobile.patch'
    if digest(patch) != manifest['mobile_patch_sha256']:
        raise SystemExit('mobile.patch hash differs from the reviewed manifest')
    with tempfile.TemporaryDirectory(prefix='pocket-upstream-verify-') as tmp:
        tmp = Path(tmp)
        if args.archive:
            if digest(args.archive) != manifest['archive_sha256']:
                raise SystemExit('upstream archive checksum mismatch')
            with tarfile.open(args.archive) as archive:
                for member in archive.getmembers():
                    path = Path(member.name)
                    if path.is_absolute() or '..' in path.parts or not (member.isfile() or member.isdir()):
                        raise SystemExit('unsafe archive entry')
                archive.extractall(tmp / 'archive', filter='data')
            roots = list((tmp / 'archive').iterdir())
            if len(roots) != 1:
                raise SystemExit('unexpected archive root')
            upstream = roots[0]
        else:
            upstream = args.upstream_tree.resolve()
        compare(manifest['upstream_files'], tree(upstream), 'pinned upstream tree')
        reconstructed = tmp / 'reconstructed'
        shutil.copytree(upstream, reconstructed)
        subprocess.run(['git', 'apply', '--whitespace=nowarn', '--check', str(patch)], cwd=reconstructed, check=True)
        subprocess.run(['git', 'apply', '--whitespace=nowarn', str(patch)], cwd=reconstructed, check=True)
        compare(manifest['vendor_files'], tree(reconstructed), 'reconstructed mobile tree')
        paths = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard', '--', 'external/ckb-light-client'], cwd=ROOT).decode().split('\0')
        actual = {}
        for name in paths:
            p = ROOT / name
            if name and p.is_file():
                actual[p.relative_to(VENDOR).as_posix()] = digest(p)
        compare(manifest['vendor_files'], actual, 'vendored mobile tree')
    print(f"Verified upstream {manifest['upstream_commit']} + mobile.patch ({len(actual)} files)")

if __name__ == '__main__':
    main()
