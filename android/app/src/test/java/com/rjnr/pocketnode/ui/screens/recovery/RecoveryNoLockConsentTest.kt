package com.rjnr.pocketnode.ui.screens.recovery

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.crypto.hexToByteArray
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.crypto.Blake2b
import com.rjnr.pocketnode.data.crypto.KeyBackupManager
import com.rjnr.pocketnode.data.crypto.KeyMaterial
import com.rjnr.pocketnode.data.crypto.KeyStoreMigrationHelper
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.crypto.KeystoreV2MigrationHelper
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.LocalWalletReset
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.RecoveryStateStore
import com.rjnr.pocketnode.data.wallet.RecoveredKeyRestorer
import com.rjnr.pocketnode.data.wallet.WalletDerivation
import com.rjnr.pocketnode.data.wallet.WalletKeyReader
import com.rjnr.pocketnode.data.wallet.WalletKeyWriter
import com.rjnr.pocketnode.util.AppRestarter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The no-screen-lock consent on the Recovery screen's backup restore, end to
 * end: a real [RecoveryViewModel], [RecoveredKeyRestorer], key writer and
 * reader, in-memory Room and real backup files. Only the system prompt and
 * the lock state ([AuthManager]) are faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RecoveryNoLockConsentTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val pin = "123456".toCharArray()
    private val keyHex = "11".repeat(32)
    private lateinit var db: AppDatabase
    private lateinit var v1Helper: KeyStoreMigrationHelper
    private lateinit var authManager: AuthManager
    private lateinit var backups: KeyBackupManager
    private lateinit var restorer: RecoveredKeyRestorer
    private lateinit var state: RecoveryStateStore
    private val keyManager = mockk<KeyManager>(relaxed = true)
    private val activity = mockk<FragmentActivity>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            // Run Room on the calling thread so advanceUntilIdle covers it.
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        val encryption = KeystoreEncryptionManager.createForTest()
        val prefs = ctx.getSharedPreferences("test_recovery_no_lock", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        v1Helper = KeyStoreMigrationHelper(db.keyMaterialDao(), encryption, prefs, NoopLogger)
        val v2Helper = KeystoreV2MigrationHelper(db.keyMaterialDao(), encryption, prefs, logger = NoopLogger)
        // A real AuthManager for the session PIN; only the lock state and the
        // system prompt are faked.
        authManager = spyk(AuthManager(ctx))
        coEvery {
            authManager.authenticateForCipher(any(), any<Cipher>(), any(), any())
        } answers { AuthManager.CipherAuthResult.Success(args[1] as Cipher) }
        backups = KeyBackupManager(tempDir.root, NoopLogger).apply {
            argon2Iterations = 1
            argon2MemoryKb = 8
            argon2Parallelism = 1
        }
        val writer = WalletKeyWriter(com.rjnr.pocketnode.data.wallet.WalletVaultIntegrationFixture(db).coordinator)
        state = RecoveryStateStore(ctx)
        state.clear()
        val reader = WalletKeyReader(db.keyMaterialDao(), v1Helper, encryption, authManager, state, mockk(), mockk(), mockk(), com.rjnr.pocketnode.data.wallet.WalletUnlockPreference(ApplicationProvider.getApplicationContext()))
        restorer = RecoveredKeyRestorer(
            db.walletDao(), db.keyMaterialDao(), writer, reader, authManager, keyManager,
            MnemonicManager(), state, backups, NoopLogger,
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun secureLock(present: Boolean) {
        every { authManager.isBiometricEnrolled() } returns present
        every { authManager.hasDeviceCredential() } returns present
    }

    private suspend fun addWallet(walletId: String, keyHex: String, backupPin: CharArray? = pin) {
        val info = WalletDerivation.walletInfo(keyHex.hexToByteArray())
        db.walletDao().insert(
            WalletEntity(
                walletId = walletId,
                name = "Name of $walletId",
                type = KeyManager.WALLET_TYPE_RAW_KEY,
                derivationPath = null,
                parentWalletId = null,
                mainnetAddress = info.mainnetAddress,
                testnetAddress = info.testnetAddress,
            )
        )
        if (backupPin != null) {
            backups.writeBackup(
                walletId,
                KeyMaterial(privateKey = keyHex, walletType = KeyManager.WALLET_TYPE_RAW_KEY, mnemonicBackedUp = false),
                backupPin.copyOf(),
            )
        }
    }

    private suspend fun recoveryWithOneWallet(): RecoveryViewModel {
        addWallet("w1", keyHex)
        return newVm()
    }

    private fun newVm(): RecoveryViewModel {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val pinManager = PinManager(ctx, Blake2b(), NoopLogger).apply {
            testPrefs = ctx.getSharedPreferences("test_recovery_no_lock_pin", Context.MODE_PRIVATE)
            testPrefs!!.edit().clear().commit()
            argon2Iterations = 1
            argon2MemoryKb = 8
            argon2Parallelism = 1
            setPinFromChars(pin.copyOf())
        }
        return RecoveryViewModel(
            backups, pinManager, authManager,
            mockk<LocalWalletReset>(relaxed = true), mockk<AppRestarter>(relaxed = true),
            restorer, mockk(relaxed = true), testDispatcher,
        )
    }

    @Test
    fun `no screen lock asks first, and declining writes nothing and keeps the backup`() = runTest {
        secureLock(false)
        val vm = recoveryWithOneWallet()

        vm.attemptPinRecovery(pin.copyOf(), activity)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showNoLockConsent)
        assertNull(db.keyMaterialDao().getByWalletId("w1"))

        vm.dismissNoLockConsent()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.showNoLockConsent)
        assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)
        assertTrue(vm.uiState.value.canRetryRestore)
        assertTrue(vm.uiState.value.error!!.contains("screen lock"))
        assertNull(db.keyMaterialDao().getByWalletId("w1"))
        assertTrue(backups.hasBackup("w1"))
        @Suppress("DEPRECATION")
        verify(exactly = 0) { keyManager.resetCorruptionFlag() }
    }

    @Test
    fun `no screen lock, accepting stores the keys at V1 and they read back`() = runTest {
        secureLock(false)
        val vm = recoveryWithOneWallet()

        vm.attemptPinRecovery(pin.copyOf(), activity)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showNoLockConsent)
        vm.confirmNoLockConsent(activity)
        advanceUntilIdle()

        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertEquals(1, db.keyMaterialDao().getKdfVersion("w1"))
        assertEquals(keyHex, v1Helper.readDecryptedKey("w1")!!.privateKeyHex)
        assertTrue(backups.hasBackup("w1"))
        @Suppress("DEPRECATION")
        verify(exactly = 1) { keyManager.resetCorruptionFlag() }
    }

    @Test
    fun `declining, setting a screen lock and trying again stores the keys at V2 without asking`() = runTest {
        secureLock(false)
        val vm = recoveryWithOneWallet()
        vm.attemptPinRecovery(pin.copyOf(), activity)
        advanceUntilIdle()
        vm.dismissNoLockConsent()
        advanceUntilIdle()

        secureLock(true)
        vm.retryRestore()
        assertEquals(RecoveryStage.PIN_ENTRY, vm.uiState.value.stage)
        vm.attemptPinRecovery(pin.copyOf(), activity)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.showNoLockConsent)
        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertEquals(2, db.keyMaterialDao().getKdfVersion("w1"))
    }

    @Test
    fun `with a screen lock the dialog never shows`() = runTest {
        secureLock(true)
        val vm = recoveryWithOneWallet()

        vm.attemptPinRecovery(pin.copyOf(), activity)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.showNoLockConsent)
        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertEquals(2, db.keyMaterialDao().getKdfVersion("w1"))
        coVerify(exactly = 2) { authManager.authenticateForCipher(activity, any(), any(), any()) }
    }

    // --- Recovery is complete only when every wallet row has verified keys ---

    @Test
    fun `a wallet whose backup cannot be opened keeps Recovery, the marker and the flag`() = runTest {
        secureLock(true)
        addWallet("w1", keyHex)
        addWallet("w2", "22".repeat(32), backupPin = "999999".toCharArray())
        val vm = newVm()

        vm.attemptPinRecovery(pin.copyOf(), activity)
        advanceUntilIdle()

        assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)
        assertEquals(listOf("Name of w2"), vm.uiState.value.missingWalletNames)
        assertEquals(2, db.keyMaterialDao().getKdfVersion("w1"))
        assertNull(db.keyMaterialDao().getByWalletId("w2"))
        assertTrue(state.isIncomplete())
        assertTrue(backups.hasBackup("w2"))
        @Suppress("DEPRECATION")
        verify(exactly = 0) { keyManager.resetCorruptionFlag() }
    }

    @Test
    fun `a wallet row with no backup file keeps Recovery and the marker`() = runTest {
        secureLock(true)
        addWallet("w1", keyHex)
        addWallet("w2", "22".repeat(32), backupPin = null)
        val vm = newVm()

        vm.attemptPinRecovery(pin.copyOf(), activity)
        advanceUntilIdle()

        assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)
        assertEquals(listOf("Name of w2"), vm.uiState.value.missingWalletNames)
        assertTrue(state.isIncomplete())
        // The marker survives a new launch: a new screen still sees w2 missing.
        assertEquals(listOf("w2"), restorer.missingWallets().map { it.walletId })
        assertTrue(restorer.isMarkedIncomplete())
    }

    @Test
    fun `a read-back cancelled once is verified on the next attempt and only then completes`() = runTest {
        secureLock(true)
        addWallet("w1", keyHex)
        var prompts = 0
        coEvery {
            authManager.authenticateForCipher(any(), any<Cipher>(), any(), any())
        } answers {
            prompts++
            if (prompts == 2) AuthManager.CipherAuthResult.Cancelled
            else AuthManager.CipherAuthResult.Success(args[1] as Cipher)
        }
        val vm = newVm()

        vm.attemptPinRecovery(pin.copyOf(), activity)
        advanceUntilIdle()
        assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)
        assertTrue(vm.uiState.value.canRetryRestore)
        assertTrue(state.isIncomplete())

        vm.retryRestore()
        vm.attemptPinRecovery(pin.copyOf(), activity)
        advanceUntilIdle()

        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertFalse(state.isIncomplete())
        // Write, cancelled read-back, then one read-back on the retry.
        assertEquals(3, prompts)
    }

    // --- Re-key a missing wallet from its phrase, end to end ---

    private val phraseWords =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    /** A root wallet of the public abandon vector, with a row but no keys and no backup. */
    private suspend fun addPhraseWalletWithoutBackup() {
        val mm = MnemonicManager()
        val key = mm.derivePrivateKey(mm.mnemonicToSeed(phraseWords))
        val info = WalletDerivation.walletInfo(key)
        db.walletDao().insert(
            WalletEntity(
                walletId = "root",
                name = "Savings",
                type = KeyManager.WALLET_TYPE_MNEMONIC,
                derivationPath = "m/44'/309'/0'/0/0",
                parentWalletId = null,
                mainnetAddress = info.mainnetAddress,
                testnetAddress = info.testnetAddress,
            )
        )
    }

    @Test
    fun `a missing wallet with no backup is re-keyed from its phrase and recovery completes`() = runTest {
        secureLock(true)
        addWallet("w1", keyHex)
        addPhraseWalletWithoutBackup()
        val vm = newVm()
        vm.attemptPinRecovery(pin.copyOf(), activity)
        advanceUntilIdle()
        assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)
        assertEquals(listOf("Savings"), vm.uiState.value.missingWalletNames)

        vm.openRekey("root", withPrivateKey = false)
        vm.submitRekeyPhrase(activity, listOf("legal", "winner", "thank", "year", "wave", "sausage",
            "worth", "useful", "legal", "winner", "thank", "yellow"))
        advanceUntilIdle()
        assertEquals("This phrase does not belong to Savings.", vm.uiState.value.rekey!!.error)
        assertNull(db.keyMaterialDao().getByWalletId("root"))

        vm.submitRekeyPhrase(activity, phraseWords)
        advanceUntilIdle()

        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertEquals(2, db.keyMaterialDao().getKdfVersion("root"))
        assertFalse(state.isIncomplete())
        @Suppress("DEPRECATION")
        verify(exactly = 1) { keyManager.resetCorruptionFlag() }
    }

    @Test
    fun `on a phone with no screen lock the phrase re-key stores V1 only after the consent`() = runTest {
        secureLock(false)
        addPhraseWalletWithoutBackup()
        // A backup for another wallet id keeps the screen on the backup PIN.
        backups.writeBackup(
            "orphan",
            KeyMaterial(privateKey = keyHex, walletType = KeyManager.WALLET_TYPE_RAW_KEY, mnemonicBackedUp = false),
            pin.copyOf(),
        )
        val vm = newVm()
        vm.attemptPinRecovery(pin.copyOf(), activity)
        advanceUntilIdle()
        assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)

        vm.openRekey("root", withPrivateKey = false)
        assertTrue(vm.uiState.value.showNoLockConsent)
        vm.confirmNoLockConsent(activity)
        vm.submitRekeyPhrase(activity, phraseWords)
        advanceUntilIdle()

        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertEquals(1, db.keyMaterialDao().getKdfVersion("root"))
    }

    // --- No backup files at all: the missing wallets are still reachable ---

    @Test
    fun `with no backup files a missing wallet is reached through the PIN and re-keyed`() = runTest {
        secureLock(true)
        addPhraseWalletWithoutBackup()
        val vm = newVm()
        advanceUntilIdle()
        assertEquals(RecoveryStage.MNEMONIC_ENTRY, vm.uiState.value.stage)
        assertTrue(vm.uiState.value.hasMissingWallets)
        assertTrue(state.isIncomplete())

        vm.requestMissingRestore()
        assertEquals(RecoveryStage.PIN_ENTRY, vm.uiState.value.stage)
        vm.attemptPinRecovery(pin.copyOf(), activity)
        advanceUntilIdle()
        assertEquals(listOf("Savings"), vm.uiState.value.missingWalletNames)

        vm.openRekey("root", withPrivateKey = false)
        vm.submitRekeyPhrase(activity, phraseWords)
        advanceUntilIdle()

        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertFalse(state.isIncomplete())
    }
}
