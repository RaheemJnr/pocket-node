# Secure Backup Follow-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** Modernize the native backup flow and remove false first-backup failure after device enrollment.
**Architecture:** Native secure sheet widgets retain secret/session ownership; backup witnesses remain bound to authenticated mutations.
**Tech Stack:** Kotlin Android native Views, Compose public screens, Robolectric/coroutines tests.
**Spec:** docs/superpowers/specs/2026-10-25-secure-backup-followup-design.md

## Global Constraints
- Preserve FLAG_SECURE, secret cleanup, no saved-state/autofill/content-capture secrets and native operation ownership.
- Preserve exact witness race checks; no crypto/storage format changes.
- No release build/sign/publish or main mutations; no version changes.
- Approved backup UI only; broader audit is out of scope.

## Review Focus
- Enabling device unlock changes envelope during enrollment: first correct quiz succeeds.
- Concurrent credential/profile/marker changes still prevent backup marking.
- Repeated words still get three distinct positions and nontrivial decoys.
- Background/Back/cancellation clears all sensitive widgets and never marks complete.
- 24-word phrases, landscape, 320dp and1.8x font maintain reachable actions.

### Task 1: Secure backup presentation and bound witness
**Files:** WalletSecretDialog.kt, SecureWalletSheet.kt (extend carefully or add focused native backup view file), WalletVaultCoordinator.kt and relevant WalletVaultStore.kt APIs only if necessary. Existing WalletSecretDialogTest, WalletBackupOperationsTest, WalletVaultCoordinator/backup witness tests; add focused regression cases.
**Interfaces:** Preserve WalletSecretDialog.show(activity, secret:CharArray, mnemonic:Boolean, verify:Boolean):Boolean and WalletBackupOperations public outcome/API. Keep callers unchanged.
- [x] Add failing regression for successful legacy enrollment+device wrap whose witness can mark backup; assert concurrent mutation still rejects. Run targeted tests RED.
- [x] Implement minimal operation-bound witness fix, covering own cleanup/replacement mutations without accepting concurrent unrelated state. Run focused tests GREEN.
- [x] Add/adjust native UI tests for grid/quiz selection/error/private-key-copy semantics, masking boundaries, 24words, large text/action reachability and lifecycle cancellation. Record meaningful baseline/red evidence.
- [x] Implement approved native secure modal presentation using existing theme infrastructure. Preserve all behavior and first-try verification semantics.
- [x] Run affected native UI/coordinator/repository tests and :app:assembleDebug -x cargoBuild with2workers/2GB Gradle heap/JBR17. No unrelated full crypto suite unless justified.
- [x] Self-review git diff --check and commit only named source/tests/spec/plan files. Keep .superpowers scratch untracked/ignored (previous accidental tracking must not recur).
- [ ] Independent task review, whole-branch review, debug emulator UI verification, push PR for external review. No merge or signed release claim.
