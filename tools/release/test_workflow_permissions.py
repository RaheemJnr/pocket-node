"""Release trust-boundary regression checks; no GitHub API or release execution."""
from pathlib import Path
import unittest
import os
import subprocess
import tempfile
import yaml

ROOT = Path(__file__).resolve().parents[2]
BUNDLE = {'PocketNode-*.apk', 'SHA256SUMS', 'SIGNING-CERTIFICATE-SHA256.txt', 'release-provenance.json'}
PRIVATE = {'KEYSTORE_BASE64', 'KEYSTORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD'}

class ReleasePermissionsTest(unittest.TestCase):
    def setUp(self):
        self.workflow = yaml.safe_load((ROOT / '.github/workflows/release.yml').read_text())
        self.jobs = self.workflow['jobs']

    def test_publication_requires_explicit_boolean_opt_in(self):
        # PyYAML's YAML 1.1 loader reads the workflow `on` key as True.
        triggers = self.workflow.get('on', self.workflow.get(True))
        control = triggers['workflow_dispatch']['inputs']['publish']
        self.assertEqual(control['type'], 'boolean')
        self.assertIs(control['default'], False)
        self.assertEqual(self.jobs['publish']['if'], '${{ inputs.publish == true }}')
        self.assertNotIn('if', self.jobs['build'])
        self.assertNotIn('if', self.jobs['sign'])

    def test_certificate_diagnostics_preserve_fail_closed_comparison(self):
        step = next(s for s in self.jobs['sign']['steps'] if s['name'] == 'Zipalign, sign, and verify')
        script = 'set -euo pipefail\n' + step['run'].split('ACTUAL=', 1)[1]
        script = script.replace('set -euo pipefail\n', 'set -euo pipefail\nACTUAL=', 1)
        trusted = 'a' * 64
        cases = [
            (trusted, trusted, 0, 'APK signer SHA-256:'),
            (trusted, 'b' * 64, 1, 'differs from the configured expected certificate'),
            (trusted, 'invalid-sensitive-input', 1, 'exactly 64 hexadecimal characters'),
            ('unparseable', trusted, 1, 'Could not parse a single'),
            (trusted, '', 1, 'exactly 64 hexadecimal characters'),
        ]
        for actual, expected, code, message in cases:
            with self.subTest(actual=actual, expected=expected), tempfile.TemporaryDirectory() as tmp:
                Path(tmp, 'verified-certificate.txt').write_text('Signer #1 certificate SHA-256 digest: ' + actual + '\n')
                result = subprocess.run(['bash', '-c', script], cwd=tmp,
                    env={**os.environ, 'RELEASE_CERT_SHA256': expected}, capture_output=True, text=True)
                self.assertEqual(result.returncode, code, result.stderr)
                self.assertIn(message, result.stdout)
                self.assertNotIn('invalid-sensitive-input', result.stdout + result.stderr)

    def test_repository_write_is_confined_to_publisher(self):
        self.assertEqual(self.workflow['permissions'], {'contents': 'read'})
        self.assertEqual(set(self.jobs), {'build', 'sign', 'publish'})
        for name, job in self.jobs.items():
            effective = job.get('permissions', self.workflow['permissions'])
            self.assertEqual(effective, {'contents': 'write' if name == 'publish' else 'read'}, name)

    def test_publisher_only_downloads_bundle_and_creates_release(self):
        self.assertIn('publish', self.jobs, 'signing and publishing must use separate runners')
        publish = self.jobs['publish']
        self.assertEqual(set(publish['needs']), {'build', 'sign'})
        self.assertNotIn('environment', publish)
        self.assertNotIn('env', publish)
        steps = publish['steps']
        self.assertEqual(len(steps), 2)
        self.assertIn('actions/download-artifact@', steps[0]['uses'])
        self.assertIn('softprops/action-gh-release@', steps[1]['uses'])
        upload = next(s for s in self.jobs['sign']['steps'] if 'actions/upload-artifact@' in s.get('uses', ''))
        self.assertEqual(upload['with']['name'], steps[0]['with']['name'])
        self.assertEqual(set(upload['with']['path'].splitlines()), BUNDLE)
        self.assertEqual(upload['with']['if-no-files-found'], 'error')
        self.assertEqual(set(steps[1]['with']['files'].splitlines()), BUNDLE)
        self.assertTrue(steps[1]['with']['fail_on_unmatched_files'])
        self.assertEqual(steps[1]['with']['tag_name'], '${{ needs.build.outputs.release_tag }}')

    def test_signing_credentials_only_exist_in_existing_sign_steps(self):
        allowed = {'Decode keystore': {'KEYSTORE_BASE64'}, 'Zipalign, sign, and verify': PRIVATE - {'KEYSTORE_BASE64'}}
        seen = set()
        for job_name, job in self.jobs.items():
            self.assertNotIn('env', job)
            for step in job['steps']:
                for key in PRIVATE:
                    expression = 'secrets.' + key
                    if expression in yaml.safe_dump(step):
                        self.assertEqual(job_name, 'sign')
                        self.assertIn(key, allowed.get(step.get('name'), set()))
                        self.assertEqual(step['env'][key], '${{ ' + expression + ' }}')
                        seen.add(key)
        self.assertEqual(seen, PRIVATE)
        for job_name in ('build', 'sign'):
            self.assertFalse(any('softprops/action-gh-release@' in s.get('uses', '') for s in self.jobs[job_name]['steps']))
        self.assertFalse(any('checkout@' in s.get('uses', '') for s in self.jobs['sign']['steps']))

if __name__ == '__main__':
    unittest.main()
