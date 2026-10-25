# Backup screen polish and PR581 review fixes

User requests fixing the supplied sparse backup screen and CodeRabbit comments. Continue the previously approved Material colour palette, grouped guidance and focused flows. Existing prototype approval supplies the visual language; this is a screen repair, not a new security design.

Backup entry: app-bar Back, concise guidance in a rounded card with short steps (authenticate; record recovery material; verify phrase words where applicable), clear external-backup/privacy reminder, full-width primary action. Scrollable content and safe insets for small screens/large text. Loading shows progress and prevents duplicate start; cancel/back remain usable through existing callbacks. Errors remain visible and retryable. Success only follows existing verified operation result; distinguish phrase and key when known; do not assume unknown material is a phrase. No secrets enter Compose; keep native WalletBackupOperations, selected wallet identity and disposal cancellation intact. Avoid decorative filler. Retain route/API compatibility and onNavigateToHome behavior.

Verify/fix three PR581 comments: recovery exits clear old stack and use singleTop, preserving complete->destinationAfterWalletReady and readOnly->Main; RESTORING cannot enter empty-list Done state; unlock picker includes BIOMETRIC_ONLY matching saved summary and callback.

No crypto/storage/dependency changes. No release build, merge or signing. Add observable regression tests and use affected tests/debug assembly only; full unrelated expensive KDF suite already ran last turn.
