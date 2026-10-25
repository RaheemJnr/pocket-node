# Backup Screen and Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** Repair backup entry layout and three verified PR581 navigation/state regressions.
**Architecture:** Preserve ViewModel/security operations and route destinations; update public Compose presentation and recovery NavOptions. Extract internal testable content/helpers only where necessary for real behavior tests.
**Tech Stack:** Kotlin Compose Material3, existing Robolectric test setup.
**Spec:** docs/superpowers/specs/2026-10-25-backup-review-fixes.md

## Global Constraints
- Preserve authentication/native secret boundaries, wallet IDs, disposal cancellation and verified success semantics.
- No crypto/storage/dependencies, release build/signing, merge or publication changes.
- Retain approved theme colours; scroll, insets, error/retry/busy/back and truthful phrase/key copy.
- Fix all three verified CodeRabbit behaviors without changing completion destination selection.

## Review Focus
- Empty wallets while operations.complete suspends must remain RESTORING and cannot prematurely exit via Done.
- Recovery entered from settings repeatedly must not accumulate Main or SecuritySettings; startup Recovery must still exit safely.
- Saved BIOMETRIC_ONLY must display a selected option and be selectable after changing preference.
- Unknown material/private-key backup must not be falsely labeled verified phrase backup.
- Small screen/large text, busy cancellation, errors and route-scoped wallet authentication remain usable.

### Task 1: Backup entry and reviewed navigation fixes
**Files:** MnemonicBackupScreen.kt, RecoveryScreen.kt, WalletProtectionSettings.kt, NavGraph.kt; corresponding focused tests under onboarding/recovery/settings/navigation. Optional internal navigation helper to exercise same production NavOptions in a lightweight real NavController graph test.
**Interfaces:** Keep existing screen public signatures/callbacks. Native operations unchanged.
- [ ] Write RED regressions for restoration with empty list while complete() deferred; biometric selected/reselection callback; recovery exit back-stack from startup and Settings routes; backup entry callback/back/loading/error/success and phrase/key truthful copy.
- [ ] Run targeted tests, confirm failures match missing behaviors, not test harness geometry.
- [ ] Implement minimal reviewed fixes and backup layout using approved visual language. Do not access or display secrets in tests or screenshots.
- [ ] Run affected tests including MnemonicBackupViewModelTest, RecoveryScreenNavigationTest, WalletProtectionSettingsTest, new backup/navigation tests, plus debug assemble -x cargoBuild. Expect green, no new test dependencies.
- [ ] Commit exact task files and forced-added docs; exclude generated caches. Record tests, verification of comments and any limits in report.
- [ ] Independent review; emulator smoke of public entry, back and repeat recovery exit, capture backup screen. Push reviewed changes to existing PR581; no merge.
