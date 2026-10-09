package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.PendingBroadcastEntity
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.CellInput
import com.rjnr.pocketnode.data.gateway.models.CellOutput
import com.rjnr.pocketnode.data.gateway.models.JniPagination
import com.rjnr.pocketnode.data.gateway.models.JniTransactionView
import com.rjnr.pocketnode.data.gateway.models.JniTxWithCell
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.Transaction
import com.rjnr.pocketnode.data.send.SendPipeline
import com.rjnr.pocketnode.data.storage.RoomBalanceCache
import com.rjnr.pocketnode.data.storage.RoomHeaderCache
import com.rjnr.pocketnode.data.storage.RoomPendingBroadcastStore
import com.rjnr.pocketnode.data.storage.RoomSubAccountCandidateStore
import com.rjnr.pocketnode.data.storage.RoomTransactionStore
import com.rjnr.pocketnode.data.storage.RoomWalletRegistry
import com.rjnr.pocketnode.data.transaction.TransactionBuilder
import com.rjnr.pocketnode.data.validation.NetworkValidator
import com.rjnr.pocketnode.data.wallet.AddressUtils
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

/**
 * #538 review, "wiring untested": the pure functions in SelfTransferSignature.kt
 * (isSelfTransferSignature, sendTransactionPendingAmount, retryPendingOverride,
 * sweepRowDisplay, activeSelfTransferCandidateArgs) are unit-tested directly in
 * SelfTransferSignatureTest.kt; this suite proves the call sites use them.
 *
 * ios/m3 port of public main's suite. The call sites live in the shared core
 * here (LedgerReader for the confirmed row, SendPipeline for the pending row
 * and the retry), so this drives a real [GatewayRepository] over a real
 * LedgerReader and SendPipeline, a real in-memory AppDatabase through the Room
 * store bindings, real WalletPreferences and a real TransactionBuilder. Only
 * the light client ([LightClientApi]) is faked, which also makes public
 * main's Robolectric shadow of LightClientNative unnecessary for this suite.
 */
@RunWith(RobolectricTestRunner::class)
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

    /** The raw transactions page the fake light client answers with, or null. */
    private var transactionsPage: String? = null

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cacheManager = CacheManager(db.transactionDao(), db.balanceCacheDao(), NoopLogger)
        transactionBuilder = TransactionBuilder(NetworkValidator())

        walletPreferences = WalletPreferences(context, NoopLogger)
        walletPreferences.setActiveWalletId(walletId)
        walletPreferences.setLastVacuumAt(System.currentTimeMillis())

        val nodeLifecycle = mockk<NodeLifecycle>(relaxed = true)
        every { nodeLifecycle.currentNetwork } returns network

        val keyManager = mockk<KeyManager>(relaxed = true)
        coEvery { keyManager.hasWallet() } returns true
        every { keyManager.deriveWalletInfoFromEntity(any()) } returns
            WalletInfo("0xpub", script, activeTestnetAddress, "ckb1active")

        // The fake recomputes the hash from whatever tx it is handed (via the
        // same real TransactionBuilder), so it always echoes back the CORRECT
        // hash regardless of which test's transaction is broadcast, avoiding
        // sendTransaction's hash-mismatch re-key path.
        val api = mockk<LightClientApi>(relaxed = true)
        every { api.getTipHeader() } returns null
        every { api.getTransaction(any()) } returns null
        every { api.sendTransaction(any()) } answers {
            val tx = json.decodeFromString<Transaction>(firstArg())
            "\"${transactionBuilder.computeTxHash(tx)}\""
        }
        every { api.getTransactions(any(), any(), any(), any()) } answers { transactionsPage }

        val transactionStore = RoomTransactionStore(db.transactionDao(), cacheManager)
        val ledgerReader = LedgerReader(
            api,
            RoomBalanceCache(cacheManager),
            transactionStore,
            RoomHeaderCache(db.headerCacheDao()),
            RoomWalletRegistry(db.walletDao()),
            RoomSubAccountCandidateStore(db.subAccountCandidateDao()),
            walletPreferences,
            walletPreferences,
            json,
            NoopLogger,
        )
        val sendPipeline = SendPipeline(
            api,
            transactionBuilder,
            ledgerReader,
            RoomPendingBroadcastStore(db.pendingBroadcastDao()),
            transactionStore,
            mockk(relaxed = true),
            mockk(relaxed = true),
            walletPreferences,
            json,
            NoopLogger,
        )

        repository = GatewayRepository(
            keyManager = keyManager,
            walletPreferences = walletPreferences,
            json = json,
            cacheManager = cacheManager,
            walletMigrationHelper = mockk(relaxed = true),
            walletDao = db.walletDao(),
            appDatabase = db,
            syncProgressDao = db.syncProgressDao(),
            sendPipeline = sendPipeline,
            syncCoordinator = mockk(relaxed = true),
            daoGateway = mockk(relaxed = true),
            gapLimitGateway = mockk(relaxed = true),
            lightClient = LightClientReadOnly(api, json, NoopLogger),
            ledgerReader = ledgerReader,
            subAccountReconciler = mockk(relaxed = true),
            syncServiceCommands = mockk(relaxed = true),
            nodeLifecycle = nodeLifecycle,
            syncEngine = mockk(relaxed = true),
            startupReconciler = mockk(relaxed = true),
            logger = NoopLogger,
        )

        runBlocking {
            db.walletDao().insert(
                WalletEntity(
                    walletId = walletId, name = walletId, type = KeyManager.WALLET_TYPE_MNEMONIC,
                    derivationPath = "m/44'/309'/0'/0/0", parentWalletId = null, accountIndex = 0,
                    mainnetAddress = "ckb1active", testnetAddress = activeTestnetAddress,
                    isActive = true, createdAt = 0L, lastActiveAt = 0L,
                )
            )
            repository.initializeWallet().getOrThrow()
        }
    }

    private val activeTestnetAddress: String get() = AddressUtils.encode(script, NetworkType.TESTNET)

    /** Wallet 2's real testnet address, so the shared derivation decodes it to [otherWalletArgs]. */
    private val otherTestnetAddress: String
        get() = AddressUtils.encode(Script(Script.SECP256K1_CODE_HASH, "type", otherWalletArgs), NetworkType.TESTNET)

    @After
    fun teardown() {
        transactionsPage = null
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
    // Probe performed: in SendPipeline.sendTransaction (public main: GatewayRepository), changed
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
    // Probe performed: in SendPipeline.retryBroadcast (public main: GatewayRepository), changed
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
    // Probe (a) performed: in LedgerReader.selfWalletLockArgsFor (public main: GatewayRepository),
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
                mainnetAddress = "ckb1other", testnetAddress = otherTestnetAddress,
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
    // Probe performed: in LedgerReader.getTransactions (public main: GatewayRepository), forced
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
        transactionsPage = json.encodeToString(
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

private val otherWalletArgs = "0x" + "44".repeat(20)
