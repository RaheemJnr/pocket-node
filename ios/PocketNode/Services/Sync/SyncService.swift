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

/// The wallet's spendable balance, as the UI draws it.
///
/// `shannons` rather than a `Double` of CKB all the way to the text: 21 billion
/// CKB is 2.1e18 shannons, far past where a `Double` stops representing a
/// shannon exactly, and a balance that is off by a shannon is a balance a user
/// cannot reconcile. The string comes from the shared `formatCkbBalance`.
///
/// `isCached` marks the value the balance cache answered with before the cell
/// walk finished. It is a real balance, only possibly a few blocks old, so the
/// screen shows it rather than a spinner and says where it came from.
struct BalanceStatus: Equatable {
    var shannons: Int64 = 0
    var isCached: Bool = false

    /// False until the first read of any kind lands, which is what lets Home
    /// tell "nothing yet" from "zero".
    var hasValue: Bool = false

    /// `"12,345.60"`, formatted by the shared core. No `CKB` suffix: the view
    /// decides how the unit is set.
    var formatted: String {
        formatCkbBalance(shannons: shannons, groupSeparator: ",", decimalSeparator: ".")
    }
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

    /// The wallet's spendable balance, cached-first.
    var balance: BalanceStatus { get }

    func choose(mode: SyncMode, customBlockHeight: Int64?) async -> Bool

    /// Run activation again for the wallet already handed over. What the sync
    /// card's Retry does after the node failed to start.
    func retry()

    /// Re-read the balance. Home calls it on appear; the poll loop calls it
    /// after every reading that moved.
    func refreshBalance()
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
    private(set) var balance = BalanceStatus()

    private let lightClient: LightClientService

    /// The blocking bridge, shared with the send path for the same reason the
    /// stores are: one `LightClientApi` per process, held by whichever object
    /// was handed it.
    let api: any LightClientApi

    private let preferences: UserDefaultsPreferences
    private let network: NetworkType
    private let logger = Logger(subsystem: "com.rjnr.pocketnode", category: "SyncService")

    /// Held for its lifetime, not for its API: the Kotlin store reads through
    /// the DAO, and letting the database go would close the file under it.
    private let database: PocketNodeCoreDatabase
    private let service: SingleWalletSyncService

    /// The pieces of the shared stack the send path has to share rather than
    /// rebuild (#8).
    ///
    /// `SendPipeline` takes all five, and every one of them has to be the
    /// instance this object already owns. A second `LedgerReader` would open a
    /// second read path over the same file; a second `SyncCoordinator` would
    /// keep its own idea of which scripts are registered, so the post-send
    /// partial re-register would race this one's; a second `SyncEngine` would
    /// publish tips nothing reads. `SyncService` owns the database, so it owns
    /// these.
    let ledger: LedgerReader
    let transactions: RoomKmpTransactionStore
    let pendingBroadcasts: RoomKmpPendingBroadcastStore
    let coordinator: SyncCoordinator
    let engine: SyncEngine

    /// The activity list's and the balance's read path. Owned here because it
    /// shares this object's database, coordinator and engine: a second copy
    /// would open a second connection and run a second rescue-rescan ledger.
    let activity: ActivityFeed

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
    @ObservationIgnored private nonisolated(unsafe) var cachedBalanceObservation: Task<Void, Never>?

    /// The one balance read in flight, if any. One at a time: the read walks
    /// every cell page and takes seconds on a wallet with history, and a
    /// second concurrent walk would duplicate that work for the same answer.
    @ObservationIgnored private nonisolated(unsafe) var balanceRead: Task<Void, Never>?

    /// Drives `pending_broadcasts` rows to a terminal state (#8).
    ///
    /// Built here rather than in `SendService` because it needs the same
    /// stores and the same tip stream, and because it has to run whether or
    /// not the Send screen has ever been opened: its whole job is finishing
    /// transactions the user has already walked away from.
    @ObservationIgnored private let watchdog: BroadcastWatchdog

    /// Whether the watchdog is running, so ``startWatchdog()`` is idempotent
    /// and a stop before a start is a no-op.
    @ObservationIgnored private var isWatchdogRunning = false

    /// The live `isSyncing` reading, in a box a Kotlin thread may read.
    ///
    /// `SendContext.isSyncing` is deliberately a supplier rather than a value:
    /// the post-broadcast re-register consults it five seconds after the
    /// broadcast and has to see the flag as it is then, because a re-register
    /// on a still-catching-up wallet jumps its filter script forward over
    /// unscanned history (#332). A Swift closure capturing `self.status` would
    /// be a main-actor read from a Kotlin thread, which Swift 6 refuses and
    /// which would be a real race anyway, so the flag is mirrored into this
    /// lock-guarded box on every progress tick.
    @ObservationIgnored let syncingFlag = SyncingFlag(false)

