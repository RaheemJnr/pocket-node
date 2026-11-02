package com.rjnr.pocketnode.data.wallet

import com.rjnr.pocketnode.core.log.NoopLogger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WalletVaultKeyManagerBoundaryTest {
    @Test fun `UI free getters never release legacy software or ESP secrets`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val manager = KeyManager(f.context, MnemonicManager(), NoopLogger)
            manager.keyStoreMigrationHelper = f.legacy
            val prefs = f.context.getSharedPreferences("boundary-default", 0)
            prefs.edit().putString("private_key", f.material.privateKey).putString("mnemonic_words", "public test words").commit()
            manager.testPrefs = prefs; manager.testWalletPrefs["a"] = prefs
            f.legacy.migrateWallet("a", f.material.privateKey, null, "raw_key", false)
            val source = f.db.keyMaterialDao().getByWalletId("a")
            for (read in listOf<suspend () -> Any?>(
                { manager.getPrivateKey() }, { manager.getMnemonic() },
                { manager.getPrivateKeyForWallet("a") }, { manager.getMnemonicForWallet("a") },
            )) {
                var denied = false
                try { read() } catch (_: IllegalStateException) { denied = true }
                assertTrue("Foreground vault authentication was bypassed", denied)
            }
            assertEquals(source, f.db.keyMaterialDao().getByWalletId("a"))
            assertTrue(prefs.contains("private_key"))
        }
    }
    @Test fun `legacy source preferences survive old global cleanup until wallet specific vault cleanup`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val manager = KeyManager(f.context, MnemonicManager(), NoopLogger)
            manager.keyStoreMigrationHelper = f.legacy
            f.legacy.markMigrationComplete()
            f.context.getSharedPreferences("ckb_wallet_keys_a", 0).edit().putString("private_key", "public disposable source").commit()
            manager.deleteEspFilesIfSafe()
            assertTrue(java.io.File(f.context.filesDir.parentFile, "shared_prefs/ckb_wallet_keys_a.xml").exists())
        }
    }
    @Test fun `legacy migration racing vault activation cannot replace the newer marker`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.legacy.migrateWallet("a", f.material.privateKey, null, "raw_key", false)
            val marker = com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity("a", byteArrayOf(), null, byteArrayOf(), "raw_key", true, 2, 3)
            val encryption = io.mockk.spyk(f.encryption)
            io.mockk.every { encryption.newEncryptCipher() } answers {
                kotlinx.coroutines.runBlocking { f.db.keyMaterialDao().upsert(marker) }
                callOriginal()
            }
            val helper = com.rjnr.pocketnode.data.crypto.KeyStoreMigrationHelper(f.db.keyMaterialDao(), encryption, f.prefs, NoopLogger, database = f.db)
            var denied = false
            try { helper.migrateWallet("a", f.material.privateKey, null, "raw_key", false) }
            catch (_: IllegalStateException) { denied = true }
            assertTrue("Concurrent V3 marker was replaced by legacy ciphertext", denied)
            assertEquals(marker, f.db.keyMaterialDao().getByWalletId("a"))
        }
    }
    @Test fun `V1 to V2 migration and extraction refuse a concurrently activated vault`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            for (extract in listOf(false, true)) {
                f.db.keyMaterialDao().delete("a")
                f.legacy.migrateWallet("a", f.material.privateKey, null, "raw_key", false)
                val marker = com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity("a", byteArrayOf(), null, byteArrayOf(), "raw_key", true, 2, 3)
                val encryption = io.mockk.spyk(f.encryption)
                io.mockk.every { encryption.encryptWithCipher(any(), any()) } answers {
                    kotlinx.coroutines.runBlocking { f.db.keyMaterialDao().upsert(marker) }
                    callOriginal()
                }
                val helper = com.rjnr.pocketnode.data.crypto.KeystoreV2MigrationHelper(f.db.keyMaterialDao(), encryption, f.prefs, logger = NoopLogger, database = f.db)
                val result = if (extract) helper.migrateWalletAndExtract("a", f.encryption.newEncryptCipherV2())
                    else helper.migrateWallet("a", f.encryption.newEncryptCipherV2())
                assertTrue("V1 to V2 migration replaced a concurrent V3 vault", result.isFailure)
                assertEquals(marker, f.db.keyMaterialDao().getByWalletId("a"))
            }
        }
    }
    @Test fun `legacy migration finalization preserves shared key needed by retained source ciphertext`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.legacy.migrateWallet("a", f.material.privateKey, null, "raw_key", false)
            val source = f.db.keyMaterialDao().getByWalletId("a")!!
            f.db.keyMaterialDao().upsert(source.copy(kdfVersion = 3))
            f.v2.finalize().getOrThrow()
            assertTrue("Shared V1 alias was deleted while an archived source may still depend on it", f.encryption.hasV1Key())
            val plaintext = f.encryption.decryptWithCipher(f.encryption.newDecryptCipher(source.iv), source.encryptedPrivateKey)
            try { assertEquals(f.material.privateKey, String(plaintext, Charsets.UTF_8)) }
            finally { plaintext.fill(0) }
        }
    }
    @Test fun `startup migration accessor rejects vault marker and populated wallet table without serving ESP`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val manager = KeyManager(f.context, MnemonicManager(), NoopLogger).also { it.keyStoreMigrationHelper = f.legacy }
            manager.testPrefs = f.context.getSharedPreferences("startup-legacy", 0).also {
                it.edit().putString("private_key", f.material.privateKey).commit()
            }
            val marker = com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity("default", byteArrayOf(), null, byteArrayOf(), "raw_key", true, 2, 3)
            f.db.keyMaterialDao().upsert(marker)
            assertTrue(runCatching { manager.legacyDefaultMaterialForMigration(f.db.walletDao()) }.isFailure)
            assertEquals(marker, f.db.keyMaterialDao().getByWalletId("default"))
            f.db.keyMaterialDao().delete("default"); f.wallet()
            assertTrue(runCatching { manager.legacyDefaultMaterialForMigration(f.db.walletDao()) }.isFailure)
            assertEquals(1, f.db.walletDao().count())
        }
    }
    @Test fun `background ESP migration cannot downgrade an existing vault marker`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val marker = com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity("a", byteArrayOf(), null, byteArrayOf(), "raw_key", true, 1, 3)
            f.db.keyMaterialDao().upsert(marker)
            var denied = false
            try { f.legacy.migrateWallet("a", f.material.privateKey, null, "raw_key", true) }
            catch (_: IllegalStateException) { denied = true }
            assertTrue("V3 marker was downgraded", denied)
            assertEquals(marker, f.db.keyMaterialDao().getByWalletId("a"))
        }
    }
}
