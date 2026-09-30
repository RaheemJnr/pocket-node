package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nervosnetwork.ckblightclient.LightClientNative
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.PendingBroadcastEntity
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.CellInput
import com.rjnr.pocketnode.data.gateway.models.CellOutput
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import com.rjnr.pocketnode.data.gateway.models.JniPagination
import com.rjnr.pocketnode.data.gateway.models.JniTransactionView
import com.rjnr.pocketnode.data.gateway.models.JniTxWithCell
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.Transaction
import com.rjnr.pocketnode.data.transaction.TransactionBuilder
import com.rjnr.pocketnode.data.validation.NetworkValidator
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletInfo
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * #538 review, "wiring untested": the pure functions in SelfTransferSignature.kt
 * (isSelfTransferSignature, sendTransactionPendingAmount, retryPendingOverride,
 * sweepRowDisplay, activeSelfTransferCandidateArgs) are unit-tested directly in
 * SelfTransferSignatureTest.kt, but nothing proved the ACTUAL call sites in
 * GatewayRepository.kt invoke them correctly. This suite calls the real
 * GatewayRepository methods (sendTransaction, retryBroadcast,
 * selfWalletLockArgsFor, getTransactions) against a real in-memory AppDatabase
 * and real WalletPreferences/CacheManager/TransactionBuilder, with only the
 * JNI-touching surface faked: BroadcastClient (a fake, same shape as
 * GatewayRepositorySendTransactionTest) for sendTransaction/retryBroadcast, and
 * a Robolectric shadow of LightClientNative (same shape as #529's
 * GatewayRepositoryDaoUnlockTest; MockK cannot stub `external fun`, which is
 * why sendTransaction/retryBroadcast route their tip read through the
 * injectable LightClientReadOnly instead and getTransactions does not) for
 * getTransactions's own direct native calls.
 *
 * Every test here was verified to fail when the production call site it pins
 * is reverted, then to pass again once restored; see this file's sibling
 * report for the exact revert used and the failure each one produced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    shadows = [SweepShadowLightClientNative::class],
    instrumentedPackages = ["com.nervosnetwork.ckblightclient"],
)
class GatewayRepositorySelfTransferWiringTest {

    private lateinit var db: AppDatabase
    private lateinit var cacheManager: CacheManager
    private lateinit var transactionBuilder: TransactionBuilder
    private lateinit var walletPreferences: WalletPreferences
    private lateinit var repository: GatewayRepository

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val walletId = "wallet-1"
    private val network = NetworkType.TESTNET
    private val script = Script(Script.SECP256K1_CODE_HASH, "type", "0x" + "11".repeat(20))
    private val ckb = 100_000_000L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cacheManager = CacheManager(db.transactionDao(), db.balanceCacheDao(), NoopLogger)
        transactionBuilder = TransactionBuilder(NetworkValidator())

        SweepShadowLightClientNative.transactionsPage = null

        walletPreferences = WalletPreferences(context, NoopLogger)
        walletPreferences.setActiveWalletId(walletId)
        walletPreferences.setLastVacuumAt(System.currentTimeMillis())

        val nodeLifecycle = mockk<NodeLifecycle>(relaxed = true)
        every { nodeLifecycle.currentNetwork } returns network

        val keyManager = mockk<KeyManager>(relaxed = true)
        coEvery { keyManager.hasWallet() } returns true
        every { keyManager.deriveWalletInfoFromEntity(any()) } returns
            WalletInfo("0xpub", script, "ckt1active", "ckb1active")
        // Test 3 (selfWalletLockArgsFor) needs a real, different Script back
        // for the "other wallet" address so the exclusion is genuinely
        // exercised, not just whatever a relaxed mock's default happens to be.
        every { keyManager.deriveLockScriptFromAddress("ckt1other") } returns
            Script(Script.SECP256K1_CODE_HASH, "type", otherWalletArgs)

        // The fake recomputes the hash from whatever tx it is handed (via the
        // same real TransactionBuilder), so it always echoes back the CORRECT
        // hash regardless of which test's transaction is broadcast, avoiding
        // sendTransaction's hash-mismatch re-key path.
        val fakeBroadcast = BroadcastClient { rawJson ->
            val tx = json.decodeFromString<Transaction>(rawJson)
            "\"${transactionBuilder.computeTxHash(tx)}\""
        }

