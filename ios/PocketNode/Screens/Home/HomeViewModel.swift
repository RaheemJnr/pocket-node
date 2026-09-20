import Foundation
import PocketNodeCore

/// Backs the wallet shell's first screen: which wallet is active, its address
/// on the selected network, and whether the backup nag belongs on screen.
///
/// No balance and no activity yet, those are M3. Everything here is read back
/// from `WalletStore` on demand rather than cached, so a backup completed on
/// another screen, or a network switched elsewhere, shows up on the next
/// ``refresh()`` without this object subscribing to anything.
@MainActor
@Observable
final class HomeViewModel {
    private(set) var walletName = ""
    private(set) var address = ""

    /// True only for a mnemonic wallet whose phrase has not been verified. A
    /// raw-key wallet has no phrase, so there is nothing the banner could send
    /// the user to do. The same condition as `ReceiveViewModel.showBackupPrompt`.
    private(set) var needsBackup = false

    private let walletStore: WalletStore
    private let preferences: any NetworkPreferences

    init(walletStore: WalletStore, preferences: any NetworkPreferences) {
        self.walletStore = walletStore
        self.preferences = preferences
        refresh()
    }

    /// Re-reads the wallet record and the selected network. Called from the
    /// view's `onAppear`, which covers both the first draw and coming back
    /// from a pushed screen.
    func refresh() {
        guard let record = walletStore.load() else {
            walletName = ""
            address = ""
            needsBackup = false
            return
        }
        walletName = record.name
        address = preferences.getSelectedNetwork() == .mainnet
            ? record.mainnetAddress
            : record.testnetAddress
        needsBackup = !record.mnemonicBackedUp && record.type == WalletCreator.typeMnemonic
    }

    /// The address as the shell shows it: head, ellipsis, tail. A CKB address
    /// is too long to read on a phone, and the two ends are what a user checks
    /// against another screen.
    var shortAddress: String { Self.shortened(address) }

    static let headLength = 12
    static let tailLength = 8

    /// Short form of `address`. Anything no longer than the two ends put
    /// together comes back whole: abbreviating it would hide characters
    /// without making the line any shorter.
    static func shortened(_ address: String) -> String {
        guard address.count > headLength + tailLength else { return address }
        return "\(address.prefix(headLength))...\(address.suffix(tailLength))"
    }
}
