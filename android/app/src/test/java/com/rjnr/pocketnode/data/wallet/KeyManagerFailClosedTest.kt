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

/** UI-free secret access always requires the foreground reader; legacy and unknown source bytes stay intact. */
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
            NoopLogger, database = db)
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
            fail("expected WalletSecretRequiresAuthenticationException")
        } catch (e: WalletSecretRequiresAuthenticationException) {
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

    // Existing unreadable rows are never silently repaired by UI-free reads.

    @Test
    fun `an unreadable row and legacy copy are preserved until foreground recovery`() = runTest {
        seedLegacyPrefs("wallet-broken")
        seedUnreadableRow("wallet-broken")

        val source = db.keyMaterialDao().getByWalletId("wallet-broken")
        expectUnreadable { keyManager.getMnemonicForWallet("wallet-broken") }
        assertEquals(source, db.keyMaterialDao().getByWalletId("wallet-broken"))
        assertEquals(99, db.keyMaterialDao().getKdfVersion("wallet-broken"))

    }

    @Test
    fun `an unreadable row cannot release a legacy private key without authentication`() = runTest {
        seedLegacyPrefs("wallet-broken")
        seedUnreadableRow("wallet-broken")

        val source = db.keyMaterialDao().getByWalletId("wallet-broken")
        expectUnreadable { keyManager.getPrivateKeyForWallet("wallet-broken") }
        assertEquals(source, db.keyMaterialDao().getByWalletId("wallet-broken"))

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

    // ESP-only secrets also require foreground authentication.

    @Test
    fun `getMnemonicForWallet requires authentication for an ESP only wallet`() = runTest {
        seedLegacyPrefs("wallet-legacy")

        expectUnreadable { keyManager.getMnemonicForWallet("wallet-legacy") }
        // Nothing was written: a pre-Room install stays pre-Room until the real
        // ESP-to-Room migration runs.
        assertNull(db.keyMaterialDao().getKdfVersion("wallet-legacy"))
    }

    @Test
    fun `getPrivateKeyForWallet requires authentication for an ESP only wallet`() = runTest {
        seedLegacyPrefs("wallet-legacy")

        expectUnreadable { keyManager.getPrivateKeyForWallet("wallet-legacy") }
        assertNull(db.keyMaterialDao().getByWalletId("wallet-legacy"))
    }

    @Test
    fun `active-wallet ESP reads require foreground authentication`() = runTest {
        seedLegacyPrefs("default")

        expectUnreadable { keyManager.getMnemonic() }
        expectUnreadable { keyManager.getPrivateKey() }
    }

    @Test
    fun `an unauthenticated secret getter does not probe an empty legacy wallet`() = runTest {
        seedLegacyPrefs("wallet-empty", withMaterial = false)

        expectUnreadable { keyManager.getMnemonicForWallet("wallet-empty") }
        expectUnreadable { keyManager.getPrivateKeyForWallet("wallet-empty") }
    }

    // Readable software-encrypted rows remain gated.

    @Test
    fun `even readable legacy Room rows cannot bypass foreground authentication`() = runTest {
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

        expectUnreadable { keyManager.getMnemonicForWallet("wallet-migrated") }
        expectUnreadable { keyManager.getPrivateKeyForWallet("wallet-migrated") }
        assertEquals(roomMnemonic, migrationHelper.readDecryptedKey("wallet-migrated")!!.mnemonic)
        assertTrue(migrationHelper.hasKeyMaterialRow("wallet-migrated"))
        verify(exactly = 0) { prefs.getString(any(), any()) }
    }
}
