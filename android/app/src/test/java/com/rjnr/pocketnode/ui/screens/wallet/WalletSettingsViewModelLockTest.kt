package com.rjnr.pocketnode.ui.screens.wallet

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.SavedStateHandle
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletKeyReader
import com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import org.junit.Test

/**
 * #524: a seed phrase or private key revealed on wallet settings never
 * survives a re-auth lock, and a reveal that finishes after a lock is
 * dropped (R5-1, R5-3). All other dependencies are relaxed mocks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletSettingsViewModelLockTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var keyManager: KeyManager
    private lateinit var keyMaterialDao: KeyMaterialDao
    private lateinit var walletKeyReader: WalletKeyReader

    private val words = listOf("abandon", "ability", "able")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        keyManager = mockk(relaxed = true)
        keyMaterialDao = mockk(relaxed = true)
        walletKeyReader = mockk(relaxed = true)
        coEvery { keyMaterialDao.getKdfVersion(any()) } returns 1
        coEvery { keyManager.getMnemonicForWallet(any()) } returns words
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = WalletSettingsViewModel(
        savedStateHandle = SavedStateHandle(mapOf("walletId" to "wallet-1")),
        walletRepository = mockk(relaxed = true),
        walletDao = mockk(relaxed = true),
        keyManager = keyManager,
        pinManager = mockk(relaxed = true),
        daoCellDao = mockk(relaxed = true),
        transactionDao = mockk(relaxed = true),
        appStatePreferences = mockk(relaxed = true),
        networkPreferences = mockk(relaxed = true),
        walletKeyReader = walletKeyReader,
        walletKeyWriter = mockk(relaxed = true),
        keyMaterialDao = keyMaterialDao,
        migrationHelper = mockk(relaxed = true),
        encryptionManager = mockk(relaxed = true),
        authManager = mockk(relaxed = true),
        logger = NoopLogger,
    )

    @Test
    fun `a lock clears a revealed seed phrase and private key`() = runTest {
        coEvery { keyManager.getPrivateKeyForWallet(any()) } returns ByteArray(32) { 1 }
        val vm = newViewModel()
        advanceUntilIdle()
        vm.onPinVerified()
        advanceUntilIdle()
        assertEquals(words, vm.uiState.value.mnemonicWords)
        assertTrue(vm.uiState.value.seedPhraseUnlocked)

        ReauthLockEvents.onLocked()
        advanceUntilIdle()

        assertNull(vm.uiState.value.mnemonicWords)
        assertNull(vm.uiState.value.privateKeyHex)
        assertFalse(vm.uiState.value.seedPhraseUnlocked)
    }

    @Test
    fun `a V1 reveal that finishes after a lock is dropped and its key bytes wiped (R5-1)`() = runTest {
        val keyBytes = ByteArray(32) { 7 }
        coEvery { keyManager.getPrivateKeyForWallet(any()) } coAnswers {
            ReauthLockEvents.onLocked()
            // The lock is fully handled (its listener ran) before the read returns.
            delay(10)
            keyBytes
        }
        val vm = newViewModel()
        advanceUntilIdle()

        vm.loadSensitiveData()
        advanceUntilIdle()

        assertNull(vm.uiState.value.mnemonicWords)
        assertNull(vm.uiState.value.privateKeyHex)
        assertArrayEquals(ByteArray(32), keyBytes)
    }

    @Test
    fun `a V2 reveal that finishes after a lock is dropped and its key bytes wiped (R5-1)`() = runTest {
        coEvery { keyMaterialDao.getKdfVersion(any()) } returns 2
        val keyBytes = ByteArray(32) { 9 }
        coEvery { walletKeyReader.readKeyMaterial(any(), any(), any(), any()) } coAnswers {
            ReauthLockEvents.onLocked()
            delay(10)
            WalletKeyReader.MaterialResult.Success(
                privateKey = keyBytes,
                mnemonic = words.joinToString(" "),
                walletType = "mnemonic",
                mnemonicBackedUp = true,
            )
        }
        val vm = newViewModel()
        advanceUntilIdle()

        vm.loadSensitiveData(mockk<FragmentActivity>(relaxed = true))
        advanceUntilIdle()

        assertNull(vm.uiState.value.mnemonicWords)
        assertNull(vm.uiState.value.privateKeyHex)
        assertFalse(vm.uiState.value.seedPhraseUnlocked)
        assertArrayEquals(ByteArray(32), keyBytes)
    }

    @Test
    fun `a V2 reveal with no lock in between is shown`() = runTest {
        coEvery { keyMaterialDao.getKdfVersion(any()) } returns 2
        coEvery { walletKeyReader.readKeyMaterial(any(), any(), any(), any()) } returns
            WalletKeyReader.MaterialResult.Success(
                privateKey = ByteArray(32) { 9 },
                mnemonic = words.joinToString(" "),
                walletType = "mnemonic",
                mnemonicBackedUp = true,
            )
        val vm = newViewModel()
        advanceUntilIdle()

        vm.loadSensitiveData(mockk<FragmentActivity>(relaxed = true))
        advanceUntilIdle()

        assertEquals(words, vm.uiState.value.mnemonicWords)
        assertTrue(vm.uiState.value.seedPhraseUnlocked)
    }
}
