package com.rjnr.pocketnode.data.gateway

import androidx.room.withTransaction
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.DaoRecoveryRetryEntity
import com.rjnr.pocketnode.data.gateway.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface RecoveryClock { fun elapsed(): Long; fun wall(): Long }
internal data class RecoverySession(val wallet: String, val network: NetworkType, val genesis: String, val script: Script, val lifecycle: Long)
internal data class RecoveryBudgetKey(val wallet: String, val network: String, val genesis: String, val point: RecoveryOutPoint) {
    constructor(session: RecoverySession, point: RecoveryOutPoint) : this(session.wallet, session.network.name, session.genesis, point)
}
internal data class RecoveryDebit(val key: RecoveryBudgetKey, val record: DaoRecoveryRetryEntity)

/** No persisted timestamp grants authority. All live waits use elapsed time.
 * A new process replays the FULL recorded interval, ignoring wall-clock age.
 * The singleton retains anchors across screen recreation and wallet switches. */
internal class DaoRecoveryBudget(private val database: AppDatabase, private val clock: RecoveryClock) {
    private data class Anchor(val record: DaoRecoveryRetryEntity, val due: Long)
    private val anchors = mutableMapOf<RecoveryBudgetKey, Anchor>()
    private val mutex = Mutex()
    private fun after(now: Long, duration: Long) = now.coerceIn(0, Long.MAX_VALUE - duration) + duration
    private fun backoff(attempts: Int) = when (attempts) { 1 -> 10_000L; 2 -> 30_000L; else -> 600_000L }
    private suspend fun read(key: RecoveryBudgetKey) = database.daoRecoveryDao().budget(
        key.wallet, key.network, key.genesis, key.point.transactionHash, key.point.outputIndex)

    suspend fun debit(key: RecoveryBudgetKey): RecoveryDebit? = mutex.withLock {
        database.withTransaction {
            // Actual wallet deletion cannot recreate a retry record from a stale queue.
            if (database.walletDao().getActive()?.walletId != key.wallet) return@withTransaction null
            val old = read(key)
            val elapsed = clock.elapsed()
            if (old != null) {
                val anchor = anchors[key]?.takeIf { it.record == old } ?: run {
                    val interval = (old.nextEligibleAt - old.lastAttemptAt).takeIf {
                        it > 0 && it <= 630_000 && old.attempts in 1..3
                    } ?: 630_000L
                    Anchor(old, after(elapsed, interval)).also { anchors[key] = it }
                }
                if (elapsed < anchor.due) return@withTransaction null
            }
            val attempts = if (old == null || old.attempts !in 1..2) 1 else old.attempts + 1
            val wall = clock.wall().coerceAtLeast(0)
            // Reserve the deadline AND subsequent wait before any native work.
            val duration = 30_000 + backoff(attempts)
            val record = DaoRecoveryRetryEntity(key.wallet, key.network, key.genesis, key.point.transactionHash,
                key.point.outputIndex, attempts, if (attempts == 1) wall else old!!.cycleStartedAt,
                wall, after(wall, duration))
            database.daoRecoveryDao().putBudget(record)
            anchors[key] = Anchor(record, after(elapsed, duration))
            RecoveryDebit(key, record)
        }
    }

    suspend fun finish(debit: RecoveryDebit) = mutex.withLock {
        database.withTransaction {
            // Cancellation/deletion/reset/successor work must win over stale bookkeeping.
            if (read(debit.key) != debit.record) return@withTransaction
            val wall = clock.wall().coerceAtLeast(0)
            val duration = backoff(debit.record.attempts)
            val record = debit.record.copy(lastAttemptAt = wall, nextEligibleAt = after(wall, duration))
            database.daoRecoveryDao().putBudget(record)
            anchors[debit.key] = Anchor(record, after(clock.elapsed(), duration))
        }
    }
}

