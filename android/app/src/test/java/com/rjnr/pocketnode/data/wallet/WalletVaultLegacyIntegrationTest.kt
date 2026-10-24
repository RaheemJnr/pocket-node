package com.rjnr.pocketnode.data.wallet

import com.rjnr.pocketnode.data.crypto.KeyMaterialUnreadableException
import org.junit.Assert.*
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WalletVaultLegacyIntegrationTest {
    @Test fun `background during legacy biometric extraction cancels migration and preserves source`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet()
            f.v2.writeNewV2Row("a", f.bundle, f.encryption.newEncryptCipherV2(), "raw_key", false).getOrThrow()
            val source = f.db.keyMaterialDao().getByWalletId("a")
            io.mockk.every { f.auth.isBiometricEnrolled() } returns true
            val pending = kotlinx.coroutines.CompletableDeferred<Unit>()
            io.mockk.coEvery { f.auth.authenticateForCipher(any(), any(), any(), any(), any(), any()) } coAnswers {
                pending.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
            val operation = kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext).launch {
                f.reader.readPrivateKey(f.activity, "a", "Send", "Authenticate")
            }
            pending.await(); f.activityController.pause().stop()
            kotlinx.coroutines.withTimeout(1_000) { operation.join() }
            assertTrue(operation.isCancelled)
            assertEquals(source, f.db.keyMaterialDao().getByWalletId("a")); assertNull(f.store.get("a"))
        }
    }

    @Test fun `vault and unknown rows never enter legacy decrypt or repair`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            for (version in listOf(3, 99)) {
                val row = com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity("a", byteArrayOf(7), null, byteArrayOf(8), "raw_key", true, 1, version)
                f.db.keyMaterialDao().upsert(row)
                try {
                    f.legacy.readDecryptedKey("a")
                    fail("Unsupported row was treated as an absent legacy row")
                } catch (_: KeyMaterialUnreadableException) { }
                assertEquals(row, f.db.keyMaterialDao().getByWalletId("a"))
            }
        }
    }
    @Test fun `subaccount migrates actual key capabilities and preserves origin metadata`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            val wallet = f.wallet(type = "mnemonic", path = "m/44'/309'/1'/0/0", parent = "parent")
            f.legacy.migrateWallet("a", f.material.privateKey, null, "mnemonic", true)
            f.phrase()
            val migrated = f.reader.readKeyMaterial(f.activity, "a", "Sign", "Authenticate") as WalletKeyReader.MaterialResult.Success
            try { assertEquals("raw_key", migrated.walletType); assertNull(migrated.mnemonic) }
            finally { migrated.privateKey.fill(0) }
            assertEquals(wallet, f.db.walletDao().getById("a"))
            f.phrase()
            val next = f.reader.readKeyMaterial(f.activity, "a", "Sign", "Authenticate") as WalletKeyReader.MaterialResult.Success
            try { assertEquals("raw_key", next.walletType); assertNull(next.mnemonic) }
            finally { next.privateKey.fill(0) }
        }
    }
}
