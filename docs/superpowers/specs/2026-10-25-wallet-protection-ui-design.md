# Wallet protection UI and education

The user approved the side-by-side modal-sheet prototype on 25 October 2026 and explicitly requested implementation plus education for existing users. Preserve Pocket Node's light blue, dark Pocket Green and Inter typography.

## Approach and scope

Use secure native bottom-aligned modal sheets for credentials and consent, and existing Compose Material 3 cards/rows for settings and recovery. Compared with restyling AlertDialog this provides the approved layout; compared with moving secrets into Compose it preserves the existing native-only credential ownership boundary. No new dependencies or cryptographic/storage changes.

Implement the approved setup, unlock, legacy PIN, external secret and consent families. Keep all caller-provided consent consequences and positive/negative meanings. Setup explains the independent passphrase, optional device authentication and external backup; everyday unlock is concise. Native fields remain masked by default, explicitly revealable, unsaved, excluded from autofill/learning and wiped before completion/cancellation/backgrounding. Screenshot protection remains enabled. Use scrollable content and keyboard resizing, labelled fields, 48dp controls and field-specific validation.

Group settings into preferred unlock method, passphrase actions, per-wallet device enrollment and existing dashboard controls. Preserve separate biometric/default and credential-only actions. Recovery prioritizes the passphrase, exposes alternate routes in an expandable group, preserves backup cleanup and read-only navigation. No recovery route or warning is deleted.

## Existing-user education

Show a reusable education card in security and wallet settings and concise guidance at passphrase setup. Explain: app PIN opens the dashboard; Android PIN/biometrics unlock the device or authorize optional device access; the independent wallet passphrase protects this installation. Existing users must complete verified protection before sensitive operations, as already enforced. Before removing/resetting the device lock or changing enrolled biometrics, complete wallet protection and verify an external backup. Passphrase protection does not survive uninstall, app-data clearing or device loss. Never imply that reading education means migration or backup verification succeeded.

For already inaccessible wallets, keep app data intact and try the surviving old-PIN backup, device key or external secret. A new passphrase cannot recreate lost material. Preserve unreadable backups and read-only access. Do not promise Josh recovery; emulator recovery with a surviving backup is not proof about his device.

## Verification and delivery

Retain existing lifecycle/owned-buffer tests; add modal semantics, masked reveal, validation and education/recovery-navigation tests. Run focused and full Android unit suites, debug compilation and public-fixture emulator UI checks. Review the final diff independently before merging. Do not build/sign/publish a release. User builds the release.
