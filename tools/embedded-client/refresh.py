#!/usr/bin/env python3
"""Refresh the explicit mobile overlay after intentional, reviewed source edits."""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import sys
sys.dont_write_bytecode = True
from verify import HERE, ROOT, VENDOR, compare, digest, tree


def normalize_patch_paths(patch):
    """Remove staging directories from headers, never source or binary content."""
    lines = []
    in_headers = False
    for line in patch.splitlines(keepends=True):
        if line.startswith(b'diff --git '):
            # With rename detection disabled, both sides name the same file.
            # Match that full path twice, including Git's optional C quoting,
            # so spaces or prefix-like text inside filenames remain unchanged.
            match = re.fullmatch(
                rb'diff --git (?P<quote>"?)a/(?:upstream|mobile)/(?P<path>.*)'
                rb'(?P=quote) (?P=quote)b/(?:upstream|mobile)/(?P=path)(?P=quote)\n',
                line,
            )
            if match is None:
                raise SystemExit('unexpected no-rename diff header')
            quote, path = match.group('quote', 'path')
            line = (
                b'diff --git ' + quote + b'a/' + path + quote + b' '
                + quote + b'b/' + path + quote + b'\n'
            )
            in_headers = True
        elif line.startswith((b'@@ ', b'GIT binary patch')):
            in_headers = False
        elif in_headers:
            line = re.sub(
                rb'^(--- |\+\+\+ )("?[ab]/)(?:upstream|mobile)/',
                rb'\1\2', line, count=1,
            )
        lines.append(line)
    return b''.join(lines)


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
    diff = subprocess.run(['git', 'diff', '--no-index', '--no-renames', '--binary', '--full-index', '--', 'upstream', 'mobile'], cwd=tmp, stdout=subprocess.PIPE)
    if diff.returncode not in (0, 1):
        raise SystemExit('could not generate mobile overlay')
    patch = normalize_patch_paths(diff.stdout)
    (HERE / 'mobile.patch').write_bytes(patch)
manifest['mobile_patch_sha256'] = digest(HERE / 'mobile.patch')
manifest['vendor_files'] = vendor = {p.as_posix(): digest(VENDOR / p) for p in paths}
original = manifest['upstream_files']
manifest['omitted_upstream_paths'] = sorted(original.keys() - vendor.keys())
manifest['mobile_modified_upstream_paths'] = sorted(k for k in original.keys() & vendor.keys() if original[k] != vendor[k])
manifest['mobile_added_paths'] = sorted(vendor.keys() - original.keys())
(HERE / 'upstream.json').write_text(json.dumps(manifest, indent=2) + '\n')
print('Refreshed mobile.patch and manifest; review both before committing.')
