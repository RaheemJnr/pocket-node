package com.rjnr.pocketnode.data.wallet

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.crypto.WalletKeyBundle
import org.junit.Assert.*
import io.mockk.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RecoveryVaultIntegrationTest {
    private fun restorer(f: WalletVaultIntegrationFixture) = RecoveredKeyRestorer(
        f.db.walletDao(), f.db.keyMaterialDao(), f.reader, f.keyManager,
        MnemonicManager(), f.recovery, f.backup, NoopLogger, f.coordinator, f.prompt,
        com.rjnr.pocketnode.data.vault.LegacyBackupRecovery(f.db, f.backup, f.coordinator, f.prompt, com.rjnr.pocketnode.data.vault.WalletSecretValidator(MnemonicManager()), f.keyManager, f.reader, f.legacy),
    )
    @Test fun `missing wallet becomes verified V3 without Android lock or weak fallback consent`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); f.recovery.addUnverified("a"); f.phrase()
            assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer(f).restore(f.activity, "a", f.material, false))
            assertEquals(3, f.db.keyMaterialDao().getKdfVersion("a"))
            assertTrue(f.recovery.unverifiedWalletIds().isEmpty())
            assertFalse(f.backup.hasAnyBackups())
        }
    }
    @Test fun `malformed V2 source is preserved and never treated as permanent key loss`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); f.v2.writeNewV2Row("a", WalletKeyBundle(f.material.privateKey, null), f.encryption.newEncryptCipherV2(), "raw_key", false).getOrThrow()
            val unreadable = f.db.keyMaterialDao().getByWalletId("a")!!.copy(encryptedPrivateKey = byteArrayOf(9))
            f.db.keyMaterialDao().upsert(unreadable)
            f.recovery.addUnverified("a"); f.phrase()
            coEvery { f.auth.authenticateForCipher(any(), any(), any(), any(), any(), any()) } coAnswers {
                com.rjnr.pocketnode.data.auth.AuthManager.CipherAuthResult.Success(secondArg())
            }
            assertTrue(restorer(f).restore(f.activity, "a", f.material, false) is RecoveredKeyRestorer.Outcome.Failed)
            assertEquals(unreadable, f.db.keyMaterialDao().getByWalletId("a"))
            assertNull(f.store.get("a")); assertTrue("a" in f.recovery.unverifiedWalletIds())
        }
    }
    @Test fun `cancel keeps exact original ciphertext and health marker`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); f.legacy.migrateWallet("a", f.material.privateKey, null, "raw_key", false)
            val old = f.db.keyMaterialDao().getByWalletId("a")
            f.recovery.addUnverified("a"); f.phrase(null)
            assertEquals(RecoveredKeyRestorer.Outcome.Cancelled, restorer(f).restore(f.activity, "a", f.material, false))
            assertEquals(old, f.db.keyMaterialDao().getByWalletId("a"))
            assertTrue("a" in f.recovery.unverifiedWalletIds())
        }
    }
    @Test fun `absent backup cannot clear present unhealthy row or recovery marker`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); f.legacy.migrateWallet("a", f.material.privateKey, null, "raw_key", false)
            val old = f.db.keyMaterialDao().getByWalletId("a")
            f.recovery.addUnverified("a"); f.recovery.markIncomplete()
            assertEquals(RecoveredKeyRestorer.Outcome.NoBackup, restorer(f).restore(f.activity, "a", null, false))
            assertEquals(old, f.db.keyMaterialDao().getByWalletId("a"))
            assertFalse(restorer(f).completeIfDone()); assertTrue(f.recovery.isIncomplete())
        }
    }

    @Test fun `native old backup recovery clears health only after authenticated durable success`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); f.recovery.addUnverified("a"); f.recovery.markIncomplete()
            java.io.File(f.context.cacheDir, "integration-backups/a.enc").writeBytes(
                RecoveryVaultIntegrationTest::class.java.getResourceAsStream("/legacy-scalar2-pin111111.enc")!!.use { it.readBytes() })
            f.phrase("222222")
            assertTrue(restorer(f).restoreLegacyBackup(f.activity, "a") is RecoveredKeyRestorer.Outcome.Failed)
            assertTrue("a" in f.recovery.unverifiedWalletIds()); assertFalse(restorer(f).completeIfDone())
            f.phrase("111111"); f.phrase()
            assertEquals(RecoveredKeyRestorer.Outcome.Restored, restorer(f).restoreLegacyBackup(f.activity, "a"))
            assertTrue(f.recovery.unverifiedWalletIds().isEmpty()); assertTrue(restorer(f).completeIfDone())
        }
    }
}
