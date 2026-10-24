package com.rjnr.pocketnode.data.wallet

import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.migration.WalletMigrationHelper
import com.rjnr.pocketnode.data.vault.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WalletBootstrapCleanupIntegrationTest {
    private fun manager(f: WalletVaultIntegrationFixture) = KeyManager(f.context, MnemonicManager(), NoopLogger).also {
        it.keyStoreMigrationHelper = f.legacy
        it.testPrefs = f.context.getSharedPreferences("ckb_wallet_keys", 0).also { prefs ->
            prefs.edit().clear().putString("private_key", f.material.privateKey).putString("wallet_type", "raw_key").commit()
        }
    }
    @Test fun `bootstrap to verified activation retires all owned default copies and preserves unrelated backups`() = walletOperationTest {
        for (roomSource in listOf(false, true)) WalletVaultIntegrationFixture().use { f ->
            val manager = manager(f)
            if (roomSource) f.legacy.migrateWallet("default", f.material.privateKey, null, "raw_key", false)
            val preferences = WalletPreferences(f.context, NoopLogger)
            WalletMigrationHelper(f.db.walletDao(), manager, preferences, f.db, f.db.syncProgressDao(), f.legacy, NoopLogger).migrateIfNeeded()
            val id = f.db.walletDao().getActive()!!.walletId
            val weak = File(f.context.cacheDir, "integration-backups/default.enc").apply { parentFile!!.mkdirs(); writeText("public disposable old backup") }
            val unrelated = File(weak.parentFile, "other.enc").apply { writeText("unrelated") }
            assertTrue(weak.exists())
            f.phrase()
            val result = f.reader.readPrivateKey(f.activity, id, "Sign", "Authenticate") as WalletKeyReader.Result.Success
            result.privateKey.fill(0)
            assertEquals("default", id)
            assertFalse(weak.exists())
            assertFalse(File(f.context.filesDir.parentFile, "shared_prefs/ckb_wallet_keys.xml").exists())
            assertEquals(3, f.db.keyMaterialDao().getKdfVersion("default"))
            assertNull(f.db.walletVaultDao().legacy(id))
            assertEquals("unrelated", unrelated.readText())
        }
    }
    @Test fun `historical UUID activation also retires identity matched default row preferences and backup`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            manager(f)
            f.wallet("historical")
            f.legacy.migrateWallet("default", f.material.privateKey, null, "raw_key", false)
            f.legacy.migrateWallet("historical", f.material.privateKey, null, "raw_key", false)
            val weak = File(f.context.cacheDir, "integration-backups/default.enc").apply { parentFile!!.mkdirs(); writeText("public disposable backup") }
            f.phrase()
            val result = f.reader.readPrivateKey(f.activity, "historical", "Sign", "Authenticate") as WalletKeyReader.Result.Success
            result.privateKey.fill(0)
            assertNull(f.db.keyMaterialDao().getByWalletId("default"))
            assertFalse(weak.exists())
            assertFalse(File(f.context.filesDir.parentFile, "shared_prefs/ckb_wallet_keys.xml").exists())
        }
    }
}
