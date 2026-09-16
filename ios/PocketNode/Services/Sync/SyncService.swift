import Foundation
import os
import PocketNodeCore

/// The sync numbers the UI draws, as a Swift value.
///
/// A mirror of the Kotlin `SyncProgress` rather than a reference to it: the
/// Kotlin object is not `Sendable` and SwiftUI wants something it can compare.
/// `justReachedTip` is the edge the Android home screen animates on; nothing
/// draws it yet, and it is carried so the next screen does not have to widen
/// this type.
struct SyncStatus: Equatable {
    var isSyncing: Bool = false
    var syncedToBlock: Int64 = 0
    var tipBlockNumber: Int64 = 0
    var percentage: Double = 0
    var etaDisplay: String = ""
    var justReachedTip: Bool = false

    init(
        isSyncing: Bool = false,
        syncedToBlock: Int64 = 0,
        tipBlockNumber: Int64 = 0,
        percentage: Double = 0,
        etaDisplay: String = "",
        justReachedTip: Bool = false
    ) {
        self.isSyncing = isSyncing
        self.syncedToBlock = syncedToBlock
        self.tipBlockNumber = tipBlockNumber
        self.percentage = percentage
        self.etaDisplay = etaDisplay
        self.justReachedTip = justReachedTip
    }

    init(_ progress: SyncProgress) {
        self.init(
            isSyncing: progress.isSyncing,
            syncedToBlock: progress.syncedToBlock,
            tipBlockNumber: progress.tipBlockNumber,
            percentage: progress.percentage,
            etaDisplay: progress.etaDisplay,
            justReachedTip: progress.justReachedTip
        )
    }

    /// 0 to 1, for `ProgressView(value:)`. The Kotlin side reports 0 to 100.
    var fraction: Double { min(max(percentage / 100, 0), 1) }
}

/// What a Home view model needs from the sync layer.
///
/// `SyncService` owns Kotlin objects and a live database, so a test that only
/// wants to check which card is on screen goes through this instead. The same
/// reason `WalletRecordStoring` exists for the backup flow.
@MainActor
protocol SyncStatusProviding: AnyObject {
    var status: SyncStatus { get }
    var mode: SyncMode? { get }
    var isRegistered: Bool { get }
    var lastError: String? { get }

    func choose(mode: SyncMode, customBlockHeight: Int64?) async -> Bool

    /// Run activation again for the wallet already handed over. What the sync
    /// card's Retry does after the node failed to start.
    func retry()
}

/// Owns the shared chain-sync stack and the database behind it.
///
/// The iOS half of `GatewayRepository`'s sync surface. Everything Kotlin lives
/// inside this object: `SyncCoordinator`, `SyncEngine` and
/// `SingleWalletSyncService` are not `Sendable`, the class is `@MainActor`, and
/// the Kotlin service does its own dispatching (its poll loop runs on
/// `Dispatchers.Default`, and the blocking bridge reads hop off the caller's
/// thread), so nothing here has to hand them to a background queue itself.
///
/// One instance for the life of the app, built by `AppContainer`.
@MainActor
@Observable
final class SyncService: SyncStatusProviding {
    private(set) var status = SyncStatus()
    private(set) var isRegistered = false
    private(set) var mode: SyncMode?
    private(set) var lastError: String?

    private let lightClient: LightClientService
    private let preferences: UserDefaultsPreferences
    private let network: NetworkType
    private let logger = Logger(subsystem: "com.rjnr.pocketnode", category: "SyncService")

    /// Held for its lifetime, not for its API: the Kotlin store reads through
    /// the DAO, and letting the database go would close the file under it.
    private let database: PocketNodeCoreDatabase
    private let service: SingleWalletSyncService

    /// The wallet handed to ``activate(wallet:force:)``. Kept so a second call
    /// for the same wallet is a no-op rather than a second registration, and so
    /// ``retry()`` knows what to activate.
    private var activeWalletId: String?
    private var activeWallet: WalletRecord?

