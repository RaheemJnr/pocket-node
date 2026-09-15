import Foundation
import PocketNodeCore

/// Backs the Receive screen: the active wallet's address for the currently
/// selected network, and whether to nudge the user to back up an
/// un-backed-up mnemonic wallet before they rely on this address.
@MainActor
@Observable
final class ReceiveViewModel {
    private(set) var address: String = ""
    private(set) var selectedNetwork: NetworkType = .mainnet
    private(set) var showBackupPrompt = false
    private(set) var backupPromptMessage = ReceiveViewModel.backupNeededMessage

    private let walletStore: WalletStore
    private let preferences: any NetworkPreferences
    private let hasPin: () -> Bool

    /// Set once the protect dialog is dismissed ("Not now") or acted on
    /// ("Back up now"), and reset only by `init` (i.e. by re-creating this
    /// view model). Without it, `refresh()` — called again on every
    /// `.onAppear`, e.g. navigating back to Receive from elsewhere — would
    /// re-show a dialog the user already dismissed this session.
    private var promptDismissed = false

    /// Called when the user taps "Back up now" on the protect dialog. The
    /// caller supplies navigation to ``BackupView``; this view model does not
    /// know how to get there.
    let onBackUp: () -> Void

    init(
        walletStore: WalletStore,
        preferences: any NetworkPreferences,
        hasPin: @escaping () -> Bool,
        onBackUp: @escaping () -> Void
    ) {
        self.walletStore = walletStore
        self.preferences = preferences
        self.hasPin = hasPin
        self.onBackUp = onBackUp
        refresh()
    }

    /// The heading shown above the address, e.g. "CKB Testnet Address",
    /// mirroring Android's `ReceiveScreen` network label.
    var networkHeading: String {
        selectedNetwork == .mainnet ? "CKB Mainnet Address" : "CKB Testnet Address"
    }

    /// Re-reads the wallet record and the selected network. Call on
    /// `.onAppear` so a network switch or a backup completed elsewhere is
    /// reflected without re-creating the view model. Does not re-show a
    /// dialog the user already dismissed — see `promptDismissed`.
    func refresh() {
        selectedNetwork = preferences.getSelectedNetwork()

        guard let record = walletStore.load() else {
            address = ""
            showBackupPrompt = false
            return
        }
        address = Self.address(for: record, network: selectedNetwork)

        // Only a mnemonic wallet has a phrase to back up or a PIN gate worth
        // nudging toward; a raw-key wallet has nothing this prompt could send
        // it to. Matches Android's `!hasMnemonicBackup || !hasPinOrBiometrics`.
        guard !promptDismissed, record.type == "mnemonic" else {
            showBackupPrompt = false
            return
        }
        let needsBackup = !record.mnemonicBackedUp
        let needsPin = !hasPin()
        guard needsBackup || needsPin else {
            showBackupPrompt = false
            return
        }
        backupPromptMessage = needsBackup ? Self.backupNeededMessage : Self.pinNeededMessage
        showBackupPrompt = true
    }

    /// Dismisses the protect dialog without navigating anywhere ("Not now").
    func dismissBackupPrompt() {
        promptDismissed = true
        showBackupPrompt = false
    }

    /// Dismisses the protect dialog and calls ``onBackUp`` ("Back up now").
    func backUpNow() {
        promptDismissed = true
        showBackupPrompt = false
        onBackUp()
    }

    private static func address(for record: WalletRecord, network: NetworkType) -> String {
        network == .mainnet ? record.mainnetAddress : record.testnetAddress
    }

    static let backupNeededMessage =
        "You haven't backed up your recovery phrase yet. If you lose this device, your funds will be unrecoverable."
    static let pinNeededMessage =
        "You haven't set a PIN to protect this wallet yet. Set one so your funds are safe if you lose this device."
}
