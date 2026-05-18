# Pocket Node

A sovereign Android wallet for [Nervos CKB](https://www.nervos.org/) that runs an embedded light client directly on your device via JNI. No remote servers, no third-party dependencies for blockchain access — your keys, your node, your wallet.

![CI](https://github.com/RaheemJnr/pocket-node/actions/workflows/android-ci.yml/badge.svg)
![Platform](https://img.shields.io/badge/platform-Android-green)
![Kotlin](https://img.shields.io/badge/kotlin-2.1-blue)
![Min SDK](https://img.shields.io/badge/minSdk-26-brightgreen)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

## Features

- **Embedded Light Client** — Rust CKB light client runs natively on-device via JNI
- **BIP39 Mnemonic** — 12-word seed phrase generation, backup verification, and import/recovery
- **Biometric Auth** — Fingerprint/face unlock with 6-digit PIN fallback and lockout protection
- **Hardware-Backed Encryption** — Private keys and mnemonic encrypted with TEE/StrongBox AES-256-GCM
- **Mainnet Ready** — Address network validation, transaction size checks, retry logic
- **Send & Receive CKB** — Full transaction lifecycle with real-time status polling
- **QR Code Scanner** — CameraX + ML Kit barcode scanning for addresses
- **Flexible Sync Modes** — New wallet, recent (30 days), full history, or custom block height
- **Transaction History** — Detailed views with confirmation tracking

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Android App (Kotlin)                      │
│              Jetpack Compose + Hilt + MVVM                  │
└──────────────────────────┬──────────────────────────────────┘
                           │ JNI (in-process)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              Rust CKB Light Client (libckb)                  │
│            Embedded native library (.so)                     │
└──────────────────────────┬──────────────────────────────────┘
                           │ P2P Network
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                  CKB Mainnet Network                         │
│                   (15 bootnodes)                             │
└─────────────────────────────────────────────────────────────┘
```

Data flows unidirectionally: UI observes StateFlow from ViewModels. ViewModels call Repository suspend functions. Repository delegates to the JNI bridge or local crypto classes. The light client syncs directly with CKB mainnet peers — no intermediary server.

## Project Structure

```
pocket-node/
├── android/                         # Android project root
│   ├── app/
│   │   ├── build.gradle.kts         # Build config, dependencies, signing
│   │   ├── proguard-rules.pro       # R8 rules (log stripping, JNI keeps)
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── assets/mainnet.toml   # Light client config (bootnodes, RPC)
│   │       └── java/com/rjnr/pocketnode/
│   │           ├── data/
│   │           │   ├── auth/          # AuthManager, PinManager
│   │           │   ├── crypto/        # Blake2b wrapper
│   │           │   ├── gateway/       # GatewayRepository (central hub)
│   │           │   ├── transaction/   # TransactionBuilder
│   │           │   ├── validation/    # NetworkValidator
│   │           │   └── wallet/        # KeyManager, MnemonicManager, AddressUtils
│   │           ├── di/                # Hilt dependency injection
│   │           └── ui/screens/        # Compose screens (Home, Send, Receive, etc.)
│   └── gradle/libs.versions.toml     # Version catalog
├── external/
│   └── ckb-light-client/             # Rust CKB light client (JNI library)
└── docs/                             # Specs and implementation docs
```

## Prerequisites

- **Android Studio** (latest stable)
- **JDK 17**
- **Android SDK** (min 26, target 35, compile 36)
- **Rust toolchain** with Android cross-compilation targets:
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
  ```
- **Android NDK** (auto-detected or at `~/Library/Android/sdk/ndk/`)

## Build & Run

```bash
cd android

# Debug build (skips JNI library compilation if already built)
./gradlew assembleDebug

# Release build (R8 minification + resource shrinking)
./gradlew assembleRelease

# Install on connected device
./gradlew installDebug
```

The Gradle `preBuild` task automatically triggers the Cargo build for the Rust JNI library. To skip it during Kotlin-only development:

```bash
./gradlew assembleDebug -x cargoBuild
```

## Sync Modes

| Mode | Description | Use Case |
|------|-------------|----------|
| **New Wallet** | Sync from current tip only | Fresh wallets with no history |
| **Recent** (default) | Last ~30 days (~200K blocks) | Most users |
| **Full History** | From genesis block | Complete transaction history |
| **Custom** | Specific block height | Advanced users |

## Security Model

| Layer | Protection |
|-------|-----------|
| Key Storage | AES-256-GCM via Android Keystore (TEE/StrongBox-backed) |
| Mnemonic | Encrypted in EncryptedSharedPreferences, never logged |
| App Access | Biometric (BIOMETRIC_STRONG) + 6-digit PIN with lockout |
| Backup | `allowBackup=false` — prevents ADB key extraction |
| Release Builds | All `Log.*` calls stripped by ProGuard |
| Transaction Signing | Performed locally — private keys never leave the device |
| Network Validation | Addresses validated against expected network before sending |

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.1.0 |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt 2.57.2 |
| State | StateFlow + MutableStateFlow |
| Serialization | kotlinx.serialization 1.8.0 |
| Blockchain | JNI bridge to Rust CKB light client |
| Storage | EncryptedSharedPreferences, Room 2.8.4 |
| Crypto | CKB SDK Java 4.0.0, BouncyCastle 1.70, secp256k1-kmp 0.21.0 |
| Auth | AndroidX Biometric 1.1.0, Blake2b PIN hashing |
| Camera | CameraX 1.4.2 + ML Kit Barcode 17.3.0 |
| Build | AGP 8.13.2, Gradle Kotlin DSL |

## CKB-Specific Notes

- CKB uses a **Cell (UTXO-like) model**, not an account model
- Minimum cell capacity: **61 CKB** (6,100,000,000 shannons)
- 1 CKB = 100,000,000 shannons (8 decimal places)
- Lock script: secp256k1-blake160 (`0x9bd7...cce8`)
- BIP44 derivation path: `m/44'/309'/0'/0/0`
- Default transaction fee: 100,000 shannons (0.001 CKB)

## Roadmap

- [x] Core wallet functionality (send, receive, balance)
- [x] Transaction history with confirmations
- [x] QR code scanning
- [x] Flexible sync modes
- [x] BIP39 mnemonic backup/recovery
- [x] Biometric + PIN authentication
- [x] Mainnet production hardening
- [x] CI/CD pipeline
- [ ] Testnet support with network switching
- [ ] Nervos DAO integration (deposit, withdraw)
- [ ] Multiple wallet support
- [ ] Google Play Store release

## DAO Grant

This project is funded by a [CKB Community DAO grant](https://talk.nervos.org/t/dis-mobile-ready-ckb-light-client-pocket-node-for-android/9879) ($15,000 over 4 months):

| Milestone | Scope | Status |
|-----------|-------|--------|
| M1 | Mainnet ready, BIP39, biometrics, PIN, CI/CD | In Progress |
| M2 | Nervos DAO integration | Planned |
| M3 | Multi-wallet, sync optimization | Planned |
| M4 | Address book, Play Store launch | Planned |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, code style, and PR guidelines.

## Security

See [SECURITY.md](SECURITY.md) for the security model and vulnerability reporting process.

## License

This project is licensed under the MIT License — see [LICENSE](LICENSE) for details.

## Resources

- [Nervos CKB Documentation](https://docs.nervos.org/)
- [CKB Light Client](https://github.com/nervosnetwork/ckb-light-client)
- [CKB SDK Java](https://github.com/nervosnetwork/ckb-sdk-java)
- [CKB Explorer (Mainnet)](https://explorer.nervos.org/)

## Acknowledgments

- Neon and Matt
- Nervos Foundation for the CKB SDK and light client
- CKBuilders Cohort

