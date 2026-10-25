### Task 1: Backup entry and reviewed navigation fixes
**Files:** MnemonicBackupScreen.kt, RecoveryScreen.kt, WalletProtectionSettings.kt, NavGraph.kt; corresponding focused tests under onboarding/recovery/settings/navigation. Optional internal navigation helper to exercise same production NavOptions in a lightweight real NavController graph test.
**Interfaces:** Keep existing screen public signatures/callbacks. Native operations unchanged.
- [ ] Write RED regressions for restoration with empty list while complete() deferred; biometric selected/reselection callback; recovery exit back-stack from startup and Settings routes; backup entry callback/back/loading/error/success and phrase/key truthful copy.
- [ ] Run targeted tests, confirm failures match missing behaviors, not test harness geometry.
- [ ] Implement minimal reviewed fixes and backup layout using approved visual language. Do not access or display secrets in tests or screenshots.
- [ ] Run affected tests including MnemonicBackupViewModelTest, RecoveryScreenNavigationTest, WalletProtectionSettingsTest, new backup/navigation tests, plus debug assemble -x cargoBuild. Expect green, no new test dependencies.
- [ ] Commit exact task files and forced-added docs; exclude generated caches. Record tests, verification of comments and any limits in report.
- [ ] Independent review; emulator smoke of public entry, back and repeat recovery exit, capture backup screen. Push reviewed changes to existing PR581; no merge.
