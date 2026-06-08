package com.rjnr.pocketnode.data.wallet

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.MIGRATION_1_2
import com.rjnr.pocketnode.data.database.MIGRATION_2_3
import com.rjnr.pocketnode.data.database.MIGRATION_3_4
import com.rjnr.pocketnode.data.database.MIGRATION_4_5
import com.rjnr.pocketnode.data.migration.KeyStoreMigrationHelper
import com.rjnr.pocketnode.data.migration.KeystoreV2MigrationHelper
import com.rjnr.pocketnode.data.migration.WalletKeyBundle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Multi-wallet isolation tests for [KeyManager] read paths.
 *
 * Pre-#289 chunk 3 these tests called `keyManager.storeKeysForWallet(...)`
 * to seed each wallet. That single-cipher V1 write path is going away in
 * chunk 4. New wallet persistence goes through [com.rjnr.pocketnode.data.wallet.WalletKeyWriter]
 * which writes V2 rows directly via [KeystoreV2MigrationHelper.writeNewV2Row].
 *
 * What we still want to pin here: per-wallet isolation of
 * `keyManager.getPrivateKeyForWallet` / `getMnemonicForWallet` /
 * `deleteWalletKeys`. We seed each wallet at kdfVersion=2 via the V2
 * helper directly — that exercises the same Room column layout the
 * production write path uses post-#289.
 */
@RunWith(RobolectricTestRunner::class)
class KeyManagerMultiWalletTest {

    private lateinit var keyManager: KeyManager
    private lateinit var db: AppDatabase
    private lateinit var encryptionManager: KeystoreEncryptionManager
    private lateinit var v2Helper: KeystoreV2MigrationHelper

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        encryptionManager = KeystoreEncryptionManager.createForTest()
        val migrationPrefs = context.getSharedPreferences("test_multi_migration", Context.MODE_PRIVATE)
        migrationPrefs.edit().clear().commit()
        val migrationHelper = KeyStoreMigrationHelper(db.keyMaterialDao(), encryptionManager, migrationPrefs)
        v2Helper = KeystoreV2MigrationHelper(db.keyMaterialDao(), encryptionManager, migrationPrefs)

        val mnemonicManager = MnemonicManager()
        keyManager = KeyManager(context, mnemonicManager)
        keyManager.keyStoreMigrationHelper = migrationHelper
    }

    @After
    fun tearDown() { db.close() }

    /**
     * Seed a wallet's key_material row by going through the real V2 write
     * path that `WalletKeyWriter` uses in production. No biometric prompt
     * required in tests because [KeystoreEncryptionManager.createForTest]
     * issues an unrestricted V2 key.
     */
    private suspend fun persistWalletForTest(
        walletId: String,
        key: ByteArray,
        mnemonic: List<String>?,
    ) {
        val bundle = WalletKeyBundle(
            privateKeyHex = key.joinToString("") { "%02x".format(it) },
            mnemonic = mnemonic?.joinToString(" "),
        )
        val walletType = if (mnemonic != null) KeyManager.WALLET_TYPE_MNEMONIC else KeyManager.WALLET_TYPE_RAW_KEY
        v2Helper.writeNewV2Row(
            walletId = walletId,
            bundle = bundle,
            v2EncryptCipher = encryptionManager.newEncryptCipherV2(),
            walletType = walletType,
            mnemonicBackedUp = false,
        ).getOrThrow()
    }

    /**
     * Read the V2 bundle for [walletId] using a fresh decrypt cipher
     * seeded with the row's stored IV. Mirrors `WalletKeyReader`'s
     * production read path. Returns null when no row exists.
     */
    private suspend fun readV2Bundle(walletId: String): WalletKeyBundle? {
        val entity = db.keyMaterialDao().getByWalletId(walletId) ?: return null
        val cipher = encryptionManager.newDecryptCipherV2(entity.iv)
        return v2Helper.readV2Bundle(walletId, cipher)
    }

    private suspend fun readV2PrivateKey(walletId: String): ByteArray? {
        val bundle = readV2Bundle(walletId) ?: return null
        return bundle.privateKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private suspend fun readV2Mnemonic(walletId: String): List<String>? {
        val bundle = readV2Bundle(walletId) ?: return null
        return bundle.mnemonic?.split(" ")
    }

    @Test
    fun `V2 rows are isolated per wallet`() = runTest {
        val key1 = ByteArray(32) { 0x01 }
        val key2 = ByteArray(32) { 0x02 }

        persistWalletForTest("wallet-1", key1, null)
        persistWalletForTest("wallet-2", key2, null)

        assertArrayEquals(key1, readV2PrivateKey("wallet-1"))
        assertArrayEquals(key2, readV2PrivateKey("wallet-2"))
    }

    @Test
    fun `deleteWalletKeys removes only target wallet`() = runTest {
        val key1 = ByteArray(32) { 0x01 }
        val key2 = ByteArray(32) { 0x02 }

        persistWalletForTest("wallet-1", key1, null)
        persistWalletForTest("wallet-2", key2, null)

        keyManager.deleteWalletKeys("wallet-1")

        assertNull(readV2PrivateKey("wallet-1"))
        assertNotNull(readV2PrivateKey("wallet-2"))
    }

    @Test
    fun `V2 bundle round-trips stored mnemonic`() = runTest {
        val key = ByteArray(32) { 0x01 }
        val mnemonic = listOf(
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "about",
        )

        persistWalletForTest("wallet-1", key, mnemonic)

        assertEquals(mnemonic, readV2Mnemonic("wallet-1"))
    }

    @Test
    fun `raw key wallet has no mnemonic in V2 bundle`() = runTest {
        val key = ByteArray(32) { 0x01 }
        persistWalletForTest("wallet-1", key, null)

        assertNull(readV2Mnemonic("wallet-1"))
    }
}
