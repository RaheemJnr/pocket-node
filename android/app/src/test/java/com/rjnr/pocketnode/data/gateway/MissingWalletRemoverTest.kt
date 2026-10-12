package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.wallet.RecoveredKeyRestorer
import com.rjnr.pocketnode.data.wallet.RecoveryStateStore
import com.rjnr.pocketnode.data.wallet.WalletRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MissingWalletRemoverTest {

    private fun wallet(id: String, active: Boolean = false, parent: String? = null) = WalletEntity(
        walletId = id, name = id, type = "mnemonic", derivationPath = null, parentWalletId = parent, isActive = active,
    )

    private val walletRepository = mockk<WalletRepository>(relaxed = true)
    private val gateway = mockk<GatewayRepository>(relaxed = true)
    private val restorer = mockk<RecoveredKeyRestorer>(relaxed = true)
    private val state = mockk<RecoveryStateStore>(relaxed = true)
    private val remover = MissingWalletRemover(walletRepository, gateway, restorer, state, NoopLogger)

    private fun setUp(all: List<WalletEntity>, missing: List<String>, activeId: String? = null) {
        coEvery { walletRepository.getAll() } returns all
        coEvery { restorer.missingWallets() } returns all.filter { it.walletId in missing }
        every { walletRepository.activeWalletIdSnapshot() } returns activeId
    }

    @Test
    fun `removes a missing wallet through the normal delete path and keeps its backup`() = runTest {
        setUp(listOf(wallet("lost"), wallet("ok", active = true)), missing = listOf("lost"), activeId = "ok")

        assertEquals(MissingWalletRemover.Result.REMOVED, remover.remove("lost"))

        coVerify(exactly = 1) { walletRepository.deleteWallet("lost", keepBackupFile = true) }
        coVerify(exactly = 0) { walletRepository.switchActiveWallet(any()) }
        coVerify(exactly = 1) { gateway.onWalletDeleted("lost") }
        verify(exactly = 1) { state.removeUnverified("lost") }
    }

    @Test
    fun `removing the active wallet switches to a wallet with keys first`() = runTest {
        setUp(
            listOf(wallet("lost", active = true), wallet("alsoLost"), wallet("ok")),
            missing = listOf("lost", "alsoLost"),
            activeId = "lost",
        )

        assertEquals(MissingWalletRemover.Result.REMOVED, remover.remove("lost"))

        coVerifyOrder {
            walletRepository.switchActiveWallet("ok")
            walletRepository.deleteWallet("lost", keepBackupFile = true)
        }
    }

    @Test
    fun `a wallet with keys is never removed here`() = runTest {
        setUp(listOf(wallet("lost"), wallet("ok")), missing = listOf("lost"))

        assertEquals(MissingWalletRemover.Result.NOT_MISSING, remover.remove("ok"))

        coVerify(exactly = 0) { walletRepository.deleteWallet(any(), any()) }
    }

    @Test
    fun `the only wallet on the phone is not removed`() = runTest {
        setUp(listOf(wallet("lost", active = true)), missing = listOf("lost"), activeId = "lost")

        assertEquals(MissingWalletRemover.Result.ONLY_WALLET, remover.remove("lost"))

        coVerify(exactly = 0) { walletRepository.deleteWallet(any(), any()) }
    }
}