    /// The last published balance, in the same kind of box and for the same
    /// reason: the send status poller asks whether it has moved, from a Kotlin
    /// thread, once every three seconds.
    @ObservationIgnored let balanceBox = BalanceBox(0)

    /// The active wallet's address and lock script on the selected network,
    /// resolved once in ``activate(wallet:force:)``. The balance read needs
    /// both, and `ActiveWallet` carries only the script args.
    @ObservationIgnored private var activeAddress: String?
    @ObservationIgnored private var activeScript: Script?

    /// - Parameter api: the shared `LightClientApi` seam, taken separately from
    ///   `lightClient` because that one publishes UI state on the main actor
    ///   while this one is the blocking bridge the Kotlin engine calls into.
    init(
        lightClient: LightClientService,
        api: any LightClientApi,
        preferences: UserDefaultsPreferences
    ) {
        self.lightClient = lightClient
        self.api = api
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
        // The three tables #9 added. `transactionStore` is what used to be
        // `EmptyTransactionStore`: the coordinator reads cached block numbers
        // from it to anchor a candidate scan, and now there are some.
        let transactionStore = RoomKmpTransactionStore(
            dao: database.transactions(),
            logger: log,
            clock: SystemClock.shared
        )
        let broadcastStore = RoomKmpPendingBroadcastStore(dao: database.pendingBroadcasts())
        let balanceStore = RoomKmpBalanceCache(
            dao: database.balanceCache(),
            logger: log,
            clock: SystemClock.shared
        )

        let coordinator = SyncCoordinator(
            walletRegistry: registry,
            syncProgressStore: progressStore,
            subAccountCandidateStore: EmptySubAccountCandidateStore.shared,
            transactionStore: transactionStore,
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
        self.coordinator = coordinator
        self.engine = engine
        self.transactions = transactionStore
        self.pendingBroadcasts = broadcastStore
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

        // The read path. `InMemoryHeaderCache` rather than a fourth table: a
        // block header never changes, so losing the map at launch costs one
        // extra walk. `EmptySubAccountCandidateStore` for the same reason it
        // is passed to the coordinator, this profile derives one address.
        let ledger = LedgerReader(
            lightClient: api,
            balanceCache: balanceStore,
            transactionStore: transactionStore,
            headerCache: InMemoryHeaderCache(maxEntries: InMemoryHeaderCache.companion.MAX_ENTRIES),
            walletRegistry: registry,
            candidates: EmptySubAccountCandidateStore.shared,
            syncPreferences: preferences,
            uiPreferences: preferences,
            json: runtime.json,
            logger: log,
            queryContext: runtime.defaultContext
        )
        self.ledger = ledger
        self.activity = ActivityFeed(
            ledger: ledger,
            transactions: transactionStore,
            pendingBroadcasts: broadcastStore,
            balanceCache: balanceStore,
            coordinator: coordinator,
            engine: engine,
            uiPreferences: preferences,
            clock: SystemClock.shared,
            scopeContext: runtime.defaultContext
        )

        // `AlwaysStartedLifecycleProvider` because the gate here is start/stop
        // from the scene phase rather than a per-check predicate: Android's
        // watchdog runs for the life of the process and asks
        // `ProcessLifecycleOwner` on every check, iOS stops the whole thing on
        // background. Two gates would mean the stop path had to agree with a
        // predicate as well, which is one more thing to get out of step.
        self.watchdog = BroadcastWatchdog(
            pendingBroadcasts: broadcastStore,
            statusGateway: LedgerTransactionStatusGateway(ledger: ledger),
            transactions: transactionStore,
            tipSource: SingleWalletTipSource(service: self.service),
            lifecycleProvider: AlwaysStartedLifecycleProvider.shared,
            dispatcher: runtime.defaultDispatcher,
            logger: log,
            clock: SystemClock.shared
        )

        observe()
    }

    // MARK: - Broadcast watchdog

    /// Start sweeping `pending_broadcasts` rows. Idempotent.
    ///
    /// Called when the scene becomes active and again once a wallet has been
    /// activated, so a launch that sits behind the lock screen still finishes
    /// whatever the last session left in flight.
    func startWatchdog() {
        guard !isWatchdogRunning else { return }
        isWatchdogRunning = true
        watchdog.start()
    }

    /// Stop sweeping. Called when the scene goes to the background, where the
    /// 15-second fallback timer would be suspended by the system anyway.
    func stopWatchdog() {
        guard isWatchdogRunning else { return }
        isWatchdogRunning = false
        watchdog.stop()
    }

    deinit {
        observation?.cancel()
        registrationObservation?.cancel()
        cachedBalanceObservation?.cancel()
        activation?.cancel()
        balanceRead?.cancel()
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
                let next = SyncStatus(progress)
                let previous = self?.status ?? SyncStatus()
                self?.status = next
                // The send path's `isSyncing` supplier reads this from a
                // Kotlin thread; see `syncingFlag`.
                self?.syncingFlag.set(next.isSyncing)
                // A poll tick that moved the chain is the cheapest trigger
                // there is for a re-read: the balance can only have changed if
                // a block the wallet is registered for was processed. A tick
                // that reports the same numbers reads nothing, which is what
                // keeps an idle wallet from walking its cells every 5 seconds.
                if next.syncedToBlock != previous.syncedToBlock
                    || next.tipBlockNumber != previous.tipBlockNumber {
                    self?.refreshBalance()
                }
            }
        }
        registrationObservation = Task { @MainActor [weak self] in
            for await registered in registrationFlow {
                guard self != nil else { return }
                self?.isRegistered = registered.boolValue
            }
        }
        // The cache-first half of the balance read. The shared feed publishes
        // here before it walks a single cell, which is the whole point: the
        // number on screen is the last one computed, not a spinner.
        let cachedFlow = activity.cachedBalance
        cachedBalanceObservation = Task { @MainActor [weak self] in
            for await cached in cachedFlow {
                guard let self, let cached else { continue }
                // A live read that has already landed wins. The cached value is
                // by definition the older of the two.
                guard !self.balance.hasValue || self.balance.isCached else { continue }
                self.publish(cached, isCached: true)
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
        stopWatchdog()
        observation?.cancel()
        registrationObservation?.cancel()
        cachedBalanceObservation?.cancel()
        activation?.cancel()
        balanceRead?.cancel()
        activity.close()
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
        activeAddress = address
        activeScript = script
        // Points the feed's broadcast flow at this wallet. Safe before the node
        // is up: it is a database subscription, not a bridge call.
        activity.observeBroadcasts(walletId: wallet.id, network: network)
        // The cached balance is a real number that is already on disk, so it
        // goes up before the node has started rather than after the first poll.
        loadCachedBalance(walletId: wallet.id)

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

    // MARK: - Balance

    /// Re-read the wallet's spendable balance.
    ///
    /// The shared `ActivityFeed.refreshBalance` is Android's `refreshBalance`
    /// with the repository's own two side effects moved out to the caller: the
    /// cached value arrives through the `onCached` closure and is published
    /// here, and the freshly computed one is published here too. The feed does
    /// the caching write, the rescue-rescan bookkeeping and the partial
    /// re-registration itself.
    ///
    /// A failure is deliberately quiet. The balance is a read that the next
    /// poll tick will run again in seconds, and a wallet whose node has not
    /// found peers yet would otherwise put an error under the sync card that
    /// says nothing the card is not already saying.
    func refreshBalance() {
        guard let walletId = activeWalletId, let address = activeAddress else { return }
        // One at a time: a cell walk takes seconds, and the poll ticks faster
        // than that on a catching-up wallet.
        guard balanceRead == nil else { return }
        let feed = activity
        let script = activeScript
        let net = network
        balanceRead = Task { @MainActor [weak self] in
            defer { self?.balanceRead = nil }
            do {
                let response = try await feed.refreshBalance(
                    address: address,
                    script: script,
                    network: net,
                    walletId: walletId
                )
                guard !Task.isCancelled else { return }
                self?.publish(response, isCached: false)
            } catch {
                self?.logger.error(
                    "balance read failed: \(error.localizedDescription, privacy: .public)"
                )
            }
        }
    }

    /// The last balance written to the cache, published without touching the
    /// node. What Home draws between launch and the first live read.
    ///
    /// It only publishes through the `cachedBalance` flow observed in
    /// ``observe()``, so a live read that has already landed is not overwritten
    /// by an older cached one: the flow's mirror checks for that.
    private func loadCachedBalance(walletId: String) {
        let feed = activity
        let net = network
        Task { _ = try? await feed.primeCachedBalance(walletId: walletId, network: net) }
    }

    private func publish(_ response: BalanceResponse, isCached: Bool) {
        balance = BalanceStatus(
            shannons: response.capacityAsLong(),
            isCached: isCached,
            hasValue: true
        )
        // The send status poller reads this from a Kotlin thread; see
        // `balanceBox`.
        balanceBox.set(balance.shannons)
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
