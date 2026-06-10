package com.rjnr.pocketnode.ui.screens.recovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.crypto.Blake2b
import com.rjnr.pocketnode.data.wallet.KeyBackupManager
import com.rjnr.pocketnode.data.wallet.KeyMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createBackupManager(): KeyBackupManager =
        KeyBackupManager(tempDir.root).also {
            it.kdfIterations = 1_000          // fast PBKDF2 for legacy reads
            it.argon2Iterations = 1           // fast Argon2id for v2 writes/reads
            it.argon2MemoryKb = 8
            it.argon2Parallelism = 1
        }

    /** A fast, in-memory PinManager with an optional stored PIN. */
    private fun createPinManager(pin: CharArray? = null): PinManager {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return PinManager(ctx, Blake2b()).apply {
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

        val vm = RecoveryViewModel(manager, createPinManager(correctPin), testDispatcher)

        assertEquals(RecoveryStage.PIN_ENTRY, vm.uiState.value.stage)
        assertEquals(0, vm.uiState.value.failedAttempts)
        assertTrue(vm.uiState.value.recoveredWallets.isEmpty())
    }

    @Test
    fun `initial state is MnemonicEntry when no backups exist`() {
        val manager = createBackupManager()

        val vm = RecoveryViewModel(manager, createPinManager(correctPin), testDispatcher)

        assertEquals(RecoveryStage.MNEMONIC_ENTRY, vm.uiState.value.stage)
    }

    @Test
    fun `attemptPinRecovery succeeds with correct PIN`() = runTest {
        val manager = createBackupManager()
        manager.writeBackup("wallet1", testMaterial(), correctPin)

        val vm = RecoveryViewModel(manager, createPinManager(correctPin), testDispatcher)
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

        val vm = RecoveryViewModel(manager, pinManager, testDispatcher)
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

        val vm = RecoveryViewModel(manager, pinManager, testDispatcher)
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

        val vm = RecoveryViewModel(manager, pinManager, testDispatcher)
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

        val vm = RecoveryViewModel(manager, createPinManager(correctPin), testDispatcher)
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
        val vm = RecoveryViewModel(manager, createPinManager(pin1), testDispatcher)
        vm.attemptPinRecovery(pin1)
        advanceUntilIdle()

        assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        assertEquals(1, vm.uiState.value.recoveredWallets.size)
        assertEquals("wallet1", vm.uiState.value.recoveredWallets[0].walletId)
        assertEquals(1, vm.uiState.value.failedWalletIds.size)
        assertEquals("wallet2", vm.uiState.value.failedWalletIds[0])
    }
}
