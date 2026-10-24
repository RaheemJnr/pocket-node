package com.rjnr.pocketnode.data.wallet

import com.rjnr.pocketnode.data.crypto.WalletKeyBundle
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import io.mockk.coEvery
import io.mockk.every
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WalletBackupWitnessIntegrationTest {
    @Test fun `actual V1 V2 migration and V3 reader bind backup witness to verified protected source`() = walletOperationTest {
        for (version in listOf(1, 2, 3)) WalletVaultIntegrationFixture().use { f ->
            val words = List(11) { "abandon" } + "about"
            val key = MnemonicManager().mnemonicToPrivateKey(words)
            val info = WalletDerivation.walletInfo(key)
            val hex = try { key.joinToString("") { "%02x".format(it) } } finally { key.fill(0) }
            val wallet = WalletEntity("seed", "Seed", "mnemonic", "m/44'/309'/0'/0/0", null,
                mainnetAddress = info.mainnetAddress, testnetAddress = info.testnetAddress)
            val bundle = WalletKeyBundle(hex, words.joinToString(" "))
            if (version == 3) {
                f.phrase()
                assertEquals(WalletKeyWriter.Result.Success, f.writer.persistCreatedWallet(f.activity, WalletRepository.Creation(wallet), bundle, "mnemonic", false))
            } else {
                f.db.walletDao().insert(wallet)
                if (version == 1) f.legacy.migrateWallet("seed", hex, bundle.mnemonic, "mnemonic", false)
                else {
                    f.v2.writeNewV2Row("seed", bundle, f.encryption.newEncryptCipherV2(), "mnemonic", false).getOrThrow()
                    every { f.auth.isBiometricEnrolled() } returns true
                    coEvery { f.auth.authenticateForCipher(any(), any(), any(), any(), any(), any()) } coAnswers {
                        com.rjnr.pocketnode.data.auth.AuthManager.CipherAuthResult.Success(secondArg())
                    }
                }
            }
            f.phrase()
            val read = f.reader.readKeyMaterial(f.activity, "seed", "Backup", "Authenticate") as WalletKeyReader.MaterialResult.Success
            try {
                val witness = read.backupWitness
                assertNotNull("Verified V$version read needs a source-bound backup witness", witness)
                assertTrue(witness!!.matches(f.db.walletDao().getById("seed"), f.db.keyMaterialDao().getByWalletId("seed"), f.db.walletVaultDao().get("seed"), f.db.walletVaultDao().profile()))
                assertFalse(witness.toString().contains("abandon"))
                val vault = f.db.walletVaultDao().get("seed")!!
                assertFalse(witness.matches(wallet, f.db.keyMaterialDao().getByWalletId("seed"), vault.copy(generation = vault.generation + 1), f.db.walletVaultDao().profile()))
            } finally { read.privateKey.fill(0) }
        }
    }
}