    // `@ObservationIgnored` because no view reads them, and because it is what
    // keeps them real stored properties: without it the `@Observable` macro
    // rewrites them as computed, and `nonisolated(unsafe)` on a computed
    // property means nothing. `nonisolated(unsafe)` so `deinit`, which is never
    // actor-isolated, can cancel them; `Task` is `Sendable` and these are only
    // ever written from the main actor, so the unchecked part is a formality.
    @ObservationIgnored private nonisolated(unsafe) var observation: Task<Void, Never>?
    @ObservationIgnored private nonisolated(unsafe) var registrationObservation: Task<Void, Never>?
    @ObservationIgnored private nonisolated(unsafe) var activation: Task<Void, Never>?

    /// - Parameter api: the shared `LightClientApi` seam, taken separately from
    ///   `lightClient` because that one publishes UI state on the main actor
    ///   while this one is the blocking bridge the Kotlin engine calls into.
    init(
        lightClient: LightClientService,
        api: any LightClientApi,
        preferences: UserDefaultsPreferences
    ) {
        self.lightClient = lightClient
        self.preferences = preferences
        self.network = preferences.getSelectedNetwork()

        let runtime = SharedRuntime.shared
        let log = NSLogLogger()

        // One database file per network, inside the same per-network folder
        // the light client keeps its `store.db` in. Android isolates its
        // `data/mainnet` and `data/testnet` stores the same way, and a shared
        // file would carry one network's checkpoints into the other.
        self.database = createIosPocketNodeCoreDatabase(
            path: Self.databasePath(network: network),
            queryContext: runtime.defaultContext
        )
        let progressStore = RoomKmpSyncProgressStore(dao: database.syncProgress())
        let registry = InMemoryWalletRegistry()

        let coordinator = SyncCoordinator(
            walletRegistry: registry,
            syncProgressStore: progressStore,
            subAccountCandidateStore: EmptySubAccountCandidateStore.shared,
            transactionStore: EmptyTransactionStore.shared,
            lightClient: api,
            syncPreferences: preferences,
            json: runtime.json,
            logger: log,
            clock: SystemClock.shared,
            queryContext: runtime.defaultContext
        )
        let engine = SyncEngine(
            lightClient: api,
            syncPreferences: preferences,
            json: runtime.json,
            logger: log,
            clock: SystemClock.shared,
            queryContext: runtime.defaultContext
        )
        self.service = SingleWalletSyncService(
            coordinator: coordinator,
            engine: engine,
            syncProgressStore: progressStore,
            walletRegistry: registry,
            syncPreferences: preferences,
            logger: log,
            clock: SystemClock.shared,
            scopeContext: runtime.defaultContext
        )

        observe()
    }

    deinit {
        observation?.cancel()
        registrationObservation?.cancel()
        activation?.cancel()
    }

    // MARK: - Observation

    /// Mirrors the two Kotlin `StateFlow`s onto this object's published state.
    ///
    /// SKIE bridges a `StateFlow<T>` to an `AsyncSequence` of `T`, so the loop
    /// below is the whole of it. The service is created before any wallet is,
    /// and the flows replay their current value to a new collector, so starting
    /// here rather than on activation costs one emission and no correctness.
    private func observe() {
        // The flows are read out here, before the tasks, and `self` is only
        // ever touched weakly inside the loops. A `guard let self` at the top
        // would hold a strong reference for as long as the flow runs, which is
        // forever: the task would keep this object alive and `deinit` would
        // never be reached.
        let progressFlow = service.syncProgress
        let registrationFlow = service.isRegistered

        observation = Task { @MainActor [weak self] in
            for await progress in progressFlow {
                guard self != nil else { return }
                self?.status = SyncStatus(progress)
            }
        }
        registrationObservation = Task { @MainActor [weak self] in
            for await registered in registrationFlow {
                guard self != nil else { return }
                self?.isRegistered = registered.boolValue
            }
        }
    }

