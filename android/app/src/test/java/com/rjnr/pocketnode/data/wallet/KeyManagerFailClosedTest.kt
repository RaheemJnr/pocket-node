package com.rjnr.pocketnode.data.wallet

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.crypto.KeyMaterialUnreadableException
import com.rjnr.pocketnode.data.crypto.KeyStoreMigrationHelper
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.crypto.V2KeyMaterialRequiresAuthException
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity
import io.mockk.coEvery
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #496: a wallet that has a `key_material` row must never silently serve the
 * legacy plaintext EncryptedSharedPreferences copy when the Room read comes
 * back empty. The read either repairs the row from that legacy copy — loudly,
 * so the inconsistency is gone afterwards — or fails closed. Only genuinely
 * legacy wallets, with no Room row at all, still take the plain fallback.
 *
 * "Room row present but unreadable" is reproduced with an unknown
 * `kdfVersion`, which is the one shape [KeyStoreMigrationHelper.readDecryptedKey]
 * turns into a null return rather than an exception (a corrupt V1 ciphertext
 * lands in the same branch; V2 rows throw before reaching it).
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
     * Stand-in for a wallet's EncryptedSharedPreferences file, seeded with the
     * legacy key material and spied so the test can assert whether it was read.
     */
    private fun seedLegacyPrefs(walletId: String, withMaterial: Boolean = true): SharedPreferences {
        val prefs = context.getSharedPreferences("test_esp_$walletId", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        if (withMaterial) {
            prefs.edit()
                .putString("private_key", legacyKeyHex)
                .putString("mnemonic_words", legacyMnemonic)
                .putString("wallet_type", KeyManager.WALLET_TYPE_MNEMONIC)
                .commit()
        }
        val spy = spyk(prefs)
        keyManager.testWalletPrefs[walletId] = spy
        if (walletId == "default") keyManager.testPrefs = spy
        return spy
    }

    /** Insert a row Room knows about but [KeyStoreMigrationHelper] cannot decrypt. */
    private suspend fun seedUnreadableRow(walletId: String, kdfVersion: Int = 99) {
        db.keyMaterialDao().upsert(
            KeyMaterialEntity(
                walletId = walletId,
                encryptedPrivateKey = ByteArray(16),
                encryptedMnemonic = null,
                iv = ByteArray(12),
                walletType = KeyManager.WALLET_TYPE_MNEMONIC,
                mnemonicBackedUp = false,
                updatedAt = 0L,
                kdfVersion = kdfVersion,
            )
        )
    }

    private inline fun expectUnreadable(block: () -> Unit) {
        try {
            block()
            fail("expected KeyMaterialUnreadableException")
        } catch (e: KeyMaterialUnreadableException) {
            assertEquals("key material present but unreadable for wallet", e.message)
        }
    }

    // -- Room row present, nothing to repair from: fail closed --

    @Test
    fun `getMnemonicForWallet fails closed when the row is unreadable and there is no legacy copy`() =
        runTest {
            seedLegacyPrefs("wallet-1", withMaterial = false)
            seedUnreadableRow("wallet-1")

            expectUnreadable { keyManager.getMnemonicForWallet("wallet-1") }
        }

    @Test
    fun `getPrivateKeyForWallet fails closed when the row is unreadable and there is no legacy copy`() =
        runTest {
            seedLegacyPrefs("wallet-1", withMaterial = false)
            seedUnreadableRow("wallet-1")

            expectUnreadable { keyManager.getPrivateKeyForWallet("wallet-1") }
        }

    @Test
    fun `active-wallet getMnemonic fails closed when the default row is unreadable`() = runTest {
        seedLegacyPrefs("default", withMaterial = false)
        seedUnreadableRow("default")

        expectUnreadable { keyManager.getMnemonic() }
    }

    @Test
    fun `active-wallet getPrivateKey fails closed when the default row is unreadable`() = runTest {
        seedLegacyPrefs("default", withMaterial = false)
        seedUnreadableRow("default")

        expectUnreadable { keyManager.getPrivateKey() }
    }

    // -- Room row present and broken, legacy copy available: repair, don't just serve --

    @Test
    fun `an unreadable row is repaired from the legacy copy rather than bypassed`() = runTest {
        seedLegacyPrefs("wallet-broken")
        seedUnreadableRow("wallet-broken")

        assertEquals(legacyMnemonic.split(" "), keyManager.getMnemonicForWallet("wallet-broken"))

        // The point of repairing rather than falling back: the row is now
        // readable, so the next read comes from Room like any other wallet's.
        val repaired = migrationHelper.readDecryptedKey("wallet-broken")
        assertNotNull(repaired)
        assertEquals(legacyKeyHex, repaired!!.privateKeyHex)
        assertEquals(legacyMnemonic, repaired.mnemonic)
        assertEquals(1, db.keyMaterialDao().getKdfVersion("wallet-broken"))
    }

    @Test
    fun `a repaired row serves the private key too`() = runTest {
        seedLegacyPrefs("wallet-broken")
        seedUnreadableRow("wallet-broken")

        val key = keyManager.getPrivateKeyForWallet("wallet-broken")
        assertEquals(legacyKeyHex, key?.joinToString("") { "%02x".format(it) })
        assertNotNull(migrationHelper.readDecryptedKey("wallet-broken"))
    }

    @Test
    fun `a failed repair still fails closed`() = runTest {
        seedLegacyPrefs("wallet-broken")
        seedUnreadableRow("wallet-broken")
        val failingHelper = spyk(migrationHelper)
        coEvery {
            failingHelper.migrateWallet(any(), any(), any(), any(), any())
        } throws RuntimeException("keystore unavailable")
        keyManager.keyStoreMigrationHelper = failingHelper

        expectUnreadable { keyManager.getMnemonicForWallet("wallet-broken") }
    }

    // -- V2 rows are decided before the fail-closed check --

    @Test
    fun `a V2 row throws the auth exception, never the unreadable one`() = runTest {
        val prefs = seedLegacyPrefs("wallet-v2")
        seedUnreadableRow("wallet-v2", kdfVersion = 2)

        try {
            keyManager.getMnemonicForWallet("wallet-v2")
            fail("expected V2KeyMaterialRequiresAuthException")
        } catch (e: V2KeyMaterialRequiresAuthException) {
            assertTrue(e.message!!.contains("wallet-v2"))
        }

        // A wallet waiting for a BiometricPrompt is not broken: it must not be
        // "repaired" from, or served out of, the legacy store.
        verify(exactly = 0) { prefs.getString(any(), any()) }
        assertEquals(2, db.keyMaterialDao().getKdfVersion("wallet-v2"))
    }

    // -- Genuinely legacy wallet (no Room row): fallback still works --

    @Test
    fun `getMnemonicForWallet falls back to prefs for a wallet with no Room row`() = runTest {
        seedLegacyPrefs("wallet-legacy")

        assertEquals(legacyMnemonic.split(" "), keyManager.getMnemonicForWallet("wallet-legacy"))
        // Nothing was written: a pre-Room install stays pre-Room until the real
        // ESP-to-Room migration runs.
        assertNull(db.keyMaterialDao().getKdfVersion("wallet-legacy"))
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
        seedLegacyPrefs("wallet-empty", withMaterial = false)

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
