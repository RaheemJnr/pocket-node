package com.rjnr.pocketnode.data.wallet

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WalletVaultWriterIntegrationTest {
    @Test fun `mnemonic origin child persists raw capability and keeps derivation metadata`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val mm = MnemonicManager()
            val seed = mm.mnemonicToSeed((List(11) { "abandon" } + "about"))
            val key = try { mm.derivePrivateKey(seed, accountIndex = 1) } finally { seed.fill(0) }
            val info = WalletDerivation.walletInfo(key)
            val hex = try { key.joinToString("") { "%02x".format(it) } } finally { key.fill(0) }
            val proposed = com.rjnr.pocketnode.data.database.entity.WalletEntity("child", "Child", "mnemonic",
                "m/44'/309'/1'/0/0", "parent", accountIndex = 1,
                mainnetAddress = info.mainnetAddress, testnetAddress = info.testnetAddress)
            f.phrase()
            assertEquals(WalletKeyWriter.Result.Success, f.writer.persistCreatedWallet(f.activity,
                WalletRepository.Creation(proposed), com.rjnr.pocketnode.data.crypto.WalletKeyBundle(hex, null), "mnemonic", false))
            assertEquals(proposed, f.db.walletDao().getById("child"))
            assertEquals("mnemonic", f.db.keyMaterialDao().getByWalletId("child")!!.walletType)
            f.phrase()
            val read = f.reader.readKeyMaterial(f.activity, "child", "Read", "Authenticate") as WalletKeyReader.MaterialResult.Success
            try { assertNull(read.mnemonic); assertEquals("raw_key", read.walletType); assertFalse(read.mnemonicBackedUp) }
            finally { read.privateKey.fill(0) }
        }
    }

    @Test fun `new namespace preserves opaque global backup and archived default source`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val backup = java.io.File(f.context.cacheDir, "integration-backups/default.enc")
            backup.parentFile!!.mkdirs()
            val opaque = byteArrayOf(5, 7, 11, 13)
            backup.writeBytes(opaque)
            val source = com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity("default", byteArrayOf(5), byteArrayOf(6), byteArrayOf(7), "mnemonic", false, 1, 2)
            f.db.keyMaterialDao().upsert(source)
            val archive = com.rjnr.pocketnode.data.database.entity.WalletVaultLegacyEntity("default", byteArrayOf(8), byteArrayOf(9), byteArrayOf(10), "mnemonic", false, 1, 2)
            f.db.walletVaultDao().archive(archive)
            f.phrase()
            assertEquals(WalletKeyWriter.Result.Success, f.writer.persistCreatedWallet(f.activity, f.creation(), f.bundle, "raw_key", false))
            assertArrayEquals(opaque, backup.readBytes())
            assertEquals(source, f.db.keyMaterialDao().getByWalletId("default"))
            assertEquals(archive.source(), f.db.walletVaultDao().legacy("default")!!.source())
            assertTrue(f.store.pendingLegacyCleanup().isEmpty())
            f.phrase()
            val read = f.reader.readPrivateKey(f.activity, "a", "Read", "Authenticate") as WalletKeyReader.Result.Success
            assertEquals(2, read.privateKey.last().toInt()); read.privateKey.fill(0)
        }
    }
    @Test fun `creation replay refuses overwrite of committed wallet`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val creation = f.creation(); f.phrase()
            assertEquals(WalletKeyWriter.Result.Success, f.writer.persistCreatedWallet(f.activity, creation, f.bundle, "raw_key", false))
            val original = f.db.walletVaultDao().get("a")!!
            f.phrase()
            assertTrue(f.writer.persistCreatedWallet(f.activity, creation, f.bundle, "raw_key", false) is WalletKeyWriter.Result.WriteFailed)
            assertArrayEquals(original.envelope, f.db.walletVaultDao().get("a")!!.envelope)
            assertEquals(creation.wallet, f.db.walletDao().getById("a"))
        }
    }
    @Test fun `source appearing during credential prompt blocks atomic creation`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val source = com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity("a", byteArrayOf(5), null, byteArrayOf(7), "raw_key", false, 1, 2)
            f.onCredential = { f.db.keyMaterialDao().upsert(source) }; f.phrase()
            assertTrue(f.writer.persistCreatedWallet(f.activity, f.creation(), f.bundle, "raw_key", false) is WalletKeyWriter.Result.WriteFailed)
            assertEquals(source, f.db.keyMaterialDao().getByWalletId("a"))
            assertNull(f.db.walletDao().getById("a")); assertNull(f.store.get("a")); assertFalse(f.store.hasProfile())
        }
    }
    @Test fun `scoped archive collision blocks new creation without retiring source`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val archive = com.rjnr.pocketnode.data.database.entity.WalletVaultLegacyEntity("a", byteArrayOf(8), byteArrayOf(9), byteArrayOf(10), "mnemonic", false, 1, 2)
            f.db.walletVaultDao().archive(archive); f.phrase()
            assertTrue(f.writer.persistCreatedWallet(f.activity, f.creation(), f.bundle, "raw_key", false) is WalletKeyWriter.Result.WriteFailed)
            assertEquals(archive.source(), f.db.walletVaultDao().legacy("a")!!.source())
            assertNull(f.db.walletDao().getById("a")); assertNull(f.store.get("a"))
        }
    }

    @Test fun `first wallet without device lock or app PIN has verified independent protection with metadata in the same commit`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.phrase()
            assertEquals(WalletKeyWriter.Result.Success, f.writer.persistCreatedWallet(f.activity, f.creation("a"), f.bundle, "raw_key", false))
            assertEquals(3, f.db.keyMaterialDao().getKdfVersion("a"))
            assertNotNull(f.db.walletDao().getById("a"))
            assertTrue(f.store.verifyPassphrase(f.pass.toCharArray()))
            f.phrase()
            val read = f.reader.readPrivateKey(f.activity, "a", "Send", "Authenticate") as WalletKeyReader.Result.Success
            assertEquals(2, read.privateKey.last().toInt()); read.privateKey.fill(0)
        }
    }
    @Test fun `cancelled first setup leaves neither profile nor wallet marker`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.phrase(null)
            assertEquals(WalletKeyWriter.Result.Cancelled, f.writer.persistCreatedWallet(f.activity, f.creation("a"), f.bundle, "raw_key", false))
            assertNull(f.store.get("a")); assertFalse(f.store.hasProfile())
        }
    }
    @Test fun `another wallet cannot enroll with a different installation passphrase`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.phrase(); assertEquals(WalletKeyWriter.Result.Success, f.writer.persistCreatedWallet(f.activity, f.creation("a"), f.bundle, "raw_key", false))
            val original = com.rjnr.pocketnode.data.vault.WalletEnvelopeCodec.encode(f.store.get("a")!!)
            f.phrase("another long wallet passphrase")
            assertTrue(f.writer.persistCreatedWallet(f.activity, f.creation("b"), f.bundle, "raw_key", false) is WalletKeyWriter.Result.WriteFailed)
            assertNull(f.store.get("b")); assertArrayEquals(original, com.rjnr.pocketnode.data.vault.WalletEnvelopeCodec.encode(f.store.get("a")!!))
        }
    }
    @Suppress("DEPRECATION") // Intentional fail-closed compatibility coverage.
    @Test fun `activity free fallback cannot create a weaker wallet`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            assertTrue(f.writer.persistNewWalletV1Fallback("a", f.bundle, "raw_key", false) is WalletKeyWriter.Result.WriteFailed)
            assertNull(f.db.keyMaterialDao().getByWalletId("a"))
        }
    }
}
