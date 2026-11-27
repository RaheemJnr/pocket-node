package com.rjnr.pocketnode.di

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.SyncStrategy
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.BalanceCacheEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.CacheManager
import com.rjnr.pocketnode.data.gateway.DaoRecoveryLifetime
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.LedgerReader
import com.rjnr.pocketnode.data.gateway.LightClientApi
import com.rjnr.pocketnode.data.gateway.NodeLifecycle
import com.rjnr.pocketnode.data.gateway.SyncCoordinator
import com.rjnr.pocketnode.data.gateway.SyncProgress
import com.rjnr.pocketnode.data.gateway.VerificationCacheGate
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.readyVerificationGate
import com.rjnr.pocketnode.data.storage.RoomBalanceCache
import com.rjnr.pocketnode.data.sync.SyncEngine
import com.rjnr.pocketnode.data.wallet.AddressUtils
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The production [GatewayRepository] comes from [AppModule.provideGatewayRepository].
 * It must observe the same [DaoRecoveryLifetime] singleton that
 * [VerificationCacheGate] and WalletRepository invalidate; a private instance
 * would leave the published balance and cache fencing inert in the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GatewayRepositoryRecoveryLifetimeTest {

    private lateinit var db: AppDatabase
    private lateinit var prefs: WalletPreferences
    private lateinit var cacheManager: CacheManager
    private val lifetime = DaoRecoveryLifetime()
    /** The verification gate's handoff counter; bumped when the gate closes. */
    @Volatile private var gateLifecycle = 0L
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val script = Script(
        codeHash = Script.SECP256K1_CODE_HASH,
        hashType = "type",
        args = "0x" + "aa".repeat(20),
    )
    private val testnetAddress = AddressUtils.encode(script, NetworkType.TESTNET)

    private val wallet = WalletEntity(
        walletId = "A", name = "A", type = KeyManager.WALLET_TYPE_MNEMONIC,
        derivationPath = "m/44'/309'/0'/0/0", parentWalletId = null, accountIndex = 0,
        mainnetAddress = AddressUtils.encode(script, NetworkType.MAINNET),
        testnetAddress = testnetAddress,
        isActive = true, createdAt = 0L, lastActiveAt = 0L,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefs = WalletPreferences(context, NoopLogger)
        prefs.setSyncStrategy(SyncStrategy.ALL_WALLETS)
        prefs.setActiveWalletId("A")
        runBlocking { db.walletDao().insert(wallet) }
        cacheManager = spyk(CacheManager(db.transactionDao(), db.balanceCacheDao(), NoopLogger, readyVerificationGate { gateLifecycle }))
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Light client answering a fixed capacity and no cells or transactions. */
    private fun lightClient(): LightClientApi = mockk(relaxed = true) {
        every { getCellsCapacity(any()) } returns
            """{"block_hash":"0x${"11".repeat(32)}","block_number":"0x20","capacity":"0x5f5e100"}"""
        every { getCells(any(), any(), any(), any()) } returns """{"objects":[],"last_cursor":""}"""
        every { getTransactions(any(), any(), any(), any()) } returns """{"objects":[],"last_cursor":""}"""
    }

    /** Builds the repository exactly as the app does, through the Hilt provider. */
    private fun provided(): GatewayRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val realKeyManager = KeyManager(context, MnemonicManager(), NoopLogger)
        val keyManager = mockk<KeyManager>(relaxed = true)
        coEvery { keyManager.hasWallet() } returns true
        every { keyManager.deriveWalletInfoFromEntity(any()) } answers {
            realKeyManager.deriveWalletInfoFromEntity(firstArg())
        }
        val nodeLifecycle = mockk<NodeLifecycle>(relaxed = true)
        every { nodeLifecycle.currentNetwork } returns NetworkType.TESTNET
        every { nodeLifecycle.network } returns MutableStateFlow(NetworkType.TESTNET)
        every { nodeLifecycle.isSwitchingNetwork } returns MutableStateFlow(false)
        coEvery { nodeLifecycle.awaitNodeReady() } returns true
        val startupReachedNode = CompletableDeferred<Unit>()
        coEvery { nodeLifecycle.initializeNode(any(), any()) } answers { startupReachedNode.complete(Unit) }
        val coordinator = mockk<SyncCoordinator>(relaxed = true)
        every { coordinator.registeredActiveWalletId } returns null
        val syncEngine = mockk<SyncEngine>(relaxed = true)
        every { syncEngine.syncProgress } returns MutableStateFlow(SyncProgress())
        val ledgerReader = LedgerReader(
            lightClient(), RoomBalanceCache(cacheManager), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), prefs, prefs, json, NoopLogger,
        )
        val repository = AppModule.provideGatewayRepository(
            keyManager = keyManager,
            walletPreferences = prefs,
            json = json,
            cacheManager = cacheManager,
            walletMigrationHelper = mockk(relaxed = true),
            walletDao = db.walletDao(),
            appDatabase = db,
            syncProgressDao = db.syncProgressDao(),
            sendPipeline = mockk(relaxed = true),
            syncCoordinator = coordinator,
            daoGateway = mockk(relaxed = true),
            gapLimitGateway = mockk(relaxed = true),
            lightClient = mockk(relaxed = true),
            ledgerReader = ledgerReader,
            subAccountReconciler = mockk(relaxed = true),
            syncServiceCommands = mockk(relaxed = true),
            nodeLifecycle = nodeLifecycle,
            syncEngine = syncEngine,
            startupReconciler = mockk(relaxed = true),
            logger = NoopLogger,
            recoveryLifetime = lifetime,
        )
        runBlocking { withTimeout(10_000) { startupReachedNode.await() } }
        return repository
    }

    @Test
    fun `provided repository clears its balance when the verification gate invalidates the singleton`() {
        val repository = provided()
        runBlocking {
            db.balanceCacheDao().upsert(BalanceCacheEntity(
                "A", "TESTNET", testnetAddress, "100000000", "1", "0x10", System.currentTimeMillis()))
            repository.onActiveWalletChanged(wallet)
        }
        assertEquals("1", repository.balance.value?.capacityCkb)

        // The production invalidator, sharing the singleton the way Hilt wires it.
        val gate = VerificationCacheGate(ApplicationProvider.getApplicationContext(), db, lifetime)
        runBlocking { gate.close() }

        assertNull("Invalidated coverage must clear the published balance", repository.balance.value)
    }

    @Test
    fun `balance written across a chain-cache handoff does not stay cached`() {
        val repository = provided()
        coEvery { cacheManager.cacheBalance(any(), any(), any()) } coAnswers {
            callOriginal()
            // A handoff landing while the cache write runs, after the
            // repository's own pre-write revision check: the gate closes
            // (its lifecycle moves) and invalidates the lifetime.
            gateLifecycle += 1
            lifetime.invalidate()
        }

        val result = runBlocking { repository.refreshBalanceForWallet("A", testnetAddress) }

        assertTrue("Read under revoked coverage must fail", result.isFailure)
        assertNull(
            "Balance from a replaced store must not persist",
            runBlocking { db.balanceCacheDao().getByWalletAndNetwork("A", "TESTNET") },
        )
    }

    @Test
    fun `wallet switch or backgrounding mid write keeps the last good cached balance`() {
        val repository = provided()
        coEvery { cacheManager.cacheBalance(any(), any(), any()) } coAnswers {
            callOriginal()
            // A wallet switch or onStopped: the lifetime moves, the gate does not.
            lifetime.invalidate()
        }

        val result = runBlocking { repository.refreshBalanceForWallet("A", testnetAddress) }

        assertTrue("The read is not published under revoked coverage", result.isFailure)
        assertNotNull(
            "The chain did not change, so the cached balance stays",
            runBlocking { db.balanceCacheDao().getByWalletAndNetwork("A", "TESTNET") },
        )
    }

    @Test
    fun `balance written under current coverage is cached`() {
        val repository = provided()

        val result = runBlocking { repository.refreshBalanceForWallet("A", testnetAddress) }

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        assertNotNull(runBlocking { db.balanceCacheDao().getByWalletAndNetwork("A", "TESTNET") })
    }
}
