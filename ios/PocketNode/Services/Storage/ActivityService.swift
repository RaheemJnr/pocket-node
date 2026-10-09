import Foundation
import os
import PocketNodeCore

/// What ``ActivityViewModel`` needs from the history layer.
///
/// The concrete implementation below owns Kotlin objects and a live database,
/// so the paging state machine is tested against a fake of this instead. The
/// same arrangement `SyncStatusProviding` has for the sync card.
@MainActor
protocol ActivityPaging: AnyObject {

    /// The network the rows belong to. The detail sheet builds its explorer
    /// link from it.
    var network: NetworkType { get }

    /// Walk the wallet's history from the light client and cache it. Throws
    /// what the shared read path threw.
    func refreshHistory() async throws

    /// One page of the cached history, joined against the broadcast rows.
    func page(filter: ActivityFilter, pageIndex: Int) async throws -> [ActivityItem]

    /// Called whenever a broadcast row changes, so the list can re-read the
    /// pages it is showing and a row can move Broadcasting to Pending in place.
    func onBroadcastsChanged(_ handler: @escaping @MainActor () -> Void)
}

/// The activity list's data source: the shared `ActivityFeed`, bound to the
/// active wallet.
///
/// It owns no storage of its own. `SyncService` builds the feed (it shares the
/// database, the coordinator and the sync engine with the sync loop) and this
/// object only knows which wallet the feed should answer for, which is what
/// keeps the wallet id out of every call site on the screen.
@MainActor
@Observable
final class ActivityService: ActivityPaging {

    let network: NetworkType

    private let feed: ActivityFeed
    private let walletId: String

    /// The wallet's lock script on this network. The shared reader takes it
    /// rather than deriving one, and refuses with "No wallet" when it is nil,
    /// which is the honest outcome for a wallet whose address does not decode.
    private let script: Script?
    private let logger = Logger(subsystem: "com.rjnr.pocketnode", category: "ActivityService")

    @ObservationIgnored private nonisolated(unsafe) var broadcastObservation: Task<Void, Never>?
    @ObservationIgnored private var broadcastHandler: (@MainActor () -> Void)?

    init(feed: ActivityFeed, walletId: String, script: Script?, network: NetworkType) {
        self.feed = feed
        self.walletId = walletId
        self.script = script
        self.network = network
    }

    deinit {
        broadcastObservation?.cancel()
    }

    func refreshHistory() async throws {
        try await feed.refresh(
            activeScript: script,
            walletId: walletId,
            network: network
        )
    }

    func page(filter: ActivityFilter, pageIndex: Int) async throws -> [ActivityItem] {
        try await feed.page(
            filter: filter,
            walletId: walletId,
            network: network,
            pageIndex: Int32(pageIndex)
        )
    }

    /// Mirrors the feed's broadcast `StateFlow` onto [handler].
    ///
    /// SKIE bridges a `StateFlow<T>` to an `AsyncSequence`, so the loop is the
    /// whole of it. The flow replays its current value to a new collector, so
    /// the first iteration fires immediately and the caller gets one redundant
    /// re-page at subscription time; that costs a database read and keeps the
    /// wiring to four lines.
    func onBroadcastsChanged(_ handler: @escaping @MainActor () -> Void) {
        broadcastHandler = handler
        broadcastObservation?.cancel()
        let flow = feed.broadcasts
        broadcastObservation = Task { @MainActor [weak self] in
            for await _ in flow {
                guard let self else { return }
                self.broadcastHandler?()
            }
        }
    }
}