    /// Stop everything this object owns and close the database.
    ///
    /// `deinit` cancels the tasks, but it cannot reach the Kotlin service or
    /// the database, both of which are main-actor isolated. In practice this
    /// object lives for the whole launch and iOS reclaims the process rather
    /// than unwinding it, so nothing calls this today; it exists so that
    /// whatever does own a shutdown hook later (a scene-phase handler, or the
    /// M4 network switch, which has to close one network's database before
    /// opening the other's) has one call to make rather than three.
    func shutdown() {
        observation?.cancel()
        registrationObservation?.cancel()
        activation?.cancel()
        service.close()
        database.close()
    }

    // MARK: - Activation

    /// Point the sync layer at this device's wallet and get it running.
    ///
    /// Idempotent per wallet: `RootView` calls it whenever the wallet shell
    /// appears, and onboarding calls it again once a wallet exists.
    ///
    /// The order is Android's. The wallet is set first so the poll loop knows
    /// whose numbers it is reporting. Registration waits for the node, because
    /// `setScripts` on a node that has not started is refused. A wallet with a
    /// stored sync mode has registered before and resumes from what it already
    /// processed; one without is waiting for the user to choose, and only the
    /// poll starts.
    /// - Parameter force: run even for the wallet already activated. Only
    ///   ``retry()`` passes true; an ordinary appearance must not re-register.
    func activate(wallet: WalletRecord, force: Bool = false) {
        let address = network == .mainnet ? wallet.mainnetAddress : wallet.testnetAddress
        guard let script = AddressUtils.shared.parseAddress(address: address) else {
            lastError = "This wallet's address could not be read."
            logger.error("activate: address does not decode to a lock script")
            // Deliberately before `activeWalletId` is claimed, so the Retry on
            // the sync card can run this again. Claiming it first would make
            // the failure permanent for the launch.
            return
        }
        guard force || activeWalletId != wallet.id else { return }
        activeWalletId = wallet.id
        activeWallet = wallet

        service.setWallet(
            wallet: ActiveWallet(address: address, scriptArgs: script.args),
            walletId: wallet.id,
            network: network,
            mainnetAddress: wallet.mainnetAddress,
            testnetAddress: wallet.testnetAddress
        )
        mode = preferences.getSyncModeOrNull(network: network, walletId: wallet.id)

        // A retry, or a wallet swap, must not leave the previous attempt
        // racing this one to set `lastError` and start the poll.
        activation?.cancel()
        activation = Task { [weak self] in
            guard let self else { return }
            self.lastError = nil
            let ready = await self.waitForNode()
            guard !Task.isCancelled else { return }
            guard ready else {
                self.lastError = "The light client did not start."
                return
            }
            if self.mode != nil {
                await self.reregister()
            }
            self.service.startPolling()
        }
    }

    /// Apply a sync mode the user picked. The first registration for a wallet
    /// that has never had one, and a fresh start for one that has.
    func choose(mode: SyncMode, customBlockHeight: Int64?) async -> Bool {
        lastError = nil
        // The sheet can be confirmed seconds after launch, before the node has
        // finished coming up. `setScripts` on a node that is not running is
        // refused, and reporting that as "the light client refused" would send
        // the user looking for a problem with their choice.
        guard await waitForNode() else {
            lastError = "The light client has not started yet."
            return false
        }
        let height = customBlockHeight.map { KotlinLong(longLong: $0) }
        do {
            let accepted = try await service.registerWallet(
                mode: mode,
                customBlockHeight: height,
                nodeReady: nodeReadySnapshot()
            )
            guard accepted.boolValue else {
                lastError = "The light client refused to start syncing. Try again in a moment."
                return false
            }
            self.mode = mode
            service.startPolling()
            return true
        } catch {
            lastError = error.localizedDescription
            logger.error("registerWallet failed: \(error.localizedDescription, privacy: .public)")
            return false
        }
    }