        repository = GatewayRepository(
            keyManager = keyManager,
            walletPreferences = walletPreferences,
            json = json,
            transactionBuilder = transactionBuilder,
            cacheManager = cacheManager,
            daoSyncManager = mockk(relaxed = true),
            walletMigrationHelper = mockk(relaxed = true),
            walletDao = db.walletDao(),
            appDatabase = db,
            headerCacheDao = db.headerCacheDao(),
            syncProgressDao = db.syncProgressDao(),
            pendingBroadcastDao = db.pendingBroadcastDao(),
            broadcastClient = fakeBroadcast,
            syncCoordinator = mockk(relaxed = true),
            daoHeaderResolver = mockk(relaxed = true),
            daoDepositReader = mockk(relaxed = true),
            lightClient = LightClientReadOnly(json, NoopLogger),
            subAccountReconciler = mockk(relaxed = true),
            subAccountDiscovery = mockk(relaxed = true),
            syncServiceCommands = mockk(relaxed = true),
            nodeLifecycle = nodeLifecycle,
            syncPoller = mockk(relaxed = true),
            startupReconciler = mockk(relaxed = true),
            logger = NoopLogger,
        )

        runBlocking {
            db.walletDao().insert(
                WalletEntity(
                    walletId = walletId, name = walletId, type = KeyManager.WALLET_TYPE_MNEMONIC,
                    derivationPath = "m/44'/309'/0'/0/0", parentWalletId = null, accountIndex = 0,
                    mainnetAddress = "ckb1active", testnetAddress = "ckt1active",
                    isActive = true, createdAt = 0L, lastActiveAt = 0L,
                )
            )
            repository.initializeWallet().getOrThrow()
        }
    }

    @After
    fun teardown() {
        SweepShadowLightClientNative.transactionsPage = null
        db.close()
    }

    private fun wellFormedTransaction(outputCapacityShannons: Long) = Transaction(
        version = "0x0",
        cellDeps = emptyList(),
        headerDeps = emptyList(),
        cellInputs = listOf(CellInput(since = "0x0", previousOutput = OutPoint("0x" + "aa".repeat(32), "0x0"))),
        cellOutputs = listOf(CellOutput(capacity = "0x" + outputCapacityShannons.toString(16), lock = script, type = null)),
        outputsData = listOf("0x"),
        witnesses = listOf("0x"),
    )

    // --- Test 1: sweep inserts self+fee ---------------------------------
    //
    // sweepGapLimitFundsInner calls sendTransaction(signed, pendingDirection =
    // "self", pendingFeeShannons = plan.feeShannons) directly; this calls the
    // exact same method the exact same way, without needing to build a real
    // sweep transaction (its candidate-script cell selection is JNI, out of
    // scope for this seam).
    //
    // Probe performed: in GatewayRepository.sendTransaction, changed
    //   val balanceChangeHex = "0x${sendTransactionPendingAmount(pendingDirection, pendingFeeShannons, recipientAmount).toString(16)}"
    // to
    //   val balanceChangeHex = "0x${recipientAmount.toString(16)}"
    // (dropping the sendTransactionPendingAmount call, i.e. the pre-#538
    // behavior). Result: this test failed with
    //   java.lang.AssertionError: expected:<1234> but was:<0>
    // (every output here is locked to the wallet's own script, so
    // recipientAmount, the sum of outputs NOT ours, is 0: the "Sent 0 CKB"
    // bug). Restored, test passes again.
    @Test
    fun `sweep inserts self+fee via sendTransaction`() = runTest {
        val tx = wellFormedTransaction(outputCapacityShannons = 100 * ckb)

        repository.sendTransaction(tx, pendingDirection = "self", pendingFeeShannons = 1234L).getOrThrow()

        val hash = transactionBuilder.computeTxHash(tx)
        val row = db.transactionDao().getByTxHash(hash)
        assertNotNull("pending row must exist", row)
        assertEquals("self", row!!.direction)
        assertEquals(1234L, row.balanceChange.removePrefix("0x").toLong(16))
    }

    // --- Test 2: retry keeps self+fee ------------------------------------
    //
    // Simulates a previously-classified "self" row (a self-transfer or a
    // sweep) that went FAILED and is now retried: retryBroadcast must read
    // its cached direction/fee before deleting the row and thread them back
    // through to sendTransaction.
    //
    // Probe performed: in GatewayRepository.retryBroadcast, changed
    //   val override = retryPendingOverride(cached?.direction, cached?.feeShannons)
    //   if (override != null) { sendTransaction(tx, pendingDirection = override.first, pendingFeeShannons = override.second).getOrThrow() }
    //   else { sendTransaction(tx).getOrThrow() }
    // to unconditionally
    //   sendTransaction(tx).getOrThrow()
    // Result: this test failed with
    //   org.junit.ComparisonFailure: expected:<[self]> but was:<[out]>
    // Restored, test passes again.
    @Test
    fun `retry keeps self+fee`() = runTest {
        val tx = wellFormedTransaction(outputCapacityShannons = 100 * ckb)
        val hash = transactionBuilder.computeTxHash(tx)
        val now = System.currentTimeMillis()

        db.pendingBroadcastDao().insert(
            PendingBroadcastEntity(
                txHash = hash, walletId = walletId, network = network.name,
                signedTxJson = json.encodeToString(tx), reservedInputs = "[]",
                state = "FAILED", submittedAtTipBlock = 0L, nullCount = 3,
                createdAt = now, lastCheckedAt = now,
            )
        )
        db.transactionDao().insert(
            TransactionEntity(
                txHash = hash, blockNumber = "0x0", blockHash = "0x0", timestamp = now,
                balanceChange = "0x" + 5678L.toString(16), direction = "self", fee = "0x0",
                confirmations = 0, blockTimestampHex = null, network = network.name,
                status = "FAILED", isLocal = true, cachedAt = now, walletId = walletId,
                feeShannons = 5678L,
            )
        )

        repository.retryBroadcast(hash).getOrThrow()

        val row = db.transactionDao().getByTxHash(hash)
        assertNotNull("re-inserted row must exist", row)
        assertEquals("self", row!!.direction)
        assertEquals(5678L, row.balanceChange.removePrefix("0x").toLong(16))
        assertEquals(5678L, row.feeShannons)
    }

    // --- Test 3: selfWalletLockArgsFor, real Room data -------------------
    //
    // otherWalletLockArgs subtraction and the RESTORED filter, both against
    // real Room rows rather than a hand-built known-args set. The
    // subtraction's only real-world job is defence in depth for exactly the
    // case a hand-built set can't represent: a candidate row whose STATE
    // update to RESTORED never landed (a race/bug), so it still reads
    // PENDING (passes the state filter) even though its script is now
    // wallet-2's own live address; `staleUnpromotedArgs` == `otherWalletArgs`
    // models that directly, so removing the filter alone (leaving the
    // subtraction) would NOT catch it, only removing the subtraction does.
    //
    // Probe (a) performed: in GatewayRepository.selfWalletLockArgsFor,
    // changed `selfArgs - otherWalletLockArgs` to `selfArgs` (dropping the
    // subtraction). Result: this test failed with
    //   java.lang.AssertionError: RESTORED-but-stale-state candidate must still be excluded (defence in depth)
    // Restored.
    //
    // Probe (b) performed: in SelfTransferSignature.activeSelfTransferCandidateArgs,
    // changed `candidates.filter { it.state != SubAccountCandidateEntity.STATE_RESTORED }`
    // to `candidates.map { it.scriptArgs }` (dropping the RESTORED filter).
    // Result: this test failed with
    //   java.lang.AssertionError: RESTORED candidate must be excluded
    // Restored.
    @Test
    fun `selfWalletLockArgsFor excludes another wallet and RESTORED candidates`() = runTest {
        val pendingArgs = "0x" + "22".repeat(20)
        val restoredArgs = "0x" + "33".repeat(20)
        // Models a candidate whose promotion to wallet-2 completed (its
        // script IS wallet-2's own derived args) but whose row was never
        // marked RESTORED, the exact staleness the subtraction guards
        // against on top of the state filter.
        val staleUnpromotedArgs = otherWalletArgs

        db.walletDao().insert(
            WalletEntity(
                walletId = "wallet-2", name = "wallet-2", type = KeyManager.WALLET_TYPE_MNEMONIC,
                derivationPath = "m/44'/309'/0'/0/0", parentWalletId = null, accountIndex = 0,
                mainnetAddress = "ckb1other", testnetAddress = "ckt1other",
                isActive = false, createdAt = 0L, lastActiveAt = 0L,
            )
        )
        db.subAccountCandidateDao().insertAll(
            listOf(
                SubAccountCandidateEntity(
                    parentWalletId = walletId, derivationPath = "m/44'/309'/0'/0/1",
                    accountIndex = 0, scriptArgs = pendingArgs,
                    state = SubAccountCandidateEntity.STATE_PENDING, createdAt = 0L,
                ),
                SubAccountCandidateEntity(
                    parentWalletId = walletId, derivationPath = "m/44'/309'/1'/0/0",
                    accountIndex = 1, scriptArgs = restoredArgs,
                    state = SubAccountCandidateEntity.STATE_RESTORED, createdAt = 0L,
                ),
                SubAccountCandidateEntity(
                    parentWalletId = walletId, derivationPath = "m/44'/309'/2'/0/0",
                    accountIndex = 2, scriptArgs = staleUnpromotedArgs,
                    state = SubAccountCandidateEntity.STATE_PENDING, createdAt = 0L,
                ),
            )
        )

        val result = repository.selfWalletLockArgsFor(script.args, walletId)

        assertTrue("main script must be present", script.args.lowercase() in result)
        assertTrue("PENDING candidate must be present", pendingArgs.lowercase() in result)
        assertFalse("RESTORED candidate must be excluded", restoredArgs.lowercase() in result)
        assertFalse(
            "RESTORED-but-stale-state candidate must still be excluded (defence in depth)",
            staleUnpromotedArgs.lowercase() in result,
        )
    }

    // --- Test 4: getTransactions sweep branch ----------------------------
    //
    // A sweep's candidate-script inputs are invisible to the info.script
    // walk, so only the main-script OUTPUT interaction is faked here,
    // matching the real bug's shape: the walk sees a positive net (it would
    // read as "Received") unless the WalletPreferences sweep marker (and the
    // fresh isSelfTransferSignature re-check) reclassify it.
    //
    // Probe performed: in GatewayRepository.getTransactions, forced
    //   val sweepDisplay = sweepRowDisplay(...)
    // to
    //   val sweepDisplay: PendingTransferDisplay? = null
    // Result: this test failed with
    //   org.junit.ComparisonFailure: expected:<[self]> but was:<[in]>
    // Restored, test passes again.
    @Test
    fun `getTransactions sweep branch reclassifies a candidate-funded sweep`() = runTest {
        val sweepHash = "0x" + "55".repeat(32)
        val sweepFee = 999L
        val sweptCapacity = 150 * ckb

        walletPreferences.addSweepTxHash(walletId, network.name, sweepHash, sweepFee)

        val sweepOutputTx = JniTransactionView(
            hash = sweepHash,
            version = "0x0",
            cellDeps = emptyList(),
            headerDeps = emptyList(),
            inputs = emptyList(),
            outputs = listOf(CellOutput(capacity = "0x" + sweptCapacity.toString(16), lock = script, type = null)),
            outputsData = listOf("0x"),
            witnesses = emptyList(),
        )
        SweepShadowLightClientNative.transactionsPage = json.encodeToString(
            JniPagination(
                objects = listOf(
                    JniTxWithCell(
                        transaction = sweepOutputTx,
                        blockNumber = "0x1",
                        txIndex = "0x0",
                        ioIndex = "0x0",
                        ioType = "output",
                        ioCapacity = "0x" + sweptCapacity.toString(16),
                    )
                ),
                lastCursor = "",
            )
        )

        val response = repository.getTransactions().getOrThrow()

        val record = response.items.firstOrNull { it.txHash == sweepHash }
        assertNotNull("sweep row must be present", record)
        assertEquals("self", record!!.direction)
        assertEquals(sweepFee, record.balanceChange.removePrefix("0x").toLong(16))
    }
}

/**
 * Stands in for the light client's JNI surface in
 * [GatewayRepositorySelfTransferWiringTest]. MockK cannot stub `external fun`
 * (it throws UnsatisfiedLinkError at first real invocation), so a Robolectric
 * shadow answers getTransactions's own direct LightClientNative reads
 * instead, same technique as #529's GatewayRepositoryDaoUnlockTest. Every
 * native not named here keeps Robolectric's default (null / 0), which
 * getTransactions already handles gracefully (an unresolved header, a
 * missing tip) for every path this suite exercises.
 */
@Implements(LightClientNative::class)
class SweepShadowLightClientNative {

    @Implementation
    fun nativeGetTransactions(
        @Suppress("UNUSED_PARAMETER") searchKeyJson: String,
        @Suppress("UNUSED_PARAMETER") order: String,
        @Suppress("UNUSED_PARAMETER") limit: Int,
        @Suppress("UNUSED_PARAMETER") cursor: String?,
    ): String? = transactionsPage

    companion object {
        var transactionsPage: String? = null
    }
}

private val otherWalletArgs = "0x" + "44".repeat(20)
