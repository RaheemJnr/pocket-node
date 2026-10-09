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

    /// The sync layer, or nil in the tests and previews that do not have one.
    /// Read through rather than mirrored: `SyncService` is `@Observable`, so a
    /// view that reads one of the properties below is subscribed to it.
    private let sync: (any SyncStatusProviding)?

    init(
        walletStore: WalletStore,
        preferences: any NetworkPreferences,
        sync: (any SyncStatusProviding)? = nil
    ) {
        self.walletStore = walletStore
        self.preferences = preferences
        self.sync = sync
        refresh()
    }

    /// Re-reads the wallet record and the selected network. Called from the
    /// view's `onAppear`, which covers both the first draw and coming back
    /// from a pushed screen.
    func refresh() {
        refreshBalance()
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

    // MARK: - Sync

    /// Whether the sync card belongs on screen at all. False only where no
    /// sync layer was injected.
    var showsSync: Bool { sync != nil }

    /// How far along this wallet's chain sync is.
    var syncStatus: SyncStatus { sync?.status ?? SyncStatus() }

    /// The mode the user picked, or nil if they never have.
    var syncMode: SyncMode? { sync?.mode }

    /// True while the wallet has no sync mode, which is the one state the card
    /// asks the user to resolve. Android reaches the same prompt from its
    /// post-import sheet.
    var needsSyncMode: Bool { showsSync && syncMode == nil }

    /// Whether the light client has been handed this wallet's lock script.
    var isRegistered: Bool { sync?.isRegistered ?? false }

    /// Whether the sync poll has reported anything yet.
    ///
    /// A fresh `SyncStatus` is all zeros, and `isSyncing` is one of those
    /// zeros, so "not started" and "caught up" are the same value. Without this
    /// the card claims Synced from the first frame, before the node has even
    /// come up. A tip of zero is the tell: the chain's tip is never zero once a
    /// poll has landed.
    var hasSyncReading: Bool { syncStatus.tipBlockNumber > 0 }

    /// The last sync failure worth telling the user about.
    var syncError: String? { sync?.lastError }

    // MARK: - Balance

    /// The wallet's spendable balance, cached-first.
    var balance: BalanceStatus { sync?.balance ?? BalanceStatus() }

    /// The balance as the card draws it, e.g. `"12,345.60 CKB"`.
    ///
    /// Formatted by the shared core from shannons, never through a Swift
    /// `Double`: a balance the user cannot reconcile against an explorer is
    /// worse than no balance.
    var balanceText: String { "\(balance.formatted) CKB" }

    /// True while the number on screen came out of the cache and the live read
    /// has not answered yet. The card puts a quiet line under the number.
    var isBalanceCached: Bool { balance.hasValue && balance.isCached }

    /// False until any balance read lands, which is what lets the card tell
    /// "nothing yet" from a real zero.
    var hasBalance: Bool { balance.hasValue }

    /// Re-read the balance. The view calls it on appear; the sync poll calls it
    /// itself after every reading that moved.
    func refreshBalance() {
        sync?.refreshBalance()
    }

    /// Run sync activation again. What the card's Retry does after the light
    /// client failed to start.
    func retrySync() {
        sync?.retry()
    }

    /// Apply a sync mode the user picked in the sheet.
    func chooseSyncMode(_ mode: SyncMode, customBlockHeight: Int64?) async -> Bool {
        guard let sync else { return false }
        return await sync.choose(mode: mode, customBlockHeight: customBlockHeight)
    }

    // MARK: - Address

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
