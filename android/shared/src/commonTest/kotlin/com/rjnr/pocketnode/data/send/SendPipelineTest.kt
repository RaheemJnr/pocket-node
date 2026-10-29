package com.rjnr.pocketnode.data.send

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.FakeSyncPreferences
import com.rjnr.pocketnode.core.prefs.FakeUiPreferences
import com.rjnr.pocketnode.core.time.FakeClock
import com.rjnr.pocketnode.data.gateway.FakeLightClientApi
import com.rjnr.pocketnode.data.gateway.LedgerReader
import com.rjnr.pocketnode.data.gateway.LightClientFixtures
import com.rjnr.pocketnode.data.gateway.SyncCoordinator
import com.rjnr.pocketnode.data.gateway.models.Cell
import com.rjnr.pocketnode.data.gateway.models.CellInput
import com.rjnr.pocketnode.data.gateway.models.CellOutput
import com.rjnr.pocketnode.data.gateway.models.JniCell
import com.rjnr.pocketnode.data.gateway.models.JniPagination
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.Transaction
import com.rjnr.pocketnode.data.storage.FakeBalanceCache
import com.rjnr.pocketnode.data.storage.FakeHeaderCache
import com.rjnr.pocketnode.data.storage.FakePendingBroadcastStore
import com.rjnr.pocketnode.data.storage.FakeSubAccountCandidateStore
import com.rjnr.pocketnode.data.storage.FakeSyncProgressStore
import com.rjnr.pocketnode.data.storage.FakeTransactionStore
import com.rjnr.pocketnode.data.storage.FakeWalletRegistry
import com.rjnr.pocketnode.data.storage.PendingBroadcastRecord
import com.rjnr.pocketnode.data.storage.PendingBroadcastStore
import com.rjnr.pocketnode.data.sync.SyncEngine
import com.rjnr.pocketnode.data.transaction.PrivateKeySigner
import com.rjnr.pocketnode.data.transaction.RecipientOutput
import com.rjnr.pocketnode.data.transaction.TransactionBuilder
import com.rjnr.pocketnode.data.validation.NetworkValidator
import com.rjnr.pocketnode.data.wallet.AddressUtils
import com.rjnr.pocketnode.data.wallet.WalletDerivation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared send path over a scripted bridge.
 *
 * What these pin is the money path's observable behaviour, which is the part
 * that must not drift when the code moves: which cells a send is allowed to
 * select, which rows exist before and after the broadcast, and which errors the
 * user sees when the node refuses. The bridge answers come from
 * [LightClientFixtures] where the recorded shape carries the meaning (the tip
 * header, the spent-outpoint walk); the live cells are built here because they
 * have to be locked to a key this test can sign with, and the fixture lock
 * belongs to a key nobody has.
 *
 * The key is the all-`0x01` scalar, a public test vector. Nothing is funded at
 * the address it derives, and no key material is printed or asserted on.
 */
class SendPipelineTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val broadcasts = FakePendingBroadcastStore()
    private val transactions = FakeTransactionStore()
    private val uiPreferences = FakeUiPreferences()
    private val clock = FakeClock()

    private fun pipelineOver(
        api: FakeLightClientApi,
        pendingBroadcasts: PendingBroadcastStore = broadcasts,
        authorizer: InputSubmissionAuthorizer = NoDaoRecoveryAdoption,
    ): SendPipeline {
        val syncPreferences = FakeSyncPreferences()
        val ledger = LedgerReader(
            lightClient = api,
            balanceCache = FakeBalanceCache(),
            transactionStore = transactions,
            headerCache = FakeHeaderCache(),
            walletRegistry = FakeWalletRegistry(),
            candidates = FakeSubAccountCandidateStore(),
            syncPreferences = syncPreferences,
            uiPreferences = uiPreferences,
            json = json,
            logger = NoopLogger,
        )
        return SendPipeline(
            lightClient = api,
            transactionBuilder = TransactionBuilder(NetworkValidator()),
            ledger = ledger,
            pendingBroadcasts = pendingBroadcasts,
            transactions = transactions,
            syncEngine = SyncEngine(api, syncPreferences, json, NoopLogger),
            syncCoordinator = SyncCoordinator(
                walletRegistry = FakeWalletRegistry(),
                syncProgressStore = FakeSyncProgressStore(),
                subAccountCandidateStore = FakeSubAccountCandidateStore(),
                transactionStore = transactions,
                lightClient = api,
                syncPreferences = syncPreferences,
                json = json,
                logger = NoopLogger,
                queryContext = EmptyCoroutineContext,
            ),
            uiPreferences = uiPreferences,
            json = json,
            logger = NoopLogger,
            inputAuthorizer = authorizer,
            clock = clock,
            // Keep every hop on the test dispatcher so the post-broadcast
            // re-register is observable from the test body: with a real
            // dispatcher it would resume off the scheduler.
            queryContext = EmptyCoroutineContext,
        )
    }

    /** The bridge answers every read a send performs, and accepts the broadcast. */
    private fun api(
        cells: List<Cell> = LIVE_CELLS,
        sendAnswer: String? = null,
    ) = FakeLightClientApi()
        .enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
        .enqueue("getTransactions", LightClientFixtures.GET_TRANSACTIONS_PAGE)
        .enqueue("getCells", cellsPage(cells))
        .apply { if (sendAnswer != null) enqueue("sendTransaction", sendAnswer) }

    private fun ctx(scope: CoroutineScope, isSyncing: Boolean = false) = SendContext(
        network = NetworkType.TESTNET,
        walletId = WALLET,
        activeScript = FROM_SCRIPT,
        isSyncing = { isSyncing },
        scope = scope,
    )

    private fun signer() = PrivateKeySigner(TEST_KEY.copyOf())


    @Test fun maxQuoteUsesCapturedCustomDestinationAndMatchesPreview() = runTest {
        val custom = AddressUtils.encode(FROM_SCRIPT.copy(codeHash = "0x" + "42".repeat(32),
            args = "0x" + "bb".repeat(1024)), NetworkType.TESTNET)
        val cells = LIVE_CELLS.map { it.copy(capacity = "0x${2000_00000000L.toString(16)}") }
        val max = pipelineOver(api(cells = cells)).maxSendable(ctx(this), FROM_ADDRESS, custom)
        val plan = pipelineOver(api(cells = cells)).previewTransfer(ctx(this), FROM_ADDRESS,
            listOf(RecipientOutput(custom, max)))
        assertEquals(0L, plan.changeShannons)
        assertTrue(plan.feeShannons > 1000L)
        assertEquals(cells.sumOf { it.capacityAsLong() } - plan.feeShannons, max)
    }

    @Test fun contradictoryDestinationIsRejectedBySingleBulkPreviewAndReviewedEntryPoints() = runTest {
        val malformed = AddressUtils.encode(FROM_SCRIPT.copy(hashType = "data"), NetworkType.TESTNET)
        val recipients = listOf(RecipientOutput(malformed, 100_00000000L))
        val pipeline = pipelineOver(api())
        kotlin.test.assertFailsWith<IllegalArgumentException> { pipeline.previewTransfer(ctx(this), FROM_ADDRESS, recipients) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { pipeline.previewBatches(ctx(this), FROM_ADDRESS, listOf(recipients)) }
        assertTrue(pipeline.prepareAndSend(ctx(this), FROM_ADDRESS, malformed, 100_00000000L, signer()).exceptionOrNull() is IllegalArgumentException)
        assertTrue(pipeline.prepareAndSendBulk(ctx(this), FROM_ADDRESS, recipients, signer()).exceptionOrNull() is IllegalArgumentException)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ReviewedTransfer(WALLET, FROM_ADDRESS, NetworkType.TESTNET, recipients, 1000, 1)
        }
        assertTrue(broadcasts.byHash.isEmpty())
    }

    @Test fun customDestinationStaysSendableThroughSingleBulkAndReviewedPaths() = runTest {
        val customScript = FROM_SCRIPT.copy(codeHash = "0x" + "42".repeat(32), hashType = "data2")
        val custom = AddressUtils.encode(customScript, NetworkType.TESTNET)
        val outputs = listOf(RecipientOutput(custom, 100_00000000L))
        val plan = pipelineOver(api()).previewTransfer(ctx(backgroundScope), FROM_ADDRESS, outputs)
        for (mode in 0..2) {
            val store = FakePendingBroadcastStore()
            val pipeline = pipelineOver(api(sendAnswer = "\"$PENDING_HASH\""), store)
            val hash = when (mode) {
                0 -> pipeline.prepareAndSend(ctx(backgroundScope), FROM_ADDRESS, custom, 100_00000000L, signer()).getOrThrow()
                1 -> pipeline.prepareAndSendBulk(ctx(backgroundScope), FROM_ADDRESS, outputs, signer()).getOrThrow()
                else -> pipeline.sendReviewed(ctx(backgroundScope),
                    ReviewedTransfer(WALLET, FROM_ADDRESS, NetworkType.TESTNET, outputs, plan.feeShannons, 1), signer(), { true })
            }
            val sent = json.decodeFromString<Transaction>(assertNotNull(store.byHash[hash]).signedTxJson)
            assertEquals(customScript, sent.cellOutputs.first().lock)
            assertEquals(100_00000000L, sent.cellOutputs.first().capacity.removePrefix("0x").toLong(16))
        }
    }

    @Test fun cancellationAtBroadcastPreservesExactPendingSignedTransaction() = runTest {
        val bridge = object : FakeLightClientApi() {
            override fun sendTransaction(txJson: String): String? {
                throw kotlinx.coroutines.CancellationException("public test cancellation")
            }
        }
        val signed = json.decodeFromString<Transaction>(pendingChangeTx())
        val result = runCatching { pipelineOver(bridge).sendTransaction(ctx(this), signed).getOrThrow() }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        val row = broadcasts.byHash.values.single()
        assertEquals("BROADCASTING", row.state)
        assertEquals(json.encodeToString(signed), row.signedTxJson)
    }

    // ---- preview: the cell set a send is allowed to select from ----

    @Test
    fun withNothingReservedSelectionTakesTheSmallestCell() = runTest {
        // The control for the test below. Selection is smallest-first, so the
        // 300 CKB cell wins on its own merits and reserving it is the only way
        // to prove the filter ran.
        val plan = pipelineOver(api()).previewTransfer(
            ctx(this),
            FROM_ADDRESS,
            listOf(RecipientOutput(TO_ADDRESS, 100_00000000L)),
        )

        assertEquals(listOf(SMALLEST_CELL.outPoint), plan.selectedCells.map { it.outPoint })
    }

    @Test
    fun previewExcludesTheOutpointsAnInFlightBroadcastReserved() = runTest {
        // Reserve the cell selection would otherwise prefer. Only a working
        // reservation filter can push the plan onto the larger cell, so unlike
        // reserving the larger one this cannot pass vacuously.
        broadcasts.insert(
            pendingRow(
                txHash = PENDING_HASH,
                state = "BROADCASTING",
                reserved = listOf(SMALLEST_CELL.outPoint),
                signedTx = pendingChangeTx(),
            )
        )

        val plan = pipelineOver(api()).previewTransfer(
            ctx(this),
            FROM_ADDRESS,
            listOf(RecipientOutput(TO_ADDRESS, 100_00000000L)),
        )

        assertEquals(listOf(LARGEST_CELL.outPoint), plan.selectedCells.map { it.outPoint })
    }

    @Test
    fun previewIncludesSyntheticChangeOnlyForAHashBroadcastThisSession() = runTest {
        // A pending row whose change output pays us back. Until this session
        // broadcasts it, its change is not in the node's in-memory pool and must
        // not be spendable; after, it must be.
        broadcasts.insert(
            pendingRow(
                txHash = PENDING_HASH,
                state = "BROADCASTING",
                reserved = LIVE_CELLS.map { it.outPoint },
                signedTx = pendingChangeTx(),
            )
        )
        val pipeline = pipelineOver(api(sendAnswer = "\"$PENDING_HASH\""))

        // Every live cell is reserved and the change is not yet session-known,
        // so there is nothing to select and planning fails.
        val beforeBroadcast = runCatching {
            pipeline.previewTransfer(
                ctx(this),
                FROM_ADDRESS,
                listOf(RecipientOutput(TO_ADDRESS, 100_00000000L)),
            )
        }
        assertTrue(beforeBroadcast.isFailure)

        // Broadcasting that exact transaction this session admits its change.
        pipeline.sendTransaction(ctx(this), json.decodeFromString(pendingChangeTx())).getOrThrow()

        val plan = pipeline.previewTransfer(
            ctx(this),
            FROM_ADDRESS,
            listOf(RecipientOutput(TO_ADDRESS, 100_00000000L)),
        )
        assertEquals(
            listOf(OutPoint(PENDING_HASH, "0x0")),
            plan.selectedCells.map { it.outPoint },
        )
    }

    // ---- prepareAndSend ----

    @Test
    fun prepareAndSendWritesBothRowsBroadcastsAndFlipsToBroadcast() = runTest {
        val api = api()
        // The hash the builder will compute is not known up front, so answer the
        // broadcast with whatever it was handed: `sendTransaction` echoes the
        // hash, which is the equality the real bridge guarantees.
        val pipeline = pipelineOver(api)
        val builder = TransactionBuilder(NetworkValidator())
        val expectedTx = builder.buildTransfer(
            FROM_ADDRESS, TO_ADDRESS, 100_00000000L, LIVE_CELLS, signer(), NetworkType.TESTNET,
        )
        val expectedHash = builder.computeTxHash(expectedTx)
        api.enqueue("sendTransaction", "\"$expectedHash\"")

        val hash = pipeline.prepareAndSend(
            ctx = ctx(this),
            fromAddress = FROM_ADDRESS,
            toAddress = TO_ADDRESS,
            amountShannons = 100_00000000L,
            signer = signer(),
        ).getOrThrow()

        assertEquals(expectedHash, hash)
        val row = assertNotNull(broadcasts.byHash[expectedHash])
        assertEquals("BROADCAST", row.state)
        assertEquals(WALLET, row.walletId)
        assertEquals("TESTNET", row.network)
        assertEquals(LightClientFixtures.TIP_HEADER_NUMBER, row.submittedAtTipBlock)
        assertEquals(0, row.nullCount)
        assertEquals(clock.now, row.createdAt)

        val pending = transactions.pendingInserts.single()
        assertEquals(expectedHash, pending.txHash)
        assertEquals("out", pending.direction)
        assertTrue(pending.balanceChange != "0x0", "the pending row must carry the net debit")
        assertNotNull(pending.feeShannons)
    }

    @Test
    fun aRejectedBroadcastDeletesBothRowsAndReportsTheNodesReason() = runTest {
        val pipeline = pipelineOver(
            api(sendAnswer = "${SendPipeline.BROADCAST_ERROR_PREFIX}Resolve failed Unknown")
        )

        val result = pipeline.prepareAndSend(
            ctx = ctx(this),
            fromAddress = FROM_ADDRESS,
            toAddress = TO_ADDRESS,
            amountShannons = 100_00000000L,
            signer = signer(),
        )

        assertEquals(
            "Broadcast rejected: Resolve failed Unknown",
            result.exceptionOrNull()?.message,
        )
        assertTrue(broadcasts.byHash.isEmpty(), "the pending_broadcasts row must be rolled back")
        assertTrue(transactions.pendingInserts.isEmpty(), "the activity row must be rolled back")
        assertEquals(1, transactions.deletedHashes.size)
    }

    @Test
    fun aNullBroadcastDeletesBothRowsAndReportsTheNativeFailure() = runTest {
        // No "sendTransaction" answer scripted: the fake answers null, which is
        // what the bridge does when the node is not running.
        val pipeline = pipelineOver(api())

        val result = pipeline.prepareAndSend(
            ctx = ctx(this),
            fromAddress = FROM_ADDRESS,
            toAddress = TO_ADDRESS,
            amountShannons = 100_00000000L,
            signer = signer(),
        )

        assertEquals("Send failed - native returned null", result.exceptionOrNull()?.message)
        assertTrue(broadcasts.byHash.isEmpty())
        assertTrue(transactions.pendingInserts.isEmpty())
    }

    @Test
    fun aHashMismatchReKeysBothRowsUnderTheReturnedHash() = runTest {
        val returnedHash = "0x" + "cd".repeat(32)
        val pipeline = pipelineOver(api(sendAnswer = "\"$returnedHash\""))

        val hash = pipeline.prepareAndSend(
            ctx = ctx(this),
            fromAddress = FROM_ADDRESS,
            toAddress = TO_ADDRESS,
            amountShannons = 100_00000000L,
            signer = signer(),
        ).getOrThrow()

        assertEquals(returnedHash, hash)
        val row = assertNotNull(broadcasts.byHash[returnedHash])
        assertEquals("BROADCAST", row.state)
        assertEquals(1, broadcasts.byHash.size, "the pre-broadcast hash must be gone")
        assertEquals(returnedHash, transactions.pendingInserts.single().txHash)
    }

    @Test
    fun aFeeThatMovedSinceTheReviewSheetAbortsTheSend() = runTest {
        val pipeline = pipelineOver(api(sendAnswer = "\"0x00\""))

        val result = pipeline.prepareAndSend(
            ctx = ctx(this),
            fromAddress = FROM_ADDRESS,
            toAddress = TO_ADDRESS,
            amountShannons = 100_00000000L,
            signer = signer(),
            expectedFeeShannons = 1L,
        )

        val actualFee = TransactionBuilder(NetworkValidator())
            .planTransfer(listOf(RecipientOutput(TO_ADDRESS, 100_00000000L)), LIVE_CELLS)
            .feeShannons
        assertEquals(
            "Fee changed since you reviewed this transaction " +
                "(expected 1, actual $actualFee shannons). Nothing was sent, please review and confirm again.",
            result.exceptionOrNull()?.message,
        )
        assertTrue(broadcasts.byHash.isEmpty(), "nothing may be reserved when the fee moved")
    }

    @Test fun singleSendInvokesLastUseCallbackBeforeBroadcast() = runTest {
        val key = TEST_KEY.copyOf(); var callbacks = 0
        val builder = TransactionBuilder(NetworkValidator())
        val signed = builder.buildTransfer(FROM_ADDRESS, TO_ADDRESS, 100_00000000L,
            LIVE_CELLS, PrivateKeySigner(key), NetworkType.TESTNET)
        val expected = builder.computeTxHash(signed)
        val api = object : FakeLightClientApi() {
            override fun sendTransaction(txJson: String): String? {
                assertTrue(key.all { it == 0.toByte() })
                assertEquals(1, callbacks)
                return "\"$expected\""
            }
        }.enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
            .enqueue("getTransactions", LightClientFixtures.GET_TRANSACTIONS_PAGE)
            .enqueue("getCells", cellsPage(LIVE_CELLS))
        val hash = pipelineOver(api).prepareAndSend(ctx(this), FROM_ADDRESS, TO_ADDRESS,
            100_00000000L, PrivateKeySigner(key), onSigned = { callbacks++; key.fill(0) }).getOrThrow()
        assertEquals(expected, hash); assertEquals(1, callbacks)
    }

    @Test fun successiveBulkBatchesKeepBorrowedSigningBufferIntact() = runTest {
        val borrowed = TEST_KEY.copyOf()
        repeat(2) { batch ->
            val api = api()
            val recipients = listOf(RecipientOutput(TO_ADDRESS, (100 + batch) * 100_000000L))
            val signed = TransactionBuilder(NetworkValidator()).buildMultiTransfer(
                FROM_ADDRESS, recipients, LIVE_CELLS, PrivateKeySigner(borrowed), NetworkType.TESTNET)
            val hash = TransactionBuilder(NetworkValidator()).computeTxHash(signed)
            api.enqueue("sendTransaction", "\"$hash\"")
            pipelineOver(api).prepareAndSendBulk(ctx(this), FROM_ADDRESS, recipients, PrivateKeySigner(borrowed)).getOrThrow()
            assertTrue(borrowed.contentEquals(TEST_KEY))
            // A later batch sees a fresh cell snapshot; the borrowed signer remains reusable.
            broadcasts.byHash.clear()
        }
    }

    // ---- bulk ----

    @Test
    fun bulkRemembersTheBatchHashSoTheActivityListCanBadgeIt() = runTest {
        val api = api()
        val pipeline = pipelineOver(api)
        val recipients = listOf(
            RecipientOutput(TO_ADDRESS, 100_00000000L),
            RecipientOutput(TO_ADDRESS_2, 70_00000000L),
        )
        val expectedHash = TransactionBuilder(NetworkValidator()).let {
            it.computeTxHash(
                it.buildMultiTransfer(
                    FROM_ADDRESS, recipients, LIVE_CELLS, signer(), NetworkType.TESTNET,
                )
            )
        }
        api.enqueue("sendTransaction", "\"$expectedHash\"")

        val hash = pipeline.prepareAndSendBulk(ctx(this), FROM_ADDRESS, recipients, signer())
            .getOrThrow()

        assertEquals(expectedHash, hash)
        assertTrue(uiPreferences.isBulkTxHash(hash))
    }

    // ---- retry ----

    @Test
    fun rejectedGuardedRetryPreservesOriginalSignedBytesAndFailedRecord() = runTest {
        val original = pendingRow(txHash = PENDING_HASH, state = "FAILED",
            reserved = listOf(LIVE_CELLS[0].outPoint), signedTx = pendingChangeTx(), nullCount = 3)
        broadcasts.insert(original)
        val bridge = api(sendAnswer = "\"$PENDING_HASH\"")
        val result = pipelineOver(bridge, authorizer = InputSubmissionAuthorizer { _, _ ->
            error("Recovery unavailable")
        }).retryBroadcast(ctx(this), PENDING_HASH)
        assertTrue(result.isFailure)
        assertEquals(original, broadcasts.byHash[PENDING_HASH])
        assertTrue(bridge.callsTo("sendTransaction").isEmpty())
    }

    @Test
    fun lateGuardRefusalPreservesRetryBytesAndConcurrentConfirmation() = runTest {
        val original = pendingRow(txHash = PENDING_HASH, state = "FAILED",
            reserved = listOf(LIVE_CELLS[0].outPoint), signedTx = pendingChangeTx(), nullCount = 3)
        broadcasts.insert(original)
        val bridge = api(sendAnswer = "\"$PENDING_HASH\"")
        var checks = 0
        val result = pipelineOver(bridge, authorizer = InputSubmissionAuthorizer { _, _ ->
            SubmissionAuthorization {
                checks++
                if (checks == 3) {
                    broadcasts.compareAndUpdateState(PENDING_HASH, "FAILED", "CONFIRMED", 999)
                    error("Recovery invalidated after preparation")
                }
            }
        }).retryBroadcast(ctx(this), PENDING_HASH)
        assertTrue(result.isFailure)
        assertEquals(original.copy(state = "CONFIRMED", lastCheckedAt = 999), broadcasts.byHash[PENDING_HASH])
        assertTrue(bridge.callsTo("sendTransaction").isEmpty())
    }

    @Test
    fun invalidationDuringPendingPersistenceAbortsBeforeBridgeSubmission() = runTest {
        var valid = true
        val store = object : PendingBroadcastStore by broadcasts {
            override suspend fun insertFresh(record: PendingBroadcastRecord): Boolean {
                val inserted = broadcasts.insertFresh(record)
                valid = false
                return inserted
            }
        }
        val bridge = api(sendAnswer = "\"$PENDING_HASH\"")
        val signed = json.decodeFromString<Transaction>(pendingChangeTx())
        val result = pipelineOver(bridge, store, InputSubmissionAuthorizer { _, _ ->
            SubmissionAuthorization { check(valid) { "Recovery expired" } }
        }).sendTransaction(ctx(this), signed)
        assertTrue(result.isFailure)
        assertTrue(bridge.callsTo("sendTransaction").isEmpty())
        assertEquals(json.encodeToString(signed), broadcasts.byHash.values.single().signedTxJson)
        assertEquals("FAILED", broadcasts.byHash.values.single().state)
        assertTrue(broadcasts.getActive(WALLET, NetworkType.TESTNET.name).isEmpty())
    }

    @Test
    fun cancellationAfterInsertCommitBeforeReturnReleasesKnownUnsentReservation() = runTest {
        val inserted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = object : PendingBroadcastStore by broadcasts {
            override suspend fun insertFresh(record: PendingBroadcastRecord): Boolean {
                val result = broadcasts.insertFresh(record)
                inserted.complete(Unit)
                release.await()
                return result
            }
        }
        val bridge = api(sendAnswer = "\"$PENDING_HASH\"")
        val signed = json.decodeFromString<Transaction>(pendingChangeTx())
        val sending = async { pipelineOver(bridge, store).sendTransaction(ctx(this), signed) }
        inserted.await()
        sending.cancel()
        release.complete(Unit)
        sending.join()
        assertEquals("FAILED", broadcasts.byHash.values.single().state)
        assertEquals(json.encodeToString(signed), broadcasts.byHash.values.single().signedTxJson)
        assertTrue(bridge.callsTo("sendTransaction").isEmpty())
    }

    @Test
    fun retryReBroadcastsTheStoredBytesRatherThanRebuilding() = runTest {
        val stored = pendingChangeTx()
        broadcasts.insert(
            pendingRow(
                txHash = PENDING_HASH,
                state = "FAILED",
                reserved = listOf(LIVE_CELLS[0].outPoint),
                signedTx = stored,
                nullCount = 3,
            )
        )
        val api = api(sendAnswer = "\"$PENDING_HASH\"")
        val pipeline = pipelineOver(api)

        val hash = pipeline.retryBroadcast(ctx(this), PENDING_HASH).getOrThrow()

        assertEquals(PENDING_HASH, hash)
        // The bytes handed to the bridge are the stored ones, re-encoded.
        val broadcastJson = api.callsTo("sendTransaction").single().args.first() as String
        assertEquals(
            json.decodeFromString<Transaction>(stored),
            json.decodeFromString<Transaction>(broadcastJson),
        )
        assertEquals("BROADCAST", broadcasts.byHash.getValue(PENDING_HASH).state)
        // Acceptance rearms observation; refusals retain the captured counters.
        assertEquals(0, broadcasts.byHash.getValue(PENDING_HASH).nullCount)
    }

    @Test
    fun acceptedRetryDoesNotOverwriteProgressDuringNativeSubmission() = runTest {
        val original = pendingRow(PENDING_HASH, "FAILED", listOf(LIVE_CELLS[0].outPoint), pendingChangeTx(), 3)
        for (state in listOf("CONFIRMED", "BROADCAST")) {
            broadcasts.insert(original)
            val advanced = original.copy(state = state, lastCheckedAt = 999)
            val bridge = object : FakeLightClientApi() {
                override fun sendTransaction(txJson: String): String? {
                    assertEquals(original.signedTxJson, txJson)
                    broadcasts.byHash[PENDING_HASH] = advanced
                    return "\"$PENDING_HASH\""
                }
            }.enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
                .enqueue("getTransactions", LightClientFixtures.GET_TRANSACTIONS_PAGE)
                .enqueue("getCells", cellsPage(LIVE_CELLS))
            pipelineOver(bridge).retryBroadcast(ctx(this), PENDING_HASH).getOrThrow()
            assertEquals(advanced, broadcasts.byHash[PENDING_HASH])
        }
    }

    @Test
    fun acceptedRetryRemainsUnresolvedForInPoolAndNotFound() = runTest {
        val original = pendingRow(PENDING_HASH, "FAILED", listOf(LIVE_CELLS[0].outPoint), pendingChangeTx(), 3)
        val currentTip = LightClientFixtures.TIP_HEADER_NUMBER
        for (status in listOf(com.rjnr.pocketnode.data.sync.TxFetchResult.InPool, com.rjnr.pocketnode.data.sync.TxFetchResult.NotFound)) {
            broadcasts.insert(original)
            pipelineOver(api(sendAnswer = "\"$PENDING_HASH\"")).retryBroadcast(ctx(this), PENDING_HASH).getOrThrow()
            val wd = com.rjnr.pocketnode.data.sync.BroadcastWatchdog(broadcasts,
                com.rjnr.pocketnode.data.sync.TransactionStatusGateway { status }, transactions,
                object : com.rjnr.pocketnode.data.gateway.TipSource {
                    override val tipFlow = kotlinx.coroutines.flow.MutableStateFlow(currentTip)
                    override suspend fun fetchAndPublishTip() = currentTip
                    override fun activeWalletAndNetworkOrNull() = WALLET to "TESTNET"
                }, { true }, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler), NoopLogger, clock)
            repeat(3) { wd.checkAll(currentTip, WALLET, "TESTNET") }
            assertEquals("BROADCAST", broadcasts.byHash.getValue(PENDING_HASH).state)
            assertEquals(currentTip, broadcasts.byHash.getValue(PENDING_HASH).submittedAtTipBlock)
            wd.checkAll(currentTip + 25, WALLET, "TESTNET")
            assertEquals("BROADCAST", broadcasts.byHash.getValue(PENDING_HASH).state)
        }
    }

    @Test
    fun retryOfAHashWithNoFailedRowExplainsItselfInsteadOfSending() = runTest {
        val result = pipelineOver(api()).retryBroadcast(ctx(this), PENDING_HASH)

        assertEquals(
            "This transaction is too old to retry automatically. Please send a new one.",
            result.exceptionOrNull()?.message,
        )
    }

    // ---- pre-flight ----

    @Test
    fun sendTransactionRefusesAnOutputBelowTheMinimumCellCapacity() = runTest {
        val dust = json.decodeFromString<Transaction>(pendingChangeTx()).let { tx ->
            tx.copy(
                cellOutputs = listOf(
                    tx.cellOutputs.first().copy(capacity = "0x${60_00000000L.toString(16)}")
                )
            )
        }

        val result = pipelineOver(api()).sendTransaction(ctx(this), dust)

        assertEquals(
            "Output capacity 60.0 CKB is below minimum 61 CKB",
            result.exceptionOrNull()?.message,
        )
        assertTrue(broadcasts.byHash.isEmpty())
    }

    @Test
    fun sendTransactionRefusesWhenTheActiveWalletIsNoLongerTheSigner() = runTest {
        val tx = json.decodeFromString<Transaction>(pendingChangeTx())

        val result = pipelineOver(api()).sendTransaction(
            ctx(this), tx, expectedWalletId = "some-other-wallet",
        )

        assertEquals(
            "Wallet changed before broadcast; transaction not sent",
            result.exceptionOrNull()?.message,
        )
        assertTrue(broadcasts.byHash.isEmpty())
    }

    // ---- mutex boundary ----

    /**
     * The broadcast must happen OUTSIDE the send mutex. Holding the mutex
     * across a network call would serialize every send behind the slowest one,
     * which is the whole reason `buildReserveAndSend` releases it and lets
     * `sendTransaction` re-acquire it briefly for the idempotent insert.
     *
     * `LightClientApi.sendTransaction` is a blocking bridge call, not a
     * `suspend` one, so a fake cannot park inside the broadcast itself and no
     * coroutine can interleave there whatever the mutex does. What this test
     * gates instead is the first suspension point AFTER the broadcast: the
     * BROADCASTING to BROADCAST compare-and-set. That call is strictly later in
     * program order than the broadcast and equally outside the mutex, so a
     * second send completing its whole selection and pre-broadcast insert while
     * the first is parked there proves the mutex was released before the
     * broadcast ran. Were the mutex still held, the second send would block on
     * `resolveSpendableCells` and this would deadlock until the timeout.
     */
    @Test
    fun theBroadcastRunsOutsideTheSendMutexSoASecondSendCanProceed() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gated = GatedPendingBroadcastStore(broadcasts, gate)
        val pipeline = pipelineOver(EchoingLightClientApi(), pendingBroadcasts = gated)

        val first = async {
            pipeline.prepareAndSend(
                ctx(this), FROM_ADDRESS, TO_ADDRESS, 100_00000000L, signer(),
            )
        }
        val second = async {
            pipeline.prepareAndSend(
                ctx(this), FROM_ADDRESS, TO_ADDRESS, 100_00000000L, signer(),
            )
        }
        advanceUntilIdle()

        // The first send is parked past its broadcast; the second ran to
        // completion behind it, which it could only do with the mutex free.
        assertEquals(2, broadcasts.byHash.size, "both sends must have reserved their inputs")
        val parked = broadcasts.byHash.values.single { it.state == "BROADCASTING" }
        val completed = broadcasts.byHash.values.single { it.state == "BROADCAST" }
        assertTrue(second.isCompleted, "the second send must not be blocked by the first")
        assertTrue(second.await().isSuccess)
        assertTrue(!first.isCompleted, "the first send is still gated")

        // The first send reserved the smallest live cell.
        assertEquals(
            listOf(SMALLEST_CELL.outPoint),
            json.decodeFromString<List<OutPoint>>(parked.reservedInputs),
        )
        // The second spent the FIRST send's change output, which is the sharper
        // half of the proof: synthetic change is only offered for a hash in the
        // session-broadcast set, and that set is written after the broadcast
        // returns. The second send could therefore only see this input because
        // the first had already broadcast while the mutex was free.
        assertEquals(
            listOf(OutPoint(parked.txHash, "0x1")),
            json.decodeFromString<List<OutPoint>>(completed.reservedInputs),
        )

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(first.await().isSuccess)
        assertEquals("BROADCAST", broadcasts.byHash.getValue(parked.txHash).state)
        assertEquals("BROADCAST", broadcasts.byHash.getValue(completed.txHash).state)
    }

    // ---- post-broadcast re-register ----

    @Test
    fun theSyncedWalletReRegistersItsScriptFiveSecondsAfterTheBroadcast() = runTest {
        val api = api(sendAnswer = "\"$PENDING_HASH\"")
        val pipeline = pipelineOver(api)
        api.enqueueFlag("setScripts", true)

        pipeline.sendTransaction(
            ctx(this, isSyncing = false),
            json.decodeFromString(pendingChangeTx()),
        ).getOrThrow()

        assertTrue(api.callsTo("setScripts").isEmpty(), "must wait out the propagation delay")
        advanceTimeBy(5_001)
        runCurrent()

        val call = api.callsTo("setScripts").single()
        assertEquals(SyncCoordinator.CMD_SET_SCRIPTS_PARTIAL, call.args[1])
        // tip - 10, the rescan window that catches the change output.
        assertTrue(
            (call.args[0] as String).contains(
                "0x${(LightClientFixtures.TIP_HEADER_NUMBER - 10).toString(16)}"
            )
        )
    }

    @Test
    fun aCatchingUpWalletDoesNotReRegisterAndSoDoesNotJumpItsScanForward() = runTest {
        val api = api(sendAnswer = "\"$PENDING_HASH\"")
        val pipeline = pipelineOver(api)
        api.enqueueFlag("setScripts", true)

        pipeline.sendTransaction(
            ctx(this, isSyncing = true),
            json.decodeFromString(pendingChangeTx()),
        ).getOrThrow()

        advanceTimeBy(5_001)
        runCurrent()

        assertNull(api.callsTo("setScripts").firstOrNull())
    }

    // ---- fixtures ----

    /**
     * Answers every broadcast with the hash of the transaction it was handed,
     * which is what the real bridge does on success. Lets a test broadcast more
     * than one distinct transaction without knowing their hashes up front, and
     * keeps them all off the hash-mismatch re-key path.
     */
    private inner class EchoingLightClientApi : FakeLightClientApi() {
        init {
            enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
            enqueue("getTransactions", LightClientFixtures.GET_TRANSACTIONS_PAGE)
            enqueue("getCells", cellsPage(LIVE_CELLS))
        }

        override fun sendTransaction(txJson: String): String {
            next("sendTransaction", txJson)
            val tx = json.decodeFromString<Transaction>(txJson)
            return "\"${TransactionBuilder(NetworkValidator()).computeTxHash(tx)}\""
        }
    }

    @Test fun syntheticPendingDataCannotBecomeSpendableCapacity() = runTest {
        for (data in listOf(listOf("0x01"), listOf("0x00"), emptyList())) {
            val store = FakePendingBroadcastStore()
            val tx = PENDING_TX.copy(outputsData = data)
            val hash = TransactionBuilder(NetworkValidator()).computeTxHash(tx)
            store.insert(pendingRow(hash, "BROADCASTING", LIVE_CELLS.map { it.outPoint }, json.encodeToString(tx)))
            val pipeline = pipelineOver(api(sendAnswer = "\"$hash\""), store)
            pipeline.sendTransaction(ctx(this), tx).getOrThrow()
            assertEquals(0L, pipeline.maxSendable(ctx(this), FROM_ADDRESS))
            assertTrue(runCatching { pipeline.previewTransfer(ctx(this), FROM_ADDRESS,
                listOf(RecipientOutput(TO_ADDRESS, 100_00000000L))) }.isFailure)
        }
    }

    /**
     * Parks the FIRST compare-and-set on [gate]. That is the first suspension
     * point after the broadcast, so holding it holds a send exactly where the
     * mutex must already have been released.
     */
    private class GatedPendingBroadcastStore(
        private val delegate: FakePendingBroadcastStore,
        private val gate: CompletableDeferred<Unit>,
    ) : PendingBroadcastStore by delegate {

        private var calls = 0

        override suspend fun compareAndUpdateState(
            hash: String,
            expected: String,
            next: String,
            now: Long,
        ): Int {
            if (calls++ == 0) gate.await()
            return delegate.compareAndUpdateState(hash, expected, next, now)
        }
    }

    private fun pendingRow(
        txHash: String,
        state: String,
        reserved: List<OutPoint>,
        signedTx: String,
        nullCount: Int = 0,
    ) = PendingBroadcastRecord(
        txHash = txHash,
        state = state,
        reservedInputs = json.encodeToString(reserved),
        signedTxJson = signedTx,
        walletId = WALLET,
        network = "TESTNET",
        submittedAtTipBlock = 100L,
        nullCount = nullCount,
        createdAt = 0L,
        lastCheckedAt = 0L,
    )

    /** [PENDING_TX] on the wire. Its single output pays the sender back: synthetic change. */
    private fun pendingChangeTx(): String = json.encodeToString(PENDING_TX)

    private fun cellsPage(cells: List<Cell>): String = json.encodeToString(
        JniPagination(
            objects = cells.map {
                JniCell(
                    output = CellOutput(capacity = it.capacity, lock = it.lock, type = it.type),
                    outputData = "0x",
                    outPoint = it.outPoint,
                    blockNumber = it.blockNumber,
                    txIndex = "0x0",
                )
            },
            lastCursor = null,
        )
    )

    @Test fun reviewedSendRefusesChangedSourceOrGenerationBeforeAnySignature() = runTest {
        for (sourceChanged in listOf(false, true)) {
            var signatures = 0
            val recordingSigner = object : com.rjnr.pocketnode.data.transaction.Signer {
                override fun signRecoverable(messageHash: ByteArray): ByteArray { signatures++; return ByteArray(65) }
            }
            val review = ReviewedTransfer(WALLET, FROM_ADDRESS, NetworkType.TESTNET,
                listOf(RecipientOutput(TO_ADDRESS, 100_00000000L)), 1000, 1)
            val context = if (sourceChanged) ctx(this).copy(walletId = "other") else ctx(this)
            val failure = runCatching { pipelineOver(api()).sendReviewed(context, review, recordingSigner, { sourceChanged }) }
            assertTrue(failure.isFailure)
            assertEquals(0, signatures)
            assertTrue(broadcasts.byHash.isEmpty())
        }
    }

    @Test fun sourceChangeOrCancellationDuringMutexReadCannotReachSigner() = runTest {
        for (cancel in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            var current = true
            var signatures = 0
            val store = object : PendingBroadcastStore by broadcasts {
                override suspend fun getActive(walletId: String, network: String): List<PendingBroadcastRecord> {
                    entered.complete(Unit)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
                    return emptyList()
                }
            }
            val operation = async {
                runCatching { pipelineOver(api(), store).sendReviewed(ctx(backgroundScope),
                    ReviewedTransfer(WALLET, FROM_ADDRESS, NetworkType.TESTNET,
                        listOf(RecipientOutput(TO_ADDRESS, 100_00000000L)), 1000, 1),
                    object : com.rjnr.pocketnode.data.transaction.Signer {
                        override fun signRecoverable(messageHash: ByteArray): ByteArray { signatures++; return ByteArray(65) }
                    }, { current }) }
            }
            entered.await()
            if (cancel) operation.cancel() else current = false
            gate.complete(Unit)
            runCatching { operation.await() }
            assertEquals(0, signatures)
            assertTrue(broadcasts.byHash.isEmpty())
        }
    }

    @Test fun reviewedFeeMismatchNeverReachesSigningOrReservation() = runTest {
        var signatures = 0
        val result = runCatching { pipelineOver(api()).sendReviewed(ctx(backgroundScope),
            ReviewedTransfer(WALLET, FROM_ADDRESS, NetworkType.TESTNET,
                listOf(RecipientOutput(TO_ADDRESS, 100_00000000L)), 1001, 1),
            object : com.rjnr.pocketnode.data.transaction.Signer {
                override fun signRecoverable(messageHash: ByteArray): ByteArray { signatures++; return ByteArray(65) }
            }, { true }) }
        assertTrue(result.isFailure)
        assertEquals(0, signatures)
        assertTrue(broadcasts.byHash.isEmpty())
    }

    @Test fun bulkPreviewAndRealSequentialSignedOutputsAgreeIncludingSelfRecipients() = runTest {
        val builder = TransactionBuilder(NetworkValidator())
        val bridge = object : FakeLightClientApi() {
            override fun sendTransaction(txJson: String): String {
                super.sendTransaction(txJson)
                return "\"${builder.computeTxHash(json.decodeFromString<Transaction>(txJson))}\""
            }
        }.enqueue("getTipHeader", LightClientFixtures.TIP_HEADER)
            .enqueue("getTransactions", LightClientFixtures.GET_TRANSACTIONS_PAGE)
            .enqueue("getCells", cellsPage(listOf(SMALLEST_CELL)))
        val pipeline = pipelineOver(bridge)
        val batches = listOf(
            listOf(RecipientOutput(FROM_ADDRESS, 100_00000000L)),
            listOf(RecipientOutput(TO_ADDRESS, 150_00000000L)),
            listOf(RecipientOutput(TO_ADDRESS_2, 80_00000000L)))
        val fees = pipeline.previewBatches(ctx(backgroundScope), FROM_ADDRESS, batches)
        assertEquals(listOf(1000L, 1000L, 1000L), fees)
        batches.forEachIndexed { index, batch ->
            pipeline.sendReviewed(ctx(backgroundScope), ReviewedTransfer(WALLET, FROM_ADDRESS,
                NetworkType.TESTNET, batch, fees[index], 1, true), signer(), { true })
        }
        val sent = bridge.callsTo("sendTransaction").map { json.decodeFromString<Transaction>(it.args[0] as String) }
        assertEquals(3, sent.size)
        val spent = sent.flatMap { tx -> tx.cellInputs.map { it.previousOutput } }
        assertEquals(spent.size, spent.distinct().size, "a synthetic change outpoint must never be selected twice")
        assertEquals(TO_SCRIPT_2, sent[2].cellOutputs.first().lock)
        assertEquals(80_00000000L, sent[2].cellOutputs.first().capacity.removePrefix("0x").toLong(16))
        assertEquals(149_99998000L - 1000, sent[2].cellOutputs.sumOf { it.capacity.removePrefix("0x").toLong(16) })
        assertEquals(FROM_SCRIPT, sent[0].cellOutputs.first().lock)
        assertEquals(100_00000000L, sent[0].cellOutputs.first().capacity.removePrefix("0x").toLong(16))
        assertEquals(TO_SCRIPT, sent[1].cellOutputs.first().lock)
        assertEquals(150_00000000L, sent[1].cellOutputs.first().capacity.removePrefix("0x").toLong(16))
        assertEquals(2, sent[1].cellInputs.size, "second batch must reuse the self-recipient and change")
        assertEquals(299_99999000L - 1000, sent[1].cellOutputs.sumOf { it.capacity.removePrefix("0x").toLong(16) })
    }

    private companion object {
        /**
         * The all-`0x01` secp256k1 scalar: a public test vector, not a wallet
         * key. Nothing is ever funded at the address it derives.
         */
        val TEST_KEY = ByteArray(32) { 1 }

        const val WALLET = "wallet-1"

        val FROM_SCRIPT: Script =
            WalletDerivation.lockScript(WalletDerivation.publicKey(TEST_KEY))
        val TO_SCRIPT = Script(
            codeHash = Script.SECP256K1_CODE_HASH,
            hashType = "type",
            args = "0x" + "bb".repeat(20),
        )
        val TO_SCRIPT_2 = Script(
            codeHash = Script.SECP256K1_CODE_HASH,
            hashType = "type",
            args = "0x" + "cc".repeat(20),
        )

        val FROM_ADDRESS: String = AddressUtils.encode(FROM_SCRIPT, NetworkType.TESTNET)
        val TO_ADDRESS: String = AddressUtils.encode(TO_SCRIPT, NetworkType.TESTNET)
        val TO_ADDRESS_2: String = AddressUtils.encode(TO_SCRIPT_2, NetworkType.TESTNET)

        val LIVE_CELLS: List<Cell> = listOf(
            Cell(
                outPoint = OutPoint(txHash = "0x" + "aa".repeat(32), index = "0x0"),
                capacity = "0x${400_00000000L.toString(16)}",
                blockNumber = "0x100",
                lock = FROM_SCRIPT,
                type = null,
                data = "0x",
            ),
            Cell(
                outPoint = OutPoint(txHash = "0x" + "bb".repeat(32), index = "0x1"),
                capacity = "0x${300_00000000L.toString(16)}",
                blockNumber = "0x101",
                lock = FROM_SCRIPT,
                type = null,
                data = "0x",
            ),
        )

        /** The 300 CKB cell: what smallest-first selection reaches for by default. */
        val SMALLEST_CELL: Cell = LIVE_CELLS[1]

        /** The 400 CKB cell: only selected once [SMALLEST_CELL] is reserved away. */
        val LARGEST_CELL: Cell = LIVE_CELLS[0]

        /**
         * An already-signed transaction that spends BOTH live cells and pays
         * the whole remainder back to the sender: the change a pending
         * broadcast is about to create.
         */
        val PENDING_TX = Transaction(
            version = "0x0",
            cellDeps = emptyList(),
            headerDeps = emptyList(),
            cellInputs = LIVE_CELLS.map { CellInput(previousOutput = it.outPoint, since = "0x0") },
            cellOutputs = listOf(
                CellOutput(
                    capacity = "0x${690_00000000L.toString(16)}",
                    lock = FROM_SCRIPT,
                    type = null,
                )
            ),
            outputsData = listOf("0x"),
            witnesses = listOf("0x"),
        )

        /** [PENDING_TX]'s real hash, so the broadcast path never takes the mismatch branch. */
        val PENDING_HASH: String =
            TransactionBuilder(NetworkValidator()).computeTxHash(PENDING_TX)
    }
}
