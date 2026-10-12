package com.rjnr.pocketnode.ui.screens.recovery

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.crypto.Blake2b
import com.rjnr.pocketnode.data.crypto.KeyBackupManager
import com.rjnr.pocketnode.data.crypto.KeyMaterial
import com.rjnr.pocketnode.data.gateway.LocalWalletReset
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.wallet.RecoveredKeyRestorer
import com.rjnr.pocketnode.util.AppRestarter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlin.coroutines.CoroutineContext

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
    private val activity = mockk<FragmentActivity>(relaxed = true)

    // A fake restorer over a fake set of wallet rows: a wallet stays missing
    // until restore() returns Restored for it. Outcomes queued in [outcomes]
    // are returned first; otherwise a decrypted backup restores and a
    // missing one gives NoBackup.
    private val walletRows = linkedMapOf("wallet1" to walletRow("wallet1"))
    private val missing = mutableSetOf("wallet1")
    private val outcomes = mutableMapOf<String, ArrayDeque<RecoveredKeyRestorer.Outcome>>()
    private val keyRestorer = mockk<RecoveredKeyRestorer>(relaxed = true).also {
        coEvery { it.missingWallets() } answers { walletRows.values.filter { w -> w.walletId in missing } }
        coEvery { it.walletIds() } answers { walletRows.keys.toSet() }
        coEvery { it.completeIfDone() } answers { walletRows.isNotEmpty() && missing.isEmpty() }
        coEvery { it.restore(any(), any(), any(), any()) } answers {
            val id = secondArg<String>()
            val outcome = outcomes[id]?.removeFirstOrNull()
                ?: if (thirdArg<KeyMaterial?>() == null) RecoveredKeyRestorer.Outcome.NoBackup
                else RecoveredKeyRestorer.Outcome.Restored
            if (outcome == RecoveredKeyRestorer.Outcome.Restored) missing.remove(id)
            outcome
        }
    }

    private fun walletRow(id: String) = WalletEntity(
        walletId = id, name = "Name of $id", type = "mnemonic",
        derivationPath = null, parentWalletId = null,
    )

    private fun addWallets(vararg ids: String) = ids.forEach {
        walletRows[it] = walletRow(it)
        missing += it
    }

    private fun queue(id: String, vararg results: RecoveredKeyRestorer.Outcome) {
        outcomes.getOrPut(id) { ArrayDeque() }.addAll(results)
    }

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

        val vm = RecoveryViewModel(manager, createPinManager(correctPin), authManager, localWalletReset, appRestarter, keyRestorer, testDispatcher)

        assertEquals(RecoveryStage.PIN_ENTRY, vm.uiState.value.stage)
        assertEquals(0, vm.uiState.value.failedAttempts)
        assertTrue(vm.uiState.value.missingWalletNames.isEmpty())
    }

    @Test
    fun `initial state is MnemonicEntry when no backups exist`() {
        val manager = createBackupManager()

        val vm = RecoveryViewModel(manager, createPinManager(correctPin), authManager, localWalletReset, appRestarter, keyRestorer, testDispatcher)

        assertEquals(RecoveryStage.MNEMONIC_ENTRY, vm.uiState.value.stage)
    }

    @Test
    fun `attemptPinRecovery succeeds with correct PIN`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)

        val vm = RecoveryViewModel(manager, createPinManager(correctPin), authManager, localWalletReset, appRestarter, keyRestorer, testDispatcher)
        vm.attemptPinRecovery(correctPin, activity)
        advanceUntilIdle()

        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertEquals(1, vm.uiState.value.restoredWalletCount)
        coVerify(exactly = 1) {
            keyRestorer.restore(activity, "wallet1", match { it.privateKey == "a".repeat(64) }, false)
        }
        assertTrue(vm.uiState.value.failedWalletIds.isEmpty())
    }

    @Test
    fun `wrong PIN is rejected via PinManager and never reaches decrypt`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)
        val pinManager = createPinManager(correctPin)

        val vm = RecoveryViewModel(manager, pinManager, authManager, localWalletReset, appRestarter, keyRestorer, testDispatcher)
        vm.attemptPinRecovery(wrongPin, activity)
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

        val vm = RecoveryViewModel(manager, pinManager, authManager, localWalletReset, appRestarter, keyRestorer, testDispatcher)
        // 5 wrong attempts → PinManager locks out for 30s.
        repeat(5) {
            vm.attemptPinRecovery(wrongPin, activity)
            advanceUntilIdle()
        }
        assertTrue("PinManager must be locked out after 5 failures", pinManager.isLockedOut())

        // A further attempt is refused before any decrypt, even with the
        // CORRECT pin, because the lockout is active.
        vm.attemptPinRecovery(correctPin, activity)
        advanceUntilIdle()
        assertEquals(RecoveryStage.PIN_ENTRY, vm.uiState.value.stage)
        assertTrue(vm.uiState.value.error!!.contains("Try again"))

        // Once the lockout expires, the correct PIN recovers.
        fakeTimeMs += 31_000L
        vm.attemptPinRecovery(correctPin, activity)
        advanceUntilIdle()
        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
    }

    @Test
    fun `permanent lockout routes to MnemonicEntry`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)
        val pinManager = createPinManager(correctPin)

        val vm = RecoveryViewModel(manager, pinManager, authManager, localWalletReset, appRestarter, keyRestorer, testDispatcher)
        // Grind to the permanent-lockout threshold (10 failures), advancing time
        // past each escalating lockout window so the next attempt isn't pre-blocked.
        val windows = longArrayOf(0, 0, 0, 0, 31_000, 61_000, 301_000, 1_801_000, 3_601_000, 3_601_000)
        for (w in windows) {
            fakeTimeMs += w
            vm.attemptPinRecovery(wrongPin, activity)
            advanceUntilIdle()
        }
        assertTrue(pinManager.isPermanentlyLocked())
        assertEquals(RecoveryStage.MNEMONIC_ENTRY, vm.uiState.value.stage)
    }

    @Test
    fun `clearError sets error to null`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)

        val vm = RecoveryViewModel(manager, createPinManager(correctPin), authManager, localWalletReset, appRestarter, keyRestorer, testDispatcher)
        vm.attemptPinRecovery(wrongPin, activity)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.error != null)

        vm.clearError()
        assertEquals(null, vm.uiState.value.error)
    }

    @Test
    fun `a wallet whose backup cannot be opened keeps the screen on Recovery`() = runTest {
        addWallets("wallet2")
        val manager = createBackupManager()
        val pin1 = "111111".toCharArray()
        val pin2 = "222222".toCharArray()
        // wallet1 encrypted with pin1, wallet2 encrypted with pin2.
        manager.writeBackup("wallet1", testMaterial("wallet1"), pin1)
        manager.writeBackup("wallet2", testMaterial("wallet2"), pin2)

        // The device PIN is pin1, so the verify gate passes and the decrypt
        // loop recovers wallet1 while wallet2 (encrypted under pin2) fails.
        val vm = RecoveryViewModel(manager, createPinManager(pin1), authManager, localWalletReset, appRestarter, keyRestorer, testDispatcher)
        vm.attemptPinRecovery(pin1, activity)
        advanceUntilIdle()

        // wallet1 restores; wallet2 has a row but no backup that opens, so
        // recovery is not complete and the screen stays.
        assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)
        coVerify(exactly = 1) { keyRestorer.restore(activity, "wallet1", match { it != null }, any()) }
        coVerify(exactly = 1) { keyRestorer.restore(activity, "wallet2", null, any()) }
        assertEquals(listOf("Name of wallet2"), vm.uiState.value.missingWalletNames)
        assertEquals(listOf("wallet2"), vm.uiState.value.failedWalletIds)
        assertFalse(vm.uiState.value.canRetryRestore)
    }

    // --- Restore from phrase (the phrase restore keeps the existing wallets) ---

    private fun newVm(manager: KeyBackupManager, pinManager: PinManager) =
        RecoveryViewModel(manager, pinManager, authManager, localWalletReset, appRestarter, keyRestorer, testDispatcher)

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

        vm.attemptPinRecovery(wrongPin, activity)
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
        // nothing decrypts, so the wallet stays missing and the screen
        // offers the phrase.
        manager.writeBackup("wallet1", testMaterial(), "222222".toCharArray())
        val vm = newVm(manager, createPinManager(correctPin))
        vm.attemptPinRecovery(correctPin.copyOf(), activity)
        advanceUntilIdle()
        assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)
        assertEquals(listOf("Name of wallet1"), vm.uiState.value.missingWalletNames)

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

        vm.attemptPinRecovery(correctPin.copyOf(), activity)
        advanceUntilIdle()

        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertArrayEquals(correctPin, authManager.getSessionPin())
    }

    @Test
    fun `a wrong backup PIN sets no session PIN`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)
        val vm = newVm(manager, createPinManager(correctPin))

        vm.attemptPinRecovery(wrongPin, activity)
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

    // --- Cancel while the phrase PIN is being checked ---

    /** Holds the PIN check until the test runs it, so Cancel can land first. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        val pending: Int get() = queue.size
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }
        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    @Test
    fun `Cancel during the PIN check drops a correct PIN`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)
        val held = HeldDispatcher()
        val vm = RecoveryViewModel(manager, createPinManager(correctPin), authManager, localWalletReset, appRestarter, keyRestorer, held)
        vm.requestPhraseRestore()
        vm.attemptPhrasePin(correctPin.copyOf())
        advanceUntilIdle()
        assertEquals(1, held.pending)

        vm.cancelPhrasePin()
        held.runAll()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.phraseRestoreReady)
        assertNull(authManager.getSessionPin())
        assertEquals(RecoveryStage.PIN_ENTRY, vm.uiState.value.stage)
        assertNull(vm.uiState.value.error)
        // The dropped check did not count as verified: the next tap asks again.
        vm.requestPhraseRestore()
        assertEquals(RecoveryStage.PHRASE_PIN_ENTRY, vm.uiState.value.stage)
        assertFalse(vm.uiState.value.phraseRestoreReady)
    }

    @Test
    fun `a wrong PIN finishing after Cancel does not reopen the PIN step`() = runTest {
        val pinManager = createPinManager(correctPin)
        val held = HeldDispatcher()
        val vm = RecoveryViewModel(createBackupManager(), pinManager, authManager, localWalletReset, appRestarter, keyRestorer, held)
        vm.requestPhraseRestore()
        vm.attemptPhrasePin(wrongPin)
        advanceUntilIdle()

        vm.cancelPhrasePin()
        held.runAll()
        advanceUntilIdle()

        assertEquals(RecoveryStage.MNEMONIC_ENTRY, vm.uiState.value.stage)
        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.phraseRestoreReady)
        // The wrong PIN still counts on the shared counter.
        assertEquals(4, pinManager.getRemainingAttempts())
    }

    @Test
    fun `only the latest phrase PIN attempt is applied`() = runTest {
        val held = HeldDispatcher()
        val vm = RecoveryViewModel(createBackupManager(), createPinManager(correctPin), authManager, localWalletReset, appRestarter, keyRestorer, held)
        vm.requestPhraseRestore()
        vm.attemptPhrasePin(correctPin.copyOf())
        vm.attemptPhrasePin(wrongPin)
        advanceUntilIdle()
        assertEquals(2, held.pending)

        held.runAll()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.phraseRestoreReady)
        assertNull(authManager.getSessionPin())
        assertEquals(RecoveryStage.PHRASE_PIN_ENTRY, vm.uiState.value.stage)
        assertEquals("Incorrect PIN. 4 attempts remaining.", vm.uiState.value.error)
    }

    // --- Restore from backup writes the recovered keys back ---

    private fun backupsFor(vararg ids: String): KeyBackupManager =
        createBackupManager().also { m -> ids.forEach { m.writeBackup(it, testMaterial(it), correctPin) } }

    @Test
    fun `every wallet is written back and only then is recovery completed`() = runTest {
        addWallets("wallet2")
        val vm = newVm(backupsFor("wallet1", "wallet2"), createPinManager(correctPin))

        vm.attemptPinRecovery(correctPin.copyOf(), activity)
        advanceUntilIdle()

        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertEquals(2, vm.uiState.value.restoredWalletCount)
        coVerify(exactly = 1) { keyRestorer.restore(activity, "wallet1", any(), any()) }
        coVerify(exactly = 1) { keyRestorer.restore(activity, "wallet2", any(), any()) }
        coVerify(exactly = 1) { keyRestorer.completeIfDone() }
        verify(exactly = 1) { keyRestorer.markIncomplete() }
    }

    @Test
    fun `a wallet row without any backup file keeps the screen on Recovery`() = runTest {
        addWallets("wallet2")
        val vm = newVm(backupsFor("wallet1"), createPinManager(correctPin))

        vm.attemptPinRecovery(correctPin.copyOf(), activity)
        advanceUntilIdle()

        assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)
        assertEquals(listOf("Name of wallet2"), vm.uiState.value.missingWalletNames)
        assertTrue(vm.uiState.value.error!!.contains("recovery phrase"))
    }

    @Test
    fun `a mismatched backup stops on an error and offers no retry`() = runTest {
        addWallets("wallet2")
        queue("wallet2", RecoveredKeyRestorer.Outcome.AddressMismatch)
        val manager = backupsFor("wallet1", "wallet2")
        val vm = newVm(manager, createPinManager(correctPin))

        vm.attemptPinRecovery(correctPin.copyOf(), activity)
        advanceUntilIdle()

        assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)
        assertTrue(vm.uiState.value.error!!.contains("does not match"))
        assertFalse(vm.uiState.value.canRetryRestore)
        assertEquals(listOf("Name of wallet2"), vm.uiState.value.missingWalletNames)
        // The backup files stay on disk.
        assertTrue(manager.hasBackup("wallet1"))
        assertTrue(manager.hasBackup("wallet2"))
    }

    @Test
    fun `a cancelled prompt leaves the wallet missing, and Try again goes back to the PIN`() = runTest {
        addWallets("wallet2")
        queue("wallet2", RecoveredKeyRestorer.Outcome.Cancelled)
        val vm = newVm(backupsFor("wallet1", "wallet2"), createPinManager(correctPin))

        vm.attemptPinRecovery(correctPin.copyOf(), activity)
        advanceUntilIdle()
        assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)
        assertTrue(vm.uiState.value.canRetryRestore)
        assertEquals(listOf("Name of wallet2"), vm.uiState.value.missingWalletNames)

        vm.retryRestore()
        assertEquals(RecoveryStage.PIN_ENTRY, vm.uiState.value.stage)
        vm.attemptPinRecovery(correctPin.copyOf(), activity)
        advanceUntilIdle()

        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        // wallet1 was no longer missing, so the retry only touched wallet2.
        coVerify(exactly = 1) { keyRestorer.restore(any(), "wallet1", any(), any()) }
        coVerify(exactly = 2) { keyRestorer.restore(any(), "wallet2", any(), any()) }
    }

    @Test
    fun `a backup without a wallet row is skipped and reported, the rest restore`() = runTest {
        val vm = newVm(backupsFor("wallet1", "orphan"), createPinManager(correctPin))

        vm.attemptPinRecovery(correctPin.copyOf(), activity)
        advanceUntilIdle()

        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertEquals(1, vm.uiState.value.restoredWalletCount)
        assertEquals(listOf("orphan"), vm.uiState.value.failedWalletIds)
        coVerify(exactly = 0) { keyRestorer.restore(any(), "orphan", any(), any()) }
    }

    @Test
    fun `with no wallet rows at all nothing completes and the screen stays`() = runTest {
        walletRows.clear()
        missing.clear()
        val vm = newVm(backupsFor("orphan"), createPinManager(correctPin))

        vm.attemptPinRecovery(correctPin.copyOf(), activity)
        advanceUntilIdle()

        assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)
        assertFalse(vm.uiState.value.canRetryRestore)
        assertTrue(vm.uiState.value.error!!.contains("do not belong"))
    }

    @Test
    fun `a destroyed activity stops before the next prompt`() = runTest {
        addWallets("wallet2")
        val dying = mockk<FragmentActivity>(relaxed = true)
        var calls = 0
        every { dying.isDestroyed } answers { calls > 0 }
        coEvery { keyRestorer.restore(dying, "wallet1", any(), any()) } answers {
            calls++
            missing.remove("wallet1")
            RecoveredKeyRestorer.Outcome.Restored
        }
        val vm = newVm(backupsFor("wallet1", "wallet2"), createPinManager(correctPin))

        vm.attemptPinRecovery(correctPin.copyOf(), dying)
        advanceUntilIdle()

        coVerify(exactly = 0) { keyRestorer.restore(any(), "wallet2", any(), any()) }
        assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)
        assertTrue(vm.uiState.value.canRetryRestore)
    }

    @Test
    fun `the decrypted backups never reach the UI state and are redacted when printed`() = runTest {
        addWallets("wallet2")
        queue("wallet2", RecoveredKeyRestorer.Outcome.Cancelled)
        val vm = newVm(backupsFor("wallet1", "wallet2"), createPinManager(correctPin))
        vm.attemptPinRecovery(correctPin.copyOf(), activity)
        advanceUntilIdle()

        val shown = vm.uiState.value.toString()
        assertFalse(shown.contains("a".repeat(64)))
        assertFalse(shown.contains("abandon"))
        val printed = RecoveredWallet("wallet1", testMaterial()).toString()
        assertFalse(printed.contains("a".repeat(64)))
        assertFalse(printed.contains("abandon"))
        assertFalse(testMaterial().toString().contains("abandon"))
    }

    @Test
    fun `a wrong PIN never reaches the key restore`() = runTest {
        val vm = newVm(backupsFor("wallet1"), createPinManager(correctPin))

        vm.attemptPinRecovery(wrongPin, activity)
        advanceUntilIdle()

        assertEquals(RecoveryStage.PIN_ENTRY, vm.uiState.value.stage)
        coVerify(exactly = 0) { keyRestorer.restore(any(), any(), any(), any()) }
        coVerify(exactly = 0) { keyRestorer.completeIfDone() }
    }

    // --- Re-key a missing wallet from its phrase or private key ---

    private val phraseWords = testMaterial().mnemonic!!.split(" ")

    /** wallet1 has a row but its backup does not open: Recovery lists it as missing. */
    private suspend fun kotlinx.coroutines.test.TestScope.recoveryWithMissingWallet1(): RecoveryViewModel {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), "222222".toCharArray())
        val vm = newVm(manager, createPinManager(correctPin))
        vm.attemptPinRecovery(correctPin.copyOf(), activity)
        advanceUntilIdle()
        assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)
        return vm
    }

    @Test
    fun `the re-key entry cannot open before the PIN is verified`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)
        val vm = newVm(manager, createPinManager(correctPin))

        vm.openRekey("wallet1", withPrivateKey = false)
        assertNull(vm.uiState.value.rekey)

        vm.attemptPinRecovery(wrongPin, activity)
        advanceUntilIdle()
        vm.openRekey("wallet1", withPrivateKey = false)
        assertNull(vm.uiState.value.rekey)
        coVerify(exactly = 0) { keyRestorer.rekeyFromPhrase(any(), any(), any(), any()) }
    }

    @Test
    fun `a re-key from the right phrase completes recovery`() = runTest {
        coEvery { keyRestorer.rekeyFromPhrase(any(), "wallet1", any(), any()) } answers {
            missing.remove("wallet1")
            RecoveredKeyRestorer.RekeyResult.Done(mapOf("wallet1" to RecoveredKeyRestorer.Outcome.Restored))
        }
        val vm = recoveryWithMissingWallet1()

        vm.openRekey("wallet1", withPrivateKey = false)
        assertEquals("wallet1", vm.uiState.value.rekey!!.wallet.walletId)
        vm.submitRekeyPhrase(activity, phraseWords)
        advanceUntilIdle()

        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertNull(vm.uiState.value.rekey)
        coVerify(exactly = 1) { keyRestorer.rekeyFromPhrase(activity, "wallet1", phraseWords, false) }
    }

    @Test
    fun `a phrase that belongs to another wallet says so and stays on the entry`() = runTest {
        coEvery { keyRestorer.rekeyFromPhrase(any(), any(), any(), any()) } returns
            RecoveredKeyRestorer.RekeyResult.NotThisWallet
        val vm = recoveryWithMissingWallet1()

        vm.openRekey("wallet1", withPrivateKey = false)
        vm.submitRekeyPhrase(activity, phraseWords)
        advanceUntilIdle()

        assertEquals("This phrase does not belong to Name of wallet1.", vm.uiState.value.rekey!!.error)
        assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)
        assertFalse(vm.uiState.value.toString().contains("abandon"))
    }

    @Test
    fun `the private-key entry opens only for raw-key wallets`() = runTest {
        val vm = recoveryWithMissingWallet1()

        vm.openRekey("wallet1", withPrivateKey = true)

        assertNull(vm.uiState.value.rekey)
    }

    @Test
    fun `a raw-key wallet is re-keyed from its private key and the key bytes are wiped`() = runTest {
        walletRows["wallet1"] = walletRow("wallet1").copy(type = "raw_key")
        var seen: ByteArray? = null
        coEvery { keyRestorer.rekeyFromPrivateKey(any(), "wallet1", any(), any()) } answers {
            seen = thirdArg()
            missing.remove("wallet1")
            RecoveredKeyRestorer.RekeyResult.Done(mapOf("wallet1" to RecoveredKeyRestorer.Outcome.Restored))
        }
        val vm = recoveryWithMissingWallet1()

        vm.openRekey("wallet1", withPrivateKey = true)
        vm.submitRekeyPrivateKey(activity, "11".repeat(32))
        advanceUntilIdle()

        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertTrue(seen!!.all { it == 0.toByte() })
    }

    @Test
    fun `on a phone with no screen lock the re-key asks the consent first`() = runTest {
        every { keyRestorer.needsNoLockConsent() } returns true
        val vm = recoveryWithMissingWallet1()

        vm.openRekey("wallet1", withPrivateKey = false)
        assertTrue(vm.uiState.value.showNoLockConsent)
        assertNull(vm.uiState.value.rekey)

        vm.dismissNoLockConsent()
        assertNull(vm.uiState.value.rekey)

        vm.openRekey("wallet1", withPrivateKey = false)
        vm.confirmNoLockConsent(activity)
        assertEquals("wallet1", vm.uiState.value.rekey!!.wallet.walletId)
        vm.submitRekeyPhrase(activity, phraseWords)
        advanceUntilIdle()

        coVerify(exactly = 1) { keyRestorer.rekeyFromPhrase(activity, "wallet1", any(), true) }
        coVerify(exactly = 0) { keyRestorer.restore(any(), any(), any(), true) }
    }
}
