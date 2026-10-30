# Release verification artifacts

The release workflow builds an unsigned APK without private signing credentials,
then signs in a separate job that executes SDK tools and trusted inline workflow
code. Never check out the release tag or execute its scripts in the signing job.
The signing certificate must match the existing `RELEASE_CERT_SHA256`; these
changes do not rotate the release key or authorize a release.

The build deletes the native Cargo target and staged JNI libraries before source
capture, disables the Gradle build cache, and requires new Cargo/staged libraries
for both device ABIs. `build_provenance.py` verifies the tracked vendor inventory
and mobile overlay digest against `tools/embedded-client/upstream.json`, captures
every tracked source file digest before building, and rejects source changes
observed afterwards. The manifest records the upstream pin, full vendor metadata,
Rust/JDK/NDK versions, source file digests (including Cargo.lock, the Gradle wrapper
and verification metadata), Cargo output and staged native digests, and every
native library digest extracted from the actual APK. Gradle may strip libraries
further, so Cargo/staged and packaged digests are recorded separately.

Only `unsigned.apk` and data-only `build-provenance.json` cross into the signing
job. Inline code binds the latter's commit and unsigned digest to the handoff,
checks signed/unsigned/evidence native digests match, and requires a single
SDK-verified signer with the expected SHA-256 fingerprint. Published assets are:

* `PocketNode-<tag>.apk`
* `SHA256SUMS` (signed APK digest)
* `SIGNING-CERTIFICATE-SHA256.txt` (lowercase public certificate fingerprint)
* `release-provenance.json` (signed APK/certificate/native digests, source/build
  evidence, workflow run URL, and limitations)

Existing v1.8.6 release notes already include an APK SHA-256 and a signing
continuity statement. These files automate and extend that information; they do
not retroactively verify or alter any published release.

Download all assets, then run `sha256sum -c SHA256SUMS`. Verify the APK signature
with Android SDK `apksigner verify --print-certs PocketNode-<tag>.apk`, and compare
its certificate SHA-256 with a previously trusted fingerprint obtained through
an independent channel. A checksum or certificate file colocated with a
compromised release is not independent authenticity proof.

Source/rebuild evidence is a claim from the untrusted build job, not a signed
attestation or proof of reproducibility. A malicious build can lie about its
inputs; the trusted job establishes artifact/certificate consistency, not a
source-to-binary proof. Full native/Android release builds and independent
reproduction remain release-time checks. Historical tags lacking the helper or
reviewed vendor metadata intentionally fail rather than silently omit evidence.

Run focused disposable tests with Python 3 and PyYAML installed:

```sh
python3 -m unittest discover -s tools/release -v
```

To exercise real local SDK signing, set `APK_SIGNER` to an SDK build-tools
`apksigner` executable. The test requires sibling `aapt2`, SDK platform android-36,
JDK `keytool`, and generates a one-day ephemeral fixture key in a temporary
directory. It never reads a user keystore and performs no app/native rebuild.