    /// Re-run activation for the wallet already on this object.
    func retry() {
        guard let activeWallet else { return }
        activate(wallet: activeWallet, force: true)
    }

    private func reregister() async {
        do {
            try await service.reregisterFromSavedProgress(nodeReady: nodeReadySnapshot())
        } catch {
            lastError = error.localizedDescription
            logger.error("re-registration failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    // MARK: - Node readiness

    /// `nodeReady` for the Kotlin side: read on the main actor, where
    /// `LightClientService` publishes it.
    private var isNodeRunning: Bool { lightClient.status == .running }

    /// The `nodeReady` predicate the shared service takes.
    ///
    /// It closes over the node state as it is right now rather than reading it
    /// live. Not laziness: the closure runs on a Kotlin thread, and reading
    /// `lightClient.status` there would be a main-actor property accessed off
    /// the main actor, which Swift 6 rightly refuses. Both callers have just
    /// established that the node is up, and the Kotlin side calls this once at
    /// the top of the registration, so the snapshot is microseconds old.
    private func nodeReadySnapshot() -> @Sendable () -> KotlinBoolean {
        let ready = isNodeRunning
        return { KotlinBoolean(bool: ready) }
    }

    /// Brings the node up if it is waiting to be started, then waits for it to
    /// reach `.running`, up to ``nodeWaitSeconds``.
    ///
    /// `LightClientService.bootstrap` initialises the node but does not start
    /// it: until M3 the only caller that wanted a running node was the Node
    /// Status screen's Start button. Sync cannot ask a user to go and find that
    /// button, so this starts it, under the same rule the button uses
    /// (`NodeControls.canStart`, which is false once the node has been stopped,
    /// because the Rust globals make that terminal). Android does the same
    /// thing in one step in `NodeLifecycle.initializeNode`.
    ///
    /// It polls rather than subscribes because `LightClientService` publishes
    /// the transition only on its own refreshes. The Kotlin coordinator has its
    /// own bounded wait for the tip after this one.
    private func waitForNode() async -> Bool {
        for _ in 0..<(Self.nodeWaitSeconds * 2) {
            if isNodeRunning { return true }
            if Task.isCancelled { return false }
            if lightClient.controls.canStart {
                await lightClient.start()
            } else {
                await lightClient.refresh()
            }
            if isNodeRunning { return true }
            try? await Task.sleep(for: .milliseconds(500))
            if Task.isCancelled { return false }
        }
        return isNodeRunning
    }

    static let nodeWaitSeconds = 30

    // MARK: - Storage

    /// `Application Support/PocketNode/<network>/pocket_node.db`, in the same
    /// per-network folder the light client keeps its `store.db` in.
    ///
    /// Through `AppDirectories` rather than spelled out here, which is the
    /// whole point of #22: this used to say `pocketnode`, `WalletStore` said
    /// `PocketNode`, and on the Mac's case-insensitive volume the second
    /// `mkdir` collided with the first and answered `ENOTDIR`. One spelling,
    /// one owner.
    ///
    /// A failure is reported and the intended path returned anyway, never a
    /// different directory. Room then fails on its first query, which is the
    /// loud outcome; what this adds is a line naming the directory, and
    /// `LightClientService` reports the same failure to the user. `static`
    /// because it runs before the instance exists, hence its own logger.
    private static func databasePath(network: NetworkType) -> String {
        let name = network.name.lowercased()
        let directory: URL
        do {
            directory = try AppDirectories.dataDirectory(network: name)
        } catch {
            // `String(describing:)` rather than `localizedDescription`: the
            // underlying POSIX error is the part worth having, and the
            // localized string drops it.
            Logger(subsystem: "com.rjnr.pocketnode", category: "SyncService").error(
                "could not open the data directory for \(name, privacy: .public): \(String(describing: error), privacy: .public)"
            )
            directory = AppDirectories.url().appendingPathComponent(name, isDirectory: true)
        }
        return directory.appendingPathComponent("pocket_node.db").path
    }
}
