package com.rjnr.pocketnode.data.wallet

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.crypto.KeyStoreMigrationHelper
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #496: a wallet that has a `key_material` row must never silently serve
 * the legacy plaintext EncryptedSharedPreferences copy when the Room read
 * comes back empty. Only genuinely legacy wallets — no Room row at all —
 * may still take the ESP fallback.
 *
 * "Room row present but unreadable" is reproduced with an unknown
 * `kdfVersion`, which is the one shape [KeyStoreMigrationHelper.readDecryptedKey]
 * turns into a null return rather than an exception (a corrupt V1 ciphertext
 * lands in the same branch; V2 rows already throw before reaching it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KeyManagerFailClosedTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var keyManager: KeyManager
    private lateinit var migrationHelper: KeyStoreMigrationHelper

    private val legacyKeyHex = "ab".repeat(32)
    private val legacyMnemonic = "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val migrationPrefs = context.getSharedPreferences("test_failclosed_migration", Context.MODE_PRIVATE)
        migrationPrefs.edit().clear().commit()
        migrationHelper = KeyStoreMigrationHelper(
            db.keyMaterialDao(),
            KeystoreEncryptionManager.createForTest(),
            migrationPrefs,
            NoopLogger,
        )
        keyManager = KeyManager(context, MnemonicManager(), NoopLogger)
        keyManager.keyStoreMigrationHelper = migrationHelper
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Stand-in for a wallet's EncryptedSharedPreferences file, seeded with
     * the legacy key material and spied so the test can assert whether it
     * was read at all.
     */
    private fun seedLegacyPrefs(walletId: String): SharedPreferences {
        val prefs = context.getSharedPreferences("test_esp_$walletId", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        prefs.edit()
            .putString("private_key", legacyKeyHex)
            .putString("mnemonic_words", legacyMnemonic)
            .putString("wallet_type", KeyManager.WALLET_TYPE_MNEMONIC)
            .commit()
        val spy = spyk(prefs)
        keyManager.testWalletPrefs[walletId] = spy
        if (walletId == "default") keyManager.testPrefs = spy
        return spy
    }

    /** Insert a row Room knows about but [KeyStoreMigrationHelper] cannot decrypt. */
    private suspend fun seedUnreadableRow(walletId: String) {
        db.keyMaterialDao().upsert(
            KeyMaterialEntity(
                walletId = walletId,
                encryptedPrivateKey = ByteArray(16),
                encryptedMnemonic = null,
                iv = ByteArray(12),
                walletType = KeyManager.WALLET_TYPE_MNEMONIC,
                mnemonicBackedUp = false,
                updatedAt = 0L,
                kdfVersion = 99,
            )
        )
    }

    // -- Wallet WITH a Room row: fail closed, never touch ESP --

    @Test
    fun `getMnemonicForWallet fails closed and does not read prefs when the Room row is unreadable`() = runTest {
        val prefs = seedLegacyPrefs("wallet-1")
        seedUnreadableRow("wallet-1")

        try {
            keyManager.getMnemonicForWallet("wallet-1")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("key material missing for wallet", e.message)
        }

        verify(exactly = 0) { prefs.getString(any(), any()) }
    }

    @Test
    fun `getPrivateKeyForWallet fails closed and does not read prefs when the Room row is unreadable`() = runTest {
        val prefs = seedLegacyPrefs("wallet-1")
        seedUnreadableRow("wallet-1")

        try {
            keyManager.getPrivateKeyForWallet("wallet-1")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("key material missing for wallet", e.message)
        }

        verify(exactly = 0) { prefs.getString(any(), any()) }
    }

    @Test
    fun `active-wallet getMnemonic fails closed when the default Room row is unreadable`() = runTest {
        val prefs = seedLegacyPrefs("default")
        seedUnreadableRow("default")

        try {
            keyManager.getMnemonic()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("key material missing for wallet", e.message)
        }

        verify(exactly = 0) { prefs.getString(any(), any()) }
    }

    @Test
    fun `active-wallet getPrivateKey fails closed when the default Room row is unreadable`() = runTest {
        val prefs = seedLegacyPrefs("default")
        seedUnreadableRow("default")

        try {
            keyManager.getPrivateKey()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("key material missing for wallet", e.message)
        }

        verify(exactly = 0) { prefs.getString(any(), any()) }
    }

    // -- Genuinely legacy wallet (no Room row): fallback still works --

    @Test
    fun `getMnemonicForWallet falls back to prefs for a wallet with no Room row`() = runTest {
        seedLegacyPrefs("wallet-legacy")

        assertEquals(legacyMnemonic.split(" "), keyManager.getMnemonicForWallet("wallet-legacy"))
    }

    @Test
    fun `getPrivateKeyForWallet falls back to prefs for a wallet with no Room row`() = runTest {
        seedLegacyPrefs("wallet-legacy")

        val key = keyManager.getPrivateKeyForWallet("wallet-legacy")
        assertEquals(legacyKeyHex, key?.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `active-wallet reads fall back to prefs when default has no Room row`() = runTest {
        seedLegacyPrefs("default")

        assertEquals(legacyMnemonic.split(" "), keyManager.getMnemonic())
        assertEquals(legacyKeyHex, keyManager.getPrivateKey().joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `a legacy wallet with no key material at all still returns null`() = runTest {
        val prefs = context.getSharedPreferences("test_esp_empty", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        keyManager.testWalletPrefs["wallet-empty"] = prefs

        assertNull(keyManager.getMnemonicForWallet("wallet-empty"))
        assertNull(keyManager.getPrivateKeyForWallet("wallet-empty"))
    }

    // -- A readable Room row still wins over the legacy copy --

    @Test
    fun `a readable Room row is preferred over the legacy prefs copy`() = runTest {
        val prefs = seedLegacyPrefs("wallet-migrated")
        val roomKeyHex = "cd".repeat(32)
        val roomMnemonic = "legal winner thank year wave sausage worth useful legal winner thank yellow"
        migrationHelper.migrateWallet(
            walletId = "wallet-migrated",
            privateKeyHex = roomKeyHex,
            mnemonic = roomMnemonic,
            walletType = KeyManager.WALLET_TYPE_MNEMONIC,
            mnemonicBackedUp = false,
        )

        assertEquals(roomMnemonic.split(" "), keyManager.getMnemonicForWallet("wallet-migrated"))
        assertEquals(
            roomKeyHex,
            keyManager.getPrivateKeyForWallet("wallet-migrated")?.joinToString("") { "%02x".format(it) },
        )
        assertTrue(migrationHelper.hasKeyMaterialRow("wallet-migrated"))
        verify(exactly = 0) { prefs.getString(any(), any()) }
    }
}
