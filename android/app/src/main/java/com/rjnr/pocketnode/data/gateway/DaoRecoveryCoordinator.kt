package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.DaoRecoveryEntity
import com.rjnr.pocketnode.data.gateway.models.*
import com.rjnr.pocketnode.data.send.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** CKB indexes are hexadecimal even without 0x. Reject signed/overflow aliases. */
data class RecoveryOutPoint(val transactionHash: String, val outputIndex: Long) {
    companion object {
        fun from(outPoint: OutPoint): RecoveryOutPoint {
            val hash = outPoint.txHash.lowercase()
            require(Regex("0x[0-9a-f]{64}").matches(hash))
            val index = outPoint.index.removePrefix("0x")
            require(index.isNotEmpty() && index.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' })
            val number = requireNotNull(index.toLongOrNull(16))
            require(number in 0..0xffff_ffffL)
            return RecoveryOutPoint(hash, number)
        }
    }
}

/** Each begin pins a native request/run and one immutable app wallet/script context. */
@ConsistentCopyVisibility
data class DaoRecoveryContext internal constructor(
    val walletId: String, val network: NetworkType, val script: Script,
    val ticket: DaoRecoveryTicket, val lifecycle: Long,
    val outPoint: RecoveryOutPoint,
)
sealed class RecoveryOutcome {
    data object Pending : RecoveryOutcome()
    data object Adopted : RecoveryOutcome()
    data object Unavailable : RecoveryOutcome()
}
@ConsistentCopyVisibility
data class ValidatedRecoverySnapshot internal constructor(
    val guard: DaoRecoveryEntity, val receipt: DaoRecoveryReceipt, val lifecycle: Long,
)

/** No native IO occurs inside Room or the gate mutex. Validity is point-in-time. */
@Singleton
class DaoRecoveryCoordinator @Inject constructor(
    private val database: AppDatabase,
    private val gate: VerificationCacheGate,
    private val api: LightClientApi,
    private val json: Json,
) : InputSubmissionAuthorizer {
    private data class Active(val context: DaoRecoveryContext, val snapshot: ValidatedRecoverySnapshot)
    private val active = mutableMapOf<DaoRecoveryEntity, Active>()
    private val cancelled = mutableSetOf<DaoRecoveryTicket>()
    private val _invalidation = MutableStateFlow(0L)
    val invalidation = _invalidation.asStateFlow()

    suspend fun begin(walletId: String, network: NetworkType, script: Script, genesisHash: String,
                      outPoint: OutPoint): DaoRecoveryContext {
        database.walletStorage.requireOpen()
        val point = RecoveryOutPoint.from(outPoint)
        val revision = gate.requireReadyRevision(network.name)
        val lifecycle = gate.lifecycleRevision()
        check(database.walletDao().getActive()?.walletId == walletId) { "Wallet changed" }
        val client = DaoRecoveryClient(api, genesisHash)
        val ticket = withContext(Dispatchers.IO) { client.request(point.transactionHash) }.valueOrNull()
            ?: error("Recovery unavailable")
        try {
            gate.requireCurrent(network.name, revision)
            check(ticket.identity.storeGeneration == gate.generation(network.name))
            check(database.walletDao().getActive()?.walletId == walletId) { "Wallet changed" }
            return DaoRecoveryContext(walletId, network, script, ticket, lifecycle, point)
        } catch (failure: Throwable) {
            client.cancel(ticket)
            throw failure
        }
    }

    suspend fun recover(context: DaoRecoveryContext, outPoint: RecoveryOutPoint = context.outPoint): RecoveryOutcome {
        return try {
            require(outPoint == context.outPoint)
            requireContext(context)
            val client = DaoRecoveryClient(api, context.ticket.identity.genesisHash)
            val poll = withContext(Dispatchers.IO) { client.poll(context.ticket) }.valueOrNull()
                ?: return RecoveryOutcome.Unavailable
            if (poll.status in setOf(DaoRecoveryStatus.QUEUED, DaoRecoveryStatus.FETCHING)) return RecoveryOutcome.Pending
            val receipt = poll.receipt ?: return RecoveryOutcome.Unavailable
            val validated = withContext(Dispatchers.IO) { client.validate(context.ticket, receipt.receiptId) }.valueOrNull()
                ?.receipt?.takeIf { it == receipt } ?: return RecoveryOutcome.Unavailable
            requireContext(context)
            val index = outPoint.outputIndex.toInt().also { require(it >= 0 && it.toLong() == outPoint.outputIndex) }
            val output = validated.rawTransaction.outputs.getOrNull(index) ?: return RecoveryOutcome.Unavailable
            val data = validated.rawTransaction.outputsData.getOrNull(index) ?: return RecoveryOutcome.Unavailable
            require(output.lock == context.script && output.type == DaoConstants.DAO_TYPE_SCRIPT)
            require(Regex("0x[0-9a-f]{16}").matches(data) && data != "0x0000000000000000")
            val depositHeight = data.removePrefix("0x").chunked(2).reversed().joinToString("").toLong(16)
            val capacity = output.capacity.removePrefix("0x").toLong(16)
            val height = validated.header.number.removePrefix("0x").toLong(16)
            require(height > depositHeight)
            val guard = DaoRecoveryEntity(context.walletId, context.network.name, validated.identity.genesisHash,
                outPoint.transactionHash, outPoint.outputIndex, capacity, depositHeight, json.encodeToString(context.script),
                height, validated.header.hash, validated.identity.storeGeneration, validated.identity.runNonce, validated.schemaVersion)
            val revision = gate.requireReadyRevision(context.network.name)
            gate.commitRecoveryIfCurrent(context.network.name, revision) {
                requireContext(context)
                val aliases = database.daoCellDao().allRows().filter { row ->
                    runCatching { RecoveryOutPoint.from(OutPoint(row.txHash, row.index)) }.getOrNull() == outPoint
                }
                require(aliases.isNotEmpty()) { "Recovery group was deleted" }
                require(aliases.all { it.walletId == context.walletId && it.network == context.network.name &&
                    it.capacity == capacity && it.depositBlockNumber == depositHeight && it.withdrawBlockNumber != null })
                val existing = database.daoRecoveryDao().guards(context.network.name, outPoint.transactionHash, outPoint.outputIndex)
                require(existing.all { it.walletId == context.walletId && it.genesisHash == guard.genesisHash &&
                    it.capacity == capacity && it.depositBlockNumber == depositHeight && it.scriptJson == guard.scriptJson })
                for (row in aliases) {
                    database.daoCellDao().upsert(row.withoutWithdrawalEvidence().copy(
                        status = row.status, withdrawBlockNumber = height, withdrawBlockHash = validated.header.hash))
                }
                database.daoRecoveryDao().putGuard(guard)
            }
            val snapshot = ValidatedRecoverySnapshot(guard, validated, context.lifecycle)
            synchronized(active) {
                active.keys.filter { it.walletId == guard.walletId && it.network == guard.network &&
                    it.transactionHash == guard.transactionHash && it.outputIndex == guard.outputIndex }.forEach(active::remove)
                active[guard] = Active(context, snapshot)
            }
            // A tip transition during SQL can leave the locator adopted, but never authorizes it.
            validateGuard(context.network, OutPoint(outPoint.transactionHash, "0x${outPoint.outputIndex.toString(16)}"), context.walletId)
            RecoveryOutcome.Adopted
        } catch (e: CancellationException) { cancel(context); throw e }
        catch (_: Exception) { RecoveryOutcome.Unavailable }
    }

    private suspend fun requireContext(context: DaoRecoveryContext) {
        currentCoroutineContext().ensureActive()
        database.walletStorage.requireOpen()
        check(synchronized(active) { context.ticket !in cancelled }) { "Recovery cancelled" }
        check(gate.isReady(context.network.name) && gate.lifecycleRevision() == context.lifecycle &&
            gate.generation(context.network.name) == context.ticket.identity.storeGeneration)
        check(database.walletDao().getActive()?.walletId == context.walletId) { "Wallet changed" }
    }

    suspend fun guards(network: NetworkType, outPoint: OutPoint): List<DaoRecoveryEntity> {
        val point = RecoveryOutPoint.from(outPoint)
        return database.daoRecoveryDao().guards(network.name, point.transactionHash, point.outputIndex)
    }

    suspend fun validateGuard(context: DaoRecoveryContext, outPoint: OutPoint): ValidatedRecoverySnapshot? {
        return try {
            check(RecoveryOutPoint.from(outPoint) == context.outPoint)
            requireContext(context)
            val snapshot = validateGuard(context.network, outPoint, context.walletId) ?: return null
            // The general lookup may have validated a successor while suspended.
            check(synchronized(active) { active[snapshot.guard]?.context == context && context.ticket !in cancelled })
            snapshot
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    suspend fun validateGuard(network: NetworkType, outPoint: OutPoint, walletId: String? = null): ValidatedRecoverySnapshot? {
        val guards = guards(network, outPoint)
        if (guards.size != 1) return null
        val guard = guards.single()
        val candidate = synchronized(active) { active[guard] } ?: return null
        return try {
            if (walletId != null) check(guard.walletId == walletId)
            requireContext(candidate.context)
            check(guards(network, outPoint) == guards)
            val receipt = withContext(Dispatchers.IO) {
                DaoRecoveryClient(api, guard.genesisHash).validate(candidate.context.ticket, candidate.snapshot.receipt.receiptId)
            }.valueOrNull()?.receipt
            check(receipt == candidate.snapshot.receipt)
            // No further Room suspension after the final native validation.
            currentCoroutineContext().ensureActive()
            database.walletStorage.requireOpen()
            check(gate.isReady(network.name) && gate.lifecycleRevision() == candidate.context.lifecycle)
            check(synchronized(active) { active[guard] == candidate && candidate.context.ticket !in cancelled })
            candidate.snapshot
        } catch (e: CancellationException) { invalidate(candidate); throw e }
        catch (_: Exception) { invalidate(candidate); null }
    }

    private fun invalidate(candidate: Active) = synchronized(active) {
        if (active[candidate.snapshot.guard] == candidate) {
            active.remove(candidate.snapshot.guard)
            _invalidation.value += 1
        }
    }

    /** Adapter for node lifecycle/tip notifications; also called implicitly by every consumer. */
    suspend fun refreshNativeValidity() {
        val candidates = synchronized(active) { active.values.toList() }
        for (candidate in candidates) {
            val guard = candidate.snapshot.guard
            validateGuard(candidate.context.network, OutPoint(guard.transactionHash, "0x${guard.outputIndex.toString(16)}"), guard.walletId)
        }
    }

    suspend fun cancel(context: DaoRecoveryContext) {
        synchronized(active) { cancelled.add(context.ticket) }
        synchronized(active) { active.values.filter { it.context == context }.toList() }.forEach(::invalidate)
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            DaoRecoveryClient(api, context.ticket.identity.genesisHash).cancel(context.ticket)
        }
    }

    suspend fun project(network: NetworkType, walletId: String?, position: DaoDeposit): DaoDeposit {
        if (guards(network, position.outPoint).isEmpty()) return position
        val snapshot = validateGuard(network, position.outPoint, walletId)
        val key = RecoveryOutPoint.from(position.outPoint)
        val rows = database.daoCellDao().allRows().filter {
            runCatching { RecoveryOutPoint.from(OutPoint(it.txHash, it.index)) }.getOrNull() == key
        }
        if (rows.any { it.status == DaoCellStatus.COMPLETED.name })
            return position.withoutRecoveryEvidence().copy(status = DaoCellStatus.COMPLETED)
        if (snapshot != null && rows.any { it.walletId != snapshot.guard.walletId || it.network != snapshot.guard.network ||
            it.capacity != snapshot.guard.capacity || it.depositBlockNumber != snapshot.guard.depositBlockNumber ||
            it.withdrawBlockNumber != snapshot.guard.withdrawBlockNumber || it.withdrawBlockHash != snapshot.guard.withdrawBlockHash })
            return position.withoutRecoveryEvidence()
        // Row reads above may suspend; never publish using their earlier native snapshot.
        if (snapshot != null && validateGuard(network, position.outPoint, walletId) != snapshot)
            return position.withoutRecoveryEvidence()
        return if (snapshot != null && position.withdrawBlockNumber == snapshot.guard.withdrawBlockNumber &&
            position.withdrawBlockHash == snapshot.guard.withdrawBlockHash &&
            (position.recoveryReceiptId == null || position.recoveryReceiptId == snapshot.receipt.receiptId)) {
            position.copy(recoveryReceiptId = snapshot.receipt.receiptId)
        } else position.withoutRecoveryEvidence()
    }

    private suspend fun requireSpendableGuardRows(guard: DaoRecoveryEntity) {
        val key = RecoveryOutPoint(guard.transactionHash, guard.outputIndex)
        val rows = database.daoCellDao().allRows().filter {
            runCatching { RecoveryOutPoint.from(OutPoint(it.txHash, it.index)) }.getOrNull() == key
        }
        check(rows.isNotEmpty() && rows.all { it.walletId == guard.walletId && it.network == guard.network &&
            it.capacity == guard.capacity && it.depositBlockNumber == guard.depositBlockNumber &&
            it.withdrawBlockNumber == guard.withdrawBlockNumber && it.withdrawBlockHash == guard.withdrawBlockHash &&
            it.status != DaoCellStatus.COMPLETED.name }) { "Withdrawal position changed" }
    }

    /** Pins even the absence of a guard, so an adoption during preparation aborts. */
    override suspend fun authorize(context: SendContext, transaction: Transaction): SubmissionAuthorization {
        val inputs = transaction.cellInputs.map { it.previousOutput }
        val pinned = inputs.map { point ->
            val found = guards(context.network, point)
            val snapshot = if (found.isEmpty()) null else requireNotNull(validateGuard(context.network, point, context.walletId)) {
                "Withdrawal details need fresh verification"
            }
            if (snapshot != null) {
                requireSpendableGuardRows(snapshot.guard)
                check(context.activeScript != null && json.encodeToString(context.activeScript) == snapshot.guard.scriptJson)
                // Recovery supports this wallet's reviewed single-input DAO unlock shape.
                // Merely containing the new hash somewhere among arbitrary deps is insufficient.
                check(transaction.cellInputs.size == 1 && transaction.headerDeps.size == 2 &&
                    transaction.headerDeps[1] == snapshot.guard.withdrawBlockHash &&
                    transaction.headerDeps[0] in snapshot.receipt.rawTransaction.headerDeps) {
                    "Signed withdrawal association changed"
                }
            }
            Triple(point, found, snapshot)
        }
        return SubmissionAuthorization {
            for ((point, found, snapshot) in pinned) {
                check(guards(context.network, point) == found) { "Withdrawal association changed" }
                if (snapshot != null) {
                    requireSpendableGuardRows(snapshot.guard)
                    check(validateGuard(context.network, point, context.walletId) == snapshot) {
                        "Withdrawal details need fresh verification"
                    }
                }
            }
        }
    }
}

private fun <T> DaoRecoveryResult<T>.valueOrNull(): T? = (this as? DaoRecoveryResult.Success<T>)?.value

internal fun DaoDeposit.withoutRecoveryEvidence(): DaoDeposit = copy(
    status = when (status) {
        DaoCellStatus.WITHDRAWING, DaoCellStatus.UNLOCKING, DaoCellStatus.COMPLETED -> status
        else -> DaoCellStatus.LOCKED
    }, depositBlockHash = "", depositEpoch = null, depositTimestamp = 0,
    withdrawBlockHash = null, withdrawEpoch = null, unlockEpoch = null, compensation = 0,
    lockRemainingHours = null, compensationCycleProgress = 0f, apc = 0.0, recoveryReceiptId = null,
    consumedDepositOutPoints = emptyList(),
)
