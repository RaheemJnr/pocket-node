#!/usr/bin/env python3
"""Capture build-job evidence; never run this script in the signing job."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

ABIS = {'arm64-v8a': 'aarch64-linux-android', 'armeabi-v7a': 'armv7-linux-androideabi'}

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def source(root):
    paths = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
    files = {}
    for name in filter(None, paths):
        p = root / name
        if p.is_symlink() or not p.is_file():
            raise SystemExit(f'unsupported/missing tracked source: {name}')
        files[name] = sha(p)
    metadata = json.loads((root / 'tools/embedded-client/upstream.json').read_text())
    vendor = root / 'external/ckb-light-client'
    actual = {n.removeprefix('external/ckb-light-client/'): h for n, h in files.items() if n.startswith('external/ckb-light-client/')}
    if actual != metadata['vendor_files']:
        raise SystemExit('vendored source differs from reviewed metadata')
    if sha(root / 'tools/embedded-client/mobile.patch') != metadata['mobile_patch_sha256']:
        raise SystemExit('mobile overlay differs from reviewed metadata')
    return {'source_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
            'tracked_source_sha256': files, 'vendor_provenance': metadata}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['prepare', 'finish'])
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    root = args.root.resolve()
    out = root / 'release-input'
    vendor = root / 'external/ckb-light-client'
    jni = root / 'android/app/src/main/jniLibs'
    snapshot = source(root)
    if args.mode == 'prepare':
        if (vendor / 'target').exists() or jni.exists() or out.exists():
            raise SystemExit('clean native target, jniLibs and release-input before capture')
        out.mkdir()
        (out / 'source-before.json').write_text(json.dumps(snapshot, sort_keys=True, indent=2) + '\n')
        return
    before = json.loads((out / 'source-before.json').read_text())
    if before != snapshot:
        raise SystemExit('tracked source changed during build')
    apks = list((root / 'android/app/build/outputs/apk/release').glob('*.apk'))
    if len(apks) != 1:
        raise SystemExit('expected exactly one unsigned release APK')
    outputs = {}
    for abi, target in ABIS.items():
        outputs[abi] = {'cargo_sha256': sha(vendor / f'target/{target}/release/libckb_light_client_lib.so'),
                        'staged_sha256': sha(jni / abi / 'libckb_light_client_lib.so')}
    with zipfile.ZipFile(apks[0]) as apk:
        names = apk.namelist()
        if len(names) != len(set(names)):
            raise SystemExit('duplicate APK entries')
        native = {n: hashlib.sha256(apk.read(n)).hexdigest() for n in names if n.startswith('lib/') and n.endswith('.so')}
        for abi in ABIS:
            if f'lib/{abi}/libckb_light_client_lib.so' not in native:
                raise SystemExit(f'missing packaged native library: {abi}')
    snapshot.update(schema_version=1, unsigned_apk_sha256=sha(apks[0]), native_build_outputs=outputs,
                    packaged_native_sha256=native,
                    rebuild_evidence='Build job removed target and jniLibs before source capture; fresh Cargo outputs and staged libraries required. Build-job claim, not an independent attestation.',
                    toolchain_report=(root / 'release-toolchains.txt').read_text() if (root / 'release-toolchains.txt').exists() else 'not supplied')
    shutil.copyfile(apks[0], out / 'unsigned.apk')
    (out / 'build-provenance.json').write_text(json.dumps(snapshot, sort_keys=True, indent=2) + '\n')

if __name__ == '__main__':
    main()
