package com.rjnr.pocketnode.data.vault

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.crypto.*
import com.rjnr.pocketnode.data.database.entity.*
import com.rjnr.pocketnode.data.wallet.*
import io.mockk.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WalletDefaultCleanupOwnershipTest {
    private suspend fun active(f: WalletVaultIntegrationFixture, material: KeyMaterial = f.material, id: String = "a") {
        val key = material.privateKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val info = try { WalletDerivation.walletInfo(key) } finally { key.fill(0) }
        f.db.walletDao().insert(WalletEntity(id, "Wallet", material.walletType, null, null, mainnetAddress = info.mainnetAddress, testnetAddress = info.testnetAddress))
        val bytes = Json.encodeToString(material).toByteArray()
        try {
            val envelope = f.crypto.create(id, 0, bytes, f.pass.toCharArray())
            f.store.activate(KeyMaterialEntity(id, byteArrayOf(), null, byteArrayOf(), "raw_key", false, 1), null, envelope, f.pass.toCharArray())
        } finally { bytes.fill(0) }
    }
    private fun weak(f: WalletVaultIntegrationFixture) = File(f.context.cacheDir, "integration-backups/default.enc").apply {
        parentFile!!.mkdirs(); writeText("public disposable old backup")
    }
    private fun cleanup(f: WalletVaultIntegrationFixture, helper: KeyStoreMigrationHelper = f.legacy) =
        WalletLegacyCleanup(f.context, f.backup, f.db, f.keyManager, helper)

    private fun seedMaterial(): KeyMaterial {
        val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val key = MnemonicManager().mnemonicToPrivateKey(words.split(" "))
        val hex = try { key.joinToString("") { "%02x".format(it) } } finally { key.fill(0) }
        return KeyMaterial(hex, words, "mnemonic", true)
    }
    private suspend fun defaultSeedWallet(f: WalletVaultIntegrationFixture, material: KeyMaterial) {
        val key = material.privateKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val info = try { WalletDerivation.walletInfo(key) } finally { key.fill(0) }
        f.db.walletDao().insert(WalletEntity("default", "Wallet", "mnemonic", "m/44'/309'/0'/0/0", null,
            mainnetAddress = info.mainnetAddress, testnetAddress = info.testnetAddress))
    }
    @Test fun `literal default opaque backup still requires ownership proof`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            active(f, id = "default"); val file = weak(f); val original = file.readBytes()
            f.phrase()
            assertEquals(VaultAccessResult.CleanupRequired, f.coordinator.read(f.activity, "default", "Unlock", "Authenticate"))
            assertArrayEquals(original, file.readBytes())
            assertTrue(f.store.pendingLegacyCleanup().contains("default"))
        }
    }
    @Test fun `default V2 seed archive and backup survive raw key recovery until foreground proof`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val seed = seedMaterial(); defaultSeedWallet(f, seed)
            f.v2.writeNewV2Row("default", WalletKeyBundle(seed.privateKey, seed.mnemonic),
                f.encryption.newEncryptCipherV2(), "mnemonic", true).getOrThrow()
            val source = f.db.keyMaterialDao().getByWalletId("default")!!
            val pin = "246813".toCharArray()
            try { f.backup.writeBackup("default", seed, pin) } finally { pin.fill('\u0000') }
            val file = File(f.context.cacheDir, "integration-backups/default.enc"); val original = file.readBytes()
            f.phrase()
            val raw = seed.copy(mnemonic = null, walletType = "raw_key")
            assertEquals(VaultAccessResult.CleanupRequired, f.coordinator.recoverExisting(f.activity, "default", raw))
            assertEquals(source, f.db.walletVaultDao().legacy("default")?.source())
            assertArrayEquals(original, file.readBytes())
            assertEquals(3, f.db.keyMaterialDao().getKdfVersion("default"))
            assertTrue(f.store.pendingLegacyCleanup().contains("default"))
        }
    }
    @Test fun `readable default seed preserved in verified replacement allows checked cleanup`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val seed = seedMaterial(); defaultSeedWallet(f, seed)
            f.legacy.migrateWallet("default", seed.privateKey, seed.mnemonic, "mnemonic", true)
            f.backup.writeBackup("default", seed, "111111".toCharArray())
            val file = File(f.context.cacheDir, "integration-backups/default.enc")
            f.phrase()
            assertTrue(f.reader.readKeyMaterial(f.activity, "default", "Unlock", "Authenticate") is WalletKeyReader.MaterialResult.NotAvailable)
            assertTrue(file.exists())
            f.phrase("111111"); f.phrase()
            val result = LegacyBackupRecovery(f.db, f.backup, f.coordinator, f.prompt,
                WalletSecretValidator(MnemonicManager()), f.keyManager, f.reader, f.legacy).recover(f.activity, "default") as VaultAccessResult.Success
            assertEquals(seed.mnemonic, result.material.mnemonic)
            assertFalse(file.exists()); assertNull(f.db.walletVaultDao().legacy("default"))
            assertFalse(f.store.pendingLegacyCleanup().contains("default"))
        }
    }
    @Test fun `opaque default backup without ownership proof blocks cleanup without deleting it`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            active(f); val file = weak(f)
            assertFalse(cleanup(f).clean("a", 0, f.material)); assertTrue(file.exists())
            assertTrue(f.store.pendingLegacyCleanup().contains("a"))
        }
    }
    @Test fun `readable different default identity remains untouched and does not block unrelated wallet`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            active(f)
            f.legacy.migrateWallet("default", "00".repeat(31) + "03", null, "raw_key", false)
            val source = f.db.keyMaterialDao().getByWalletId("default")
            val file = weak(f)
            assertTrue(cleanup(f).clean("a", 0, f.material))
            assertEquals(source, f.db.keyMaterialDao().getByWalletId("default")); assertTrue(file.exists())
        }
    }
    @Test fun `matching default mnemonic is retained if verified replacement only contains private key`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            val bytes = MnemonicManager().mnemonicToPrivateKey(words.split(" "))
            val hex = try { bytes.joinToString("") { "%02x".format(it) } } finally { bytes.fill(0) }
            val raw = KeyMaterial(hex, walletType = "raw_key", mnemonicBackedUp = false)
            active(f, raw); f.legacy.migrateWallet("default", hex, words, "mnemonic", true)
            val source = f.db.keyMaterialDao().getByWalletId("default"); val file = weak(f)
            assertFalse(cleanup(f).clean("a", 0, raw))
            assertEquals(source, f.db.keyMaterialDao().getByWalletId("default")); assertTrue(file.exists())
        }
    }
    @Test fun `changed default source after ownership capture rejects cleanup before touching files`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            active(f); f.legacy.migrateWallet("default", f.material.privateKey, null, "raw_key", false)
            val source = f.db.keyMaterialDao().getByWalletId("default")!!; val file = weak(f)
            val encryption = spyk(f.encryption)
            every { encryption.decryptWithCipher(any(), any()) } answers {
                val bytes = callOriginal()
                runBlocking { f.legacy.migrateWallet("default", f.material.privateKey, null, "raw_key", false) }
                bytes
            }
            val helper = KeyStoreMigrationHelper(f.db.keyMaterialDao(), encryption, f.prefs, NoopLogger)
            assertFalse(cleanup(f, helper).clean("a", 0, f.material))
            assertNotEquals(source, f.db.keyMaterialDao().getByWalletId("default")); assertTrue(file.exists())
        }
    }
    @Test fun `concurrent target generation change rejects stale verified cleanup witness`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            active(f); f.legacy.migrateWallet("default", f.material.privateKey, null, "raw_key", false)
            val source = f.db.keyMaterialDao().getByWalletId("default"); val file = weak(f)
            val encryption = spyk(f.encryption)
            every { encryption.decryptWithCipher(any(), any()) } answers {
                val bytes = callOriginal()
                runBlocking { f.store.rotate(f.pass.toCharArray(), "another public disposable passphrase".toCharArray()) }
                bytes
            }
            val helper = KeyStoreMigrationHelper(f.db.keyMaterialDao(), encryption, f.prefs, NoopLogger)
            assertFalse(cleanup(f, helper).clean("a", 0, f.material))
            assertEquals(source, f.db.keyMaterialDao().getByWalletId("default")); assertTrue(file.exists())
        }
    }
    @Test fun `unrelated or stale witness cannot retire current target copies`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            active(f)
            val owned = File(f.context.cacheDir, "integration-backups/a.enc").apply { parentFile!!.mkdirs(); writeText("owned legacy") }
            assertFalse(cleanup(f).clean("a", 99, f.material)); assertTrue(owned.exists())
            assertFalse(cleanup(f).clean("a", 0, f.material.copy(privateKey = "00".repeat(31) + "03"))); assertTrue(owned.exists())
        }
    }
}
