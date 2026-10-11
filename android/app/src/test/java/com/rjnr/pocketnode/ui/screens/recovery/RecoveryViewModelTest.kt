package com.rjnr.pocketnode.ui.screens.recovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.crypto.Blake2b
import com.rjnr.pocketnode.data.crypto.KeyBackupManager
import com.rjnr.pocketnode.data.crypto.KeyMaterial
import com.rjnr.pocketnode.data.gateway.LocalWalletReset
import com.rjnr.pocketnode.util.AppRestarter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RecoveryViewModelTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val correctPin = "123456".toCharArray()
    private val wrongPin = "999999".toCharArray()
    private var fakeTimeMs: Long = 1_000_000L

    private fun testMaterial(walletId: String = "wallet1") = KeyMaterial(
        privateKey = "a".repeat(64),
        mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        walletType = "mnemonic",
        mnemonicBackedUp = true,
        createdAt = "2026-01-01T00:00:00Z"
    )

    private lateinit var authManager: AuthManager
    private val localWalletReset = mockk<LocalWalletReset>(relaxed = true)
    private val appRestarter = mockk<AppRestarter>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        authManager = AuthManager(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createBackupManager(): KeyBackupManager =
        KeyBackupManager(tempDir.root, NoopLogger).also {
            it.kdfIterations = 1_000          // fast PBKDF2 for legacy reads
            it.argon2Iterations = 1           // fast Argon2id for v2 writes/reads
            it.argon2MemoryKb = 8
            it.argon2Parallelism = 1
        }

    /** A fast, in-memory PinManager with an optional stored PIN. */
    private fun createPinManager(pin: CharArray? = null): PinManager {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return PinManager(ctx, Blake2b(), NoopLogger).apply {
            testPrefs = ctx.getSharedPreferences("test_recovery_pin", Context.MODE_PRIVATE)
            testPrefs!!.edit().clear().commit()
            timeProvider = { fakeTimeMs }
            argon2Iterations = 1
            argon2MemoryKb = 8
            argon2Parallelism = 1
            if (pin != null) setPinFromChars(pin.copyOf())
        }
    }

    @Test
    fun `initial state is PinEntry when backups exist`() {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)

        val vm = RecoveryViewModel(manager, createPinManager(correctPin), authManager, localWalletReset, appRestarter, testDispatcher)

        assertEquals(RecoveryStage.PIN_ENTRY, vm.uiState.value.stage)
        assertEquals(0, vm.uiState.value.failedAttempts)
        assertTrue(vm.uiState.value.recoveredWallets.isEmpty())
    }

    @Test
    fun `initial state is MnemonicEntry when no backups exist`() {
        val manager = createBackupManager()

        val vm = RecoveryViewModel(manager, createPinManager(correctPin), authManager, localWalletReset, appRestarter, testDispatcher)

        assertEquals(RecoveryStage.MNEMONIC_ENTRY, vm.uiState.value.stage)
    }

    @Test
    fun `attemptPinRecovery succeeds with correct PIN`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)

        val vm = RecoveryViewModel(manager, createPinManager(correctPin), authManager, localWalletReset, appRestarter, testDispatcher)
        vm.attemptPinRecovery(correctPin)
        advanceUntilIdle()

        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertEquals(1, vm.uiState.value.recoveredWallets.size)
        assertEquals("wallet1", vm.uiState.value.recoveredWallets[0].walletId)
        assertEquals("a".repeat(64), vm.uiState.value.recoveredWallets[0].material.privateKey)
        assertTrue(vm.uiState.value.failedWalletIds.isEmpty())
    }

    @Test
    fun `wrong PIN is rejected via PinManager and never reaches decrypt`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)
        val pinManager = createPinManager(correctPin)

        val vm = RecoveryViewModel(manager, pinManager, authManager, localWalletReset, appRestarter, testDispatcher)
        vm.attemptPinRecovery(wrongPin)
        advanceUntilIdle()

        assertEquals(RecoveryStage.PIN_ENTRY, vm.uiState.value.stage)
        assertEquals(1, vm.uiState.value.failedAttempts)
        // PinManager (MAX_ATTEMPTS = 5) drives the counter, not a per-screen 3.
        assertEquals("Incorrect PIN. 4 attempts remaining.", vm.uiState.value.error)
        // The failed recovery attempt was recorded in the SHARED persistent
        // lockout state — the bypass is closed.
        assertEquals(4, pinManager.getRemainingAttempts())
    }

    @Test
    fun `recovery PIN attempts hit the persistent lockout (no bypass)`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)
        val pinManager = createPinManager(correctPin)

        val vm = RecoveryViewModel(manager, pinManager, authManager, localWalletReset, appRestarter, testDispatcher)
        // 5 wrong attempts → PinManager locks out for 30s.
        repeat(5) {
            vm.attemptPinRecovery(wrongPin)
            advanceUntilIdle()
        }
        assertTrue("PinManager must be locked out after 5 failures", pinManager.isLockedOut())

        // A further attempt is refused before any decrypt, even with the
        // CORRECT pin, because the lockout is active.
        vm.attemptPinRecovery(correctPin)
        advanceUntilIdle()
        assertEquals(RecoveryStage.PIN_ENTRY, vm.uiState.value.stage)
        assertTrue(vm.uiState.value.error!!.contains("Try again"))

        // Once the lockout expires, the correct PIN recovers.
        fakeTimeMs += 31_000L
        vm.attemptPinRecovery(correctPin)
        advanceUntilIdle()
        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
    }

    @Test
    fun `permanent lockout routes to MnemonicEntry`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)
        val pinManager = createPinManager(correctPin)

        val vm = RecoveryViewModel(manager, pinManager, authManager, localWalletReset, appRestarter, testDispatcher)
        // Grind to the permanent-lockout threshold (10 failures), advancing time
        // past each escalating lockout window so the next attempt isn't pre-blocked.
        val windows = longArrayOf(0, 0, 0, 0, 31_000, 61_000, 301_000, 1_801_000, 3_601_000, 3_601_000)
        for (w in windows) {
            fakeTimeMs += w
            vm.attemptPinRecovery(wrongPin)
            advanceUntilIdle()
        }
        assertTrue(pinManager.isPermanentlyLocked())
        assertEquals(RecoveryStage.MNEMONIC_ENTRY, vm.uiState.value.stage)
    }

    @Test
    fun `clearError sets error to null`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)

        val vm = RecoveryViewModel(manager, createPinManager(correctPin), authManager, localWalletReset, appRestarter, testDispatcher)
        vm.attemptPinRecovery(wrongPin)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.error != null)

        vm.clearError()
        assertEquals(null, vm.uiState.value.error)
    }

    @Test
    fun `partial recovery reports both recovered and failed wallets`() = runTest {
        val manager = createBackupManager()
        val pin1 = "111111".toCharArray()
        val pin2 = "222222".toCharArray()
        // wallet1 encrypted with pin1, wallet2 encrypted with pin2.
        manager.writeBackup("wallet1", testMaterial("wallet1"), pin1)
        manager.writeBackup("wallet2", testMaterial("wallet2"), pin2)

        // The device PIN is pin1, so the verify gate passes and the decrypt
        // loop recovers wallet1 while wallet2 (encrypted under pin2) fails.
        val vm = RecoveryViewModel(manager, createPinManager(pin1), authManager, localWalletReset, appRestarter, testDispatcher)
        vm.attemptPinRecovery(pin1)
        advanceUntilIdle()

        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertEquals(1, vm.uiState.value.recoveredWallets.size)
        assertEquals("wallet1", vm.uiState.value.recoveredWallets[0].walletId)
        assertEquals(1, vm.uiState.value.failedWalletIds.size)
        assertEquals("wallet2", vm.uiState.value.failedWalletIds[0])
    }

    // --- Restore from phrase (the phrase restore keeps the existing wallets) ---

    private fun newVm(manager: KeyBackupManager, pinManager: PinManager) =
        RecoveryViewModel(manager, pinManager, authManager, localWalletReset, appRestarter, testDispatcher)

    /** Ten wrong PINs, past each escalating lockout window, to the permanent lockout. */
    private fun lockPermanently(pinManager: PinManager) {
        val windows = longArrayOf(0, 0, 0, 0, 31_000, 61_000, 301_000, 1_801_000, 3_601_000, 3_601_000)
        for (w in windows) {
            fakeTimeMs += w
            pinManager.verifyPinFromChars(wrongPin)
        }
        assertTrue(pinManager.isPermanentlyLocked())
    }

    @Test
    fun `with a PIN set, Restore from phrase asks for the PIN and opens nothing`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)
        val vm = newVm(manager, createPinManager(correctPin))

        vm.requestPhraseRestore()
        advanceUntilIdle()

        assertEquals(RecoveryStage.PHRASE_PIN_ENTRY, vm.uiState.value.stage)
        assertFalse(vm.uiState.value.phraseRestoreReady)
        assertFalse(vm.uiState.value.showStartFreshConfirm)
    }

    @Test
    fun `with no backups, the phrase button also asks for the PIN`() = runTest {
        val vm = newVm(createBackupManager(), createPinManager(correctPin))
        assertEquals(RecoveryStage.MNEMONIC_ENTRY, vm.uiState.value.stage)

        vm.requestPhraseRestore()
        advanceUntilIdle()

        assertEquals(RecoveryStage.PHRASE_PIN_ENTRY, vm.uiState.value.stage)
        assertFalse(vm.uiState.value.phraseRestoreReady)
    }

    @Test
    fun `the correct PIN opens the phrase restore and keeps the wallets`() = runTest {
        val vm = newVm(createBackupManager(), createPinManager(correctPin))
        vm.requestPhraseRestore()

        vm.attemptPhrasePin(correctPin.copyOf())
        advanceUntilIdle()

        assertTrue(vm.uiState.value.phraseRestoreReady)
        assertEquals(RecoveryStage.MNEMONIC_ENTRY, vm.uiState.value.stage)
        assertArrayEquals(correctPin, authManager.getSessionPin())
        coVerify(exactly = 0) { localWalletReset.removeAllWalletsAndPin() }

        vm.onPhraseRestoreOpened()
        assertFalse(vm.uiState.value.phraseRestoreReady)
    }

    @Test
    fun `a wrong PIN on the phrase step counts on the shared lockout`() = runTest {
        val pinManager = createPinManager(correctPin)
        val vm = newVm(createBackupManager(), pinManager)
        vm.requestPhraseRestore()

        vm.attemptPhrasePin(wrongPin)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.phraseRestoreReady)
        assertEquals(RecoveryStage.PHRASE_PIN_ENTRY, vm.uiState.value.stage)
        assertEquals("Incorrect PIN. 4 attempts remaining.", vm.uiState.value.error)
        assertEquals(4, pinManager.getRemainingAttempts())
        assertNull(authManager.getSessionPin())

        repeat(4) {
            vm.attemptPhrasePin(wrongPin)
            advanceUntilIdle()
        }
        assertTrue(pinManager.isLockedOut())

        // Locked out: even the correct PIN is refused until the window ends.
        vm.attemptPhrasePin(correctPin.copyOf())
        advanceUntilIdle()
        assertFalse(vm.uiState.value.phraseRestoreReady)
        assertTrue(vm.uiState.value.error!!.contains("Try again"))

        fakeTimeMs += 31_000L
        vm.attemptPhrasePin(correctPin.copyOf())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.phraseRestoreReady)
    }

    @Test
    fun `the backup PIN and the phrase PIN share one counter`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)
        val pinManager = createPinManager(correctPin)
        val vm = newVm(manager, pinManager)

        vm.attemptPinRecovery(wrongPin)
        advanceUntilIdle()
        vm.requestPhraseRestore()
        vm.attemptPhrasePin(wrongPin)
        advanceUntilIdle()

        assertEquals(3, pinManager.getRemainingAttempts())
        assertEquals("Incorrect PIN. 3 attempts remaining.", vm.uiState.value.error)
    }

    @Test
    fun `cancel on the phrase PIN step goes back and opens nothing`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)
        val vm = newVm(manager, createPinManager(correctPin))
        vm.requestPhraseRestore()

        vm.cancelPhrasePin()
        advanceUntilIdle()

        assertEquals(RecoveryStage.PIN_ENTRY, vm.uiState.value.stage)
        assertFalse(vm.uiState.value.phraseRestoreReady)
    }

    @Test
    fun `a PIN already verified by the backup restore is not asked again`() = runTest {
        val manager = createBackupManager()
        // The backup was written under another PIN: the app PIN verifies but
        // nothing decrypts, so the screen falls back to the phrase.
        manager.writeBackup("wallet1", testMaterial(), "222222".toCharArray())
        val vm = newVm(manager, createPinManager(correctPin))
        vm.attemptPinRecovery(correctPin.copyOf())
        advanceUntilIdle()
        assertEquals(RecoveryStage.MNEMONIC_ENTRY, vm.uiState.value.stage)

        vm.requestPhraseRestore()

        assertTrue(vm.uiState.value.phraseRestoreReady)
    }

    @Test
    fun `permanent lockout asks to start fresh and Cancel changes nothing`() = runTest {
        val pinManager = createPinManager(correctPin)
        lockPermanently(pinManager)
        val vm = newVm(createBackupManager(), pinManager)

        vm.requestPhraseRestore()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showStartFreshConfirm)
        assertFalse(vm.uiState.value.phraseRestoreReady)
        assertEquals(RecoveryStage.MNEMONIC_ENTRY, vm.uiState.value.stage)

        vm.dismissStartFresh()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.showStartFreshConfirm)
        assertFalse(vm.uiState.value.phraseRestoreReady)
        coVerify(exactly = 0) { localWalletReset.removeAllWalletsAndPin() }
        assertTrue(pinManager.hasPin())
    }

    @Test
    fun `the PIN step reaching permanent lockout offers start fresh next`() = runTest {
        val pinManager = createPinManager(correctPin)
        val vm = newVm(createBackupManager(), pinManager)
        vm.requestPhraseRestore()
        val windows = longArrayOf(0, 0, 0, 0, 31_000, 61_000, 301_000, 1_801_000, 3_601_000, 3_601_000)
        for (w in windows) {
            fakeTimeMs += w
            vm.attemptPhrasePin(wrongPin)
            advanceUntilIdle()
        }
        assertTrue(pinManager.isPermanentlyLocked())
        assertEquals(RecoveryStage.MNEMONIC_ENTRY, vm.uiState.value.stage)
        assertFalse(vm.uiState.value.phraseRestoreReady)

        vm.requestPhraseRestore()

        assertTrue(vm.uiState.value.showStartFreshConfirm)
        assertFalse(vm.uiState.value.phraseRestoreReady)
    }

    @Test
    fun `Remove and continue wipes the wallets and the PIN, then restarts the app`() = runTest {
        val pinManager = createPinManager(correctPin)
        lockPermanently(pinManager)
        coEvery { localWalletReset.removeAllWalletsAndPin() } coAnswers { pinManager.removePin(force = true) }
        val vm = newVm(createBackupManager(), pinManager)
        vm.requestPhraseRestore()

        vm.confirmStartFresh()
        advanceUntilIdle()

        coVerify(exactly = 1) { localWalletReset.removeAllWalletsAndPin() }
        verify(exactly = 1) { appRestarter.restart() }
        assertFalse(pinManager.hasPin())
        // The restarted process routes to onboarding; nothing opens in this one.
        assertFalse(vm.uiState.value.phraseRestoreReady)
        assertTrue(vm.uiState.value.isStartingFresh)
    }

    @Test
    fun `a failed wipe keeps the dialog and opens nothing`() = runTest {
        val pinManager = createPinManager(correctPin)
        lockPermanently(pinManager)
        coEvery { localWalletReset.removeAllWalletsAndPin() } throws IllegalStateException("1 wallet(s) could not be removed")
        val vm = newVm(createBackupManager(), pinManager)
        vm.requestPhraseRestore()

        vm.confirmStartFresh()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.phraseRestoreReady)
        assertTrue(vm.uiState.value.showStartFreshConfirm)
        assertTrue(vm.uiState.value.startFreshFailed)
        assertFalse(vm.uiState.value.isStartingFresh)
        verify(exactly = 0) { appRestarter.restart() }
    }

    @Test
    fun `start fresh cannot run without its confirm dialog`() = runTest {
        val vm = newVm(createBackupManager(), createPinManager(correctPin))

        vm.confirmStartFresh()
        advanceUntilIdle()

        coVerify(exactly = 0) { localWalletReset.removeAllWalletsAndPin() }
        assertFalse(vm.uiState.value.phraseRestoreReady)
    }

    @Test
    fun `a PIN store that cannot be read asks to retry and never offers start fresh`() = runTest {
        val pinManager = mockk<PinManager>(relaxed = true)
        every { pinManager.hasPin() } throws SecurityException("keystore unavailable")
        every { pinManager.isLockedOut() } throws SecurityException("keystore unavailable")
        val vm = newVm(createBackupManager(), pinManager)

        vm.requestPhraseRestore()

        assertFalse(vm.uiState.value.showStartFreshConfirm)
        assertFalse(vm.uiState.value.phraseRestoreReady)
        assertEquals(RecoveryStage.PHRASE_PIN_ENTRY, vm.uiState.value.stage)
        assertEquals("Could not check your PIN. Try again.", vm.uiState.value.error)

        // A PIN entered while the store is unreadable is a retry, not a crash.
        vm.attemptPhrasePin(correctPin.copyOf())
        advanceUntilIdle()

        assertEquals(RecoveryStage.PHRASE_PIN_ENTRY, vm.uiState.value.stage)
        assertEquals("Could not check your PIN. Try again.", vm.uiState.value.error)
        assertFalse(vm.uiState.value.phraseRestoreReady)
        assertFalse(vm.uiState.value.showStartFreshConfirm)
        coVerify(exactly = 0) { localWalletReset.removeAllWalletsAndPin() }
    }

    @Test
    fun `the backup restore sets the session PIN once the PIN is verified`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)
        val vm = newVm(manager, createPinManager(correctPin))

        vm.attemptPinRecovery(correctPin.copyOf())
        advanceUntilIdle()

        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertArrayEquals(correctPin, authManager.getSessionPin())
    }

    @Test
    fun `a wrong backup PIN sets no session PIN`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)
        val vm = newVm(manager, createPinManager(correctPin))

        vm.attemptPinRecovery(wrongPin)
        advanceUntilIdle()

        assertNull(authManager.getSessionPin())
    }

    @Test
    fun `with no PIN set, Restore from phrase opens as before`() = runTest {
        val vm = newVm(createBackupManager(), createPinManager(pin = null))

        vm.requestPhraseRestore()

        assertTrue(vm.uiState.value.phraseRestoreReady)
        assertFalse(vm.uiState.value.showStartFreshConfirm)
        coVerify(exactly = 0) { localWalletReset.removeAllWalletsAndPin() }
    }
}
