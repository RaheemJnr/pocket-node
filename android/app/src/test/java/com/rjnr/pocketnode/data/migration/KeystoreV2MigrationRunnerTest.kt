package com.rjnr.pocketnode.data.migration

import android.content.Context
import android.content.SharedPreferences
import androidx.fragment.app.FragmentActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import javax.crypto.Cipher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KeystoreV2MigrationRunnerTest {

    private lateinit var db: AppDatabase
    private lateinit var keyMaterialDao: KeyMaterialDao
    private lateinit var encryptionManager: KeystoreEncryptionManager
    private lateinit var prefs: SharedPreferences
    private lateinit var helper: KeystoreV2MigrationHelper
    private lateinit var legacyHelper: KeyStoreMigrationHelper
    private lateinit var authManager: AuthManager
    private lateinit var runner: KeystoreV2MigrationRunner
    private lateinit var activity: FragmentActivity

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        keyMaterialDao = db.keyMaterialDao()
        encryptionManager = KeystoreEncryptionManager.createForTest()
        prefs = ctx.getSharedPreferences("test_v2_migration", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        legacyHelper = KeyStoreMigrationHelper(keyMaterialDao, encryptionManager, prefs)
        helper = KeystoreV2MigrationHelper(keyMaterialDao, encryptionManager, prefs)
        authManager = mockk(relaxed = true)
        runner = KeystoreV2MigrationRunner(helper, encryptionManager, authManager)
        activity = mockk(relaxed = true)
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `runner migrates V1 row inserted after prefs flag was set`() = runTest {
        // Mark migration globally complete first (mimics post-v1.7.0 state).
        prefs.edit().putBoolean(KeystoreV2MigrationHelper.KEY_MIGRATION_V2_COMPLETE, true).commit()

        // Insert a V1 row AFTER the flag was set (simulates new wallet on v1.7.x).
        legacyHelper.migrateWallet("post-flag-wallet", "aa".repeat(32), "test mnemonic", "mnemonic", false)
        assertEquals(1, keyMaterialDao.getV1WalletIds().size)

        // Fake AuthManager auto-approves with a real V2 cipher.
        coEvery {
            authManager.authenticateForCipher(any(), any<Cipher>(), any(), any())
        } answers {
            AuthManager.CipherAuthResult.Success(args[1] as Cipher)
        }

        val outcome = runner.runMigration(activity)

        assertEquals(KeystoreV2MigrationRunner.Outcome.Completed, outcome)
        assertEquals(2, keyMaterialDao.getByWalletId("post-flag-wallet")!!.kdfVersion)
    }

    @Test
    fun `runner continues past KeyPermanentlyInvalidatedException, returns per-wallet Failed`() = runTest {
        legacyHelper.migrateWallet("wallet-A", "aa".repeat(32), "mnemonic-a", "mnemonic", false)
        legacyHelper.migrateWallet("wallet-B", "bb".repeat(32), "mnemonic-b", "mnemonic", false)

        // Auto-approve every BiometricPrompt — the failure is in the keystore cipher generation,
        // not the auth path.
        coEvery {
            authManager.authenticateForCipher(any(), any<Cipher>(), any(), any())
        } answers { AuthManager.CipherAuthResult.Success(args[1] as Cipher) }

        // Spy the real encryption manager so newEncryptCipherV2 throws KPIE on the SECOND call.
        // pendingWalletIds returns alphabetical order (KeyMaterialDao.getV1WalletIds uses
        // `ORDER BY walletId`), so call #1 succeeds for wallet-A and call #2 throws for wallet-B.
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        val spiedEm = spyk(encryptionManager)
        every { spiedEm.newEncryptCipherV2() } answers {
            if (callCount.getAndIncrement() == 1)
                throw android.security.keystore.KeyPermanentlyInvalidatedException("test")
            else
                callOriginal()
        }
        val runnerWithSpy = KeystoreV2MigrationRunner(helper, spiedEm, authManager)
        val outcome = runnerWithSpy.runMigration(activity)

        when (outcome) {
            is KeystoreV2MigrationRunner.Outcome.Failed -> {
                assertEquals(listOf("wallet-B"), outcome.failedWalletIds)
            }
            else -> fail("Expected Failed, got $outcome")
        }
        assertEquals(2, keyMaterialDao.getByWalletId("wallet-A")!!.kdfVersion)
        assertEquals(1, keyMaterialDao.getByWalletId("wallet-B")!!.kdfVersion)
    }

    @Test
    fun `runner breaks loop on ERROR_LOCKOUT and leaves remaining wallets untouched`() = runTest {
        legacyHelper.migrateWallet("wallet-A", "aa".repeat(32), "mnemonic-a", "mnemonic", false)
        legacyHelper.migrateWallet("wallet-B", "bb".repeat(32), "mnemonic-b", "mnemonic", false)

        // First (and only) prompt returns ERROR_LOCKOUT. The runner must abort
        // immediately rather than burning through wallet-B's prompt.
        coEvery {
            authManager.authenticateForCipher(any(), any<Cipher>(), any(), any())
        } answers {
            AuthManager.CipherAuthResult.Error(
                androidx.biometric.BiometricPrompt.ERROR_LOCKOUT,
                "Too many attempts"
            )
        }

        val outcome = runner.runMigration(activity)

        when (outcome) {
            is KeystoreV2MigrationRunner.Outcome.Failed -> {
                assertEquals(listOf("wallet-A"), outcome.failedWalletIds)
            }
            else -> fail("Expected Failed (session-fatal lockout), got $outcome")
        }
        // Both wallets are still V1: wallet-A failed authentication, wallet-B was never prompted.
        assertEquals(1, keyMaterialDao.getByWalletId("wallet-A")!!.kdfVersion)
        assertEquals(1, keyMaterialDao.getByWalletId("wallet-B")!!.kdfVersion)
        // Confirm the runner stopped after the first prompt — wallet-B was never touched.
        coVerify(exactly = 1) {
            authManager.authenticateForCipher(any(), any<Cipher>(), any(), any())
        }
    }

    @Test
    fun `runner Cancel accumulates and continues to next wallet (Policy A)`() = runTest {
        legacyHelper.migrateWallet("wallet-A", "aa".repeat(32), "mnemonic-a", "mnemonic", false)
        legacyHelper.migrateWallet("wallet-B", "bb".repeat(32), "mnemonic-b", "mnemonic", false)

        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        coEvery {
            authManager.authenticateForCipher(any(), any<Cipher>(), any(), any())
        } answers {
            if (callCount.getAndIncrement() == 0)
                AuthManager.CipherAuthResult.Cancelled  // wallet-A cancelled
            else
                AuthManager.CipherAuthResult.Success(args[1] as Cipher)  // wallet-B succeeds
        }

        val outcome = runner.runMigration(activity)

        when (outcome) {
            is KeystoreV2MigrationRunner.Outcome.Failed -> {
                assertEquals(listOf("wallet-A"), outcome.failedWalletIds)
            }
            else -> fail("Expected Failed (Policy A: cancel becomes per-wallet failure), got $outcome")
        }
        assertEquals(1, keyMaterialDao.getByWalletId("wallet-A")!!.kdfVersion)  // still V1
        assertEquals(2, keyMaterialDao.getByWalletId("wallet-B")!!.kdfVersion)  // migrated
    }
}
