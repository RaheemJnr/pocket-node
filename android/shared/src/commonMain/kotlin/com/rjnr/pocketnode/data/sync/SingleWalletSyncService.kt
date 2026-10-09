package com.rjnr.pocketnode.data.sync

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.prefs.SyncPreferences
import com.rjnr.pocketnode.core.time.Clock
import com.rjnr.pocketnode.core.time.SystemClock
import com.rjnr.pocketnode.data.gateway.SyncCoordinator
import com.rjnr.pocketnode.data.gateway.SyncPollSource
import com.rjnr.pocketnode.data.gateway.SyncProgress
import com.rjnr.pocketnode.data.gateway.models.AccountStatusResponse
import com.rjnr.pocketnode.data.gateway.models.JniScriptStatus
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.gateway.models.getCheckpoint
import com.rjnr.pocketnode.data.storage.InMemoryWalletRegistry
import com.rjnr.pocketnode.data.storage.SyncProgressRecord
import com.rjnr.pocketnode.data.storage.SyncProgressStore
import com.rjnr.pocketnode.data.storage.WalletRecord
import com.rjnr.pocketnode.data.wallet.WalletDerivation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

/**
 * The whole chain-sync surface for one wallet, in one object Swift can drive.
 *
 * Android's equivalent is spread across `GatewayRepository`: `registerAccount`
 * for the first registration, `registerAllWalletScripts` for the later ones,
 * `onActiveWalletChanged` for the tracker resets and `startSyncPolling` for the
 * poll. All four are methods on a repository that also owns balance, send, DAO
 * and multi-wallet state, none of which iOS has yet. This is the slice that is
 * only sync, for a profile that only ever has one wallet.
 *
 * ## Why it owns its scope
 *
 * Every other shared class takes the caller's [CoroutineScope]
 * ([SyncEngine.start] still does). Swift cannot construct one: `CoroutineScope`
 * is a Kotlin interface from a non-exported dependency, with no Swift-visible
 * factory. So this class creates the one the poll loop runs in, and [close]
 * cancels it. That makes the object's lifetime meaningful, which is why
 * `SyncService.swift` holds exactly one for the life of the app.
 *
 * ## What it deliberately does not do
 *
 * The BALANCED multi-wallet filter, the sub-account discovery candidates
 * (#82/#382) and the per-wallet progress fan-out all stay on the Android path.
 * The coordinator still runs them, but against
 * [EmptySubAccountCandidateStore][com.rjnr.pocketnode.data.storage.EmptySubAccountCandidateStore]
 * and a one-entry registry, so they are no-ops rather than divergence.
 *
 * [scopeContext] is where the poll loop runs. It defaults to
 * [Dispatchers.Default] for the same reason the rest of the shared core does:
 * `Dispatchers.IO` is JVM-only and invisible from `commonMain`.
 */
