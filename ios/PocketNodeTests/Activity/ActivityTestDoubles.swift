import Foundation
import PocketNodeCore

@testable import PocketNode

/// Builders for the Kotlin value types the activity screens read.
///
/// Kotlin data classes have no default arguments across the bridge, so every
/// parameter has to be spelled out at every call site. These put that in one
/// place so a test reads as the one field it is about.
enum ActivityFixtures {

    static func record(
        txHash: String = "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
        blockNumber: String = "0x1170ea8",
        blockHash: String = "0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
        timestamp: Int64 = 0,
        balanceChange: String = "0x5f5e100",
        direction: String = "out",
        confirmations: Int32 = 4,
        blockTimestampHex: String? = "0x18c8d0a7a00",
        isBulk: Bool = false,
        status: String = "CONFIRMED",
        feeShannons: Int64? = nil
    ) -> TransactionRecord {
        TransactionRecord(
            txHash: txHash,
            blockNumber: blockNumber,
            blockHash: blockHash,
            timestamp: timestamp,
            balanceChange: balanceChange,
            direction: direction,
            fee: "0x0",
            confirmations: confirmations,
            blockTimestampHex: blockTimestampHex,
            isDaoRelated: direction.hasPrefix("dao_"),
            isBulk: isBulk,
            status: status,
            feeShannons: feeShannons.map { KotlinLong(longLong: $0) }
        )
    }

    static func broadcast(
        txHash: String = "0xabc",
        state: String = "BROADCASTING",
        nullCount: Int32 = 0,
        createdAt: Int64 = 0
    ) -> PendingBroadcastRecord {
        PendingBroadcastRecord(
            txHash: txHash,
            state: state,
            reservedInputs: "[]",
            signedTxJson: "{}",
            walletId: "w1",
            network: "TESTNET",
            submittedAtTipBlock: 100,
            nullCount: nullCount,
            createdAt: createdAt,
            lastCheckedAt: createdAt
        )
    }

    /// An item with its display fields derived by the shared core, so a test
    /// exercises the same mapping the app does rather than a hand-set state.
    static func item(
        record: TransactionRecord? = nil,
        broadcast: PendingBroadcastRecord? = nil,
        isBulk: Bool = false,
        elapsedMillis: Int64? = nil
    ) -> ActivityItem {
        let ledgerRow = record ?? Self.record()
        let state = displayStateOf(record: ledgerRow, broadcast: broadcast)
        let since = pendingSince(record: ledgerRow, broadcast: broadcast)
        return ActivityItem(
            record: ledgerRow,
            broadcast: broadcast,
            isBulk: isBulk,
            displayState: state,
            pendingSinceMs: since,
            elapsed: elapsedMillis.map { elapsedBucket(elapsedMillis: $0) },
            failureReason: state == TxDisplayState.failed
                ? failureReasonOf(broadcast: broadcast)
                : nil
        )
    }
}

/// An ``ActivityPaging`` with no Kotlin feed and no database behind it.
///
/// It answers from a fixed list so the paging state machine can be driven
/// deterministically: which page was asked for, what a short page does, and
/// what a filter switch throws away.
@MainActor
final class FakeActivityPaging: ActivityPaging {

    let network: NetworkType

    /// Rows per filter. Anything not listed answers empty.
    var rows: [ActivityFilter: [ActivityItem]] = [:]

    /// Every `(filter, pageIndex)` asked for, in order.
    private(set) var requestedPages: [(ActivityFilter, Int)] = []

    private(set) var refreshCount = 0

    /// Thrown by the next ``refreshHistory()`` if set.
    var refreshError: Error?

    /// Thrown by the next ``page(filter:pageIndex:)`` if set.
    var pageError: Error?

    private var broadcastHandler: (@MainActor () -> Void)?

    init(network: NetworkType = NetworkType.testnet) {
        self.network = network
    }

    func refreshHistory() async throws {
        refreshCount += 1
        if let refreshError { throw refreshError }
    }

    func page(filter: ActivityFilter, pageIndex: Int) async throws -> [ActivityItem] {
        requestedPages.append((filter, pageIndex))
        if let pageError { throw pageError }
        let all = rows[filter] ?? []
        let start = pageIndex * ActivityViewModel.pageSize
        guard start < all.count else { return [] }
        let end = min(start + ActivityViewModel.pageSize, all.count)
        return Array(all[start..<end])
    }

    func onBroadcastsChanged(_ handler: @escaping @MainActor () -> Void) {
        broadcastHandler = handler
    }

    /// Fire the broadcast callback, as a Room change would.
    func emitBroadcastChange() {
        broadcastHandler?()
    }
}

/// An error whose `localizedDescription` is predictable, so a test can assert
/// on the message the view model surfaces.
struct ActivityTestError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}
