import PocketNodeCore

@testable import PocketNode

/// A `SyncStatusProviding` with no Kotlin behind it.
///
/// `SyncService` opens a Room database and starts a poll loop in its
/// initializer, neither of which a view-model test wants. This stands in for it
/// so the tests below can put the sync layer in any state and check what Home
/// decides to show.
@MainActor
final class FakeSyncStatusProvider: SyncStatusProviding {
    var status: SyncStatus
    var mode: SyncMode?
    var isRegistered: Bool
    var lastError: String?
    var balance: BalanceStatus

    /// How many times ``refreshBalance()`` was called.
    private(set) var balanceRefreshCount = 0

    /// Every `(mode, height)` pair handed to ``choose(mode:customBlockHeight:)``.
    private(set) var chosen: [(SyncMode, Int64?)] = []
    /// What the next choice answers.
    var acceptsChoice = true
    /// How many times ``retry()`` was called.
    private(set) var retryCount = 0

    init(
        status: SyncStatus = SyncStatus(),
        mode: SyncMode? = nil,
        isRegistered: Bool = false,
        lastError: String? = nil,
        balance: BalanceStatus = BalanceStatus()
    ) {
        self.status = status
        self.mode = mode
        self.isRegistered = isRegistered
        self.lastError = lastError
        self.balance = balance
    }

    func choose(mode: SyncMode, customBlockHeight: Int64?) async -> Bool {
        chosen.append((mode, customBlockHeight))
        guard acceptsChoice else { return false }
        self.mode = mode
        return true
    }

    func retry() {
        retryCount += 1
        lastError = nil
    }

    func refreshBalance() {
        balanceRefreshCount += 1
    }
}
