# Security Policy

## Reporting Vulnerabilities

If you discover a security vulnerability in Pocket Node, please report it responsibly:

1. **Do NOT open a public issue.** Security vulnerabilities should be reported privately.
2. **Email**: [mumedian6@gmail.com](mailto:mumedian6@gmail.com) for critical vulnerabilities.
3. **GitHub**: Use [Security Advisories](https://github.com/RaheemJnr/pocket-node/security/advisories/new) for non-critical reports.
4. Include:
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

We will acknowledge receipt within 48 hours and provide a timeline for resolution.

## Security Model

### Key Storage

Private keys and BIP39 mnemonics are encrypted using Android's `EncryptedSharedPreferences` backed by the Android Keystore system:

- **Encryption**: AES-256-GCM
- **Key backing**: StrongBox HSM (Pixel 3+, Galaxy S9+) or TEE (all Android 8.0+ devices)
- **Key generation**: `MasterKey` with `setRequestStrongBoxBacked(true)`, automatic TEE fallback

### Authentication

- **Biometric**: `BiometricPrompt` with `BIOMETRIC_STRONG` authenticators
- **PIN fallback**: 6-digit PIN, hashed with Blake2b + per-device salt
- **Lockout**: 5 failed attempts triggers 30-second lockout

### Transaction Security

- All transaction signing is performed locally on-device
- Private keys never leave the device or are transmitted over the network
- Address network validation prevents cross-network sends (mainnet vs testnet)
- Transaction size checked against CKB protocol limits before broadcast

### Build Security

- `android:allowBackup="false"` prevents ADB backup of key material
- Release builds strip all `android.util.Log` calls via ProGuard (`-assumenosideeffects`)
- R8 code shrinking and resource shrinking enabled for release builds
- Release signing uses environment-variable-based keystore configuration

### Light Client

- The embedded CKB light client communicates directly with CKB mainnet peers via P2P
- RPC binds to `127.0.0.1:9000` (localhost only, not exposed externally)
- No intermediary servers — the app is fully self-sovereign

## Known Limitations

- An internal Milestone 4 security audit completed 2026-05-19 (#186, #187, #188 in the issue tracker). External audit by an independent firm is tracked under #204 and may engage post-grant. No formal third-party audit has been completed at the time of writing.
- PIN derivation currently uses a single Blake2b pass with a per-device salt. Strengthening to Argon2id with cumulative lockout is tracked under #214 for v1.7.0.
- Keystore-backed wallet keys are not yet bound to per-operation user authentication. Adding `setUserAuthenticationRequired` plus `BiometricPrompt`-gated `CryptoObject` use is tracked under #213 for v1.7.0.
- Network switch requires a full app restart. The embedded Rust JNI bridge holds global state in `OnceLock` and cannot be re-initialized in-process. The app handles this with a confirm dialog plus `Process.killProcess` (see `GatewayRepository.switchNetwork`).
- No certificate pinning for any outgoing connections (CoinGecko price fetch only; no auth headers, no user-supplied URLs).
- Light client P2P traffic is not encrypted beyond CKB protocol-level protections.

## Accepted Risks

The following items have been identified during security review and are accepted for the current release with documented reasoning. They are revisited at each milestone.

### BouncyCastle `bcprov-jdk15on` 1.70 — CVE chain (transitive via `ckb-sdk-java` 4.0.0)

**Acknowledged CVEs**

| CVE | Severity | Vulnerable code path |
|-----|----------|----------------------|
| CVE-2024-29857 | High (CVSS 7.5) | EC certificate import with crafted F2m parameters causes excessive CPU in `ECCurve.java` |
| CVE-2023-33201 | Medium (CVSS 5.3) | LDAP wildcard handling in X.500 name parsing can disclose information |
| CVE-2025-8916 | Medium | Excessive memory allocation in BC's PKIX/Provider DER parse path; affects 1.44 through 1.78 |

**Reachability analysis**

Pocket Node uses BouncyCastle only as a transitive dependency of `ckb-sdk-java`, which in turn uses it for Blake2b digest, secp256k1 signing helpers, and ASN.1/DER serialization of CKB transactions. The app does **not**:

- Import third-party EC certificates from any source (the lock script is fixed `secp256k1-blake160`)
- Parse LDAP, X.500, or PKIX structures from network input or user input
- Accept DER/ASN.1 input from anywhere outside the CKB transaction format, which is validated by `ckb-sdk-java` against a fixed schema before reaching BC code paths
- Run a TLS/PKI server, certificate authority, or X.509 verification flow

Therefore the three CVEs listed above have no reachable attack path from any Pocket Node call site. The `jdk15on` artifact line is end-of-life upstream; BouncyCastle 1.78+ ships as `bcprov-jdk18on`. A direct Pocket Node upgrade would conflict with the `ckb-sdk-java` 4.0.0 dependency resolution.

**Mitigation path**

Migration tracking: an upstream issue against `nervosnetwork/ckb-sdk-java` requesting `bcprov-jdk18on` ≥ 1.78 is filed under #219 in this repo. Once upstream ships a compatible release, this row will be revisited.

**Reviewer**: internal M4 Phase 1 audit (#188 Finding High 1)
**Sign-off date**: 2026-05-19
**Next review**: at v2.0.0 release cut or on publication of any new BouncyCastle CVE with a reachable code path.

## Supported Versions

| Version | Supported |
|---------|-----------|
| 1.6.x   | Yes       |
| 1.5.x   | Security fixes via in-app updater to 1.6.1 |
| < 1.5.0 | No        |

## Scope

The following are in scope for security reports:

- Private key or mnemonic exposure
- Authentication bypass
- Transaction manipulation
- Data leakage through logs, backups, or unencrypted storage
- JNI bridge vulnerabilities

The following are **out of scope**:

- CKB protocol-level vulnerabilities (report to [Nervos](https://github.com/nervosnetwork))
- Social engineering attacks
- Physical device access with a rooted/jailbroken device
- Denial of service against the light client P2P network