class SingleWalletSyncService(
    private val coordinator: SyncCoordinator,
    private val engine: SyncEngine,
    private val syncProgressStore: SyncProgressStore,
    private val walletRegistry: InMemoryWalletRegistry,
    private val syncPreferences: SyncPreferences,
    private val logger: Logger,
    private val clock: Clock = SystemClock,
    scopeContext: CoroutineContext = Dispatchers.Default,
) {

    /**
     * The scope the poll loop runs in. `SupervisorJob` so one failed child
     * cannot take the loop down with it, matching the repository scope the
     * Android poll has always run in.
     */
    private val scope = CoroutineScope(SupervisorJob() + scopeContext)

    @Volatile
    private var activeWallet: ActiveWallet? = null

    @Volatile
    private var walletId: String = ""

    @Volatile
    private var network: NetworkType = NetworkType.MAINNET

    /**
     * Bumped on every registration. A poll that read the chain state before a
     * re-registration carries the old value and is refused a write when it
     * lands after it; see [recordProgress].
     *
     * `internal` rather than private, with [recordProgress], so the module's
     * own tests can stage that interleaving directly. Driving it through the
     * poll loop would need a suspension point between the chain read and the
     * store write that no test dispatcher can give: the two run in one
     * continuation chain.
     */
    @Volatile
    internal var registrationEpoch = 0L
        private set

    private val _isRegistered = MutableStateFlow(false)

    /**
     * Whether this wallet's script has been handed to the light client in this
     * process.
     *
     * The repository's own reading of the same flag: set when registration is
     * asked for and succeeds, not when the node confirms the script. The
     * stricter, node-confirmed reading is the `isRegistered` that
     * [SingleWalletSyncPollSource] derives on every poll.
     */
    val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()

    /** @see SyncEngine.syncProgress */
    val syncProgress: StateFlow<SyncProgress> get() = engine.syncProgress

    /** @see SyncEngine.tipFlow */
    val tipFlow: StateFlow<Long> get() = engine.tipFlow

    /**
     * Point the service at a wallet.
     *
     * Two things at once because they are two halves of one fact: the poll
     * loop reads [ActiveWallet] to know whose progress it is reporting, and the
     * coordinator reads the registry to know whose script to register. Both
     * addresses are stored because the coordinator picks between them itself
     * (it prefers the testnet one and falls back to mainnet, since either
     * decodes to the same lock script).
     *
     * Cheap and idempotent, so the caller can re-run it on every appearance
     * rather than tracking whether it has run.
     */
    fun setWallet(
        wallet: ActiveWallet,
        walletId: String,
        network: NetworkType,
        mainnetAddress: String,
        testnetAddress: String,
    ) {
        this.activeWallet = wallet
        this.walletId = walletId
        this.network = network
        walletRegistry.set(
            listOf(
                WalletRecord(
                    walletId = walletId,
                    mainnetAddress = mainnetAddress,
                    testnetAddress = testnetAddress,
                    lastActiveAt = 0L,
                )
            )
        )
    }

    /**
     * First registration: the user has just picked how far back to sync.
     *
     * The single-wallet slice of `GatewayRepository.registerAccount`, on its
     * `forceResync` branch. Android's other branch resumes from saved progress;
     * here that is [reregisterFromSavedProgress], because on iOS the two are
     * reached from different places rather than chosen inside one method.
     *
     * Order matters and follows Android's: the preference is written only after
     * the light client has accepted the scripts, so a refused registration
     * leaves nothing behind claiming the wallet is set up.
     *
     * [nodeReady] is a plain (non-suspending) predicate because Swift cannot
     * pass a Kotlin `suspend` lambda. The caller polls its node state itself
     * before calling in, and this is the last-moment re-check.
     *
     * @return whatever the light client answered. False writes no preference
     *   and leaves [isRegistered] alone.
     */
    suspend fun registerWallet(
        mode: SyncMode,
        customBlockHeight: Long?,
        nodeReady: () -> Boolean,
    ): Boolean {
        if (activeWallet == null) {
            logger.w(TAG, "registerWallet: no active wallet")
            return false
        }
        if (!nodeReady()) {
            logger.w(TAG, "registerWallet: node is not ready")
            return false
        }

        // The tip read is a bridge round-trip, so it stays OUTSIDE the
        // registration lock (the lock is a leaf: nothing under it waits for
        // the node or the tip).
        val tipHeight = engine.readChainSyncState().tipNumber

        // The set, the epoch bump, the progress reset and the prefs all run
        // under the coordinator's registration lock, the one every other
        // registration and the poll's progress write take (#539). They used
        // to be written after the set released it: a poll could then write an
        // old-range block over the reset row, and a queued re-registration
        // could compute its set from the old progress and the old mode.
        val startBlock = coordinator.withRegistrationLock {
            registerWalletLocked(mode, customBlockHeight, tipHeight)
        } ?: return false
        _isRegistered.value = true

        // The percentage is measured from where this wallet started, not from
        // genesis, so the tracker is reset and re-seeded on the block we just
        // registered. Without the seed the first sample anchors the maths to a
        // transient syncedToBlock=0 reading while the node warms up (#150).
        engine.resetTracker()
        engine.seedStartHeight(startBlock)
        engine.resetProgressState()
        return true
    }

    /**
     * The half of [registerWallet] that holds the registration lock. Answers
     * the registered start block, or null when the set did not land.
     *
     * The wallet, its id and the network are read here, under the lock, so a
     * [setWallet] between the tip read and the lock cannot have this register
     * one wallet and record progress for another.
     *
     * Nothing is written before the set, so a refused or failed set has
     * nothing to roll back. Every write follows the set landing: the epoch
     * bump and the prefs in `onLanded` (the moment the light client accepted,
     * before the coordinator's own bookkeeping can throw), and the progress
     * reset in a `finally` under [NonCancellable], so a cancellation or a
     * failed bookkeeping write after the set still leaves the row agreeing
     * with what the light client has.
     */
    private suspend fun SyncCoordinator.LockedRegistration.registerWalletLocked(
        mode: SyncMode,
        customBlockHeight: Long?,
        tipHeight: Long,
    ): Long? {
        val wallet = activeWallet ?: run {
            logger.w(TAG, "registerWallet: no active wallet under the lock")
            return null
        }
        val id = walletId
        val net = network

        val startBlock = startBlockFor(mode, customBlockHeight, tipHeight, net)
        val blockNumberHex = "0x${startBlock.toString(16)}"
        logger.i(
            TAG,
            "registerWallet mode=$mode tip=$tipHeight startBlock=$startBlock ($blockNumberHex)"
        )

        val status = JniScriptStatus(
            script = WalletDerivation.lockScriptFromAddress(wallet.address),
            scriptType = "lock",
            blockNumber = blockNumberHex,
        )

        var landed = false
        try {
            val ok = setScriptsLocked(
                listOf(status),
                listOf(id),
                SyncCoordinator.CMD_SET_SCRIPTS_ALL,
                net,
                // Records whose set this is, so the "is the active wallet
                // registered" checks see it (the public setScriptsAndRecord
                // leaves registeredActiveWalletId null).
                forActiveWallet = id,
                onLanded = {
                    landed = true
                    // Invalidate any poll already in flight. Its
                    // `readChainSyncState` ran against the previous
                    // registration, so the block it carries describes the old
                    // range; written over the reset row below, the higher-only
                    // guard in `recordProgress` would keep it there forever.
                    // The poll's write takes this same lock, so it either ran
                    // before this set or checks the epoch after this bump.
                    registrationEpoch++
                    // Written with a null network, which means "the currently
                    // selected one": the same key an explicit network produces
                    // today, written the way Android's `registerAccount`
                    // writes it. Null is what keeps the writer aligned with
                    // the reader (`registerAllWalletScripts` calls
                    // `getSyncMode(walletId = ...)` with the network
                    // defaulted) if the selected network ever changes under a
                    // live service.
                    syncPreferences.setSyncMode(mode, walletId = id)
                    if (mode == SyncMode.CUSTOM) {
                        syncPreferences.setCustomBlockHeight(customBlockHeight, walletId = id)
                    }
                    syncPreferences.setInitialSyncCompleted(true, walletId = id)
                },
            )
            if (!ok) {
                logger.w(TAG, "registerWallet: light client refused the registration")
                return null
            }
        } finally {
            if (landed) {
                // `setScriptsLocked` wrote the row through `updateLightStart`,
                // which deliberately preserves `localSavedBlockNumber` so a
                // concurrent poll write is not clobbered. That is right for a
                // re-registration and wrong for this one: picking a new mode
                // means the old progress no longer describes anything, and
                // leaving it would have the next launch's
                // `reregisterFromSavedProgress` resume from the block the
                // PREVIOUS mode reached. Changing RECENT to All history would
                // then silently stay RECENT. Android avoids it by zeroing the
                // row first (`resyncAccount` -> `setWalletSyncBlock(id, 0)` ->
                // `registerAccount(forceResync)`); replacing it here is the
                // same thing in one write.
                withContext(NonCancellable) {
                    syncProgressStore.upsert(
                        SyncProgressRecord(
                            walletId = id,
                            network = net.name,
                            lightStartBlockNumber = startBlock,
                            localSavedBlockNumber = startBlock,
                            updatedAt = clock.nowMs(),
                        )
                    )
                }
            }
        }
        return startBlock
    }

    /**
     * The start block for [mode], with the two safety clamps
     * `GatewayRepository.registerAccount` applies after `toFromBlock`. The rule
     * itself is [clampStartBlock]; only the logging is here.
     */
    private fun startBlockFor(
        mode: SyncMode,
        customBlockHeight: Long?,
        tipHeight: Long,
        network: NetworkType,
    ): Long {
        val calculated = engine
            .startBlockFor(mode, network, tipHeight, customBlockHeight)
            .toLongOrNull() ?: 0L
        val clamped = clampStartBlock(calculated, mode, tipHeight, network)
        if (clamped != calculated) {
            logger.w(
                TAG,
                "start block $calculated adjusted to $clamped for $mode (tip=$tipHeight)"
            )
        }
        return clamped
    }

    /**
     * Second and later launches: re-register from what this device already
     * synced.
     *
     * `GatewayRepository` makes the same choice by asking whether a
     * `sync_progress` row exists, and re-registers through the coordinator so
     * the resume height comes from the row rather than from the mode. The
     * caller here makes that choice (a stored sync mode means the wallet has
     * been registered before) and this is the re-register half.
     *
     * The resume block is `localSavedBlockNumber`, which the poll advances
     * (see [progressRecordingSource]) and which [registerWallet] resets to the
     * newly chosen start. So this resumes where the device actually got to, and
     * a mode change is not quietly undone by it.
     *
     * Registration failures throw out of the coordinator; they are caught by
     * the caller, which has the UI to say so.
     */
    suspend fun reregisterFromSavedProgress(nodeReady: () -> Boolean) {
        if (activeWallet == null) {
            logger.w(TAG, "reregisterFromSavedProgress: no active wallet")
            return
        }
        coordinator.registerAllWalletScripts(
            SyncCoordinator.SyncContext(
                network = network,
                activeWalletId = walletId,
                awaitNodeReady = { nodeReady() },
                getWalletSyncBlock = { id -> savedBlockFor(id) },
                onScriptsRegistered = { _isRegistered.value = true },
            )
        )
    }

    /**
     * Read the node's tip and publish it to [tipFlow], answering what was read.
     *
     * The fallback half of [com.rjnr.pocketnode.data.gateway.TipSource], which
     * [SingleWalletTipSource] delegates here: the watchdog's 15-second timer
     * has to be able to move the tip itself when poll events have stalled.
     * Degrades to 0 the way every other bridge read does rather than throwing.
     */
    suspend fun fetchAndPublishTip(): Long {
        val tip = runCatching { engine.readChainSyncState().tipNumber }
            .onFailure { logger.w(TAG, "fetchAndPublishTip: ${it.message}") }
            .getOrDefault(0L)
        engine.publishTip(tip)
        return tip
    }

    /**
     * The active wallet's id and network name, or null before one is set.
     *
     * The other half of [com.rjnr.pocketnode.data.gateway.TipSource]: the
     * watchdog needs to know whose `pending_broadcasts` rows to sweep, and
     * that is exactly what [setWallet] already recorded.
     */
    fun activeWalletAndNetworkOrNull(): Pair<String, String>? {
        if (activeWallet == null || walletId.isEmpty()) return null
        return walletId to network.name
    }

    /** The last block fully processed for [id], or 0 when nothing is recorded. */
    private suspend fun savedBlockFor(id: String): Long =
        syncProgressStore.getAllForNetwork(network.name)
            .firstOrNull { it.walletId == id }
            ?.localSavedBlockNumber
            ?: 0L

    /** Start the sync poll. Idempotent: a second call while running does nothing. */
    fun startPolling() {
        engine.start(scope, progressRecordingSource)
    }

    /**
     * [SingleWalletSyncPollSource] plus the one thing it does not do: write the
     * wallet's progress back to the store.
     *
     * Android does this inline in `GatewayRepository.getAccountStatus`, between
     * reading the chain state and deriving the numbers, for every registered
     * wallet. Only the active one exists here, so it is a wrapper rather than a
     * fork of the poll source.
     *
     * Without it `localSavedBlockNumber` never moves off the block the wallet
     * registered at, and every launch re-registers from there: the sync appears
     * to restart from scratch each time the app is opened.
     */
    private val progressRecordingSource: SyncPollSource = object : SyncPollSource {

        private val delegate = SingleWalletSyncPollSource(engine) { activeWallet }

        override fun hasWalletInfo(): Boolean = delegate.hasWalletInfo()

        override suspend fun getAccountStatus(): Result<AccountStatusResponse> {
            // Captured before the read, not after: the point is to notice a
            // registration that happened while this poll was in flight.
            val epoch = registrationEpoch
            return delegate.getAccountStatus().onSuccess { status ->
                recordProgress(status.syncedToBlock.toLongOrNull() ?: 0L, epoch)
            }
        }
    }

    /**
     * Advance the wallet's saved progress to [block], if it is ahead.
     *
     * The higher-only guard and the update-then-upsert order are both Android's
     * (`getAccountStatus`'s `if (block > getWalletSyncBlock(...))` and
     * `setWalletSyncBlock`). Higher-only because the light client can report a
     * lower block for a script mid-rescan, and rewriting the row with it would
     * throw away real progress. Update-then-upsert because the atomic statement
     * preserves a `lightStartBlockNumber` written by a registration landing
     * beside this write.
     *
     * [epoch] is the registration generation the poll was read against. A
     * mismatch means a registration landed in between, so this block belongs to
     * a range that no longer applies and must not be written: the higher-only
     * guard would make it permanent.
     */
    internal suspend fun recordProgress(block: Long, epoch: Long) {
        if (block <= 0L) return
        // tryLock, never a queue: a poll that finds a registration in flight
        // skips this tick, and the next one reads against the new
        // registration (#539). Holding the lock across the read and the write
        // is what makes the epoch check below hold: no registration can land
        // between the check and the write.
        val ran = coordinator.tryWithRegistrationLock { recordProgressLocked(block, epoch) }
        if (ran == null) {
            logger.d(TAG, "a registration holds the lock; skipping this poll's progress write")
        }
    }

    private suspend fun recordProgressLocked(block: Long, epoch: Long) {
        val id = walletId
        if (id.isEmpty()) return
        if (epoch != registrationEpoch) {
            logger.d(TAG, "dropping a poll from before the last registration (block $block)")
            return
        }
        if (block <= savedBlockFor(id)) return

        val now = clock.nowMs()
        val updated = syncProgressStore.updateLocalSaved(id, network.name, block, now)
        if (updated == 0) {
            syncProgressStore.upsert(
                SyncProgressRecord(
                    walletId = id,
                    network = network.name,
                    lightStartBlockNumber = block,
                    localSavedBlockNumber = block,
                    updatedAt = now,
                )
            )
        }
    }

    /** Stop the sync poll and clear the derived progress state. */
    fun stopPolling() {
        engine.stop()
    }

    /**
     * Release the scope. Terminal: [startPolling] afterwards launches into a
     * cancelled scope and the loop never runs.
     */
    fun close() {
        stopPolling()
        scope.cancel()
    }

    companion object {
        private const val TAG = "SingleWalletSyncService"
    }
}

