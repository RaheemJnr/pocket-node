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
    private fun operations(f: WalletVaultIntegrationFixture, input: RecoverySecretDialog = mockk(), reader: WalletKeyReader = f.reader): RecoveryOperations {
        val restorer = RecoveredKeyRestorer(f.db.walletDao(), f.db.keyMaterialDao(), reader, f.keyManager,
            MnemonicManager(), f.recovery, f.backup, NoopLogger, f.coordinator, f.prompt,
            LegacyBackupRecovery(f.db, f.backup, f.coordinator, f.prompt, WalletSecretValidator(MnemonicManager()), f.keyManager, f.reader, f.legacy), f.db.walletVaultDao())
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
            f.wallet(); backup(f, suffix = ".enc.tmp")
            val input = mockk<RecoverySecretDialog>()
            coEvery { input.request(f.activity, true) } returns f.material.privateKey.toCharArray()
            val ops = operations(f, input)
            assertTrue(ops.wallets().single().hasBackup)
            f.phrase(); assertEquals(VaultAccessResult.CleanupRequired, f.coordinator.recoverExisting(f.activity, "a", f.material))
            assertTrue(ops.wallets().single().cleanupPending)
            f.phrase()
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
            assertTrue(status.hasLegacyArchive); assertTrue(status.cleanupPending); assertFalse(status.hasBackup)
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
            f.wallet(); f.wallet("b"); f.phrase(); f.coordinator.recoverExisting(f.activity, "a", f.material)
            f.phrase(); f.coordinator.recoverExisting(f.activity, "b", f.material)
            val before = f.store.snapshot(); val envelope = f.store.get("a")!!
            val nextPhrase = "another independent wallet passphrase".toCharArray()
            val updated = f.crypto.rewrap(envelope, "a", f.pass.toCharArray(), nextPhrase, envelope.generation + 1)
            f.store.replaceAfterAuthenticatedReset(before, listOf(updated), nextPhrase, mapOf("a" to f.db.walletDao().getById("a")!!)); nextPhrase.fill('\u0000')
            val ops = operations(f)
            assertEquals(listOf("b"), ops.wallets().map { it.walletId })
            assertFalse(ops.complete())
            f.phrase(); f.phrase("another independent wallet passphrase")
            assertEquals(RecoveredKeyRestorer.Outcome.Restored, ops.unlock(f.activity, "b", VaultReadPreference.PASSPHRASE))
            assertTrue(ops.complete())
        }
    }

    @Test fun `external input owner cancels suspended consumer on stop resume destroy and reauth`() = walletOperationTest {
        for (event in listOf("stop", "destroy", "reauth")) WalletVaultIntegrationFixture().use { f ->
            val input = mockk<RecoverySecretDialog>()
            val restorer = mockk<RecoveredKeyRestorer>()
            val chars = f.material.privateKey.toCharArray()
            var bytes: ByteArray? = null
            val entered = CompletableDeferred<Unit>()
            coEvery { input.request(f.activity, true) } returns chars
            coEvery { restorer.rekeyFromPrivateKey(f.activity, "a", any(), false) } coAnswers {
                bytes = thirdArg(); entered.complete(Unit); awaitCancellation()
            }
            val ops = RecoveryOperations(restorer, f.db, f.backup, input, f.coordinator, f.recovery, f.store)
            val running = launch { ops.external(f.activity, "a", true) }
            entered.await()
            when (event) {
                "stop" -> { f.activityController.pause().stop(); f.activityController.start().resume() }
                "destroy" -> f.activityController.pause().stop().destroy()
                else -> com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents.onLocked()
            }
            repeat(10) { yield() }
            try {
                assertTrue("$event must cancel the suspended secret consumer", running.isCancelled)
                running.join(); assertTrue(bytes!!.all { it == 0.toByte() }); assertTrue(chars.all { it == '\u0000' })
            } finally { running.cancelAndJoin() }
        }
    }

    @Test fun `external private key repairs surviving empty V3 marker for mnemonic origin with explicit spending consent`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(type = "mnemonic")
            f.db.keyMaterialDao().upsert(com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity("a", byteArrayOf(), null, byteArrayOf(), "mnemonic", false, 0, 3))
            f.recovery.addUnverified("a")
            val input = mockk<RecoverySecretDialog>()
            coEvery { input.request(f.activity, true) } returns f.material.privateKey.toCharArray()
            f.choices += true; f.phrase()
            val result = operations(f, input).external(f.activity, "a", true) as RecoveredKeyRestorer.RekeyResult.Done
            assertEquals(RecoveredKeyRestorer.Outcome.Restored, result.outcomes["a"])
            assertNotNull(f.store.get("a")); assertNull(f.db.walletVaultDao().legacy("a"))
        }
    }

    @Test fun `pending archived V2 phrase flows through recreated ViewModel and native external operation`() = pendingExternalEntry(false, false)
    @Test fun `pending archived V2 raw input flows through recreated ViewModel and preserves full seed`() = pendingExternalEntry(true, false)
    @Test fun `pending archived V2 phrase entry cannot retire opaque PIN backup`() = pendingExternalEntry(false, true)
    @Test fun `pending archived V2 raw entry cannot retire opaque PIN backup`() = pendingExternalEntry(true, true)
    private fun pendingExternalEntry(raw: Boolean, opaque: Boolean) = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            phraseWallet(f)
            val mm = MnemonicManager(); val seed = mm.mnemonicToSeed(words.split(" ")); val key = mm.derivePrivateKey(seed)
            val keyHex = key.joinToString("") { "%02x".format(it) }; seed.fill(0); key.fill(0)
            val material = com.rjnr.pocketnode.data.crypto.KeyMaterial(keyHex, words, "mnemonic", true)
            f.v2.writeNewV2Row("a", com.rjnr.pocketnode.data.crypto.WalletKeyBundle(keyHex, words), f.encryption.newEncryptCipherV2(), "mnemonic", true).getOrThrow()
            f.phrase()
            assertEquals(VaultAccessResult.CleanupRequired, f.coordinator.recoverExisting(f.activity, "a", material))
            val archive = f.db.walletVaultDao().legacy("a")!!
            f.recovery.clear()
            if (opaque) f.backup.writeBackup("a", material, "111111".toCharArray())
            val input = mockk<RecoverySecretDialog>(); val owned = (if (raw) keyHex else words).toCharArray()
            coEvery { input.request(f.activity, raw) } returns owned
            val invalidated = mockk<WalletKeyReader>()
            coEvery { invalidated.authenticateCapturedV2Source(any(), archive.source()) } returns WalletKeyReader.MaterialResult.KeyInvalidated
            val ops = operations(f, input, invalidated)
            val vm = vm(f, ops)
            assertTrue(vm.uiState.value.missingWallets.single().cleanupPending)
            f.choices += true; f.phrase()
            vm.recoverExternal(f.activity, "a", raw); settled(vm)
            coVerify(exactly = 1) { input.request(f.activity, raw) }
            coVerify(exactly = 1) { invalidated.authenticateCapturedV2Source(any(), archive.source()) }
            assertTrue(owned.all { it == '\u0000' })
            if (opaque) {
                assertEquals(RecoveryStage.RESTORE_FAILED, vm.uiState.value.stage)
                assertEquals(0, vm.uiState.value.restoredWalletCount)
                assertTrue(vm.uiState.value.missingWallets.single().cleanupPending)
                assertEquals(archive.source(), f.db.walletVaultDao().legacy("a")?.source()); assertTrue(f.backup.hasBackup("a"))
                assertFalse(ops.complete())
            } else {
                assertEquals(RecoveryStage.SUCCESS, vm.uiState.value.stage)
                assertNull(f.db.walletVaultDao().legacy("a"))
                f.phrase()
                val read = f.coordinator.read(f.activity, "a", "Read", "Read") as VaultAccessResult.Success
                assertEquals(words, read.material.mnemonic)
            }
        }
    }

}
