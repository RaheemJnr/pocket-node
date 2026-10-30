import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile
import yaml

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'tools/release/build_provenance.py'
ABIS = {'arm64-v8a': 'aarch64-linux-android', 'armeabi-v7a': 'armv7-linux-androideabi'}

class ProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.vendor = self.root / 'external/ckb-light-client'
        self.vendor.mkdir(parents=True)
        (self.vendor / 'Cargo.lock').write_text('fixture source')
        metadata = self.root / 'tools/embedded-client'
        metadata.mkdir(parents=True)
        h = hashlib.sha256(b'fixture source').hexdigest()
        (metadata / 'upstream.json').write_text(json.dumps({'vendor_files': {'Cargo.lock': h}, 'mobile_patch_sha256': hashlib.sha256(b'patch').hexdigest(), 'upstream_commit': 'a'*40}))
        (metadata / 'mobile.patch').write_text('patch')
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        subprocess.run(['git', '-C', str(self.root), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(self.root), '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture'], check=True)
        self.commit = subprocess.check_output(['git', '-C', str(self.root), 'rev-parse', 'HEAD'], text=True).strip()

    def run_build(self, mode):
        return subprocess.run([sys.executable, str(SCRIPT), mode, '--root', str(self.root)], capture_output=True, text=True)

    def prepared(self):
        result = self.run_build('prepare')
        self.assertEqual(result.returncode, 0, result.stderr)
        for abi, target in ABIS.items():
            for p in [self.vendor / f'target/{target}/release/libckb_light_client_lib.so', self.root / f'android/app/src/main/jniLibs/{abi}/libckb_light_client_lib.so']:
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_bytes(abi.encode())
        apk = self.root / 'android/app/build/outputs/apk/release/app.apk'
        apk.parent.mkdir(parents=True)
        if getattr(self, 'real_apk', False):
            signer = Path(os.environ['APK_SIGNER'])
            manifest = self.root / 'AndroidManifest.xml'
            manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="invalid.example.fixture"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/><application/></manifest>')
            subprocess.run([str(signer.parent / 'aapt2'), 'link', '--manifest', str(manifest), '-I', str(signer.parents[2] / 'platforms/android-36/android.jar'), '-o', str(apk)], check=True, capture_output=True)
        with zipfile.ZipFile(apk, 'a' if getattr(self, 'real_apk', False) else 'w') as z:
            for abi in ABIS:
                z.writestr(f'lib/{abi}/libckb_light_client_lib.so', abi.encode())
        return apk

    def test_workflow_has_data_only_handoff(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/release.yml').read_text())
        steps = workflow['jobs']['sign']['steps']
        self.assertTrue(any(s.get('name') == 'Generate release verification artifacts' for s in steps), 'missing automatic release verification')
        self.assertFalse(any('checkout@' in s.get('uses', '') for s in steps))

    def test_source_capture_binds_actual_packaged_libraries(self):
        apk = self.prepared()
        result = self.run_build('finish')
        self.assertEqual(result.returncode, 0, result.stderr)
        manifest = json.loads((self.root / 'release-input/build-provenance.json').read_text())
        self.assertEqual(manifest['source_commit'], self.commit)
        self.assertEqual(manifest['unsigned_apk_sha256'], hashlib.sha256(apk.read_bytes()).hexdigest())
        self.assertEqual(set(manifest['native_build_outputs']), set(ABIS))
        for abi in ABIS:
            digest = hashlib.sha256(abi.encode()).hexdigest()
            self.assertEqual(manifest['native_build_outputs'][abi]['cargo_sha256'], digest)
            self.assertEqual(manifest['native_build_outputs'][abi]['staged_sha256'], digest)
        self.assertEqual(len(manifest['packaged_native_sha256']), 2)

    def test_changed_source_rejected(self):
        self.prepared()
        (self.vendor / 'Cargo.lock').write_text('changed')
        self.assertNotEqual(self.run_build('finish').returncode, 0)

    def test_stale_target_rejected(self):
        (self.vendor / 'target').mkdir()
        self.assertNotEqual(self.run_build('prepare').returncode, 0)

    def test_missing_native_rejected(self):
        self.prepared()
        (self.vendor / 'target/aarch64-linux-android/release/libckb_light_client_lib.so').unlink()
        self.assertNotEqual(self.run_build('finish').returncode, 0)

    def test_wrong_overlay_rejected(self):
        (self.root / 'tools/embedded-client/mobile.patch').write_text('unreviewed')
        self.assertNotEqual(self.run_build('prepare').returncode, 0)

    def test_missing_packaged_library_rejected(self):
        apk = self.prepared()
        with zipfile.ZipFile(apk, 'w') as z:
            z.writestr('not-a-native-library', b'fixture')
        self.assertNotEqual(self.run_build('finish').returncode, 0)

    def test_multiple_apks_rejected(self):
        apk = self.prepared()
        apk.with_name('other.apk').write_bytes(apk.read_bytes())
        self.assertNotEqual(self.run_build('finish').returncode, 0)

    def verification_fixture(self):
        apk = self.prepared()
        result = self.run_build('finish')
        self.assertEqual(result.returncode, 0, result.stderr)
        unsigned = self.root / 'unsigned'
        unsigned.mkdir()
        for name in ('unsigned.apk', 'build-provenance.json'):
            (unsigned / name).write_bytes((self.root / 'release-input' / name).read_bytes())
        (self.root / 'PocketNode-v1.fixture.apk').write_bytes(apk.read_bytes())
        (self.root / 'verified-certificate.txt').write_text('Signer #1 certificate SHA-256 digest: ' + 'b'*64 + '\n')
        return {'RELEASE_TAG': 'v1.fixture', 'SOURCE_COMMIT': self.commit, 'RELEASE_CERT_SHA256': 'b'*64, 'BUILD_RUN_URL': 'https://example.invalid/fixture'}

    def verify(self, env):
        workflow = yaml.safe_load((ROOT / '.github/workflows/release.yml').read_text())
        step = next(s for s in workflow['jobs']['sign']['steps'] if s.get('name') == 'Generate release verification artifacts')
        # Execute the exact inline trusted generator, never a copied implementation.
        return subprocess.run(['bash', '-e', '-c', step['run']], cwd=self.root, env={**os.environ, **env}, capture_output=True, text=True)

    def test_required_workflow_signs_and_verifies_before_certificate_acceptance(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/release.yml').read_text())
        step = next(s for s in workflow['jobs']['sign']['steps'] if s.get('name') == 'Zipalign, sign, and verify')
        sdk = self.root / 'sdk/build-tools/fixture'
        sdk.mkdir(parents=True)
        (self.root / 'unsigned').mkdir()
        (self.root / 'unsigned/unsigned.apk').write_bytes(b'unsigned fixture')
        (self.root / 'release.jks').write_bytes(b'disposable fixture')
        (sdk / 'zipalign').write_text('#!/bin/sh\ncp "$4" "$5"\n')
        (sdk / 'apksigner').write_text("#!/usr/bin/env python3\nimport pathlib,sys\nwith open('calls.txt','a') as f: f.write(sys.argv[1]+'\\n')\nif sys.argv[1]=='sign':\n    pathlib.Path(sys.argv[sys.argv.index('--out')+1]).write_bytes(b'signed fixture')\nelif sys.argv[1]=='verify':\n    assert pathlib.Path(sys.argv[-1]).read_bytes()==b'signed fixture'\n    print('Signer #1 certificate SHA-256 digest: '+'b'*64)\nelse: raise AssertionError(sys.argv)\n")
        for tool in ('zipalign', 'apksigner'): (sdk / tool).chmod(0o755)
        env = {**os.environ, 'ANDROID_HOME':str(self.root / 'sdk'), 'RELEASE_TAG':'fixture',
            'KEYSTORE_PASSWORD':'fixture', 'KEY_PASSWORD':'fixture', 'KEY_ALIAS':'fixture', 'RELEASE_CERT_SHA256':'b'*64}
        result = subprocess.run(['bash','-e','-c',step['run']], cwd=self.root, env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual((self.root / 'calls.txt').read_text().splitlines(), ['sign','verify'])
        self.assertEqual((self.root / 'PocketNode-fixture.apk').read_bytes(), b'signed fixture')

    def test_signed_artifact_and_certificate_outputs(self):
        env = self.verification_fixture()
        result = self.verify(env)
        self.assertEqual(result.returncode, 0, result.stderr)
        digest = hashlib.sha256((self.root / 'PocketNode-v1.fixture.apk').read_bytes()).hexdigest()
        self.assertEqual((self.root / 'SHA256SUMS').read_text(), digest + '  PocketNode-v1.fixture.apk\n')
        self.assertEqual((self.root / 'SIGNING-CERTIFICATE-SHA256.txt').read_text(), 'b'*64 + '\n')
        manifest = json.loads((self.root / 'release-provenance.json').read_text())
        self.assertEqual(manifest['apk']['sha256'], digest)

    def test_reject_unsigned_digest_source_cert_and_native_mismatches(self):
        env = self.verification_fixture()
        evidence_path = self.root / 'unsigned/build-provenance.json'
        original = evidence_path.read_text()
        for key, bad in [('source_commit', 'c'*40), ('unsigned_apk_sha256', 'd'*64), ('packaged_native_sha256', {})]:
            with self.subTest(key=key):
                data = json.loads(original)
                data[key] = bad
                evidence_path.write_text(json.dumps(data))
                self.assertNotEqual(self.verify(env).returncode, 0)
        evidence_path.write_text(original)
        for expected in ('', 'c'*64):
            self.assertNotEqual(self.verify({**env, 'RELEASE_CERT_SHA256': expected}).returncode, 0)
        with zipfile.ZipFile(self.root / 'PocketNode-v1.fixture.apk', 'w') as apk:
            apk.writestr('lib/arm64-v8a/libckb_light_client_lib.so', b'tampered')
        self.assertNotEqual(self.verify(env).returncode, 0)

    def test_real_disposable_signing_certificate(self):
        signer = os.environ.get('APK_SIGNER')
        if not signer:
            self.skipTest('set APK_SIGNER to run disposable SDK signing integration')
        self.real_apk = True
        env = self.verification_fixture()
        subprocess.run(['keytool', '-genkeypair', '-keystore', str(self.root / 'fixture.jks'), '-storepass', 'fixturepass', '-keypass', 'fixturepass', '-alias', 'fixture', '-keyalg', 'RSA', '-dname', 'CN=Disposable Test Fixture', '-validity', '1'], check=True, capture_output=True)
        signed = self.root / 'PocketNode-v1.fixture.apk'
        subprocess.run([signer, 'sign', '--min-sdk-version', '26', '--ks', str(self.root / 'fixture.jks'), '--ks-pass', 'pass:fixturepass', '--out', str(signed), str(self.root / 'unsigned/unsigned.apk')], check=True, capture_output=True)
        certs = subprocess.check_output([signer, 'verify', '--min-sdk-version', '26', '--print-certs', str(signed)], text=True)
        (self.root / 'verified-certificate.txt').write_text(certs)
        import re
        fingerprint = re.search(r'^Signer #1 certificate SHA-256 digest: ([a-f0-9]{64})$', certs, re.M).group(1)
        result = self.verify({**env, 'RELEASE_CERT_SHA256': fingerprint})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / 'SIGNING-CERTIFICATE-SHA256.txt').read_text().strip(), fingerprint)

if __name__ == '__main__':
    unittest.main()
