package com.rjnr.pocketnode.data.restorehint

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.SubAccountDiscovery
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #559: verifying a hint on import and what applying it may and may not touch. */
@RunWith(RobolectricTestRunner::class)
class RestoreHintImporterTest {

    private val words = List(11) { "abandon" } + "about"
    private val otherWords = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")

    private lateinit var db: AppDatabase
    private lateinit var prefs: WalletPreferences
    private lateinit var discovery: SubAccountDiscovery
    private lateinit var importer: RestoreHintImporter

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        prefs = WalletPreferences(context, NoopLogger)
        val mnemonicManager = MnemonicManager()
        discovery = SubAccountDiscovery(mnemonicManager, KeyManager(context, mnemonicManager, NoopLogger))
        importer = RestoreHintImporter(db.subAccountCandidateDao(), discovery, prefs, NoopLogger)
    }

    @After
    fun tearDown() = db.close()

    private fun payload(network: NetworkType = NetworkType.TESTNET, accounts: List<RestoreHintAccount>? = null) =
        RestoreHintPayload(
            network = network.name,
            createdAtMs = 0L,
            tipHeight = 19_000_000L,
            tipHash = "0x11",
            kind = RestoreHintKind.MNEMONIC,
            accounts = accounts ?: listOf(
                RestoreHintAccount(0, 18_000_000L, 18_200_000L, "CUSTOM"),
                RestoreHintAccount(12, 18_100_000L, 18_050_000L, "RECENT"),
            ),
            discovery = RestoreHintDiscovery(found = listOf(3, 12), highestScanned = 12),
        )

    private fun file(p: RestoreHintPayload = payload()) = RestoreHintCodec.seal(p, RestoreHintSecret.fromMnemonic(words))

    @Test
    fun `a hint made with the same phrase is ready with the safe start`() {
        val v = importer.verify(file(), RestoreHintSecret.fromMnemonic(words), NetworkType.TESTNET)
        val plan = (v as RestoreHintImporter.Verification.Ready).plan
        assertEquals(17_999_000L, plan.startBlock)
        assertEquals(18_000_000L, plan.sourceCoverageStart)
        assertEquals(SyncMode.CUSTOM, plan.syncMode)
        assertEquals(listOf(3, 12), plan.seedIndices)
    }

    @Test
    fun `rejections map to the three user messages`() {
        assertEquals(
            RestoreHintImporter.Verification.Rejected(RestoreHintImporter.Reason.NOT_THIS_WALLET),
            importer.verify(file(), RestoreHintSecret.fromMnemonic(otherWords), NetworkType.TESTNET),
        )
        assertEquals(
            RestoreHintImporter.Verification.Rejected(RestoreHintImporter.Reason.OTHER_NETWORK),
            importer.verify(file(payload(NetworkType.MAINNET)), RestoreHintSecret.fromMnemonic(words), NetworkType.TESTNET),
        )
        assertEquals(
            RestoreHintImporter.Verification.Rejected(RestoreHintImporter.Reason.UNREADABLE),
            importer.verify("{not a hint", RestoreHintSecret.fromMnemonic(words), NetworkType.TESTNET),
        )
        // Authentic, but nothing for the main account: unusable, not partially used.
        assertEquals(
            RestoreHintImporter.Verification.Rejected(RestoreHintImporter.Reason.UNREADABLE),
            importer.verify(
                file(payload(accounts = listOf(RestoreHintAccount(2, 1L, null, "RECENT")))),
                RestoreHintSecret.fromMnemonic(words),
                NetworkType.TESTNET,
            ),
        )
    }

    @Test
    fun `prepare seeds pending candidates and re-arms the rescue rescan without marking anything done`() = runTest {
        val walletId = "w1"
        // An existing in-window candidate keeps its state (IGNORE on conflict).
        val existing = discovery.deriveCandidates(words, window = 3).first { it.accountIndex == 3 }
        db.subAccountCandidateDao().insertAll(
            listOf(
                SubAccountCandidateEntity(
                    walletId, existing.derivationPath, 3, existing.scriptArgs,
                    state = SubAccountCandidateEntity.STATE_EMPTY, createdAt = 0L,
                )
            )
        )
        prefs.setZeroCellRescanDone(walletId)
        val secret = RestoreHintSecret.fromMnemonic(words)
        val plan = (importer.verify(file(), secret, NetworkType.TESTNET) as RestoreHintImporter.Verification.Ready).plan

        importer.prepare(walletId, plan, secret)

        val rows = db.subAccountCandidateDao().getForParent(walletId).associateBy { it.accountIndex }
        assertEquals(setOf(3, 12), rows.keys)
        assertEquals(SubAccountCandidateEntity.STATE_EMPTY, rows.getValue(3).state)
        assertEquals(SubAccountCandidateEntity.STATE_PENDING, rows.getValue(12).state)
        assertEquals(0L, rows.getValue(12).registeredFromBlock)
        assertEquals(
            discovery.deriveCandidatesFromSeed(secret.copySeed()!!, listOf(12)).single().scriptArgs,
            rows.getValue(12).scriptArgs,
        )
        assertFalse("rescue rescan re-armed", prefs.isZeroCellRescanDone(walletId))
        assertFalse("initial sync not marked complete", prefs.hasCompletedInitialSync(walletId = walletId))
    }

    @Test
    fun `a raw key hint seeds nothing`() = runTest {
        val key = ByteArray(32) { 3 }
        val p = payload().copy(
            kind = RestoreHintKind.RAW_KEY,
            accounts = listOf(RestoreHintAccount(0, 100_000L, null, "RECENT")),
        )
        val secret = RestoreHintSecret.fromPrivateKey(key)
        val plan = (importer.verify(RestoreHintCodec.seal(p, secret), secret, NetworkType.TESTNET)
            as RestoreHintImporter.Verification.Ready).plan
        importer.prepare("raw", plan, secret)
        assertEquals(emptyList<SubAccountCandidateEntity>(), db.subAccountCandidateDao().getForParent("raw"))
        assertEquals(99_000L, plan.startBlock)
    }
}
