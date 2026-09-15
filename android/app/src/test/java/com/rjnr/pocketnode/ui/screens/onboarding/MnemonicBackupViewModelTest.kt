package com.rjnr.pocketnode.ui.screens.onboarding

import androidx.lifecycle.SavedStateHandle
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.SeedPhraseAuthorizer
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
 * Tests for the [MnemonicBackupViewModel] reveal gates.
 *
 * Raw key (#290): pre-#290, `loadMnemonic` fetched the private key directly
 * in the VM's `init` block — anyone navigating to MnemonicBackupScreen for a
 * raw_key wallet from Settings could see the key with no re-auth. The fix
 * defers the fetch behind a `pinRequiredForPrivateKey` UI flag that the
 * screen surfaces as a "Reveal private key" button gated by PinEntryScreen.
 *
 * Recovery phrase (#488): the same hole existed for mnemonic wallets, but
 * only V2 key material happened to be covered — it threw
 * `V2KeyMaterialRequiresAuthException` on the un-authenticated read path. A
 * kdfVersion=1 wallet decrypts silently, so Settings → Backup Wallet rendered
 * all 12 words with no PIN or biometric step. The gate is now raised before
 * `getMnemonic()` for every key-material version.
 *
 * The onboarding path is exempt in both cases: the first-run hop out of
 * wallet creation (`onboarding=true` on the nav route) runs before
 * `InitialPinSetup`, so there is no credential to check.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MnemonicBackupViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: GatewayRepository
    private lateinit var walletRepository: WalletRepository
    private lateinit var pinManager: PinManager
    private lateinit var seedPhraseAuthorizer: SeedPhraseAuthorizer
    private lateinit var keyMaterialDao: KeyMaterialDao
    private lateinit var keyManager: KeyManager

    private val words = listOf(
        "abandon", "ability", "able", "about", "above", "absent",
        "absorb", "abstract", "absurd", "abuse", "access", "accident"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        walletRepository = mockk(relaxed = true)
        pinManager = mockk(relaxed = true)
        seedPhraseAuthorizer = mockk(relaxed = true)
        keyMaterialDao = mockk(relaxed = true)
        keyManager = mockk(relaxed = true)
        // Default: V1 key material unless a test says otherwise.
        coEvery { keyMaterialDao.getKdfVersion(any()) } returns 1
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun createViewModel(
        onboarding: Boolean = false,
        walletId: String? = null,
    ) = MnemonicBackupViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf("onboarding" to onboarding, "walletId" to walletId)
        ),
        repository = repository,
        walletRepository = walletRepository,
        pinManager = pinManager,
        seedPhraseAuthorizer = seedPhraseAuthorizer,
        keyMaterialDao = keyMaterialDao,
        keyManager = keyManager,
    )

    private fun rawKeyEntity() = mockk<WalletEntity>(relaxed = true).also {
        every { it.type } returns "raw_key"
        every { it.parentWalletId } returns null
        every { it.walletId } returns "wallet-raw"
    }

    private fun mnemonicEntity() = mockk<WalletEntity>(relaxed = true).also {
        every { it.type } returns "mnemonic"
        every { it.parentWalletId } returns null
        every { it.walletId } returns "wallet-1"
    }

    // -- #488: recovery-phrase gate ------------------------------------

    @Test
    fun `V1 mnemonic wallet is gated behind the PIN before the phrase is read`() = runTest {
        coEvery { walletRepository.getActive() } returns mnemonicEntity()
        coEvery { keyMaterialDao.getKdfVersion("wallet-1") } returns 1
        every { pinManager.hasPin() } returns true
        coEvery { repository.getMnemonic() } returns words

        val vm = createViewModel()
        advanceUntilIdle()

        // The regression in #488: this read used to happen eagerly in init.
        coVerify(exactly = 0) { repository.getMnemonic() }
        val state = vm.uiState.value
        assertTrue("V1 wallet must be gated", state.pinRequiredForMnemonic)
        assertTrue("V1 gate is the app PIN", state.mnemonicGateUsesPin)
        assertTrue(state.words.isEmpty())
    }

    @Test
    fun `V1 mnemonic wallet loads the phrase after the PIN is verified`() = runTest {
        coEvery { walletRepository.getActive() } returns mnemonicEntity()
        coEvery { keyMaterialDao.getKdfVersion("wallet-1") } returns 1
        every { pinManager.hasPin() } returns true
        coEvery { repository.getMnemonic() } returns words

        val vm = createViewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pinRequiredForMnemonic)

        vm.onPinVerified()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getMnemonic() }
        val state = vm.uiState.value
        assertFalse("Gate clears once the words are loaded", state.pinRequiredForMnemonic)
        assertEquals(words, state.words)
        assertEquals("Three verification slots are picked", 3, state.verifyPositions.size)
        state.verifyPositions.forEach { pos ->
            assertTrue(
                "Correct word must be among the choices",
                state.verifyOptions.getValue(pos).contains(words[pos])
            )
        }
    }

    @Test
    fun `V2 mnemonic wallet is gated behind biometrics, not the PIN screen`() = runTest {
        coEvery { walletRepository.getActive() } returns mnemonicEntity()
        coEvery { keyMaterialDao.getKdfVersion("wallet-1") } returns 2
        every { pinManager.hasPin() } returns true

        val vm = createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getMnemonic() }
        val state = vm.uiState.value
        assertTrue(state.pinRequiredForMnemonic)
        assertFalse("V2 gate is the BiometricPrompt", state.mnemonicGateUsesPin)
    }

    @Test
    fun `onboarding path is not gated and shows the phrase directly`() = runTest {
        // First-run hop out of wallet creation: no PIN exists yet, so there is
        // nothing to authenticate against.
        coEvery { walletRepository.getActive() } returns mnemonicEntity()
        coEvery { keyMaterialDao.getKdfVersion("wallet-1") } returns 1
        every { pinManager.hasPin() } returns false
        coEvery { repository.getMnemonic() } returns words

        val vm = createViewModel(onboarding = true)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getMnemonic() }
        val state = vm.uiState.value
        assertFalse("Onboarding must not be gated", state.pinRequiredForMnemonic)
        assertEquals(words, state.words)
    }

    @Test
    fun `V1 mnemonic wallet with no PIN falls through to the direct read`() = runTest {
        // Legacy/interrupted install: no PIN means no credential to check, so
        // gating would only lock the user out of their own backup.
        coEvery { walletRepository.getActive() } returns mnemonicEntity()
        coEvery { keyMaterialDao.getKdfVersion("wallet-1") } returns 1
        every { pinManager.hasPin() } returns false
        coEvery { repository.getMnemonic() } returns words

        val vm = createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getMnemonic() }
        assertFalse(vm.uiState.value.pinRequiredForMnemonic)
        assertEquals(words, vm.uiState.value.words)
    }

    @Test
    fun `V2 reveal populates the words after a successful biometric authorize`() = runTest {
        coEvery { walletRepository.getActive() } returns mnemonicEntity()
        coEvery { keyMaterialDao.getKdfVersion("wallet-1") } returns 2
        every { pinManager.hasPin() } returns true
        coEvery {
            seedPhraseAuthorizer.authorize(any(), any(), any(), any())
        } returns SeedPhraseAuthorizer.SeedResult.Words(words)

        val vm = createViewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pinRequiredForMnemonic)

        vm.revealMnemonicWithBiometrics(mockk(relaxed = true))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.pinRequiredForMnemonic)
        assertEquals(words, state.words)
        assertEquals(3, state.verifyPositions.size)
    }

    @Test
    fun `cancelled biometric keeps the gate up and reveals nothing`() = runTest {
        coEvery { walletRepository.getActive() } returns mnemonicEntity()
        coEvery { keyMaterialDao.getKdfVersion("wallet-1") } returns 2
        every { pinManager.hasPin() } returns true
        coEvery {
            seedPhraseAuthorizer.authorize(any(), any(), any(), any())
        } returns SeedPhraseAuthorizer.SeedResult.Cancelled

        val vm = createViewModel()
        advanceUntilIdle()

        vm.revealMnemonicWithBiometrics(mockk(relaxed = true))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue("Gate stays up so the user can retry", state.pinRequiredForMnemonic)
        assertTrue(state.words.isEmpty())
    }

    @Test
    fun `onboarding route flag does not exempt once a PIN exists`() = runTest {
        // The flag is a route argument, so it is not evidence on its own. The
        // justification for the exemption is "no PIN yet"; once one exists the
        // wallet is past onboarding and the gate applies regardless.
        coEvery { walletRepository.getActive() } returns mnemonicEntity()
        coEvery { keyMaterialDao.getKdfVersion("wallet-1") } returns 1
        every { pinManager.hasPin() } returns true
        coEvery { repository.getMnemonic() } returns words

        val vm = createViewModel(onboarding = true)
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getMnemonic() }
        val state = vm.uiState.value
        assertTrue("A claimed onboarding run with a PIN must still be gated", state.pinRequiredForMnemonic)
        assertTrue(state.mnemonicGateUsesPin)
        assertTrue(state.words.isEmpty())
    }

    @Test
    fun `a failed kdfVersion lookup fails closed instead of revealing`() = runTest {
        coEvery { walletRepository.getActive() } returns mnemonicEntity()
        coEvery { keyMaterialDao.getKdfVersion("wallet-1") } throws IllegalStateException("db closed")
        every { pinManager.hasPin() } returns false  // no PIN to fall back on
        coEvery { repository.getMnemonic() } returns words

        val vm = createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getMnemonic() }
        val state = vm.uiState.value
        assertTrue("Unverifiable version must keep the gate up", state.pinRequiredForMnemonic)
        assertFalse("Falls closed onto the biometric gate", state.mnemonicGateUsesPin)
        assertTrue(state.words.isEmpty())
    }

    // -- Re-arming on background ----------------------------------------

    @Test
    fun `backgrounding clears the revealed words and re-arms the gate`() = runTest {
        coEvery { walletRepository.getActive() } returns mnemonicEntity()
        coEvery { keyMaterialDao.getKdfVersion("wallet-1") } returns 1
        every { pinManager.hasPin() } returns true
        coEvery { repository.getMnemonic() } returns words

        val vm = createViewModel()
        advanceUntilIdle()
        vm.onPinVerified()
        advanceUntilIdle()
        assertEquals(words, vm.uiState.value.words)

        vm.onBackgrounded()

        val state = vm.uiState.value
        assertTrue("Words must not survive backgrounding", state.words.isEmpty())
        assertTrue(state.verifyPositions.isEmpty())
        assertTrue(state.verifyOptions.isEmpty())
        assertTrue("Gate must be back up", state.pinRequiredForMnemonic)
        assertTrue("Same gate as before", state.mnemonicGateUsesPin)
        assertEquals(1, state.currentStep)
    }

    @Test
    fun `backgrounding re-arms the V2 biometric gate, not the PIN gate`() = runTest {
        coEvery { walletRepository.getActive() } returns mnemonicEntity()
        coEvery { keyMaterialDao.getKdfVersion("wallet-1") } returns 2
        every { pinManager.hasPin() } returns true
        coEvery {
            seedPhraseAuthorizer.authorize(any(), any(), any(), any())
        } returns SeedPhraseAuthorizer.SeedResult.Words(words)

        val vm = createViewModel()
        advanceUntilIdle()
        vm.revealMnemonicWithBiometrics(mockk(relaxed = true))
        advanceUntilIdle()
        assertEquals(words, vm.uiState.value.words)

        vm.onBackgrounded()

        val state = vm.uiState.value
        assertTrue(state.words.isEmpty())
        assertTrue(state.pinRequiredForMnemonic)
        assertFalse(state.mnemonicGateUsesPin)
    }

    @Test
    fun `backgrounding an un-gated onboarding screen does not strand the user`() = runTest {
        // No gate applied, so there is nothing to re-arm — re-arming would
        // leave a pre-PIN user staring at a reveal button they cannot pass.
        coEvery { walletRepository.getActive() } returns mnemonicEntity()
        coEvery { keyMaterialDao.getKdfVersion("wallet-1") } returns 1
        every { pinManager.hasPin() } returns false
        coEvery { repository.getMnemonic() } returns words

        val vm = createViewModel(onboarding = true)
        advanceUntilIdle()
        assertEquals(words, vm.uiState.value.words)

        vm.onBackgrounded()

        val state = vm.uiState.value
        assertEquals("Words stay put on the un-gated path", words, state.words)
        assertFalse(state.pinRequiredForMnemonic)
    }

    @Test
    fun `backgrounding on the success step keeps the completed state`() = runTest {
        coEvery { walletRepository.getActive() } returns mnemonicEntity()
        coEvery { keyMaterialDao.getKdfVersion("wallet-1") } returns 1
        every { pinManager.hasPin() } returns true
        coEvery { repository.getMnemonic() } returns words

        val vm = createViewModel()
        advanceUntilIdle()
        vm.onPinVerified()
        advanceUntilIdle()
        vm.uiState.value.verifyPositions.forEach { pos -> vm.selectWord(pos, words[pos]) }
        vm.verify()
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.currentStep)

        vm.onBackgrounded()

        val state = vm.uiState.value
        assertEquals("Confirmation the user already earned is kept", 3, state.currentStep)
        assertTrue("But the words are still dropped", state.words.isEmpty())
        assertFalse(state.pinRequiredForMnemonic)
    }

    // -- Per-wallet entry point (Manage Wallets → "Backup wallet") -----

    @Test
    fun `walletId argument gates and then reads that wallet, not the active one`() = runTest {
        // Manage Wallets can open the backup flow for a wallet that is not the
        // active one. Reading the active wallet's phrase there would show the
        // wrong words and mark the wrong wallet backed up.
        val other = mockk<WalletEntity>(relaxed = true).also {
            every { it.type } returns "mnemonic"
            every { it.parentWalletId } returns null
            every { it.walletId } returns "wallet-other"
        }
        coEvery { walletRepository.getById("wallet-other") } returns other
        coEvery { keyMaterialDao.getKdfVersion("wallet-other") } returns 1
        every { pinManager.hasPin() } returns true
        coEvery { keyManager.getMnemonicForWallet("wallet-other") } returns words

        val vm = createViewModel(walletId = "wallet-other")
        advanceUntilIdle()

        // Gate first: the named wallet is resolved without reading its phrase,
        // and the active wallet is never consulted.
        coVerify(exactly = 0) { walletRepository.getActive() }
        coVerify(exactly = 0) { keyManager.getMnemonicForWallet(any()) }
        coVerify(exactly = 0) { repository.getMnemonic() }
        assertTrue(vm.uiState.value.pinRequiredForMnemonic)
        assertTrue(vm.uiState.value.mnemonicGateUsesPin)

        vm.onPinVerified()
        advanceUntilIdle()

        coVerify(exactly = 1) { keyManager.getMnemonicForWallet("wallet-other") }
        coVerify(exactly = 0) { repository.getMnemonic() }
        assertEquals(words, vm.uiState.value.words)
    }

    @Test
    fun `completing the flow marks the named wallet backed up, not the active one`() = runTest {
        val other = mockk<WalletEntity>(relaxed = true).also {
            every { it.type } returns "mnemonic"
            every { it.parentWalletId } returns null
            every { it.walletId } returns "wallet-other"
        }
        coEvery { walletRepository.getById("wallet-other") } returns other
        coEvery { keyMaterialDao.getKdfVersion("wallet-other") } returns 1
        every { pinManager.hasPin() } returns true
        coEvery { keyManager.getMnemonicForWallet("wallet-other") } returns words

        val vm = createViewModel(walletId = "wallet-other")
        advanceUntilIdle()
        vm.onPinVerified()
        advanceUntilIdle()

        // Answer the three verification slots correctly.
        val state = vm.uiState.value
        state.verifyPositions.forEach { pos -> vm.selectWord(pos, words[pos]) }
        vm.verify()
        advanceUntilIdle()

        coVerify(exactly = 1) { keyManager.setMnemonicBackedUpForWallet("wallet-other", true) }
        coVerify(exactly = 0) { repository.setMnemonicBackedUp(any()) }
        assertEquals(3, vm.uiState.value.currentStep)
    }

    @Test
    fun `no walletId argument keeps the active-wallet read path`() = runTest {
        coEvery { walletRepository.getActive() } returns mnemonicEntity()
        coEvery { keyMaterialDao.getKdfVersion("wallet-1") } returns 1
        every { pinManager.hasPin() } returns true
        coEvery { repository.getMnemonic() } returns words

        val vm = createViewModel()
        advanceUntilIdle()
        vm.onPinVerified()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getMnemonic() }
        coVerify(exactly = 0) { keyManager.getMnemonicForWallet(any()) }
        assertEquals(words, vm.uiState.value.words)
    }

    // -- #290: raw-key private-key gate --------------------------------

    @Test
    fun `raw_key wallet with PIN does not fetch private key until onPinVerified`() = runTest {
        coEvery { walletRepository.getActive() } returns rawKeyEntity()
        coEvery { repository.getMnemonic() } returns null  // raw_key has no mnemonic
        every { pinManager.hasPin() } returns true

        val vm = createViewModel()
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

        val vm = createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getPrivateKey() }
        val state = vm.uiState.value
        assertFalse(state.pinRequiredForPrivateKey)
        assertEquals("ccdd", state.privateKeyHex)
        assertTrue(state.privateKeyRevealed)
    }

    @Test
    fun `mnemonic wallet does not trigger the raw-key PIN gate`() = runTest {
        coEvery { walletRepository.getActive() } returns mnemonicEntity()
        coEvery { repository.getMnemonic() } returns words
        // Un-gated onboarding run, so the words load and only the raw-key
        // gate is under test here.
        every { pinManager.hasPin() } returns false

        val vm = createViewModel(onboarding = true)
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
            every { it.walletId } returns "wallet-sub"
        }
        coEvery { walletRepository.getActive() } returns sub
        coEvery { repository.getMnemonic() } returns null
        every { pinManager.hasPin() } returns true

        val vm = createViewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getPrivateKey() }
        val state = vm.uiState.value
        assertFalse(state.pinRequiredForPrivateKey)
        assertFalse("Sub-accounts render the parent notice, not a gate", state.pinRequiredForMnemonic)
        assertTrue(state.isSubAccount)
    }

    @Test
    fun `onPinVerified is a no-op when no PIN gate is active`() = runTest {
        coEvery { walletRepository.getActive() } returns rawKeyEntity()
        coEvery { repository.getMnemonic() } returns null
        every { pinManager.hasPin() } returns false  // no gate
        coEvery { repository.getPrivateKey() } returns byteArrayOf(0x01)

        val vm = createViewModel()
        advanceUntilIdle()
        // One fetch from init (no-PIN path).
        coVerify(exactly = 1) { repository.getPrivateKey() }

        vm.onPinVerified()
        advanceUntilIdle()
        // No second fetch — gate isn't active.
        coVerify(exactly = 1) { repository.getPrivateKey() }
    }
}
