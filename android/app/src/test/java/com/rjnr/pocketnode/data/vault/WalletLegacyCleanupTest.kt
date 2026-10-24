package com.rjnr.pocketnode.data.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.crypto.KeyBackupManager
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.rjnr.pocketnode.data.wallet.*
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WalletLegacyCleanupTest {
    private suspend fun activate(f: WalletVaultIntegrationFixture) {
        f.wallet()
        f.legacy.migrateWallet("a", f.material.privateKey, null, "raw_key", false)
        val source = f.db.keyMaterialDao().getByWalletId("a")!!
        val bytes = Json.encodeToString(f.material).toByteArray()
        try {
            val envelope = f.crypto.create("a", 0, bytes, f.pass.toCharArray())
            f.store.activate(source, source.iv, envelope, f.pass.toCharArray())
        } finally { bytes.fill(0) }
    }
    @get:Rule val folder = TemporaryFolder()
    @Test fun `checked cleanup removes only requested wallet preferences and all its weak backup copies`() = walletOperationTest {
        val fixture = com.rjnr.pocketnode.data.wallet.WalletVaultIntegrationFixture()
        activate(fixture)
        val context = fixture.context
        val prefs = context.getSharedPreferences("ckb_wallet_keys_a", 0)
        prefs.edit().putString("private_key", "disposable public fixture").commit()
        val sibling = context.getSharedPreferences("ckb_wallet_keys_b", 0)
        sibling.edit().putString("private_key", "disposable sibling").commit()
        val root = File(context.cacheDir, "integration-backups")
        fixture.backup.writeBackup("a", fixture.material, "111111".toCharArray())
        File(root, "a.enc.tmp").writeBytes(File(root, "a.enc").readBytes())
        val other = File(root, "b.enc").apply { writeText("sibling") }
        val cleanup = WalletLegacyCleanup(context, fixture.backup, fixture.db, fixture.keyManager, fixture.legacy)
        assertFalse(cleanup.clean("a", 0, fixture.material))
        assertTrue(File(root, "a.enc").exists()); assertTrue(File(root, "a.enc.tmp").exists())
        fixture.phrase("111111"); fixture.phrase()
        val resolver = LegacyBackupRecovery(fixture.db, fixture.backup, fixture.coordinator, fixture.prompt,
            WalletSecretValidator(MnemonicManager()), fixture.keyManager, fixture.reader, fixture.legacy)
        assertTrue(resolver.recover(fixture.activity, "a") is VaultAccessResult.Success)
        assertFalse(File(root, "a.enc").exists()); assertFalse(File(root, "a.enc.tmp").exists())
        assertFalse(File(context.filesDir.parentFile, "shared_prefs/ckb_wallet_keys_a.xml").exists())
        assertEquals("sibling", other.readText()); assertTrue(sibling.contains("private_key"))
        assertTrue(cleanup.clean("a", 1, fixture.material))
        fixture.close()
    }
    @Test fun `failed deletion remains retryable and never reports cleanup complete`() = walletOperationTest {
        val fixture = com.rjnr.pocketnode.data.wallet.WalletVaultIntegrationFixture()
        activate(fixture)
        val context = fixture.context
        val blocked = File(folder.root, "a.enc").apply { mkdir() }
        File(blocked, "child").writeText("cannot remove nonempty directory")
        val cleanup = WalletLegacyCleanup(context, KeyBackupManager(folder.root, NoopLogger), fixture.db, fixture.keyManager, fixture.legacy)
        assertFalse(cleanup.clean("a", 0, fixture.material)); assertTrue(blocked.exists())
        File(blocked, "child").delete(); blocked.delete()
        assertTrue(cleanup.clean("a", 0, fixture.material))
        fixture.close()
    }
}
