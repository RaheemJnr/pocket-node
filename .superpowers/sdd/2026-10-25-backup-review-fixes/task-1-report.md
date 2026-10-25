# Task 1 implementation report

Implemented the approved backup entry repair and all three verified review comments. Public screen signatures, route wallet IDs, native authentication/secret operations and disposal cancellation remain intact.

## Changes
- Backup entry now has app-bar Back, short numbered guidance in a rounded Material card, shared privacy notice, full-width primary action, scrolling and consumed scaffold insets. Busy state disables start, shows progress and offers Cancel; errors support retry. Phrase/key/unknown copy stays accurate; only verified phrase results claim phrase verification.
- Recovery's empty-wallet Done state is limited to RESTORE_FAILED. A deferred completion check remains RESTORING with progress/cancel and disabled read-only exit.
- Both recovery callbacks use the same internal exitRecovery helper. It clears the root graph history and enables singleTop, retaining the existing completion destination selection and read-only Main destination.
- Unlock selection includes BIOMETRIC_ONLY and preserves selected state and preference callbacks.

## RED → GREEN evidence
- Extracted existing recovery NavOptions unchanged into a shared production helper before the navigation regression run.
- RED: 17 tests, 7 expected failures: Settings recovery old stack twice; missing backup short steps; missing busy Cancel; unknown material incorrectly labeled verified phrase; empty-list RESTORING hidden; missing selected biometrics-only choice. Startup exits and existing callback tests passed. Log: task-1-red.log.
- Initial test-only scroll assumption was removed because the old screen had no scroll parent; semantic clicks keep routing tests independent of Robolectric geometry. Radio matching explicitly requires selection semantics so the overview summary cannot be mistaken for an option.
- GREEN: 24 affected tests, zero failures/errors. RecoveryNavigationTest 3; RecoveryScreenNavigationTest 5; WalletProtectionSettingsTest 4; MnemonicBackupScreenTest 7; MnemonicBackupViewModelTest 5. Log: task-1-green.log.
- Extra screen coverage checks 320dp width at 1.8x font size, reachable full-width primary action, visible app-bar Back, and operation cancellation on composition disposal.
- Debug assembly succeeded in the same final invocation; git diff --check passed.

Final command (JAVA_HOME=/Users/raheemjnr/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home):
```
./android/gradlew -p android :app:testDebugUnitTest --tests '*RecoveryNavigationTest' --tests '*RecoveryScreenNavigationTest' --tests '*WalletProtectionSettingsTest' --tests '*MnemonicBackupScreenTest' --tests '*MnemonicBackupViewModelTest' :app:assembleDebug -x cargoBuild --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2g'
```

## Rulings and limits
- Ruling: use affected tests and debug assembly only, as explicitly requested; no rerun of the unrelated expensive crypto/KDF suite. Cost: unrelated behavior is covered by prior verification, not a fresh full-suite run here.
- No dependencies added. No changes to native secure UI, crypto, storage, release signing, or publication.
- UI tests use semantic actions for routing and public metadata/state only. Real touch interaction and screenshot review are assigned to the parent agent's emulator pass. No secrets were accessed in these tests.
- Independent review, emulator smoke/screenshot and push remain with the parent agent; this implementation report does not claim those are complete.
- Two verification invocation issues were corrected before the final pass: Gradle task-filter placement, then test-only DpRect width access. Neither required a production fix.