/** One serial runner, coalesced normalized candidates, bounded rotating admission. */
internal class DaoRecoveryQueue(private val budget: DaoRecoveryBudget) {
    private val _checking = MutableStateFlow<RecoveryOutPoint?>(null)
    val checking = _checking.asStateFlow()
    private var cursor: RecoveryOutPoint? = null
    suspend fun follow(sessions: Flow<RecoverySession?>, candidates: suspend (RecoverySession) -> List<RecoveryOutPoint>,
                       sessionEnded: suspend () -> Unit = {},
                       attempt: suspend (RecoverySession, RecoveryOutPoint) -> Boolean) {
        sessions.distinctUntilChanged().collectLatest { session ->
            if (session != null) try {
                run(session, { candidates(session) }) { attempt(session, it) }
            } finally { withContext(NonCancellable) { sessionEnded() } }
        }
    }
    suspend fun run(session: RecoverySession, candidates: suspend () -> List<RecoveryOutPoint>,
                    attempt: suspend (RecoveryOutPoint) -> Boolean) {
        try {
            while (currentCoroutineContext().isActive) {
                val all = try { candidates().distinct() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { emptyList() }
                val start = (all.indexOf(cursor) + 1).coerceAtLeast(0)
                val queue = (all.drop(start) + all.take(start)).take(128)
                var worked = false
                for (point in queue) {
                    cursor = point
                    val debit = try { budget.debit(RecoveryBudgetKey(session, point)) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                    if (debit == null) continue
                    worked = true
                    _checking.value = point
                    try {
                        withTimeoutOrNull(30_000) { attempt(point) }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Unavailable, never absence or authority. */ }
                    finally {
                        _checking.value = null
                        withContext(NonCancellable) {
                            // A failed refinement retains the stricter pre-work reservation.
                            try { budget.finish(debit) } catch (_: Exception) { }
                        }
                    }
                    // Re-read eligibility and candidate set after every operation.
                    break
                }
                if (!worked) delay(1_000)
            }
        } finally { _checking.value = null }
    }
}


private val recoveryStatuses = setOf(DaoCellStatus.LOCKED.name, DaoCellStatus.UNLOCKABLE.name)
private fun recoveryHash(value: String?) = value?.lowercase()?.takeIf { Regex("0x[0-9a-f]{64}").matches(it) }

/** Only positive contradictory locators qualify; unknown header data stays ordinary fetch. */
internal fun hasRecoveryContradiction(rows: List<com.rjnr.pocketnode.data.database.entity.DaoCellEntity>, canonical: JniHeaderView?): Boolean {
    if (rows.isEmpty() || rows.any { it.status !in recoveryStatuses }) return false
    val historical = rows.mapNotNull { row ->
        val hash = recoveryHash(row.withdrawBlockHash) ?: return@mapNotNull null
        row.withdrawBlockNumber?.takeIf { it >= 0 }?.let { it to hash }
    }
    if (historical.distinct().size > 1) return true
    val height = canonical?.number?.removePrefix("0x")?.toLongOrNull(16) ?: return false
    val hash = recoveryHash(canonical.hash) ?: return false
    return historical.any { (number, prior) -> number == height && prior != hash }
}

/** The rich read-only RPC exposes negotiated protocols; getPeers() does not.
 * LightClient=120 in the pinned ckb-network SupportProtocols. Direction is irrelevant.
 * Native dispatch still checks exact proved-chain eligibility and busy/quarantined peers. */
internal fun hasSuitableRecoveryPeer(json: Json, raw: String?): Boolean = try {
    val envelope = json.parseToJsonElement(requireNotNull(raw)).jsonObject
    check(envelope["error"] == null || envelope["error"] is JsonNull)
    envelope.getValue("result").jsonArray.any { entry ->
        val peer = entry.jsonObject
        val capable = peer["protocols"]?.jsonArray?.any {
            val protocol = it.jsonObject
            protocol["id"]?.jsonPrimitive?.content?.removePrefix("0x")?.toIntOrNull(16) == 120 &&
                !protocol["version"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
        } == true
        val proved = peer["sync_state"]?.jsonObject?.get("proved_best_known_header")
        capable && proved != null && proved !is JsonNull &&
            recoveryHash(proved.jsonObject["hash"]?.jsonPrimitive?.contentOrNull) != null &&
            proved.jsonObject["number"]?.jsonPrimitive?.content?.removePrefix("0x")?.toLongOrNull(16)?.let { it >= 0 } == true
    }
} catch (_: Exception) { false }

internal fun RecoveryOutPoint.toOutPoint() = OutPoint(transactionHash, "0x${outputIndex.toString(16)}")

/** A locator adoption succeeds only after exact-context validation. No liveness inference. */
internal class DaoRecoveryAttemptRunner(private val coordinator: DaoRecoveryCoordinator) {
    private val contexts = mutableMapOf<RecoveryOutPoint, DaoRecoveryContext>()
    suspend fun run(session: RecoverySession, point: RecoveryOutPoint, eligible: suspend () -> Boolean): Boolean {
        if (!eligible()) return false
        contexts.remove(point)?.let { coordinator.cancel(it) }
        val context = coordinator.begin(session.wallet, session.network, session.script, session.genesis, point.toOutPoint())
        contexts[point] = context
        var current = false
        try {
            while (eligible()) {
                when (coordinator.recover(context)) {
                    RecoveryOutcome.Pending -> delay(1_000)
                    RecoveryOutcome.Unavailable -> return false
                    RecoveryOutcome.Adopted -> {
                        current = coordinator.validateGuard(context, point.toOutPoint()) != null && eligible()
                        return current
                    }
                }
            }
            return false
        } finally {
            if (!current) {
                withContext(NonCancellable) { coordinator.cancel(context) }
                contexts.remove(point)
            }
        }
    }
    suspend fun cancelAll() {
        val old = contexts.values.toList()
        contexts.clear()
        for (context in old) coordinator.cancel(context)
    }
}

/** Owns process-local budget anchors. Work exists only inside a RESUMED DAO screen. */
@Singleton
class DaoRecoveryScheduler @Inject constructor(
    private val database: AppDatabase,
    private val coordinator: DaoRecoveryCoordinator,
    private val api: LightClientApi,
    private val gate: VerificationCacheGate,
    private val json: Json,
) {
    private val budget = DaoRecoveryBudget(database, object : RecoveryClock {
        override fun elapsed() = android.os.SystemClock.elapsedRealtime()
        override fun wall() = System.currentTimeMillis()
    })
    private val queue = DaoRecoveryQueue(budget)
    val checking = queue.checking
    // Multiple composition instances cannot create overlapping native operations.
    private val foregroundOwner = Mutex()

    suspend fun foreground(repository: GatewayRepository, positions: StateFlow<List<DaoDeposit>>, refresh: suspend () -> Unit) = foregroundOwner.withLock {
        coroutineScope {
            val attempts = DaoRecoveryAttemptRunner(coordinator)
            launch { coordinator.invalidation.drop(1).collect { refresh() } }
            launch { repository.tipFlow.collect { coordinator.refreshNativeValidity() } }
            // TipFlow publishes monotonic heights; periodic validation also catches same-height reorgs.
            launch { while (isActive) { coordinator.refreshNativeValidity(); delay(1_000) } }
            val sessions = combine(repository.walletInfo, repository.network, gate.readyNetworks) { info, network, ready ->
                val owner = repository.activeWalletAndNetworkOrNull()
                if (info == null || owner == null || owner.second != network.name || network.name !in ready) null
                else {
                    val lifecycle = gate.lifecycleRevision()
                    val genesis = nativeRead {
                        if (api.status() != 1) null else api.getGenesisBlock()?.let {
                            recoveryHash(json.parseToJsonElement(it).jsonObject["header"]?.jsonObject?.get("hash")?.jsonPrimitive?.content)
                        }
                    }
                    if (genesis == null || gate.lifecycleRevision() != lifecycle || !gate.isReady(network.name)) null
                    else RecoverySession(owner.first, network, genesis, info.script, lifecycle)
                }
            }
            try {
                queue.follow(sessions, { session -> candidates(session, positions.value) },
                    sessionEnded = { attempts.cancelAll(); coordinator.refreshNativeValidity() }) { session, point ->
                    val ok = attempts.run(session, point) {
                        repository.activeWalletAndNetworkOrNull() == (session.wallet to session.network.name) &&
                            repository.walletInfo.value?.script == session.script &&
                            gate.lifecycleRevision() == session.lifecycle && gate.isReady(session.network.name) &&
                            positions.value.any { it.status.name in recoveryStatuses && runCatching { RecoveryOutPoint.from(it.outPoint) }.getOrNull() == point } &&
                            nativeReady()
                    }
                    if (ok) refresh()
                    ok
                }
            } finally { withContext(NonCancellable) { attempts.cancelAll() } }
        }
    }

    private suspend fun nativeReady() = nativeRead {
        api.status() == 1 && hasSuitableRecoveryPeer(json, api.callRpc("get_peers"))
    } == true

    internal suspend fun candidates(session: RecoverySession, positions: List<DaoDeposit>): List<RecoveryOutPoint> {
        if (!gate.isReady(session.network.name) || gate.lifecycleRevision() != session.lifecycle || !nativeReady()) return emptyList()
        val visible = positions.filter { it.status.name in recoveryStatuses && it.withdrawBlockNumber != null }
            .mapNotNull { runCatching { RecoveryOutPoint.from(it.outPoint) }.getOrNull() }.toSet()
        val groups = database.daoCellDao().allRows().groupBy {
            runCatching { RecoveryOutPoint.from(OutPoint(it.txHash, it.index)) }.getOrNull()
        }
        val eligible = mutableListOf<RecoveryOutPoint>()
        for ((point, rows) in groups) {
            if (point == null || point !in visible || rows.any { it.walletId != session.wallet || it.network != session.network.name ||
                    it.status !in recoveryStatuses || it.withdrawBlockNumber == null }) continue
            val guards = coordinator.guards(session.network, point.toOutPoint())
            if (guards.isNotEmpty()) {
                if (guards.all { it.walletId == session.wallet && it.genesisHash == session.genesis } &&
                    coordinator.validateGuard(session.network, point.toOutPoint(), session.wallet) == null) eligible += point
                continue
            }
            val canonical = nativeRead {
                api.getHeaderByNumber("0x${rows.first().withdrawBlockNumber!!.toString(16)}")
                    ?.let { json.decodeFromString<JniHeaderView>(it) }
            }
            if (hasRecoveryContradiction(rows, canonical)) eligible += point
        }
        return eligible
    }

    private suspend fun <T> nativeRead(read: () -> T): T? = withContext(Dispatchers.IO) {
        try { read() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
}
