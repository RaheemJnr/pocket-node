package com.rjnr.pocketnode.data.vault

import com.rjnr.pocketnode.data.wallet.*
import java.io.File
import io.mockk.*
import com.rjnr.pocketnode.data.auth.AuthManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LegacyBackupRecoveryTest {
    private fun resolver(f: WalletVaultIntegrationFixture) = LegacyBackupRecovery(f.db, f.backup, f.coordinator, f.prompt, WalletSecretValidator(MnemonicManager()), f.keyManager, f.reader, f.legacy)
    private fun fixture(f: WalletVaultIntegrationFixture, id: String = "default") = File(f.context.cacheDir, "integration-backups/$id.enc").apply {
        parentFile!!.mkdirs()
        writeBytes(LegacyBackupRecoveryTest::class.java.getResourceAsStream("/legacy-scalar2-pin111111.enc")!!.use { it.readBytes() })
    }
    @Test fun `old PIN authenticates real default backup independently and retires exact owned copies`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); val file = fixture(f); f.phrase("111111"); f.phrase()
            val result = resolver(f).recover(f.activity, "a")
            assertTrue(result is VaultAccessResult.Success)
            assertEquals(f.material.privateKey, (result as VaultAccessResult.Success).material.privateKey)
            assertEquals(3, f.db.keyMaterialDao().getKdfVersion("a"))
            assertFalse(file.exists()); assertTrue(f.store.pendingLegacyCleanup().isEmpty())
        }
    }
    @Test fun `wrong old PIN preserves backup and missing wallet`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); val file = fixture(f); val bytes = file.readBytes(); f.phrase("222222")
            assertTrue(resolver(f).recover(f.activity, "a") is VaultAccessResult.Unavailable)
            assertArrayEquals(bytes, file.readBytes()); assertNull(f.store.get("a"))
        }
    }
    @Test fun `cancel old PIN leaves backup and target untouched`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); val file = fixture(f); val bytes = file.readBytes(); f.phrase(null)
            assertEquals(VaultAccessResult.Cancelled, resolver(f).recover(f.activity, "a"))
            assertArrayEquals(bytes, file.readBytes()); assertNull(f.store.get("a"))
        }
    }
    @Test fun `absent backup remains read only with existing metadata`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val wallet = f.wallet()
            assertTrue(resolver(f).recover(f.activity, "a") is VaultAccessResult.Unavailable)
            assertEquals(wallet, f.db.walletDao().getById("a")); assertNull(f.store.get("a"))
        }
    }

    @Test fun `changed backup bytes during wallet authentication remain pending and are not deleted`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); val file = fixture(f); val changed = byteArrayOf(9, 8, 7)
            var requests = 0
            f.onCredential = { if (++requests == 2) file.writeBytes(changed) }
            f.phrase("111111"); f.phrase()
            assertEquals(VaultAccessResult.CleanupRequired, resolver(f).recover(f.activity, "a"))
            assertArrayEquals(changed, file.readBytes()); assertTrue("a" in f.store.pendingLegacyCleanup())
        }
    }
    @Test fun `a concurrent target source change rejects captured proof before activation`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); val file = fixture(f); val bytes = file.readBytes()
            f.onCredential = { f.legacy.migrateWallet("a", f.material.privateKey, null, "raw_key", false) }
            f.phrase("111111"); f.phrase()
            assertTrue(resolver(f).recover(f.activity, "a") is VaultAccessResult.Unavailable)
            assertEquals(1, f.db.keyMaterialDao().getKdfVersion("a")); assertNull(f.store.get("a"))
            assertArrayEquals(bytes, file.readBytes())
        }
    }
    @Test fun `unreadable interrupted copy blocks retirement of the authenticated committed copy`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); val file = fixture(f); val bytes = file.readBytes()
            val pending = File(file.path + ".tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            f.phrase("111111")
            assertTrue(resolver(f).recover(f.activity, "a") is VaultAccessResult.Unavailable)
            assertArrayEquals(bytes, file.readBytes()); assertTrue(pending.exists()); assertNull(f.store.get("a"))
        }
    }
    @Test fun `authenticated default of different ownership does not block scoped wallet recovery`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); val own = fixture(f, "a")
            f.backup.writeBackup("default", f.material.copy(privateKey = "00".repeat(31) + "03"), "111111".toCharArray())
            val unrelated = File(own.parentFile, "default.enc"); val bytes = unrelated.readBytes()
            f.phrase("111111"); f.phrase()
            assertTrue(resolver(f).recover(f.activity, "a") is VaultAccessResult.Success)
            assertFalse(own.exists()); assertArrayEquals(bytes, unrelated.readBytes())
        }
    }
    @Test fun `wrong target address never activates an otherwise authentic backup`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val wallet = f.wallet(); f.db.walletDao().insert(wallet.copy(testnetAddress = "wrong"))
            val file = fixture(f); f.phrase("111111")
            assertTrue(resolver(f).recover(f.activity, "a") is VaultAccessResult.Unavailable)
            assertTrue(file.exists()); assertNull(f.store.get("a"))
        }
    }
    @Test fun `scoped opaque seed backup is not deleted by raw external recovery`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); val file = fixture(f, "a"); val bytes = file.readBytes(); f.phrase()
            assertEquals(VaultAccessResult.CleanupRequired, f.coordinator.recoverExisting(f.activity, "a", f.material))
            assertArrayEquals(bytes, file.readBytes()); assertTrue("a" in f.store.pendingLegacyCleanup())
        }
    }

    @Test fun `surviving seed in scoped backup upgrades raw vault before retirement`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            val key = MnemonicManager().mnemonicToPrivateKey(words.split(" "))
            val info = WalletDerivation.walletInfo(key)
            val hex = key.joinToString("") { "%02x".format(it) }; key.fill(0)
            val seed = com.rjnr.pocketnode.data.crypto.KeyMaterial(hex, words, "mnemonic", true)
            f.db.walletDao().insert(com.rjnr.pocketnode.data.database.entity.WalletEntity("a", "Seed wallet", "mnemonic", "m/44'/309'/0'/0/0", null,
                mainnetAddress = info.mainnetAddress, testnetAddress = info.testnetAddress))
            f.backup.writeBackup("a", seed, "111111".toCharArray())
            f.phrase()
            assertEquals(VaultAccessResult.CleanupRequired, f.coordinator.recoverExisting(f.activity, "a", seed.copy(mnemonic = null, walletType = "raw_key")))
            f.phrase("111111"); f.phrase()
            val result = resolver(f).recover(f.activity, "a") as VaultAccessResult.Success
            assertEquals(words, result.material.mnemonic)
            assertFalse(f.backup.hasBackup("a")); assertTrue(f.store.pendingLegacyCleanup().isEmpty())
            f.phrase(); assertEquals(words, (f.coordinator.read(f.activity, "a", "Read", "Read") as VaultAccessResult.Success).material.mnemonic)
        }
    }
    @Test fun `generation change during old PIN proof rejects stale target binding`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); f.phrase(); assertTrue(f.coordinator.recoverExisting(f.activity, "a", f.material) is VaultAccessResult.Success)
            val file = fixture(f); val bytes = file.readBytes()
            f.onCredential = { f.store.rotate(f.pass.toCharArray(), "another public test passphrase".toCharArray()) }
            f.phrase("111111")
            assertTrue(resolver(f).recover(f.activity, "a") is VaultAccessResult.Unavailable)
            assertArrayEquals(bytes, file.readBytes()); assertEquals(1L, f.store.get("a")!!.generation)
        }
    }
    @Test fun `malformed stored path rejects real old backup without mutation`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(type = "mnemonic", path = "m/invalid"); val file = fixture(f); f.phrase("111111")
            assertTrue(resolver(f).recover(f.activity, "a") is VaultAccessResult.Unavailable)
            assertTrue(file.exists()); assertNull(f.store.get("a"))
        }
    }
    @Test fun `background during old PIN input aborts and clears issued credential`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); val file = fixture(f); val pin = "111111".toCharArray(); f.credentials += pin
            f.onCredential = { f.activityController.pause().stop() }
            val result = runCatching { resolver(f).recover(f.activity, "a") }
            assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertTrue(pin.all { it == '\u0000' }); assertTrue(file.exists()); assertNull(f.store.get("a"))
        }
    }

    @Test fun `matching backup proof does not authorize deleting an opaque unrelated default row`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); val file = fixture(f)
            f.v2.writeNewV2Row("default", com.rjnr.pocketnode.data.crypto.WalletKeyBundle("00".repeat(31) + "03", null),
                f.encryption.newEncryptCipherV2(), "raw_key", false).getOrThrow()
            val unrelated = f.db.keyMaterialDao().getByWalletId("default")
            f.phrase("111111"); f.phrase()
            resolver(f).recover(f.activity, "a")
            assertEquals(unrelated, f.db.keyMaterialDao().getByWalletId("default"))
        }
    }
    @Test fun `checked captured deletion refuses a newly rewritten copy`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val file = fixture(f)
            val expected = f.backup.cleanupSnapshot("default")
            file.writeBytes(byteArrayOf(4, 5, 6))
            assertFalse(f.backup.deleteCapturedCopies("default", expected))
            assertArrayEquals(byteArrayOf(4, 5, 6), file.readBytes())
            assertTrue(f.backup.deleteCapturedCopies("default", f.backup.cleanupSnapshot("default")))
            assertFalse(file.exists())
        }
    }

    @Test fun `raw old backup preserves richer readable scoped V1 source seed`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            val key = MnemonicManager().mnemonicToPrivateKey(words.split(" "))
            val info = WalletDerivation.walletInfo(key)
            val hex = key.joinToString("") { "%02x".format(it) }; key.fill(0)
            f.db.walletDao().insert(com.rjnr.pocketnode.data.database.entity.WalletEntity("a", "Seed wallet", "mnemonic", null, null,
                mainnetAddress = info.mainnetAddress, testnetAddress = info.testnetAddress))
            f.legacy.migrateWallet("a", hex, words, "mnemonic", true)
            f.backup.writeBackup("a", com.rjnr.pocketnode.data.crypto.KeyMaterial(hex, null, "raw_key", false), "111111".toCharArray())
            f.phrase("111111"); f.phrase()
            val result = resolver(f).recover(f.activity, "a") as VaultAccessResult.Success
            assertEquals(words, result.material.mnemonic)
            assertFalse(f.backup.hasBackup("a")); assertNull(f.db.walletVaultDao().legacy("a"))
        }
    }

    @Test fun `default V2 archive retires only after its old backup seed is verified in V3`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            val key = MnemonicManager().mnemonicToPrivateKey(words.split(" "))
            val info = WalletDerivation.walletInfo(key)
            val hex = key.joinToString("") { "%02x".format(it) }; key.fill(0)
            val seed = com.rjnr.pocketnode.data.crypto.KeyMaterial(hex, words, "mnemonic", true)
            f.db.walletDao().insert(com.rjnr.pocketnode.data.database.entity.WalletEntity("default", "Seed wallet", "mnemonic", null, null,
                mainnetAddress = info.mainnetAddress, testnetAddress = info.testnetAddress))
            f.v2.writeNewV2Row("default", com.rjnr.pocketnode.data.crypto.WalletKeyBundle(hex, words), f.encryption.newEncryptCipherV2(), "mnemonic", true).getOrThrow()
            f.backup.writeBackup("default", seed, "111111".toCharArray())
            f.phrase("111111"); f.phrase()
            coEvery { f.auth.authenticateForCipher(any(), any(), any(), any(), any(), any()) } coAnswers {
                AuthManager.CipherAuthResult.Success(secondArg())
            }
            assertEquals(words, (resolver(f).recover(f.activity, "default") as VaultAccessResult.Success).material.mnemonic)
            assertNull(f.db.walletVaultDao().legacy("default")); assertFalse(f.backup.hasBackup("default"))
        }
    }
    @Test fun `raw backup preserves richer readable global ESP seed`() = globalSeed(false)
    @Test fun `changed global ESP snapshot stays pending and preserves source`() = globalSeed(true)
    private fun globalSeed(changeSource: Boolean) = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            val key = MnemonicManager().mnemonicToPrivateKey(words.split(" "))
            val info = WalletDerivation.walletInfo(key)
            val hex = key.joinToString("") { "%02x".format(it) }; key.fill(0)
            f.db.walletDao().insert(com.rjnr.pocketnode.data.database.entity.WalletEntity("a", "Seed wallet", "mnemonic", null, null,
                mainnetAddress = info.mainnetAddress, testnetAddress = info.testnetAddress))
            val prefs = f.keyManager.testPrefs!!
            prefs.edit().putString("private_key", hex).putString("mnemonic_words", words).putString("wallet_type", "mnemonic").commit()
            f.backup.writeBackup("a", com.rjnr.pocketnode.data.crypto.KeyMaterial(hex, null, "raw_key", false), "111111".toCharArray())
            var requests = 0
            f.onCredential = { if (++requests == 2 && changeSource) prefs.edit().putString("changed", "replacement").commit() }
            f.phrase("111111"); f.phrase()
            val result = resolver(f).recover(f.activity, "a")
            if (changeSource) {
                assertEquals(VaultAccessResult.CleanupRequired, result)
                assertEquals(words, prefs.getString("mnemonic_words", null))
                assertTrue(f.backup.hasBackup("a"))
            } else {
                assertEquals(words, (result as VaultAccessResult.Success).material.mnemonic)
                assertFalse(f.backup.hasBackup("a"))
            }
        }
    }

    @Test fun `raw V1 source never proves scoped seed backup capabilities`() = opaqueSeedBackup("a", false)
    @Test fun `raw V1 source never proves interrupted seed backup capabilities`() = opaqueSeedBackup("a", true)
    @Test fun `raw V1 source never proves default seed backup capabilities`() = opaqueSeedBackup("default", false)
    @Test fun `raw V1 source never proves interrupted default seed backup capabilities`() = opaqueSeedBackup("default", true)
    private fun seedMaterial(): com.rjnr.pocketnode.data.crypto.KeyMaterial {
        val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val key = MnemonicManager().mnemonicToPrivateKey(words.split(" "))
        val hex = key.joinToString("") { "%02x".format(it) }; key.fill(0)
        return com.rjnr.pocketnode.data.crypto.KeyMaterial(hex, words, "mnemonic", true)
    }
    private suspend fun seedWallet(f: WalletVaultIntegrationFixture, id: String, seed: com.rjnr.pocketnode.data.crypto.KeyMaterial) {
        val key = seed.privateKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val info = try { WalletDerivation.walletInfo(key) } finally { key.fill(0) }
        f.db.walletDao().insert(com.rjnr.pocketnode.data.database.entity.WalletEntity(id, "Seed wallet", "mnemonic", null, null,
            mainnetAddress = info.mainnetAddress, testnetAddress = info.testnetAddress))
    }
    private fun opaqueSeedBackup(backupId: String, interrupted: Boolean) = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val seed = seedMaterial(); seedWallet(f, "a", seed)
            f.legacy.migrateWallet("a", seed.privateKey, null, "raw_key", false)
            if (backupId == "default") f.legacy.migrateWallet("default", seed.privateKey, null, "raw_key", false)
            f.backup.writeBackup(backupId, seed, "111111".toCharArray())
            val committed = File(f.context.cacheDir, "integration-backups/$backupId.enc")
            val file = if (interrupted) File(committed.path + ".tmp").also { assertTrue(committed.renameTo(it)) } else committed
            val bytes = file.readBytes()
            f.phrase()
            assertEquals(VaultAccessResult.CleanupRequired, f.coordinator.recoverExisting(f.activity, "a", seed.copy(mnemonic = null, walletType = "raw_key")))
            assertArrayEquals(bytes, file.readBytes())
            assertNotNull(f.db.walletVaultDao().legacy("a"))
            f.phrase("111111"); f.phrase()
            assertEquals(seed.mnemonic, (resolver(f).recover(f.activity, "a") as VaultAccessResult.Success).material.mnemonic)
            assertFalse(file.exists())
        }
    }

    @Test fun `native raw backup preserves authenticated scoped V2 seed`() = nativeV2("a", "success")
    @Test fun `native raw backup preserves authenticated default V2 seed`() = nativeV2("default", "success")
    @Test fun `native raw backup V2 cancellation aborts without activation`() = nativeV2("a", "cancel")
    @Test fun `native raw backup V2 temporary auth failure aborts without activation`() = nativeV2("a", "temporary")
    private fun nativeV2(id: String, authResult: String) = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val seed = seedMaterial(); seedWallet(f, id, seed)
            f.v2.writeNewV2Row(id, com.rjnr.pocketnode.data.crypto.WalletKeyBundle(seed.privateKey, seed.mnemonic),
                f.encryption.newEncryptCipherV2(), "mnemonic", true).getOrThrow()
            val source = f.db.keyMaterialDao().getByWalletId(id)
            f.backup.writeBackup(id, seed.copy(mnemonic = null, walletType = "raw_key"), "111111".toCharArray())
            val file = File(f.context.cacheDir, "integration-backups/$id.enc"); val bytes = file.readBytes()
            coEvery { f.auth.authenticateForCipher(any(), any(), any(), any(), any(), any()) } coAnswers {
                when (authResult) {
                    "cancel" -> AuthManager.CipherAuthResult.Cancelled
                    "temporary" -> AuthManager.CipherAuthResult.Error(1, "Temporarily unavailable")
                    "changed" -> {
                        f.db.keyMaterialDao().upsert(source!!.copy(updatedAt = source.updatedAt + 1))
                        AuthManager.CipherAuthResult.Success(secondArg())
                    }
                    else -> AuthManager.CipherAuthResult.Success(secondArg())
                }
            }
            f.phrase("111111"); f.phrase()
            val result = resolver(f).recover(f.activity, id)
            if (authResult == "success") {
                assertEquals(seed.mnemonic, (result as VaultAccessResult.Success).material.mnemonic)
                assertFalse(file.exists()); assertNull(f.db.walletVaultDao().legacy(id))
            } else {
                if (authResult == "cancel") assertEquals(VaultAccessResult.Cancelled, result)
                else assertTrue(result is VaultAccessResult.Unavailable)
                assertEquals(if (authResult == "changed") source!!.copy(updatedAt = source.updatedAt + 1) else source, f.db.keyMaterialDao().getByWalletId(id)); assertNull(f.store.get(id))
                assertArrayEquals(bytes, file.readBytes())
            }
            coVerify(exactly = 1) { f.auth.authenticateForCipher(any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test fun `invalidated V2 source requires explicit spending only acknowledgment`() = inaccessibleV2(true)
    @Test fun `declined inaccessible V2 source remains untouched`() = inaccessibleV2(false)
    @Test fun `cancelled inaccessible V2 source remains untouched`() = inaccessibleV2(null)
    @Test fun `source changed during inaccessible V2 acknowledgment rejects proof`() = inaccessibleV2(true, true)
    private fun inaccessibleV2(consent: Boolean?, changeSource: Boolean = false) = walletOperationTest {
        val encryption = spyk(com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager.createForTest())
        WalletVaultIntegrationFixture(encryptionOverride = encryption).use { f ->
            val seed = seedMaterial(); seedWallet(f, "a", seed)
            f.v2.writeNewV2Row("a", com.rjnr.pocketnode.data.crypto.WalletKeyBundle(seed.privateKey, seed.mnemonic),
                encryption.newEncryptCipherV2(), "mnemonic", true).getOrThrow()
            val source = f.db.keyMaterialDao().getByWalletId("a")!!
            f.backup.writeBackup("a", seed.copy(mnemonic = null, walletType = "raw_key"), "111111".toCharArray())
            every { encryption.newDecryptCipherV2(any()) } throws android.security.keystore.KeyPermanentlyInvalidatedException("public test invalidation")
            f.choices += consent
            var choices = 0
            f.onChoice = {
                choices++
                if (changeSource) f.db.keyMaterialDao().upsert(source.copy(updatedAt = source.updatedAt + 1))
            }
            f.phrase("111111"); f.phrase()
            val result = resolver(f).recover(f.activity, "a")
            assertEquals(1, choices)
            if (consent == true && !changeSource) {
                assertTrue(result is VaultAccessResult.Success)
                assertNull((result as VaultAccessResult.Success).material.mnemonic)
                assertNull(f.db.walletVaultDao().legacy("a")); assertFalse(f.backup.hasBackup("a"))
            } else {
                if (changeSource) assertTrue(result is VaultAccessResult.Unavailable) else assertEquals(VaultAccessResult.Cancelled, result)
                assertNull(f.store.get("a")); assertTrue(f.backup.hasBackup("a"))
                assertEquals(if (changeSource) source.copy(updatedAt = source.updatedAt + 1) else source, f.db.keyMaterialDao().getByWalletId("a"))
            }
        }
    }
    @Test fun `fresh authenticated V2 migration retains seed and retires exact source without backups`() = walletOperationTest {
        for (id in listOf("a", "default")) WalletVaultIntegrationFixture().use { f ->
            val seed = seedMaterial(); seedWallet(f, id, seed)
            f.v2.writeNewV2Row(id, com.rjnr.pocketnode.data.crypto.WalletKeyBundle(seed.privateKey, seed.mnemonic),
                f.encryption.newEncryptCipherV2(), "mnemonic", true).getOrThrow()
            coEvery { f.auth.authenticateForCipher(any(), any(), any(), any(), any(), any()) } coAnswers {
                AuthManager.CipherAuthResult.Success(secondArg())
            }
            f.phrase()
            val result = f.reader.readKeyMaterial(f.activity, id, "Read", "Authenticate") as WalletKeyReader.MaterialResult.Success
            try { assertEquals(seed.mnemonic, result.mnemonic) } finally { result.privateKey.fill(0) }
            assertNull(f.db.walletVaultDao().legacy(id))
        }
    }
    @Test fun `external raw recovery cannot retire unproven V2 archive without backup`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val seed = seedMaterial(); seedWallet(f, "a", seed)
            f.v2.writeNewV2Row("a", com.rjnr.pocketnode.data.crypto.WalletKeyBundle(seed.privateKey, seed.mnemonic),
                f.encryption.newEncryptCipherV2(), "mnemonic", true).getOrThrow()
            val source = f.db.keyMaterialDao().getByWalletId("a")
            f.phrase()
            assertEquals(VaultAccessResult.CleanupRequired, f.coordinator.recoverExisting(f.activity, "a", seed.copy(mnemonic = null, walletType = "raw_key")))
            assertEquals(source, f.db.walletVaultDao().legacy("a")?.source())
        }
    }

    @Test fun `native raw backup rejects source changed during V2 authentication`() = nativeV2("a", "changed")
    @Test fun `failed V2 migration cleanup resumes with fresh archive auth and no backup`() = resumeArchive("success")
    @Test fun `fresh V2 archive authentication can authorize explicit profile reset`() = resumeArchive("reset")
    @Test fun `cancelled V2 archive resume preserves saved vault and source`() = resumeArchive("cancel")
    @Test fun `temporary V2 archive resume preserves saved vault and source`() = resumeArchive("temporary")
    @Test fun `generation change during V2 archive resume rejects stale proof`() = resumeArchive("generation")
    private fun resumeArchive(retry: String) = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val seed = seedMaterial(); seedWallet(f, "a", seed)
            f.v2.writeNewV2Row("a", com.rjnr.pocketnode.data.crypto.WalletKeyBundle(seed.privateKey, seed.mnemonic),
                f.encryption.newEncryptCipherV2(), "mnemonic", true).getOrThrow()
            val source = f.db.keyMaterialDao().getByWalletId("a")
            var authentications = 0
            coEvery { f.auth.authenticateForCipher(any(), any(), any(), any(), any(), any()) } coAnswers {
                authentications++
                when {
                    authentications == 1 -> AuthManager.CipherAuthResult.Success(secondArg())
                    retry == "cancel" -> AuthManager.CipherAuthResult.Cancelled
                    retry == "temporary" -> AuthManager.CipherAuthResult.Error(1, "Temporary")
                    retry == "generation" -> {
                        f.store.rotate(f.pass.toCharArray(), "another public test passphrase".toCharArray())
                        AuthManager.CipherAuthResult.Success(secondArg())
                    }
                    else -> AuthManager.CipherAuthResult.Success(secondArg())
                }
            }
            f.cleanupOk = false; f.phrase()
            assertTrue(f.reader.readKeyMaterial(f.activity, "a", "Read", "Authenticate") is WalletKeyReader.MaterialResult.NotAvailable)
            val active = f.db.walletVaultDao().get("a")!!
            assertEquals(source, f.db.walletVaultDao().legacy("a")?.source())
            f.cleanupOk = true
            val next = "public independently authenticated reset phrase"
            if (retry == "reset") { f.choices += true; f.phrase(next) } else f.phrase()
            val result = resolver(f).resumeCleanup(f.activity, "a")
            assertEquals(2, authentications)
            if (retry == "success" || retry == "reset") {
                if (retry == "reset") assertTrue(f.store.verifyPassphrase(next.toCharArray()))
                assertEquals(seed.mnemonic, (result as VaultAccessResult.Success).material.mnemonic)
                assertNull(f.db.walletVaultDao().legacy("a")); assertTrue(f.store.pendingLegacyCleanup().isEmpty())
            } else {
                if (retry == "cancel") assertEquals(VaultAccessResult.Cancelled, result) else assertTrue(result is VaultAccessResult.Unavailable)
                assertEquals(source, f.db.walletVaultDao().legacy("a")?.source())
                assertEquals(active.generation + if (retry == "generation") 1 else 0, f.db.walletVaultDao().get("a")!!.generation)
            }
        }
    }

    @Test fun `richer scoped XML seed survives raw backup or raw Room migration`() = walletOperationTest {
        for (fromBackup in listOf(true, false)) WalletVaultIntegrationFixture().use { f ->
            val id = if (fromBackup) "backupseed" else "roomseed"
            val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            val bytes = MnemonicManager().mnemonicToPrivateKey(words.split(" "))
            val info = WalletDerivation.walletInfo(bytes)
            val raw = f.material.copy(privateKey = bytes.joinToString("") { "%02x".format(it) })
            bytes.fill(0)
            f.db.walletDao().insert(com.rjnr.pocketnode.data.database.entity.WalletEntity(id, id, "mnemonic", "m/44'/309'/0'/0/0", null,
                mainnetAddress = info.mainnetAddress, testnetAddress = info.testnetAddress))
            val prefs = f.context.getSharedPreferences("ckb_wallet_keys_$id", 0)
            prefs.edit().putString("private_key", raw.privateKey).putString("mnemonic_words", words).putBoolean("mnemonic_backed_up", true).commit()
            f.keyManager.testWalletPrefs[id] = prefs
            if (fromBackup) {
                f.backup.writeBackup(id, raw, "111111".toCharArray()); f.phrase("111111"); f.phrase()
            } else {
                f.legacy.migrateWallet(id, raw.privateKey, null, "raw_key", false)
                val source = f.db.keyMaterialDao().getByWalletId(id)!!
                f.phrase()
                assertEquals(VaultAccessResult.CleanupRequired, f.coordinator.protectLegacy(f.activity, id, raw, source.iv))
                f.phrase()
            }
            val result = if (fromBackup) resolver(f).recover(f.activity, id) else resolver(f).resumeCleanup(f.activity, id)
            assertTrue("richer readable XML must upgrade the replacement (backup=$fromBackup, result=${result::class.simpleName})", result is VaultAccessResult.Success)
            assertEquals(words, (result as VaultAccessResult.Success).material.mnemonic)
            f.phrase(); assertEquals(words, (f.coordinator.read(f.activity, id, "Read", "Read") as VaultAccessResult.Success).material.mnemonic)
            assertFalse(java.io.File(f.context.filesDir.parentFile, "shared_prefs/ckb_wallet_keys_$id.xml").exists())
        }
    }
    @Test fun `independent scoped XML and bak require matching identity and preserve richer seed`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val id = "copies"
            val prefs = f.context.getSharedPreferences("ckb_wallet_keys_$id", 0)
            prefs.edit().putString("private_key", f.material.privateKey).commit()
            f.keyManager.testWalletPrefs[id] = prefs
            val xml = java.io.File(f.context.filesDir.parentFile, "shared_prefs/ckb_wallet_keys_$id.xml")
            val bak = java.io.File(xml.path + ".bak")
            val original = xml.readBytes()
            bak.writeText("<map><string name=\"private_key\">${f.material.privateKey}</string><string name=\"mnemonic_words\">public richer seed</string></map>")
            assertEquals("public richer seed", f.keyManager.preferencesForVaultCleanup(id)?.mnemonic)
            assertArrayEquals(original, xml.readBytes())
            val saved = bak.readBytes()
            assertArrayEquals(saved, bak.readBytes())
            bak.writeText("<map><string name=\"private_key\">different public key</string></map>")
            assertNull(f.keyManager.preferencesForVaultCleanup(id))
            assertArrayEquals(original, xml.readBytes()); assertTrue(bak.exists())
            bak.writeText("unreadable independent copy")
            assertNull(f.keyManager.preferencesForVaultCleanup(id))
            assertEquals("unreadable independent copy", bak.readText())
        }
    }

    @Test fun `independent scoped backup and changed XML are never deleted on backup proof`() = walletOperationTest {
        for (extension in listOf(".xml", ".xml.bak")) WalletVaultIntegrationFixture().use { f ->
            f.wallet(); val file = fixture(f, "a")
            val source = java.io.File(f.context.filesDir.parentFile, "shared_prefs/ckb_wallet_keys_a$extension")
            source.parentFile!!.mkdirs(); source.writeText("original unproven ciphertext")
            f.onCredential = { source.writeText("changed independent ciphertext") }
            f.phrase("111111"); f.phrase()
            assertFalse(resolver(f).recover(f.activity, "a") is VaultAccessResult.Success)
            assertEquals("changed independent ciphertext", source.readText()); assertTrue(file.exists())
        }
    }

    @Test @Config(sdk = [30]) fun `nested captured V2 credential stop resume preserves seed for backup and archive only recovery`() = walletOperationTest {
        for (archiveOnly in listOf(false, true)) WalletVaultIntegrationFixture().use { f ->
            val id = if (archiveOnly) "archive" else "backup"
            val seed = seedMaterial(); seedWallet(f, id, seed)
            f.v2.writeNewV2Row(id, com.rjnr.pocketnode.data.crypto.WalletKeyBundle(seed.privateKey, seed.mnemonic),
                f.encryption.newEncryptCipherV2(), "mnemonic", true).getOrThrow()
            if (archiveOnly) {
                f.phrase()
                assertEquals(VaultAccessResult.CleanupRequired, f.coordinator.recoverExisting(f.activity, id, seed.copy(mnemonic = null, walletType = "raw_key")))
            } else {
                f.backup.writeBackup(id, seed.copy(mnemonic = null, walletType = "raw_key"), "111111".toCharArray())
                f.phrase("111111")
            }
            every { f.auth.hasDeviceCredential() } returns true
            coEvery { f.auth.authenticateForCipher(any(), any(), any(), any(), any(), any()) } coAnswers {
                f.activityController.pause().stop()
                kotlinx.coroutines.yield()
                f.activityController.start().resume()
                AuthManager.CipherAuthResult.Success(secondArg())
            }
            f.phrase()
            val result = if (archiveOnly) resolver(f).resumeCleanup(f.activity, id) else resolver(f).recover(f.activity, id)
            assertTrue(result is VaultAccessResult.Success)
            assertEquals(seed.mnemonic, (result as VaultAccessResult.Success).material.mnemonic)
            assertNull(f.db.walletVaultDao().legacy(id)); assertFalse(f.backup.hasBackup(id))
        }
    }

    @Test fun `software readable V1 archive and richer ESP cannot reset profile without current credential`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val id = "internal-archive"
            val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            val key = MnemonicManager().mnemonicToPrivateKey(words.split(" "))
            val info = WalletDerivation.walletInfo(key)
            val raw = f.material.copy(privateKey = key.joinToString("") { "%02x".format(it) }); key.fill(0)
            f.db.walletDao().insert(com.rjnr.pocketnode.data.database.entity.WalletEntity(id, id, "mnemonic", "m/44'/309'/0'/0/0", null,
                mainnetAddress = info.mainnetAddress, testnetAddress = info.testnetAddress))
            val prefs = f.context.getSharedPreferences("ckb_wallet_keys_$id", 0)
            prefs.edit().putString("private_key", raw.privateKey).putString("mnemonic_words", words).commit()
            f.keyManager.testWalletPrefs[id] = prefs
            f.legacy.migrateWallet(id, raw.privateKey, null, "raw_key", false)
            val source = f.db.keyMaterialDao().getByWalletId(id)!!
            assertNull("First-time V1 protection starts without an old profile", f.db.walletVaultDao().profile())
            f.phrase()
            assertEquals(VaultAccessResult.CleanupRequired, f.coordinator.protectLegacy(f.activity, id, raw, source.iv))
            val before = f.db.walletVaultDao().get(id)!!
            val profile = f.db.walletVaultDao().profile()!!
            val file = File(f.context.filesDir.parentFile, "shared_prefs/ckb_wallet_keys_$id.xml")
            val fileBytes = file.readBytes()
            val replacement = "public unauthorized new passphrase"
            f.choices += true; f.phrase(replacement)
            val denied = resolver(f).resumeCleanup(f.activity, id)
            assertTrue("Internal readable sources must not authorize a new profile", denied is VaultAccessResult.Unavailable)
            assertArrayEquals(before.envelope, f.db.walletVaultDao().get(id)!!.envelope)
            assertArrayEquals(profile.envelope, f.db.walletVaultDao().profile()!!.envelope)
            assertEquals(source, f.db.walletVaultDao().legacy(id)!!.source())
            assertArrayEquals(fileBytes, file.readBytes())
            assertTrue(f.store.verifyPassphrase(f.pass.toCharArray()))
            assertFalse(f.store.verifyPassphrase(replacement.toCharArray()))
            assertEquals("Reset must not be offered for internal archive authority", 1, f.choices.size)
            f.choices.clear(); f.phrase()
            val recovered = resolver(f).resumeCleanup(f.activity, id) as VaultAccessResult.Success
            assertEquals(words, recovered.material.mnemonic)
            assertTrue(f.store.verifyPassphrase(f.pass.toCharArray()))
            assertNull(f.db.walletVaultDao().legacy(id)); assertFalse(file.exists())
        }
    }

    @Test fun `matching backup PIN authentication can authorize explicit profile reset`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet("pin-reset"); f.phrase()
            assertTrue(f.coordinator.recoverExisting(f.activity, "pin-reset", f.material) is VaultAccessResult.Success)
            f.backup.writeBackup("pin-reset", f.material, "111111".toCharArray())
            val next = "public independently authenticated backup reset"
            f.phrase("111111"); f.phrase(next); f.choices += true
            assertTrue(resolver(f).recover(f.activity, "pin-reset") is VaultAccessResult.Success)
            assertTrue(f.store.verifyPassphrase(next.toCharArray()))
            assertFalse(f.store.verifyPassphrase(f.pass.toCharArray()))
        }
    }

}
