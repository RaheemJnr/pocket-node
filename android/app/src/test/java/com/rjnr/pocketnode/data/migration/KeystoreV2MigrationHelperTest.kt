package com.rjnr.pocketnode.data.migration

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KeystoreV2MigrationHelperTest {

    private lateinit var db: AppDatabase
    private lateinit var keyMaterialDao: KeyMaterialDao
    private lateinit var encryptionManager: KeystoreEncryptionManager
    private lateinit var migrationPrefs: SharedPreferences
    private lateinit var v1Helper: KeyStoreMigrationHelper
    private lateinit var v2Helper: KeystoreV2MigrationHelper
    private var fakeNow: Long = 1_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        keyMaterialDao = db.keyMaterialDao()
        encryptionManager = KeystoreEncryptionManager.createForTest()
        migrationPrefs = context.getSharedPreferences("test_v2_migration", Context.MODE_PRIVATE)
        migrationPrefs.edit().clear().commit()

        // V1 helper to seed pre-v1.7.0 wallets into the in-memory DB
        v1Helper = KeyStoreMigrationHelper(keyMaterialDao, encryptionManager, migrationPrefs)
        v2Helper = KeystoreV2MigrationHelper(
            keyMaterialDao,
            encryptionManager,
            migrationPrefs,
            nowProvider = { fakeNow }
        )
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun seedV1Wallet(walletId: String, privateKeyHex: String, mnemonic: String?) {
        v1Helper.migrateWallet(walletId, privateKeyHex, mnemonic, "mnemonic", true)
        // V1 helper writes with the default kdfVersion = 1, which matches the
        // production state of a wallet upgraded from v1.6.x.
    }

    // -- Discovery --

    @Test
    fun `pendingWalletIds returns all seeded V1 wallets`() = runTest {
        seedV1Wallet("alpha", "aa".repeat(32), "word1 word2 word3")
        seedV1Wallet("beta", "bb".repeat(32), null)
        seedV1Wallet("gamma", "cc".repeat(32), "more words here")

        val pending = v2Helper.pendingWalletIds()
        assertEquals(listOf("alpha", "beta", "gamma"), pending)
    }

    @Test
    fun `pendingWalletIds excludes wallets already on V2`() = runTest {
        seedV1Wallet("alpha", "aa".repeat(32), "alpha mnemonic")
        seedV1Wallet("beta", "bb".repeat(32), "beta mnemonic")

        // Migrate alpha
        val cipher = encryptionManager.newEncryptCipherV2()
        v2Helper.migrateWallet("alpha", cipher).getOrThrow()

        val pending = v2Helper.pendingWalletIds()
        assertEquals(listOf("beta"), pending)
    }

    @Test
    fun `isMigrationComplete is false until finalize runs`() = runTest {
        assertFalse(v2Helper.isMigrationComplete())

        seedV1Wallet("alpha", "aa".repeat(32), null)
        v2Helper.migrateWallet("alpha", encryptionManager.newEncryptCipherV2()).getOrThrow()

        // Still false even after all wallets migrated — finalize is the explicit
        // signal that the migration cycle is done.
        assertFalse(v2Helper.isMigrationComplete())

        v2Helper.finalize().getOrThrow()
        assertTrue(v2Helper.isMigrationComplete())
    }

    // -- Per-wallet migration --

    @Test
    fun `migrateWallet rewrites row with kdfVersion 2 and bundled blob`() = runTest {
        seedV1Wallet("alpha", "ab".repeat(32), "twelve word mnemonic here for testing only")

        val cipher = encryptionManager.newEncryptCipherV2()
        v2Helper.migrateWallet("alpha", cipher).getOrThrow()

        val migrated = keyMaterialDao.getByWalletId("alpha")!!
        assertEquals(2, migrated.kdfVersion)
        assertNull(
            "V2 rows must store the bundle in encryptedPrivateKey, not in encryptedMnemonic",
            migrated.encryptedMnemonic
        )

        // Round-trip: read the bundle back under a V2 decrypt cipher.
        val decryptCipher = encryptionManager.newDecryptCipherV2(migrated.iv)
        val bundle = v2Helper.readV2Bundle("alpha", decryptCipher)
        assertNotNull(bundle)
        assertEquals("ab".repeat(32), bundle!!.privateKeyHex)
        assertEquals("twelve word mnemonic here for testing only", bundle.mnemonic)
    }

    @Test
    fun `migrateWallet preserves null mnemonic for raw-key wallets`() = runTest {
        seedV1Wallet("rawkey", "ff".repeat(32), null)

        v2Helper.migrateWallet("rawkey", encryptionManager.newEncryptCipherV2()).getOrThrow()

        val migrated = keyMaterialDao.getByWalletId("rawkey")!!
        val bundle = v2Helper.readV2Bundle("rawkey", encryptionManager.newDecryptCipherV2(migrated.iv))
        assertEquals("ff".repeat(32), bundle!!.privateKeyHex)
        assertNull(bundle.mnemonic)
    }

    @Test
    fun `migrateWallet is idempotent for already-V2 wallets`() = runTest {
        seedV1Wallet("alpha", "aa".repeat(32), "alpha mnemonic")
        v2Helper.migrateWallet("alpha", encryptionManager.newEncryptCipherV2()).getOrThrow()
        val firstMigratedAt = keyMaterialDao.getByWalletId("alpha")!!.updatedAt

        // Calling again should succeed but NOT re-encrypt (no fresh cipher consumed).
        // The updatedAt should stay the same — proving the second call was a no-op.
        fakeNow += 1_000L
        v2Helper.migrateWallet("alpha", encryptionManager.newEncryptCipherV2()).getOrThrow()

        val second = keyMaterialDao.getByWalletId("alpha")!!
        assertEquals(2, second.kdfVersion)
        assertEquals(
            "Second migrate call must not rewrite the row",
            firstMigratedAt,
            second.updatedAt
        )
    }

    @Test
    fun `migrateWallet fails when row is missing`() = runTest {
        val result = v2Helper.migrateWallet("nonexistent", encryptionManager.newEncryptCipherV2())
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue("error mentions missing row, got: $msg", msg.contains("No key_material row"))
    }

    @Test
    fun `migrateWallet rejects unknown kdfVersion values`() = runTest {
        // Construct a row with an unrecognized kdfVersion directly. This guards
        // against future schema changes that introduce a v3 without updating
        // the migration helper.
        keyMaterialDao.upsert(
            KeyMaterialEntity(
                walletId = "future",
                encryptedPrivateKey = ByteArray(32) { 0x42 },
                encryptedMnemonic = null,
                iv = ByteArray(12) { 0x10 },
                walletType = "mnemonic",
                mnemonicBackedUp = false,
                updatedAt = fakeNow,
                kdfVersion = 99
            )
        )

        val result = v2Helper.migrateWallet("future", encryptionManager.newEncryptCipherV2())
        assertTrue(result.isFailure)
        assertTrue(
            (result.exceptionOrNull()?.message ?: "").contains("Unknown kdfVersion")
        )
    }

    // -- Crash safety --

    @Test
    fun `crash between wallet migrations leaves remaining wallets recoverable`() = runTest {
        seedV1Wallet("alpha", "aa".repeat(32), "alpha mnemonic")
        seedV1Wallet("beta", "bb".repeat(32), "beta mnemonic")
        seedV1Wallet("gamma", "cc".repeat(32), null)

        // Migrate alpha and beta. Simulate a crash (process kill) right before
        // gamma's migration would have run.
        v2Helper.migrateWallet("alpha", encryptionManager.newEncryptCipherV2()).getOrThrow()
        v2Helper.migrateWallet("beta", encryptionManager.newEncryptCipherV2()).getOrThrow()
        // <crash here>

        // On the next launch the caller re-instantiates the helper and resumes.
        // pendingWalletIds returns only the un-migrated wallets — alpha and
        // beta are NOT re-migrated.
        val v2HelperAfterCrash = KeystoreV2MigrationHelper(
            keyMaterialDao,
            encryptionManager,
            migrationPrefs,
            nowProvider = { fakeNow }
        )
        val resumed = v2HelperAfterCrash.pendingWalletIds()
        assertEquals(listOf("gamma"), resumed)

        // Alpha and beta retain their migrated state (kdfVersion=2) and remain
        // decryptable under V2.
        val alphaMigrated = keyMaterialDao.getByWalletId("alpha")!!
        assertEquals(2, alphaMigrated.kdfVersion)
        val alphaBundle = v2HelperAfterCrash.readV2Bundle(
            "alpha",
            encryptionManager.newDecryptCipherV2(alphaMigrated.iv)
        )
        assertEquals("aa".repeat(32), alphaBundle!!.privateKeyHex)

        // Finish the migration.
        v2HelperAfterCrash.migrateWallet("gamma", encryptionManager.newEncryptCipherV2()).getOrThrow()
        assertEquals(emptyList<String>(), v2HelperAfterCrash.pendingWalletIds())

        v2HelperAfterCrash.finalize().getOrThrow()
        assertTrue(v2HelperAfterCrash.isMigrationComplete())
    }

    @Test
    fun `crash mid-encryption leaves the row on V1 untouched`() = runTest {
        // The "crash mid-encryption" path: the caller obtains a V2 cipher but
        // never calls migrateWallet (the process dies before doFinal). The row
        // must remain at kdfVersion=1 with V1 ciphertext intact, so the next
        // launch finds it via pendingWalletIds and can re-migrate.
        seedV1Wallet("alpha", "aa".repeat(32), "alpha mnemonic")

        // Acquire a cipher but never use it — simulating a kill between
        // BiometricPrompt success and migrateWallet's first DB write.
        encryptionManager.newEncryptCipherV2() // discarded

        // Row is unchanged.
        val unchanged = keyMaterialDao.getByWalletId("alpha")!!
        assertEquals(1, unchanged.kdfVersion)
        assertEquals(listOf("alpha"), v2Helper.pendingWalletIds())

        // Retry succeeds.
        v2Helper.migrateWallet("alpha", encryptionManager.newEncryptCipherV2()).getOrThrow()
        assertEquals(2, keyMaterialDao.getByWalletId("alpha")!!.kdfVersion)
    }

    // -- Finalize --

    @Test
    fun `finalize refuses to run when wallets are still on V1`() = runTest {
        seedV1Wallet("alpha", "aa".repeat(32), "alpha")
        seedV1Wallet("beta", "bb".repeat(32), null)

        v2Helper.migrateWallet("alpha", encryptionManager.newEncryptCipherV2()).getOrThrow()
        // beta still on V1

        val result = v2Helper.finalize()
        assertTrue(result.isFailure)
        assertTrue(
            (result.exceptionOrNull()?.message ?: "").contains("Refusing to finalize")
        )
        assertFalse(v2Helper.isMigrationComplete())
    }

    @Test
    fun `finalize succeeds when all wallets are V2`() = runTest {
        seedV1Wallet("alpha", "aa".repeat(32), "alpha")
        v2Helper.migrateWallet("alpha", encryptionManager.newEncryptCipherV2()).getOrThrow()

        v2Helper.finalize().getOrThrow()
        assertTrue(v2Helper.isMigrationComplete())
    }

    @Test
    fun `finalize is callable with an empty database`() = runTest {
        // Fresh-install case: no wallets exist yet, the migration is trivially
        // complete on first v1.7.0 launch.
        v2Helper.finalize().getOrThrow()
        assertTrue(v2Helper.isMigrationComplete())
    }
}
