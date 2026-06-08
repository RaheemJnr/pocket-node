package com.rjnr.pocketnode.ui.screens.onboarding

import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.wallet.WalletRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Test

/**
 * Tests for [MnemonicBackupViewModel] PIN gate on the raw-key backup path
 * (#290). Pre-#290, `loadMnemonic` fetched the private key directly in
 * the VM's `init` block — anyone navigating to MnemonicBackupScreen for a
 * raw_key wallet from Settings could see the key with no re-auth. The fix
 * defers the fetch behind a `pinRequiredForPrivateKey` UI flag that the
 * screen surfaces as a "Reveal private key" button gated by
 * [PinEntryScreen] verification.
 *
 * The onboarding path (raw_key import before PIN setup) is unchanged: when
 * `PinManager.hasPin() == false`, the key is fetched directly so the user
 * can complete the simplified backup flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MnemonicBackupViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: GatewayRepository
    private lateinit var walletRepository: WalletRepository
    private lateinit var pinManager: PinManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        walletRepository = mockk(relaxed = true)
        pinManager = mockk(relaxed = true)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun rawKeyEntity() = mockk<WalletEntity>(relaxed = true).also {
        every { it.type } returns "raw_key"
        every { it.parentWalletId } returns null
    }

    @Test
    fun `raw_key wallet with PIN does not fetch private key until onPinVerified`() = runTest {
        coEvery { walletRepository.getActive() } returns rawKeyEntity()
        coEvery { repository.getMnemonic() } returns null  // raw_key has no mnemonic
        every { pinManager.hasPin() } returns true

        val vm = MnemonicBackupViewModel(repository, walletRepository, pinManager)
        advanceUntilIdle()

        // Pre-PIN: gate is set, private key NOT fetched.
        coVerify(exactly = 0) { repository.getPrivateKey() }
        val pre = vm.uiState.value
        assertTrue("PIN must be required", pre.pinRequiredForPrivateKey)
        assertNull(pre.privateKeyHex)
        assertFalse(pre.privateKeyRevealed)

        // After PIN verify → the screen calls onPinVerified() and the key is fetched.
        coEvery { repository.getPrivateKey() } returns byteArrayOf(0xaa.toByte(), 0xbb.toByte())
        vm.onPinVerified()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getPrivateKey() }
        val post = vm.uiState.value
        assertEquals("aabb", post.privateKeyHex)
        assertTrue(post.privateKeyRevealed)
    }

    @Test
    fun `raw_key wallet WITHOUT PIN fetches private key directly (onboarding path)`() = runTest {
        // Pre-PIN-setup edge case: a raw-key import shown its backup screen
        // before the user has set a PIN. Same UX as pre-#290.
        coEvery { walletRepository.getActive() } returns rawKeyEntity()
        coEvery { repository.getMnemonic() } returns null
        every { pinManager.hasPin() } returns false
        coEvery { repository.getPrivateKey() } returns byteArrayOf(0xcc.toByte(), 0xdd.toByte())

        val vm = MnemonicBackupViewModel(repository, walletRepository, pinManager)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getPrivateKey() }
        val state = vm.uiState.value
        assertFalse(state.pinRequiredForPrivateKey)
        assertEquals("ccdd", state.privateKeyHex)
        assertTrue(state.privateKeyRevealed)
    }

    @Test
    fun `mnemonic wallet does not trigger the raw-key PIN gate`() = runTest {
        val mnemonicEntity = mockk<WalletEntity>(relaxed = true).also {
            every { it.type } returns "mnemonic"
            every { it.parentWalletId } returns null
        }
        coEvery { walletRepository.getActive() } returns mnemonicEntity
        coEvery { repository.getMnemonic() } returns listOf(
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "about"
        )
        every { pinManager.hasPin() } returns true

        val vm = MnemonicBackupViewModel(repository, walletRepository, pinManager)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getPrivateKey() }
        val state = vm.uiState.value
        assertFalse(state.pinRequiredForPrivateKey)
        assertEquals(12, state.words.size)
    }

    @Test
    fun `sub-account wallet does not trigger the raw-key PIN gate`() = runTest {
        val sub = mockk<WalletEntity>(relaxed = true).also {
            every { it.type } returns "raw_key"
            every { it.parentWalletId } returns "parent-id"
        }
        coEvery { walletRepository.getActive() } returns sub
        coEvery { repository.getMnemonic() } returns null
        every { pinManager.hasPin() } returns true

        val vm = MnemonicBackupViewModel(repository, walletRepository, pinManager)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getPrivateKey() }
        val state = vm.uiState.value
        assertFalse(state.pinRequiredForPrivateKey)
        assertTrue(state.isSubAccount)
    }

    @Test
    fun `onPinVerified is a no-op when PIN gate is not active`() = runTest {
        coEvery { walletRepository.getActive() } returns rawKeyEntity()
        coEvery { repository.getMnemonic() } returns null
        every { pinManager.hasPin() } returns false  // no gate
        coEvery { repository.getPrivateKey() } returns byteArrayOf(0x01)

        val vm = MnemonicBackupViewModel(repository, walletRepository, pinManager)
        advanceUntilIdle()
        // One fetch from init (no-PIN path).
        coVerify(exactly = 1) { repository.getPrivateKey() }

        vm.onPinVerified()
        advanceUntilIdle()
        // No second fetch — gate isn't active.
        coVerify(exactly = 1) { repository.getPrivateKey() }
    }
}
