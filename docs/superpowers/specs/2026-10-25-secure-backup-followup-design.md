# Secure backup follow-up

User approved proceeding with the previewed backup UI and the signed upgrade defect on 2026-10-25. Scope is the backup flow, not every dialog in the app audit.

## Design and approaches
Use the established native secure modal presentation for recovery phrase reveal, private-key reveal, three-word quiz, and its errors; retain the existing public Compose entry/completion screen styling. The approved preview is /Users/raheemjnr/Documents/ChatGPT/pocket/artifacts/passphrase-ui-current/backup-quiz-preview.html. Native modal reuse is preferred to a Compose secret surface (would violate the existing security boundary) or keeping platform AlertDialog (the visual defect).
Phrase reveal: numbered two-column grid in reading order, short warning, primary “I’ve written it down” when verification is requested; read-only reveals close without marking a backup. Support 12/24 words and large text scrolling.
Quiz: three random distinct positions with four shuffled distinct choices per position, including repeated-word phrases; compact cards/two-column choices where space allows, selection state and “N of 3 selected”, primary “Verify backup” disabled until all selected. Incorrect choices give clear retry feedback and reset selections. Match approved rounded modal/current light-dark theme; all actions reachable on narrow/landscape/large text. No invented drag behavior.
Private key: readable wrapping key, account-only backup caveat, timed-copy action with feedback, Close. Never claim a verified backup for a viewed/copied private key. Cancel/back/outside/background must never complete verification.
Native secret widgets remain FLAG_SECURE, excluded from saved state/autofill/content capture, cleared on disposal/cancel/background/reauth lock. Do not expose secrets to Compose, logging or saved state. Keep operations/session ownership.

## Upgrade defect
Official signed1.8.5->1.8.6 smoke: first backup after legacy protection and enabling optional device unlock rejects correct quiz with “Wallet protection changed”; fresh retry succeeds. WalletVaultCoordinator.enroll captures envelope/row before updateDeviceWrap; returned witness references pre-wrap bytes, which WalletBackupWitness.matches correctly rejects.
Fix must bind the successful operation's witness to the post-mutation state authenticated by that operation. Preserve exact concurrent wallet/marker/profile/envelope checks; do not weaken matches or blindly recapture potentially unrelated state. Also consider cleanup/unrecovered replacement paths that mutate state during an authenticated read. Add regression tests for first backup with device wrap and retained rejection on intervening mutation. No crypto format change.

## Delivery constraints
Use isolated /tmp/pocket-node-576-fix branch from main45153718. Do not change user's release checkout, build/sign/publish a release, alter version numbers or merge without repository checks and review. Debug builds allowed; reuse native artifacts (-x cargoBuild). Focused tests plus independent review and emulator UI smoke. User must later rebuild signed candidate and repeat official upgrade. Broader UI audit remains future work.
