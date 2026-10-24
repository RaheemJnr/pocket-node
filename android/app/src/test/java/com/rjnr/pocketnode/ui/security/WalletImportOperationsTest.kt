package com.rjnr.pocketnode.ui.security

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.wallet.*
import com.rjnr.pocketnode.data.restorehint.*
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WalletImportOperationsTest {
    @Test fun `stop destroy reauth and stop-resume cancel suspended registration or hint ownership`() = walletOperationTest {
        for (phase in listOf("registration", "hint")) for (event in listOf("stop", "destroy", "reauth", "resume")) {
            WalletVaultIntegrationFixture().use { f ->
                val prefs = WalletPreferences(f.context, NoopLogger)
                val mm = MnemonicManager()
                val db = f.db
                val wallets = WalletRepository(db.walletDao(), f.keyManager, prefs, prefs, mm, db,
                    db.transactionDao(), db.balanceCacheDao(), db.daoCellDao(), db.pendingDaoWithdrawDao(),
                    db.pendingDaoUnlockDao(), db.keyMaterialDao(), db.subAccountCandidateDao(),
                    SubAccountDiscovery(mm, f.keyManager), prefs, NoopLogger)
                val chars = ("abandon ".repeat(11) + "about").toCharArray()
                val input = mockk<RecoverySecretDialog>()
                coEvery { input.requestImport(any(), false) } returns chars
                val hints = mockk<RestoreHintImporter>()
                val deriver = mockk<MnemonicHintDeriver>()
                val entered = CompletableDeferred<Unit>()
                var owned: RestoreHintSecret? = null
                var cleared = false
                var lateSuccess = false
                coEvery { deriver.derive(any()) } coAnswers {
                    val secret = RestoreHintSecret.fromMnemonic(firstArg())
                    owned = secret
                    if (phase == "hint") {
                        try { entered.complete(Unit); awaitCancellation() }
                        finally { secret.wipe(); cleared = true }
                    } else secret
                }
                coEvery { hints.verify(any(), any(), any()) } returns RestoreHintImporter.Verification.Rejected(RestoreHintImporter.Reason.UNREADABLE)
                var published: com.rjnr.pocketnode.data.database.entity.WalletEntity? = null
                f.phrase()
                val job = async {
                    val outcome = WalletImportOperations(input, wallets, f.writer, mm, hints, deriver)
                        .import(f.activity, false, "Public fixture", "hint", NetworkType.MAINNET,
                            onCommitted = { wallet, _ -> published = wallet }) { _, _ ->
                            if (phase == "registration") {
                                assertTrue(chars.all { it == '\u0000' })
                                assertThrows(IllegalStateException::class.java) { owned!!.copySeed() }
                                try { entered.complete(Unit); awaitCancellation() }
                                finally { cleared = true }
                            }
                        }
                    lateSuccess = outcome is WalletImportOperations.Outcome.Completed
                }
                try {
                    withTimeout(10_000) { entered.await() }
                    when (event) {
                        "reauth" -> ReauthLockEvents.onLocked()
                        "destroy" -> f.activityController.pause().stop().destroy()
                        "resume" -> f.activityController.pause().stop().start().resume()
                        else -> f.activityController.pause().stop()
                    }
                    // Drain cancellation propagation, without releasing either suspended
                    // operation. A retained foreground operation times out here.
                    withTimeout(1_000) { job.join() }
                    assertTrue("$phase/$event must cancel without work completing", job.isCancelled)
                    assertTrue(cleared); assertFalse(lateSuccess)
                    assertTrue(chars.all { it == '\u0000' })
                    owned?.let { assertThrows(IllegalStateException::class.java) { it.copySeed() } }
                    assertEquals(1, wallets.getAll().size)
                    assertEquals(published!!.walletId, wallets.getAll().single().walletId)
                    coVerify(exactly = if (phase == "registration") 1 else 0) { hints.verify(any(), any(), any()) }
                } finally { job.cancelAndJoin() }
            }
        }
    }
}