/**
 * The two safety clamps [SingleWalletSyncService.registerWallet] applies to the
 * block [toFromBlock][com.rjnr.pocketnode.data.gateway.models.toFromBlock]
 * calculated, the same pair `GatewayRepository.registerAccount` applies on
 * Android.
 *
 * A height above the tip would register a filter that can never match, and is
 * reachable from the sync sheet's custom-height field; it falls back to the
 * last 200,000 blocks. A height of zero on a mode that did not ask for genesis
 * means the tip was still unknown, and the network's checkpoint is a far better
 * guess than block 0.
 *
 * Top-level and public so both platforms can assert the rule directly rather
 * than through a registration: the iOS parity suite
 * (`ios/PocketNodeTests/Parity/M3ParityTests.swift`) calls this, and
 * `M3ParityFixtures` records the table both sides check.
 */
fun clampStartBlock(
    calculated: Long,
    mode: SyncMode,
    tipHeight: Long,
    network: NetworkType,
): Long {
    val checkpoint = getCheckpoint(network)
    return when {
        tipHeight > 0 && calculated > tipHeight -> (tipHeight - 200_000L).coerceAtLeast(0L)
        calculated == 0L && mode != SyncMode.FULL_HISTORY && checkpoint > 0L -> checkpoint
        else -> calculated
    }
}
