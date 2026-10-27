package com.rjnr.pocketnode.data.vault

import com.rjnr.pocketnode.data.crypto.writeBackup

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
            f.store.activate(source, source.iv, envelope, f.pass.toCharArray(), expectedWallet = f.db.walletDao().getById(source.walletId))
        } finally { bytes.fill(0) }
    }
    @get:Rule val folder = TemporaryFolder()
    @Test fun `unproven scoped preferences remain intact despite authenticated weak backups`() = walletOperationTest {
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
        assertEquals(VaultAccessResult.CleanupRequired, resolver.recover(fixture.activity, "a"))
        assertTrue(File(root, "a.enc").exists()); assertTrue(File(root, "a.enc.tmp").exists())
        assertTrue(File(context.filesDir.parentFile, "shared_prefs/ckb_wallet_keys_a.xml").exists())
        assertEquals("disposable public fixture", prefs.getString("private_key", null))
        assertEquals("sibling", other.readText()); assertTrue(sibling.contains("private_key"))
        assertFalse(cleanup.clean("a", 1, fixture.material))
        fixture.close()
    }
    @Test fun `failed deletion remains retryable and never reports cleanup complete`() = walletOperationTest {
        val fixture = com.rjnr.pocketnode.data.wallet.WalletVaultIntegrationFixture()
        activate(fixture)
        val context = fixture.context
        val blocked = File(folder.root, "a.enc").apply { mkdir() }
        File(blocked, "child").writeText("cannot remove nonempty directory")
        val cleanup = WalletLegacyCleanup(context, KeyBackupManager(folder.root, NoopLogger, com.rjnr.pocketnode.data.wallet.WalletStorageBoundary(androidx.test.core.app.ApplicationProvider.getApplicationContext())), fixture.db, fixture.keyManager, fixture.legacy)
        assertFalse(cleanup.clean("a", 0, fixture.material)); assertTrue(blocked.exists())
        File(blocked, "child").delete(); blocked.delete()
        assertTrue(cleanup.clean("a", 0, fixture.material))
        fixture.close()
    }

    @Test fun `retiring one wallet preserves shared wrapper for another row and interrupted archive`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet()
            f.legacy.migrateWallet("a", f.material.privateKey, null, "raw_key", false)
            val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            val key = MnemonicManager().mnemonicToPrivateKey(words.split(" "))
            val info = WalletDerivation.walletInfo(key)
            val hex = try { key.joinToString("") { "%02x".format(it) } } finally { key.fill(0) }
            f.db.walletDao().insert(com.rjnr.pocketnode.data.database.entity.WalletEntity("b", "Seed", "mnemonic", null, null,
                mainnetAddress = info.mainnetAddress, testnetAddress = info.testnetAddress))
            f.legacy.migrateWallet("b", hex, words, "mnemonic", true)
            val sibling = f.db.keyMaterialDao().getByWalletId("b")!!
            f.phrase()
            val raw = f.reader.readKeyMaterial(f.activity, "a", "Read", "Read") as WalletKeyReader.MaterialResult.Success
            try { assertNull(raw.mnemonic) } finally { raw.privateKey.fill(0) }
            assertNull(f.db.walletVaultDao().legacy("a"))
            assertEquals(sibling, f.db.keyMaterialDao().getByWalletId("b"))
            assertEquals(words, f.legacy.legacySourceForVaultCleanup(sibling)!!.mnemonic)
            assertTrue(f.encryption.hasV1Key())

            f.cleanupOk = false
            f.phrase()
            assertTrue(f.reader.readKeyMaterial(f.activity, "b", "Read", "Read") is WalletKeyReader.MaterialResult.NotAvailable)
            assertEquals(sibling, f.db.walletVaultDao().legacy("b")?.source())
            assertTrue(f.store.pendingLegacyCleanup().contains("b"))
            assertTrue(f.v2.finalize().isSuccess)
            assertTrue(f.encryption.hasV1Key())
            assertEquals(words, f.legacy.legacySourceForVaultCleanup(f.db.walletVaultDao().legacy("b")!!.source())!!.mnemonic)

            f.cleanupOk = true
            f.phrase()
            val recovered = f.coordinator.read(f.activity, "b", "Read", "Read") as VaultAccessResult.Success
            assertEquals(words, recovered.material.mnemonic)
            assertNull(f.db.walletVaultDao().legacy("b"))
            assertTrue(f.store.pendingLegacyCleanup().isEmpty())
            for (id in listOf("a", "b")) {
                val marker = f.db.keyMaterialDao().getByWalletId(id)!!
                assertEquals(3, marker.kdfVersion)
                assertTrue(marker.encryptedPrivateKey.isEmpty())
                assertNull(marker.encryptedMnemonic)
                assertTrue(marker.iv.isEmpty())
            }
            // Retaining the unused wrapper does not retain either wallet's old ciphertext.
            assertTrue(f.encryption.hasV1Key())
        }
    }
}
