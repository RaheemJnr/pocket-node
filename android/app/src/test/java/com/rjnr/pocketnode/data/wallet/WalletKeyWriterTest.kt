package com.rjnr.pocketnode.data.wallet

import android.content.Context
import android.content.SharedPreferences
import androidx.fragment.app.FragmentActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.migration.KeystoreV2MigrationHelper
import com.rjnr.pocketnode.data.migration.WalletKeyBundle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import javax.crypto.Cipher
import kotlinx.coroutines.test.runTest
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
 * Unit tests for [WalletKeyWriter] — covers the new-wallet V2 persistence
 * path introduced in #289 chunk 2.
 *
 * Uses a real in-memory Room database + real [KeystoreEncryptionManager]
 * (with test-key factory) so the kdfVersion=2 write + rollback semantics
 * are asserted against actual DAO behavior, not mocks. Only [AuthManager]
 * and [KeyBackupManager] are mocked — those are the seams we drive the
 * test paths through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WalletKeyWriterTest {

    private lateinit var db: AppDatabase
    private lateinit var keyMaterialDao: KeyMaterialDao
    private lateinit var encryptionManager: KeystoreEncryptionManager
    private lateinit var helper: KeystoreV2MigrationHelper
    private lateinit var prefs: SharedPreferences
    private lateinit var authManager: AuthManager
    private lateinit var keyBackupManager: KeyBackupManager
    private lateinit var writer: WalletKeyWriter
    private lateinit var activity: FragmentActivity

    private val bundle = WalletKeyBundle(
        privateKeyHex = "aa".repeat(32),
        mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    )

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        keyMaterialDao = db.keyMaterialDao()
        encryptionManager = KeystoreEncryptionManager.createForTest()
        prefs = ctx.getSharedPreferences("test_v2_writer", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        helper = KeystoreV2MigrationHelper(keyMaterialDao, encryptionManager, prefs)
        authManager = mockk(relaxed = true)
        keyBackupManager = mockk(relaxed = true)
        writer = WalletKeyWriter(keyMaterialDao, helper, encryptionManager, authManager, keyBackupManager)
        activity = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `persistNewWallet writes a row at kdfVersion=2 with no session PIN (first-wallet path)`() = runTest {
        coEvery {
            authManager.authenticateForCipher(any(), any<Cipher>(), any(), any())
        } answers { AuthManager.CipherAuthResult.Success(args[1] as Cipher) }
        coEvery { authManager.getSessionPin() } returns null

        val result = writer.persistNewWallet(activity, "w-1", bundle, "mnemonic", false)

        assertEquals(WalletKeyWriter.Result.Success, result)
        val entity = keyMaterialDao.getByWalletId("w-1")
        assertNotNull(entity)
        assertEquals(2, entity!!.kdfVersion)
        coVerify(exactly = 0) { keyBackupManager.writeBackup(any(), any(), any()) }
    }

    @Test
    fun `persistNewWallet writes Room + PIN backup when session PIN available`() = runTest {
        val pin = charArrayOf('1', '2', '3', '4', '5', '6')
        coEvery {
            authManager.authenticateForCipher(any(), any<Cipher>(), any(), any())
        } answers { AuthManager.CipherAuthResult.Success(args[1] as Cipher) }
        coEvery { authManager.getSessionPin() } returns pin

        val result = writer.persistNewWallet(activity, "w-2", bundle, "mnemonic", true)

        assertEquals(WalletKeyWriter.Result.Success, result)
        assertEquals(2, keyMaterialDao.getByWalletId("w-2")!!.kdfVersion)
        coVerify(exactly = 1) { keyBackupManager.writeBackup("w-2", any(), pin) }
    }

    @Test
    fun `persistNewWallet returns Cancelled when user dismisses BiometricPrompt, no Room write`() = runTest {
        coEvery {
            authManager.authenticateForCipher(any(), any<Cipher>(), any(), any())
        } returns AuthManager.CipherAuthResult.Cancelled

        val result = writer.persistNewWallet(activity, "w-3", bundle, "mnemonic", false)

        assertEquals(WalletKeyWriter.Result.Cancelled, result)
        assertNull(keyMaterialDao.getByWalletId("w-3"))
    }

    @Test
    fun `persistNewWallet rolls back Room row when PIN backup write throws`() = runTest {
        val pin = charArrayOf('1', '2', '3', '4', '5', '6')
        coEvery {
            authManager.authenticateForCipher(any(), any<Cipher>(), any(), any())
        } answers { AuthManager.CipherAuthResult.Success(args[1] as Cipher) }
        coEvery { authManager.getSessionPin() } returns pin
        coEvery { keyBackupManager.writeBackup(any(), any(), any()) } throws RuntimeException("disk full")

        val result = writer.persistNewWallet(activity, "w-4", bundle, "mnemonic", false)

        assertTrue(result is WalletKeyWriter.Result.WriteFailed)
        // Rollback should have removed the key_material row so we don't leave
        // an orphan Room row without a matching backup blob.
        assertNull(keyMaterialDao.getByWalletId("w-4"))
    }
}
