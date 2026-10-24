package com.rjnr.pocketnode.data.vault

import com.rjnr.pocketnode.data.wallet.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LegacyBackupRecoveryTest {
    private fun resolver(f: WalletVaultIntegrationFixture) = LegacyBackupRecovery(f.db, f.backup, f.coordinator, f.prompt, WalletSecretValidator(MnemonicManager()), f.keyManager, f.legacy)
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

}
