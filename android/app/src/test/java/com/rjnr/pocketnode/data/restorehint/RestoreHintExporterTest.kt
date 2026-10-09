package com.rjnr.pocketnode.data.restorehint

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import com.rjnr.pocketnode.data.database.entity.SyncProgressEntity
import com.rjnr.pocketnode.data.database.entity.TransactionEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.LightClientReadOnly
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletKeyReader
import com.rjnr.pocketnode.data.wallet.WalletPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #559: the export reads the right Room rows, and it is gated like the phrase
 * reveal. The phrase is the public BIP-39 test vector.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreHintExporterTest {

    private val words = (List(11) { "abandon" } + "about")
    private val network = NetworkType.TESTNET
    private val tipJson =
        """{"hash":"0xabc","number":"0x1200000","epoch":"0x0","timestamp":"0x0","parent_hash":"0x0",""" +
            """"transactions_root":"0x0","proposals_hash":"0x0","extra_hash":"0x0","dao":"0x0","nonce":"0x0"}"""

    private lateinit var db: AppDatabase
    private lateinit var prefs: WalletPreferences
    private val lightClient = mockk<LightClientReadOnly>()
    private val walletKeyReader = mockk<WalletKeyReader>()
    private val keyMaterialDao = mockk<KeyMaterialDao>()
    private val authManager = mockk<AuthManager>()
    private val encryptionManager = mockk<KeystoreEncryptionManager>()
    private val activity = mockk<FragmentActivity>(relaxed = true)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        prefs = WalletPreferences(context, NoopLogger)
        coEvery { lightClient.getTipHeader() } returns tipJson
    }

    @After
    fun tearDown() = db.close()

    private fun exporter() = RestoreHintExporter(
        walletDao = db.walletDao(),
        syncProgressDao = db.syncProgressDao(),
        transactionDao = db.transactionDao(),
        subAccountCandidateDao = db.subAccountCandidateDao(),
        syncPreferences = prefs,
        lightClient = lightClient,
        walletKeyReader = walletKeyReader,
        keyMaterialDao = keyMaterialDao,
        authManager = authManager,
        encryptionManager = encryptionManager,
        json = Json { ignoreUnknownKeys = true },
        logger = NoopLogger,
    )

    private fun wallet(id: String, type: String = KeyManager.WALLET_TYPE_MNEMONIC, parent: String? = null, index: Int = 0, active: Boolean = false) =
        WalletEntity(
            walletId = id, name = id, type = type, derivationPath = null, parentWalletId = parent,
            accountIndex = index, isActive = active,
        )

    private fun tx(hash: String, walletId: String, block: Long, net: NetworkType = network) = TransactionEntity(
        txHash = hash, blockNumber = "0x" + block.toString(16), blockHash = "0x0", timestamp = 0L,
        balanceChange = "0x0", direction = "in", fee = "0x0", confirmations = 1, blockTimestampHex = null,
        network = net.name, status = "CONFIRMED", isLocal = false, cachedAt = 0L, walletId = walletId,
    )

    private suspend fun progress(walletId: String, lightStart: Long, net: NetworkType = network) =
        db.syncProgressDao().upsert(SyncProgressEntity(walletId, net.name, lightStart, lightStart + 10, 0L))

    private fun candidate(index: Int, state: String, registeredFrom: Long) = SubAccountCandidateEntity(
        parentWalletId = "root", derivationPath = "m/44'/309'/$index'/0/0", accountIndex = index,
        scriptArgs = "0x$index", state = state, createdAt = 0L, registeredFromBlock = registeredFrom,
    )

    @Test
    fun `a mnemonic wallet exports its root, synced sub-accounts and discovery`() = runTest {
        val root = wallet("root", active = true)
        db.walletDao().insert(root)
        db.walletDao().insert(wallet("sub2", parent = "root", index = 2))
        db.walletDao().insert(wallet("sub3", parent = "root", index = 3)) // never synced here
        progress("root", 18_000_000L)
        progress("root", 1L, NetworkType.MAINNET) // other network, ignored
        progress("sub2", 18_400_000L)
        db.transactionDao().insert(tx("0x01", "root", 18_300_000L))
        db.transactionDao().insert(tx("0x02", "root", 18_100_000L))
        db.transactionDao().insert(tx("0x03", "root", 5L, NetworkType.MAINNET)) // other network
        db.transactionDao().insert(tx("0x04", "root", 0L)) // pending, no block
        prefs.setSyncMode(SyncMode.CUSTOM, network, "root")
        prefs.setSyncMode(SyncMode.RECENT, network, "sub2")
        db.subAccountCandidateDao().insertAll(
            listOf(
                candidate(2, SubAccountCandidateEntity.STATE_RESTORED, 18_000_000L),
                candidate(4, SubAccountCandidateEntity.STATE_FOUND, 18_000_000L),
                candidate(6, SubAccountCandidateEntity.STATE_EMPTY, 18_000_000L),
                candidate(9, SubAccountCandidateEntity.STATE_PENDING, 0L), // never scanned
                // Chain-axis slot (account 0): not a sub-account.
                SubAccountCandidateEntity("root", "m/44'/309'/0'/1/3", 0, "0xc", SubAccountCandidateEntity.STATE_FOUND, 0L, 1L),
            )
        )

        val payload = exporter().buildPayload(root, network, 18_874_368L, "0xabc", 42L)

        assertEquals(
            RestoreHintPayload(
                network = "TESTNET",
                createdAtMs = 42L,
                tipHeight = 18_874_368L,
                tipHash = "0xabc",
                kind = RestoreHintKind.MNEMONIC,
                accounts = listOf(
                    RestoreHintAccount(0, 18_000_000L, 18_100_000L, "CUSTOM"),
                    RestoreHintAccount(2, 18_400_000L, null, "RECENT"),
                ),
                discovery = RestoreHintDiscovery(found = listOf(2, 4), highestScanned = 6),
            ),
            payload,
        )
    }

    @Test
    fun `a raw key wallet exports only index 0 and no discovery`() = runTest {
        val root = wallet("raw", type = KeyManager.WALLET_TYPE_RAW_KEY, active = true)
        db.walletDao().insert(root)
        progress("raw", 17_000_000L)
        db.transactionDao().insert(tx("0x01", "raw", 16_500_000L))

        val payload = exporter().buildPayload(root, network, 1L, "0x1", 0L)

        assertEquals(RestoreHintKind.RAW_KEY, payload.kind)
        assertEquals(listOf(RestoreHintAccount(0, 17_000_000L, 16_500_000L, prefs.getSyncMode(network, "raw").name)), payload.accounts)
        assertEquals(RestoreHintDiscovery(), payload.discovery)
    }

    @Test
    fun `a root with no progress row exports coverage 0`() = runTest {
        val root = wallet("root", active = true)
        db.walletDao().insert(root)
        val payload = exporter().buildPayload(root, network, 1L, "0x1", 0L)
        assertEquals(0L, payload.accounts.single().coverageStart)
    }

    @Test
    fun `a V2 export authenticates through the key reader and verifies with the phrase`() = runTest {
        db.walletDao().insert(wallet("root", active = true))
        db.walletDao().insert(wallet("sub1", parent = "root", index = 1, active = false))
        progress("root", 18_000_000L)
        coEvery { keyMaterialDao.getKdfVersion("root") } returns 2
        val key = ByteArray(32) { 5 }
        coEvery { walletKeyReader.readKeyMaterial(activity, "root", any(), any()) } returns
            WalletKeyReader.MaterialResult.Success(key, words.joinToString(" "), "mnemonic", true)

        val result = exporter().export(activity, network, nowMs = 7L)

        assertTrue(result is RestoreHintExporter.ExportResult.Ready)
        result as RestoreHintExporter.ExportResult.Ready
        assertEquals("pocket-node-restore-hint-testnet.json", result.fileName)
        val opened = RestoreHintCodec.open(result.text, RestoreHintSecret.fromMnemonic(words), network)
        assertTrue(opened is RestoreHintOpenResult.Valid)
        assertEquals(0x1200000L, (opened as RestoreHintOpenResult.Valid).payload.tipHeight)
        assertTrue("the private key copy is wiped", key.all { it == 0.toByte() })
        // V2: the BiometricPrompt inside the key reader is the gate; no extra prompt.
        coVerify(exactly = 0) { authManager.authenticateForCipher(any(), any(), any(), any()) }
    }

    @Test
    fun `an export from a sub-account covers its parent`() = runTest {
        db.walletDao().insert(wallet("root"))
        db.walletDao().insert(wallet("sub1", parent = "root", index = 1, active = true))
        progress("root", 18_000_000L)
        progress("sub1", 18_500_000L)
        coEvery { keyMaterialDao.getKdfVersion("root") } returns 2
        coEvery { walletKeyReader.readKeyMaterial(activity, "root", any(), any()) } returns
            WalletKeyReader.MaterialResult.Success(ByteArray(32), words.joinToString(" "), "mnemonic", true)

        val result = exporter().export(activity, network) as RestoreHintExporter.ExportResult.Ready
        val payload = (RestoreHintCodec.open(result.text, RestoreHintSecret.fromMnemonic(words), network)
            as RestoreHintOpenResult.Valid).payload
        assertEquals(listOf(0, 1), payload.accounts.map { it.index })
    }

    @Test
    fun `a V1 export with no screen lock is refused before any key read`() = runTest {
        db.walletDao().insert(wallet("root", active = true))
        coEvery { keyMaterialDao.getKdfVersion("root") } returns 1
        every { authManager.isBiometricEnrolled() } returns false
        every { authManager.hasDeviceCredential() } returns false

        assertEquals(RestoreHintExporter.ExportResult.NeedsScreenLock, exporter().export(activity, network))
        coVerify(exactly = 0) { walletKeyReader.readKeyMaterial(any(), any(), any(), any()) }
    }

    @Test
    fun `a V1 export asks for device auth first and stops if it is cancelled`() = runTest {
        db.walletDao().insert(wallet("root", active = true))
        coEvery { keyMaterialDao.getKdfVersion("root") } returns 1
        every { authManager.isBiometricEnrolled() } returns false
        every { authManager.hasDeviceCredential() } returns true
        every { encryptionManager.newEncryptCipherV2() } returns mockk(relaxed = true)
        coEvery { authManager.authenticateForCipher(activity, any(), any(), any()) } returns
            AuthManager.CipherAuthResult.Cancelled

        assertEquals(RestoreHintExporter.ExportResult.Cancelled, exporter().export(activity, network))
        coVerify(exactly = 0) { walletKeyReader.readKeyMaterial(any(), any(), any(), any()) }
    }

    @Test
    fun `no tip means no export`() = runTest {
        db.walletDao().insert(wallet("root", active = true))
        coEvery { lightClient.getTipHeader() } returns null
        assertEquals(RestoreHintExporter.ExportResult.NoTip, exporter().export(activity, network))
    }
}
