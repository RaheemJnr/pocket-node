package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.core.prefs.FakeSyncPreferences
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.storage.FakeSubAccountCandidateStore
import com.rjnr.pocketnode.data.storage.FakeSyncProgressStore
import com.rjnr.pocketnode.data.storage.FakeTransactionStore
import com.rjnr.pocketnode.data.storage.FakeWalletRegistry
import com.rjnr.pocketnode.data.storage.WalletRecord
import com.rjnr.pocketnode.data.wallet.AddressUtils
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guard for the production-reported sync-stall on 2021-era
 * wallets ("stayed at 0 for 30 minutes"). The most likely failure mode
 * is that the user's CUSTOM block height never reaches the light client's
 * `setScripts` — it then starts scanning from genesis (blockNumber=0x0)
 * silently, the UI displays "0%", and the user assumes the app is frozen.
 *
 * This test pins down the registration path: given a wallet whose
 * preferences say CUSTOM mode + height 5_000_000, when
 * [SyncCoordinator.registerAllWalletScripts] runs, the JSON payload
 * sent into `setScripts` MUST contain `blockNumber = "0x4c4b40"`.
 *
 * If a future refactor breaks this contract (e.g. someone drops the
 * `getCustomBlockHeight` lookup, or the per-wallet override path
 * stops working), this test fails immediately.
 */
class SyncCoordinatorCustomBlockTest {

    private lateinit var wallets: FakeWalletRegistry
    private lateinit var syncPreferences: FakeSyncPreferences
    private lateinit var coordinator: SyncCoordinator
    private lateinit var lightClient: FakeLightClientApi

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Real testnet script (from AddressUtilsTest) — encodes to a real CKB
    // address and round-trips back to a Script. The script is what
    // WalletDerivation.lockScriptFromAddress recovers; we precompute the
    // address so the SyncCoordinator can derive the script back from it.
    private val sampleScript = Script(
        codeHash = "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
        hashType = "type",
        args = "0x" + "aa".repeat(20),
    )
    private val testnetAddress by lazy { AddressUtils.encode(sampleScript, NetworkType.TESTNET) }
    private val mainnetAddress by lazy { AddressUtils.encode(sampleScript, NetworkType.MAINNET) }

    @BeforeTest
    fun setUp() {
        wallets = FakeWalletRegistry()
        syncPreferences = FakeSyncPreferences()
        // No tip header scripted → the coordinator falls through with
        // `tipHeight = 0L`, which is the path under test for CUSTOM mode (it
        // doesn't read tip). No scripts registered → the PARTIAL clamp finds
        // nothing to clamp against, preserving the pass-through under test.
        lightClient = FakeLightClientApi().apply { enqueueFlag("setScripts", true) }
        coordinator = SyncCoordinator(
            walletRegistry = wallets,
            syncProgressStore = FakeSyncProgressStore(),
            subAccountCandidateStore = FakeSubAccountCandidateStore(),
            transactionStore = FakeTransactionStore(),
            lightClient = lightClient,
            syncPreferences = syncPreferences,
            json = json,
            logger = NoopLogger,
        )
    }

    /** Every `scriptsJson` the coordinator handed to the light client, in order. */
    private fun setScriptsPayloads(): List<String> =
        lightClient.callsTo("setScripts").map { it.args[0] as String }

    private fun seedWallet(walletId: String, syncMode: SyncMode, customHeight: Long?) {
        wallets.wallets += WalletRecord(
            walletId = walletId,
            mainnetAddress = mainnetAddress,
            testnetAddress = testnetAddress,
            lastActiveAt = 1L,
        )
        syncPreferences.setSyncMode(syncMode, walletId = walletId)
        if (customHeight != null) {
            syncPreferences.setCustomBlockHeight(customHeight, walletId = walletId)
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
        assertEquals(1, setScriptsPayloads().size, "setScripts should be called once")
        val sent = setScriptsPayloads().single()
        assertTrue(
            sent.contains("\"block_number\":\"0x4c4b40\""),
            "Expected blockNumber 0x4c4b40 for 5_000_000 in setScripts payload, got: $sent",
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

        val sent = setScriptsPayloads().single()
        assertTrue(
            sent.contains("\"block_number\":"),
            "Expected a blockNumber field in payload regardless of value, got: $sent",
        )
    }

    @Test
    fun `FULL_HISTORY mode produces blockNumber 0x0`() = runTest {
        val walletId = "wallet-full"
        seedWallet(walletId, SyncMode.FULL_HISTORY, customHeight = null)

        coordinator.registerAllWalletScripts(ctxFor(walletId))

        val sent = setScriptsPayloads().single()
        assertTrue(
            sent.contains("\"block_number\":\"0x0\""),
            "Expected blockNumber 0x0 for genesis in FULL_HISTORY payload, got: $sent",
        )
    }

    @Test
    fun `setScripts is invoked exactly once for a single wallet registration`() = runTest {
        val walletId = "wallet-once"
        seedWallet(walletId, SyncMode.CUSTOM, customHeight = 5_000_000L)

        coordinator.registerAllWalletScripts(ctxFor(walletId))

        assertEquals(1, setScriptsPayloads().size)
    }

    @Test
    fun `prior savedBlock from sync_progress overrides the syncMode computed start`() = runTest {
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

        val sent = setScriptsPayloads().single()
        assertTrue(
            sent.contains("\"block_number\":\"0x895440\""),
            "Expected resume at 0x895440 for 9_000_000, not the CUSTOM start, got: $sent",
        )
    }
}
