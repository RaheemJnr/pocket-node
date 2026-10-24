package com.rjnr.pocketnode.ui.security

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.wallet.WalletBackupWitness
import com.rjnr.pocketnode.data.wallet.WalletKeyReader
import com.rjnr.pocketnode.data.wallet.WalletRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class WalletBackupOperationsTest {
    private val reader = mockk<WalletKeyReader>()
    private val wallets = mockk<WalletRepository>()
    private val witness = mockk<WalletBackupWitness>()
    private val dialog = mockk<WalletSecretDialog>()
    private val wallet = mockk<WalletEntity>(relaxed = true)
    private fun operations() = WalletBackupOperations(reader, wallets, dialog)

    @Test fun `only a completed native quiz marks the exact centrally authenticated wallet`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            coEvery { wallets.getById("chosen") } returns wallet
            coEvery { wallets.markVerifiedMnemonicBackup(witness) } returns true
            val bytes = ByteArray(32) { 2 }
            coEvery { reader.readKeyMaterial(activity, "chosen", any(), any()) } returns WalletKeyReader.MaterialResult.Success(bytes, "abandon ".repeat(11) + "about", "mnemonic", false, witness)
            coEvery { dialog.show(activity, any(), true, true) } coAnswers {
                assertArrayEquals(ByteArray(32), bytes)
                assertTrue(secondArg<CharArray>().concatToString().endsWith("about")); true
            }
            assertEquals(WalletBackupOperations.Outcome.Shown(true, true), operations().show(activity, "chosen", true))
            coVerify(exactly = 1) { wallets.markVerifiedMnemonicBackup(witness) }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `background or reauth while backup marking is suspended cancels the operation`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            for (stop in listOf(true, false)) {
            val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
            val activity = controller.get()
            coEvery { wallets.getById("chosen") } returns wallet
            coEvery { reader.readKeyMaterial(activity, "chosen", any(), any()) } returns WalletKeyReader.MaterialResult.Success(ByteArray(32), "abandon ".repeat(11) + "about", "mnemonic", false, witness)
            coEvery { dialog.show(activity, any(), true, true) } returns true
            var marked = false
            coEvery { wallets.markVerifiedMnemonicBackup(witness) } coAnswers {
                if (stop) controller.pause().stop() else com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents.onLocked()
                yield()
                marked = true
                true
            }
            val operation = async { operations().show(activity, "chosen", true) }
            runCurrent(); operation.join()
            assertFalse(marked)
            assertTrue(operation.isCancelled)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `cancelled quiz changed wallet and raw capability never mark mnemonic backup`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            for (mode in 0..2) {
                clearMocks(wallets)
                val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
                var current = wallet
                coEvery { wallets.getById("chosen") } answers { current }
                val bytes = ByteArray(32) { 2 }
                coEvery { reader.readKeyMaterial(activity, "chosen", any(), any()) } returns WalletKeyReader.MaterialResult.Success(bytes, if (mode == 2) null else "abandon ".repeat(11) + "about", "mnemonic", false, witness)
                coEvery { dialog.show(activity, any(), any(), true) } coAnswers {
                    assertArrayEquals(ByteArray(32), bytes)
                    if (mode == 1) current = mockk(relaxed = true)
                    if (mode == 2) assertFalse(thirdArg<Boolean>())
                    mode != 0
                }
                val outcome = operations().show(activity, "chosen", true)
                assertFalse(outcome is WalletBackupOperations.Outcome.Shown && outcome.backedUp)
                coVerify(exactly = 0) { wallets.markVerifiedMnemonicBackup(any()) }
            }
        } finally { Dispatchers.resetMain() }
    }
}
