# Wallet Protection UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Implement the approved modal-sheet UI and prevention education without changing wallet security behavior.
**Architecture:** A shared native secure sheet renders existing suspend prompt interfaces. Compose presentation components group existing settings and recovery actions without altering their ViewModels or persistence.
**Tech Stack:** Kotlin, native Android Dialog/EditText, existing Compose Material 3, Robolectric.
**Spec:** docs/superpowers/specs/2026-10-25-wallet-protection-ui-design.md

## Global Constraints
- No release builds, signing, publishing or key/storage format changes.
- Preserve native-only owned credential buffers, FLAG_SECURE, lifecycle cancellation, autofill/learning exclusion and verification gates.
- Keep old backups, explicit destructive-capability consents and read-only recovery intact.
- Light blue, dark Pocket Green, existing Inter fonts; no dependency additions.

## Review Focus
- Backgrounding/re-authentication while a secret is revealed must clear fields and cancel delivery.
- Large fonts and keyboard must leave all actions reachable.
- Consent negative selection is not the same as dismissal; preserve null/false semantics.
- Hidden alternative recovery methods must remain reachable during pending cleanup.
- Education cannot mark a backup or wallet migration completed.

### Task 1: Secure modal inputs and consent
Files: create ui/security/SecureWalletSheet.kt; modify WalletPassphraseDialog.kt, RecoverySecretDialog.kt, MainActivity.kt and their tests.
Interface: shared Dialog with native content, positive/negative button IDs, secure fields and theme owner. Existing VaultPrompt and RecoverySecretDialog public signatures remain unchanged.
- [x] Adapt existing tests to generic Dialog and add reveal/error assertions; run focused tests and observe expected failure.
- [x] Implement sheet and migrate prompts; simplify coordinator setup/unlock copy without changing operations.
- [x] Run focused lifecycle/prompt/coordinator tests, debug compile and inspect modal accessibility.

### Task 2: Education and grouped settings/recovery
Files: create ui/security/WalletProtectionEducation.kt and settings/WalletProtectionSettings.kt; modify SecuritySettingsScreen.kt, RecoveryScreen.kt, WalletSettingsScreen.kt and recovery UI tests.
Interface: reusable education Composable; settings content receives existing state and callbacks. No new persisted completion flags.
- [x] Add recovery alternative-expansion and education visibility tests; run red.
- [x] Implement grouped cards, expandable alternatives, status/progress copy, and education at settings/backup entry points.
- [x] Run focused tests then full Android unit suite and debug build (skip existing native rebuild).

### Task 3: Visual verification and review
- [x] Inspect debug UI on preserved public-fixture emulator, both themes, keyboard/scroll and cancellation. No real secrets or release artifacts.
- [x] Fresh independent review of final diff; resolve material findings and rerun affected checks.
- [ ] Record evidence, commit and open PR; merge only after review/checks meet prior user instructions.
