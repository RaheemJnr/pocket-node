import Foundation
import PocketNodeCore

/// Backs the Receive screen: the active wallet's address for the currently
/// selected network, and whether to nudge the user to back up an
/// un-backed-up mnemonic wallet before they rely on this address.
@MainActor
@Observable
final class ReceiveViewModel {
    private(set) var address: String = ""
    private(set) var showBackupPrompt = false

    private let walletStore: WalletStore
    private let preferences: any NetworkPreferences

    /// Called when the user taps "Back up now" on the protect dialog. The
    /// caller supplies navigation to ``BackupView``; this view model does not
    /// know how to get there.
    let onBackUp: () -> Void

    init(walletStore: WalletStore, preferences: any NetworkPreferences, onBackUp: @escaping () -> Void) {
        self.walletStore = walletStore
        self.preferences = preferences
        self.onBackUp = onBackUp
        refresh()
    }

    /// Re-reads the wallet record and the selected network. Call on
    /// `.onAppear` so a network switch or a backup completed elsewhere is
    /// reflected without re-creating the view model.
    func refresh() {
        guard let record = walletStore.load() else {
            address = ""
            showBackupPrompt = false
            return
        }
        address = Self.address(for: record, network: preferences.getSelectedNetwork())
        // Only a mnemonic wallet has a phrase to back up; a raw-key wallet
        // has nothing this prompt could send it to back up.
        showBackupPrompt = !record.mnemonicBackedUp && record.type == "mnemonic"
    }

    /// Dismisses the protect dialog without navigating anywhere ("Not now").
    func dismissBackupPrompt() {
        showBackupPrompt = false
    }

    /// Dismisses the protect dialog and calls ``onBackUp`` ("Back up now").
    func backUpNow() {
        showBackupPrompt = false
        onBackUp()
    }

    private static func address(for record: WalletRecord, network: NetworkType) -> String {
        network == .mainnet ? record.mainnetAddress : record.testnetAddress
    }
}
