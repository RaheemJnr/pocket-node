package com.rjnr.pocketnode.data.wallet

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WalletVaultWriterIntegrationTest {
    @Test fun `first wallet without device lock or app PIN has verified independent protection before metadata insertion`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.phrase()
            assertEquals(WalletKeyWriter.Result.Success, f.writer.persistNewWallet(f.activity, "a", f.bundle, "raw_key", false))
            assertEquals(3, f.db.keyMaterialDao().getKdfVersion("a"))
            assertNull(f.db.walletDao().getById("a"))
            assertTrue(f.store.verifyPassphrase(f.pass.toCharArray()))
            f.wallet(); f.phrase()
            val read = f.reader.readPrivateKey(f.activity, "a", "Send", "Authenticate") as WalletKeyReader.Result.Success
            assertEquals(2, read.privateKey.last().toInt()); read.privateKey.fill(0)
        }
    }
    @Test fun `cancelled first setup leaves neither profile nor wallet marker`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.phrase(null)
            assertEquals(WalletKeyWriter.Result.Cancelled, f.writer.persistNewWallet(f.activity, "a", f.bundle, "raw_key", false))
            assertNull(f.store.get("a")); assertFalse(f.store.hasProfile())
        }
    }
    @Test fun `another wallet cannot enroll with a different installation passphrase`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.phrase(); assertEquals(WalletKeyWriter.Result.Success, f.writer.persistNewWallet(f.activity, "a", f.bundle, "raw_key", false))
            val original = com.rjnr.pocketnode.data.vault.WalletEnvelopeCodec.encode(f.store.get("a")!!)
            f.phrase("another long wallet passphrase")
            assertTrue(f.writer.persistNewWallet(f.activity, "b", f.bundle, "raw_key", false) is WalletKeyWriter.Result.WriteFailed)
            assertNull(f.store.get("b")); assertArrayEquals(original, com.rjnr.pocketnode.data.vault.WalletEnvelopeCodec.encode(f.store.get("a")!!))
        }
    }
    @Test fun `activity free fallback cannot create a weaker wallet`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            assertTrue(f.writer.persistNewWalletV1Fallback("a", f.bundle, "raw_key", false) is WalletKeyWriter.Result.WriteFailed)
            assertNull(f.db.keyMaterialDao().getByWalletId("a"))
        }
    }
}
