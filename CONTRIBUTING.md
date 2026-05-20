# Contributing to Pocket Node

Thank you for your interest in contributing to Pocket Node! This document covers the development setup, coding conventions, and PR process.

## Prerequisites

- **Android Studio** (latest stable)
- **JDK 17**
- **Android SDK** (min SDK 26, target SDK 35, compile SDK 36)
- **Rust toolchain** with Android cross-compilation targets (for JNI library changes):
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
  ```
- **Android NDK** (auto-detected by Gradle or at `~/Library/Android/sdk/ndk/`)

## Development Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/RaheemJnr/pocket-node.git
   cd pocket-node
   ```

2. Open the `android/` directory in Android Studio.

3. Build and run:
   ```bash
   cd android
   ./gradlew assembleDebug -x cargoBuild  # Skip JNI build for Kotlin-only work
   ./gradlew installDebug                  # Install on connected device
   ```

4. Run tests:
   ```bash
   cd android
   ./gradlew test -x cargoBuild
   ```

## Code Style

### Kotlin

- Follow standard [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use `@Serializable` with `@SerialName("snake_case")` for JSON models
- Use `Result<T>` with `runCatching {}` for error handling in repositories
- Use `StateFlow` for observable state in ViewModels
- Prefer `_uiState.update { it.copy(...) }` for state mutations

### Jetpack Compose

- One `@Composable` screen function per file (e.g., `HomeScreen.kt`)
- Use `hiltViewModel()` for ViewModel injection
- Use `collectAsState()` to observe StateFlow
- Material 3 components exclusively (no XML layouts)

### Naming

- ViewModels: `{Screen}ViewModel` with `{Screen}UiState` data class
- Screens: `@Composable fun {Name}Screen()`
- Packages: `com.rjnr.pocketnode.{layer}.{feature}`
- Branches: `feature/{issue-number}-short-description`

## Branch Workflow

1. Create a feature branch from `main`:
   ```bash
   git checkout -b feature/{issue-number}-short-description
   ```

2. Make your changes and commit:
   ```bash
   git commit -m "feat: brief description of change"
   ```

3. Push and open a PR against `main`:
   ```bash
   git push -u origin feature/{issue-number}-short-description
   ```

### Commit Messages

Use conventional-style prefixes:

- `feat:` — new feature
- `fix:` — bug fix
- `refactor:` — code restructuring without behavior change
- `chore:` — build, CI, dependency updates
- `docs:` — documentation only

## Pull Requests

- Link the related issue in the PR description
- Describe what changed and why
- Ensure CI passes (build + tests)
- Keep PRs focused — one issue per PR when possible

## Manual Sync-Stall Smoke (#150)

The automated test suite covers the registration path and the detector logic in isolation. The end-to-end behavior — peers connecting, blocks streaming, the UI updating — needs a real device against the live network. Run this once before any release that touches `SyncCoordinator`, `SyncProgressTracker`, `SyncStallDetector`, or the polling loop in `GatewayRepository`.

### Setup

1. Pick a **testnet** seed phrase whose first activity is at least ~3M blocks back (old enough that the light client must actually scan past warm-up before the first balance hit). If you don't have one, request testnet CKB to a fresh address from the faucet, wait a day, then archive that seed for reuse.
2. Install the debug build: `./gradlew installDebug`.
3. In the app: **Settings → Network → Testnet** (if not already there). Confirm the app restarts on testnet.
4. Import the test seed via **Wallets → Add wallet → Import recovery phrase**.
5. When the post-import sync sheet appears, pick **From a specific date** and enter a block height ~6 months before the wallet's first known activity. (This forces a long scan — the exact pathology V.bit reported.)

### Observe (30 min)

Keep `adb logcat` open in a second terminal, filtered:

```bash
adb logcat -s SyncCoordinator:I GatewayRepository:I HomeViewModel:D
```

Watch for:

- [ ] `SyncCoordinator: setScripts cmd=… walletId=… startBlock=…` fires once with the block height you entered (hex form). If `startBlock=0` despite picking CUSTOM, **stop** — that is the regression Step 4's test was supposed to catch.
- [ ] `GatewayRepository: syncPoll synced=X tip=Y delta=Z progress=P` fires every 5s. `synced` must advance over time, even if slowly.
- [ ] The Home screen's percentage starts at a non-zero value within a poll cycle or two (Step 3 truthful baseline). If it stays at 0% for >1 minute while logcat shows `synced` advancing, the baseline seed is broken.
- [ ] After ~5 min of no `synced` advance (rare on testnet, simulate by enabling airplane mode mid-sync), the stall banner appears with "Sync hasn't progressed in N min".
- [ ] Tap **Use Recent**. The banner clears, the sync mode flips to RECENT, polling resumes from the recent-checkpoint, and balance shows within ~2 min.

### Failure modes to flag

| Symptom | Likely cause |
|---------|--------------|
| `startBlock=0` after picking CUSTOM | `WalletPreferences.getCustomBlockHeight` regression or `SyncCoordinator` lookup path broken |
| UI stuck at 0% while logcat advances | `SyncProgressTracker.seedStartHeight` not being called on wallet load |
| Banner never fires after airplane-mode test | `SyncStallDetector` baseline reset on every emission, or `showSyncStallBanner` not wired into `HomeUiState` |
| "Use Recent" button does nothing | `changeSyncMode(RECENT)` short-circuited by `currentSyncMode` guard (#108) — re-check the guard's intent |

If anything in the checklist fails, open an issue with the logcat trace attached.

## Security

- Never commit secrets, private keys, or keystore files
- Never log sensitive data (private keys, mnemonics, PINs)
- Release builds strip all `Log.*` calls via ProGuard — but still avoid logging sensitive data in source
- See [SECURITY.md](SECURITY.md) for the vulnerability reporting process

## Questions?

Open an issue or start a discussion on the repository.
