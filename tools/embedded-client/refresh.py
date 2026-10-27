#!/usr/bin/env python3
"""Refresh the explicit mobile overlay after intentional, reviewed source edits."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import sys
sys.dont_write_bytecode = True
from verify import HERE, ROOT, VENDOR, compare, digest, tree

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--upstream-tree', required=True, type=Path)
args = parser.parse_args()
manifest = json.loads((HERE / 'upstream.json').read_text())
upstream = args.upstream_tree.resolve()
compare(manifest['upstream_files'], tree(upstream), 'pinned upstream tree')
paths = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard', '--', 'external/ckb-light-client'], cwd=ROOT).decode().split('\0')
paths = sorted({(ROOT / p).relative_to(VENDOR) for p in paths if p and (ROOT / p).is_file()})
with tempfile.TemporaryDirectory(prefix='pocket-mobile-patch-') as tmp:
    tmp = Path(tmp)
    shutil.copytree(upstream, tmp / 'upstream')
    (tmp / 'mobile').mkdir()
    for p in paths:
        (tmp / 'mobile' / p).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(VENDOR / p, tmp / 'mobile' / p)
    diff = subprocess.run(['git', 'diff', '--no-index', '--binary', '--full-index', '--', 'upstream', 'mobile'], cwd=tmp, stdout=subprocess.PIPE)
    if diff.returncode not in (0, 1):
        raise SystemExit('could not generate mobile overlay')
    patch = diff.stdout
    for old, new in [(b'a/upstream/', b'a/'), (b'b/mobile/', b'b/'), (b'a/mobile/', b'a/'), (b'b/upstream/', b'b/')]:
        patch = patch.replace(old, new)
    (HERE / 'mobile.patch').write_bytes(patch)
manifest['mobile_patch_sha256'] = digest(HERE / 'mobile.patch')
manifest['vendor_files'] = vendor = {p.as_posix(): digest(VENDOR / p) for p in paths}
original = manifest['upstream_files']
manifest['omitted_upstream_paths'] = sorted(original.keys() - vendor.keys())
manifest['mobile_modified_upstream_paths'] = sorted(k for k in original.keys() & vendor.keys() if original[k] != vendor[k])
manifest['mobile_added_paths'] = sorted(vendor.keys() - original.keys())
(HERE / 'upstream.json').write_text(json.dumps(manifest, indent=2) + '\n')
print('Refreshed mobile.patch and manifest; review both before committing.')
