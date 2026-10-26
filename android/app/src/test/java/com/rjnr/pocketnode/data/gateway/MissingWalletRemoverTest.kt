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

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28], manifest = org.robolectric.annotation.Config.NONE)
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
    @Test
    fun `pending cleanup still completes recovery bookkeeping and registration`() = runTest {
        setUp(listOf(wallet("lost"), wallet("ok", active = true)), missing = listOf("lost"), activeId = "ok")
        coEvery { walletRepository.deleteWallet("lost", keepBackupFile = true) } throws com.rjnr.pocketnode.data.wallet.WalletCleanupPendingException()
        assertEquals("REMOVED_CLEANUP_PENDING", remover.remove("lost").name)
        verify(exactly = 1) { state.removeUnverified("lost") }
        coVerify(exactly = 1) { gateway.onWalletDeleted("lost") }
    }

    @Test fun `vacuum failure with pending cleanup still removes recovery state and updates gateway`() = vacuumFailureAfterRemoval(pending = true)
    @Test fun `vacuum failure after clean removal still removes recovery state and updates gateway`() = vacuumFailureAfterRemoval(pending = false)

    private fun vacuumFailureAfterRemoval(pending: Boolean) = runTest {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.rjnr.pocketnode.data.database.AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val preferences = com.rjnr.pocketnode.data.wallet.WalletPreferences(context, NoopLogger)
        val mnemonics = com.rjnr.pocketnode.data.wallet.MnemonicManager()
        val keys = com.rjnr.pocketnode.data.wallet.KeyManager(context, mnemonics, NoopLogger)
        val realRepository = WalletRepository(db.walletDao(), keys, preferences, preferences, mnemonics, db,
            db.transactionDao(), db.balanceCacheDao(), db.daoCellDao(), db.pendingDaoWithdrawDao(), db.pendingDaoUnlockDao(),
            db.keyMaterialDao(), db.subAccountCandidateDao(), com.rjnr.pocketnode.data.wallet.SubAccountDiscovery(mnemonics, keys), preferences, NoopLogger)
        val realState = RecoveryStateStore(context)
        realState.addUnverified("lost"); realState.addUnverified("ok")
        val realRemover = MissingWalletRemover(realRepository, gateway, restorer, realState, NoopLogger)
        val directory = java.nio.file.Files.createTempDirectory("recovery-housekeeping").toFile()
        keys.setBackupManager(com.rjnr.pocketnode.data.crypto.KeyBackupManager(directory, NoopLogger, keys.walletStorage))
        val enc = java.io.File(directory, "lost.enc").apply { writeText("synthetic committed") }
        val tmp = java.io.File(directory, "lost.enc.tmp").apply { writeText("synthetic interrupted") }
        val xml = java.io.File(context.applicationInfo.dataDir, "shared_prefs/ckb_wallet_keys_lost.xml")
        if (pending) { xml.parentFile!!.mkdirs(); xml.mkdir(); java.io.File(xml, "blocker").writeText("synthetic") }
        db.walletDao().insert(wallet("lost")); db.walletDao().insert(wallet("ok", active = true))
        preferences.setActiveWalletId("ok")
        coEvery { restorer.missingWallets() } returns listOf(wallet("lost"))
        io.mockk.mockkObject(com.rjnr.pocketnode.data.database.DatabaseMaintenanceUtil)
        try {
            every { com.rjnr.pocketnode.data.database.DatabaseMaintenanceUtil.vacuum(db) } throws android.database.sqlite.SQLiteFullException("synthetic full disk")
            assertEquals(if (pending) MissingWalletRemover.Result.REMOVED_CLEANUP_PENDING else MissingWalletRemover.Result.REMOVED, realRemover.remove("lost"))
            org.junit.Assert.assertNull(db.walletDao().getById("lost"))
            assertEquals(pending, db.walletPendingCleanupDao().get("lost") != null)
            if (pending) org.junit.Assert.assertTrue(db.walletPendingCleanupDao().get("lost")!!.keepBackup)
            assertEquals(setOf("ok"), realState.unverifiedWalletIds())
            assertEquals("synthetic committed", enc.readText()); assertEquals("synthetic interrupted", tmp.readText())
            coVerify(exactly = 1) { gateway.onWalletDeleted("lost") }
        } finally {
            io.mockk.unmockkObject(com.rjnr.pocketnode.data.database.DatabaseMaintenanceUtil)
            db.close(); directory.deleteRecursively(); xml.deleteRecursively(); realState.clear()
        }
    }

}
