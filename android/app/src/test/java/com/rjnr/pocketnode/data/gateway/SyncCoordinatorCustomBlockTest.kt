package com.rjnr.pocketnode.data.gateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.wallet.AddressUtils
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression guard for the production-reported sync-stall on 2021-era
 * wallets ("stayed at 0 for 30 minutes"). The most likely failure mode
 * is that the user's CUSTOM block height never reaches
 * `LightClientNative.nativeSetScripts` — the light client then starts
 * scanning from genesis (blockNumber=0x0) silently, the UI displays
 * "0%", and the user assumes the app is frozen.
 *
 * This test pins down the registration path: given a wallet whose
 * preferences say CUSTOM mode + height 5_000_000, when
 * [SyncCoordinator.registerAllWalletScripts] runs, the JSON payload
 * sent into `nativeSetScripts` MUST contain `blockNumber = "0x4c4b40"`.
 *
 * If a future refactor breaks this contract (e.g. someone drops the
 * `getCustomBlockHeight` lookup, or the per-wallet override path
 * stops working), this test fails immediately.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SyncCoordinatorCustomBlockTest {

    private lateinit var db: AppDatabase
    private lateinit var walletPreferences: WalletPreferences
    private lateinit var coordinator: SyncCoordinator
    private lateinit var keyManager: KeyManager
    private lateinit var fakeBridge: FakeLightClientBridge

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Test double for [LightClientBridge]. Captures every setScripts
     * invocation so assertions can inspect the JSON payload that
     * would have hit the real native call.
     */
    private class FakeLightClientBridge : LightClientBridge {
        val setScriptsCalls: MutableList<Pair<String, Int>> = mutableListOf()
        // Null tip → the coordinator falls through with `tipHeight = 0L`,
        // which is the path under test for CUSTOM mode (it doesn't read
        // tip). Avoids having to construct a fully-valid JniHeaderView
        // JSON just for this assertion's sake.
        var tipHeaderJson: String? = null
        var setScriptsReturn: Boolean = true

        override suspend fun setScripts(scriptsJson: String, command: Int): Boolean {
            setScriptsCalls += scriptsJson to command
            return setScriptsReturn
        }

        override suspend fun getTipHeaderRaw(): String? = tipHeaderJson
        // No scripts registered in the fake — clamp finds nothing to clamp
        // against, preserving the original pass-through behavior under test.
        override suspend fun getScriptsRaw(): String? = null
    }

    // Real testnet script (from AddressUtilsTest) — encodes to a real CKB
    // address and round-trips back to a Script. The script is what
    // KeyManager.deriveLockScriptFromAddress recovers; we precompute the
    // address so the SyncCoordinator can derive the script back from it.
    private val sampleScript = Script(
        codeHash = "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
        hashType = "type",
        args = "0x" + "aa".repeat(20),
    )
    private val testnetAddress by lazy { AddressUtils.encode(sampleScript, NetworkType.TESTNET) }
    private val mainnetAddress by lazy { AddressUtils.encode(sampleScript, NetworkType.MAINNET) }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        walletPreferences = WalletPreferences(ctx)
        // Real KeyManager — only deriveLockScriptFromAddress is exercised;
        // it's a pure bech32 decode that needs no other dependencies wired.
        keyManager = KeyManager(ctx, MnemonicManager())
        fakeBridge = FakeLightClientBridge()
        coordinator = SyncCoordinator(
            walletDao = db.walletDao(),
            syncProgressDao = db.syncProgressDao(),
            walletPreferences = walletPreferences,
            keyManager = keyManager,
            json = json,
            lightClient = fakeBridge,
            subAccountCandidateDao = db.subAccountCandidateDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedWallet(walletId: String, syncMode: SyncMode, customHeight: Long?) {
        db.walletDao().insert(
            WalletEntity(
                walletId = walletId,
                name = "Test",
                type = KeyManager.WALLET_TYPE_MNEMONIC,
                derivationPath = "m/44'/309'/0'/0/0",
                parentWalletId = null,
                accountIndex = 0,
                mainnetAddress = mainnetAddress,
                testnetAddress = testnetAddress,
                isActive = true,
                createdAt = 0L,
                lastActiveAt = 1L,
            )
        )
        walletPreferences.setSyncMode(syncMode, walletId = walletId)
        if (customHeight != null) {
            walletPreferences.setCustomBlockHeight(customHeight, walletId = walletId)
        }
    }

    private fun ctxFor(walletId: String) = SyncCoordinator.SyncContext(
        network = NetworkType.TESTNET,
        activeWalletId = walletId,
        awaitNodeReady = { true },
        getWalletSyncBlock = { 0L }, // no prior sync — forces the syncMode-driven calculation
        onScriptsRegistered = { /* no-op */ },
    )

    @Test
    fun `CUSTOM mode at block 5_000_000 produces blockNumber 0x4c4b40 to setScripts`() = runTest {
        val walletId = "wallet-custom-2021"
        seedWallet(walletId, SyncMode.CUSTOM, customHeight = 5_000_000L)

        coordinator.registerAllWalletScripts(ctxFor(walletId))

        // The JSON payload is a List<JniScriptStatus>; assert the height we
        // care about is in there as the hex form. Substring match is
        // intentional — defends against future serialization changes
        // (field renames, ordering) by checking only the load-bearing fact.
        assertEquals("setScripts should be called once", 1, fakeBridge.setScriptsCalls.size)
        val sent = fakeBridge.setScriptsCalls.single().first
        assertTrue(
            "Expected blockNumber 0x4c4b40 (5_000_000) in setScripts payload, got: $sent",
            sent.contains("\"block_number\":\"0x4c4b40\""),
        )
    }

    @Test
    fun `CUSTOM mode at block 0 still produces a blockNumber field`() = runTest {
        // Edge case: user picks CUSTOM but leaves the height at 0. We don't
        // want a missing blockNumber field — that would mean the JSON
        // serializer dropped the value and the native side would get
        // garbage. Asserting field presence (not the value) defends
        // against that specific regression while leaving the policy
        // question (should we floor to checkpoint?) for product to
        // decide separately.
        val walletId = "wallet-custom-zero"
        seedWallet(walletId, SyncMode.CUSTOM, customHeight = 0L)

        coordinator.registerAllWalletScripts(ctxFor(walletId))

        val sent = fakeBridge.setScriptsCalls.single().first
        assertTrue(
            "Expected a blockNumber field in payload regardless of value, got: $sent",
            sent.contains("\"block_number\":"),
        )
    }

    @Test
    fun `FULL_HISTORY mode produces blockNumber 0x0`() = runTest {
        val walletId = "wallet-full"
        seedWallet(walletId, SyncMode.FULL_HISTORY, customHeight = null)

        coordinator.registerAllWalletScripts(ctxFor(walletId))

        val sent = fakeBridge.setScriptsCalls.single().first
        assertTrue(
            "Expected blockNumber 0x0 (genesis) in FULL_HISTORY payload, got: $sent",
            sent.contains("\"block_number\":\"0x0\""),
        )
    }

    @Test
    fun `setScripts is invoked exactly once for a single-wallet registration`() = runTest {
        val walletId = "wallet-once"
        seedWallet(walletId, SyncMode.CUSTOM, customHeight = 5_000_000L)

        coordinator.registerAllWalletScripts(ctxFor(walletId))

        assertEquals(1, fakeBridge.setScriptsCalls.size)
    }

    @Test
    fun `prior savedBlock from sync_progress overrides syncMode-computed start`() = runTest {
        // Once a wallet has progress recorded, registration must resume from
        // it — NOT restart from the CUSTOM block (which would re-scan from
        // 2021 on every wallet switch, the user-reported pathology).
        val walletId = "wallet-resumed"
        seedWallet(walletId, SyncMode.CUSTOM, customHeight = 5_000_000L)

        // SyncContext.getWalletSyncBlock returns 9_000_000 — wallet was
        // mid-sync at this block when last shut down.
        coordinator.registerAllWalletScripts(
            ctxFor(walletId).copy(getWalletSyncBlock = { 9_000_000L })
        )

        val sent = fakeBridge.setScriptsCalls.single().first
        assertTrue(
            "Expected resume at 0x895440 (9_000_000), not CUSTOM start, got: $sent",
            sent.contains("\"block_number\":\"0x895440\""),
        )
    }
}
