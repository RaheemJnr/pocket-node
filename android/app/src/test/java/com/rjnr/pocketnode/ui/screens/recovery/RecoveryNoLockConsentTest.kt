package com.rjnr.pocketnode.ui.screens.recovery

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.wallet.*
import com.rjnr.pocketnode.data.vault.*
import com.rjnr.pocketnode.ui.security.RecoverySecretDialog
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Replaces former V1/no-lock consent expectations with real verified V3 recovery semantics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RecoveryNoLockConsentTest {
    private fun operations(f: WalletVaultIntegrationFixture, input: RecoverySecretDialog = mockk()): RecoveryOperations {
        val restorer = RecoveredKeyRestorer(f.db.walletDao(), f.db.keyMaterialDao(), f.reader, f.keyManager,
            MnemonicManager(), f.recovery, f.backup, NoopLogger, f.coordinator, f.prompt,
            LegacyBackupRecovery(f.db, f.backup, f.coordinator, f.prompt, WalletSecretValidator(MnemonicManager()), f.keyManager, f.reader, f.legacy))
        return RecoveryOperations(restorer, f.db, f.backup, input, f.coordinator, f.recovery, f.store)
    }
    private fun backup(f: WalletVaultIntegrationFixture, id: String = "a", suffix: String = ".enc") {
        java.io.File(f.context.cacheDir, "integration-backups/$id$suffix").apply { parentFile!!.mkdirs(); writeBytes(
            RecoveryNoLockConsentTest::class.java.getResourceAsStream("/legacy-scalar2-pin111111.enc")!!.use { it.readBytes() }) }
    }
    private suspend fun settled(vm: RecoveryViewModel) {
        withTimeout(10_000) { while (vm.uiState.value.stage == RecoveryStage.RESTORING) delay(10) }
    }
    private suspend fun vm(f: WalletVaultIntegrationFixture, ops: RecoveryOperations): RecoveryViewModel {
        val vm = RecoveryViewModel(ops)
        withTimeout(10_000) { while (vm.uiState.value.missingWallets.isEmpty()) delay(10) }
        return vm
    }
    @Test fun `no screen lock cancelling old PIN preserves backup and incomplete marker`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); backup(f); val vm = vm(f, operations(f)); f.phrase(null)
            vm.restoreBackup(f.activity, "a"); settled(vm)
            assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)
            assertNull(f.store.get("a")); assertTrue(f.backup.hasBackup("a")); assertTrue(f.recovery.isIncomplete())
        }
    }
    @Test fun `no screen lock old backup creates verified V3 and retires weak copy`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); backup(f); val vm = vm(f, operations(f)); f.phrase("111111"); f.phrase()
            vm.restoreBackup(f.activity, "a"); settled(vm)
            assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage); assertEquals(3, f.db.keyMaterialDao().getKdfVersion("a"))
            assertFalse(f.backup.hasBackup("a")); assertFalse(f.recovery.isIncomplete())
        }
    }
    @Test fun `cancel then explicit retry authenticates fresh old PIN without dashboard PIN`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); backup(f); val vm = vm(f, operations(f)); f.phrase(null)
            vm.restoreBackup(f.activity, "a"); settled(vm)
            f.phrase("111111"); f.phrase(); vm.restoreBackup(f.activity, "a"); settled(vm)
            assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        }
    }
    @Test fun `external key creates V3 without weak no-lock downgrade`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); val input = mockk<RecoverySecretDialog>(); val owned = f.material.privateKey.toCharArray()
            coEvery { input.request(f.activity, true) } returns owned
            val vm = vm(f, operations(f, input)); f.phrase()
            vm.recoverExternal(f.activity, "a", true); settled(vm)
            assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage); assertTrue(owned.all { it == '\u0000' })
            assertEquals(3, f.db.keyMaterialDao().getKdfVersion("a")); assertFalse(f.backup.hasAnyBackups())
        }
    }
    @Test fun `wrong old PIN preserves affected wallet and successfully restored sibling`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); f.wallet("b"); backup(f); backup(f, "b"); val vm = vm(f, operations(f))
            f.phrase("111111"); f.phrase(); vm.restoreBackup(f.activity, "a"); settled(vm)
            f.phrase("222222"); vm.restoreBackup(f.activity, "b"); settled(vm)
            assertEquals(1, vm.uiState.value.restoredWalletCount); assertEquals(listOf("b"), vm.uiState.value.missingWallets.map { it.walletId })
            assertTrue(f.recovery.isIncomplete()); assertTrue(f.backup.hasBackup("b")); assertNull(f.store.get("b"))
        }
    }
    @Test fun `no backup wallet remains read only across fresh ViewModel`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); val first = vm(f, operations(f)); assertFalse(first.uiState.value.missingWallets.single().hasBackup)
            assertTrue(f.recovery.isIncomplete()); val second = vm(f, operations(f))
            assertEquals(listOf("a"), second.uiState.value.missingWallets.map { it.walletId }); assertNull(f.store.get("a"))
        }
    }
    @Test fun `cancelled passphrase after backup decrypt requires new authentication on retry`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); backup(f); val vm = vm(f, operations(f)); f.phrase("111111"); f.phrase(null)
            vm.restoreBackup(f.activity, "a"); settled(vm); assertNull(f.store.get("a")); assertTrue(f.backup.hasBackup("a"))
            f.phrase("111111"); f.phrase(); vm.restoreBackup(f.activity, "a"); settled(vm)
            assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
        }
    }
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private suspend fun phraseWallet(f: WalletVaultIntegrationFixture) {
        val mm = MnemonicManager(); val seed = mm.mnemonicToSeed(words.split(" ")); val key = mm.derivePrivateKey(seed)
        val info = WalletDerivation.walletInfo(key); seed.fill(0); key.fill(0)
        f.db.walletDao().insert(com.rjnr.pocketnode.data.database.entity.WalletEntity("a", "Savings", "mnemonic", "m/44'/309'/0'/0/0", null,
            mainnetAddress = info.mainnetAddress, testnetAddress = info.testnetAddress))
    }
    @Test fun `matching external phrase restores missing wallet and wipes native buffer`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            phraseWallet(f); val input = mockk<RecoverySecretDialog>(); val owned = words.toCharArray()
            coEvery { input.request(f.activity, false) } returns owned
            val vm = vm(f, operations(f, input)); f.phrase(); vm.recoverExternal(f.activity, "a", false); settled(vm)
            assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage); assertTrue(owned.all { it == '\u0000' }); assertEquals(3, f.db.keyMaterialDao().getKdfVersion("a"))
        }
    }
    @Test fun `wrong phrase matches no address and cannot clear recovery health`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); val input = mockk<RecoverySecretDialog>(); coEvery { input.request(f.activity, false) } returns words.toCharArray()
            val vm = vm(f, operations(f, input)); vm.recoverExternal(f.activity, "a", false); settled(vm)
            assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage); assertNull(f.store.get("a")); assertTrue(f.recovery.isIncomplete())
        }
    }
    @Test fun `interrupted backup presence offers old PIN and cleanup never raw replacement`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); backup(f, suffix = ".enc.tmp"); val ops = operations(f)
            assertTrue(ops.wallets().single().hasBackup)
            f.phrase(); assertEquals(VaultAccessResult.CleanupRequired, f.coordinator.recoverExisting(f.activity, "a", f.material))
            assertTrue(ops.wallets().single().cleanupPending)
            val result = ops.external(f.activity, "a", true) as RecoveredKeyRestorer.RekeyResult.Done
            assertEquals(RecoveredKeyRestorer.Outcome.CleanupRequired, result.outcomes["a"])
            f.phrase("111111"); f.phrase()
            assertEquals(RecoveredKeyRestorer.Outcome.Restored, ops.backup(f.activity, "a"))
        }
    }
    @Test fun `pending cleanup without health marker cannot report complete`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); backup(f); f.phrase()
            assertEquals(VaultAccessResult.CleanupRequired, f.coordinator.recoverExisting(f.activity, "a", f.material))
            assertTrue(f.recovery.unverifiedWalletIds().isEmpty())
            assertFalse(operations(f).complete())
            assertTrue(f.backup.hasBackup("a"))
        }
    }

    @Test fun `archive only cleanup route reauthenticates and preserves source on cancellation`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet()
            f.v2.writeNewV2Row("a", f.bundle, f.encryption.newEncryptCipherV2(), "raw_key", false).getOrThrow()
            coEvery { f.auth.authenticateForCipher(any(), any(), any(), any(), any(), any()) } coAnswers {
                com.rjnr.pocketnode.data.auth.AuthManager.CipherAuthResult.Success(secondArg())
            }
            f.cleanupOk = false; f.phrase()
            assertTrue(f.reader.readKeyMaterial(f.activity, "a", "Read", "Auth") is WalletKeyReader.MaterialResult.NotAvailable)
            val source = f.db.walletVaultDao().legacy("a")
            val ops = operations(f); val status = ops.wallets().single()
            assertTrue(status.hasV2Archive); assertTrue(status.cleanupPending); assertFalse(status.hasBackup)
            f.cleanupOk = true
            coEvery { f.auth.authenticateForCipher(any(), any(), any(), any(), any(), any()) } returns com.rjnr.pocketnode.data.auth.AuthManager.CipherAuthResult.Cancelled
            assertEquals(RecoveredKeyRestorer.Outcome.Cancelled, ops.cleanup(f.activity, "a"))
            assertEquals(source?.source(), f.db.walletVaultDao().legacy("a")?.source())
            coEvery { f.auth.authenticateForCipher(any(), any(), any(), any(), any(), any()) } coAnswers {
                com.rjnr.pocketnode.data.auth.AuthManager.CipherAuthResult.Success(secondArg())
            }
            f.phrase()
            assertEquals(RecoveredKeyRestorer.Outcome.Restored, ops.cleanup(f.activity, "a"))
            assertTrue(ops.complete()); assertNull(f.db.walletVaultDao().legacy("a"))
        }
    }

    @Test fun `old profile wallet remains listed and cannot complete without fresh authentication`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); f.wallet("b"); f.phrase(); f.coordinator.persistNew(f.activity, "a", f.material)
            f.phrase(); f.coordinator.persistNew(f.activity, "b", f.material)
            val before = f.store.snapshot(); val envelope = f.store.get("a")!!
            val nextPhrase = "another independent wallet passphrase".toCharArray()
            val updated = f.crypto.rewrap(envelope, "a", f.pass.toCharArray(), nextPhrase, envelope.generation + 1)
            f.store.replaceAfterAuthenticatedReset(before, listOf(updated), nextPhrase); nextPhrase.fill('\u0000')
            val ops = operations(f)
            assertEquals(listOf("b"), ops.wallets().map { it.walletId })
            assertFalse(ops.complete())
            f.phrase(); f.phrase("another independent wallet passphrase")
            assertEquals(RecoveredKeyRestorer.Outcome.Restored, ops.unlock(f.activity, "b", VaultReadPreference.PASSPHRASE))
            assertTrue(ops.complete())
        }
    }

}
