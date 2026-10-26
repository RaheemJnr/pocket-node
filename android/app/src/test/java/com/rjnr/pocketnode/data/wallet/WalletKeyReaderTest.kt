package com.rjnr.pocketnode.data.wallet

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.fragment.app.FragmentActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity
import io.mockk.*
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStoreException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WalletKeyReaderTest {
    @Test
    fun `reader success diagnostics redact mnemonic and signing key`() {
        val phrase = "public disposable phrase never print"
        val key = ByteArray(32) { 42 }
        val signing = WalletKeyReader.Result.Success(key).toString()
        val material = WalletKeyReader.MaterialResult.Success(key, phrase, "mnemonic", true).toString()
        assertFalse(material.contains(phrase))
        assertFalse(material.contains("42"))
        assertFalse(signing.contains("42"))
    }

    @Test
    fun `legacy software key cannot be released before verified passphrase protection`() = runTest {
        val dao = mockk<com.rjnr.pocketnode.data.database.dao.KeyMaterialDao>()
        val row = KeyMaterialEntity("wallet", byteArrayOf(1), null, ByteArray(12), "raw_key", false, 0, 1)
        coEvery { dao.getByWalletId("wallet") } returns row
        val helper = mockk<com.rjnr.pocketnode.data.crypto.KeyStoreMigrationHelper>()
        coEvery { helper.readDecryptedKey("wallet") } returns com.rjnr.pocketnode.data.crypto.DecryptedKeyData(
            "00".repeat(31) + "01", null, "raw_key", false)
        val pin = mockk<com.rjnr.pocketnode.data.auth.PinManager>()
        every { pin.hasPin() } returns true
        val prompt = mockk<com.rjnr.pocketnode.data.vault.VaultPrompt>()
        coEvery { prompt.requestLegacyPin(any(), any(), any()) } returns null
        val reader = WalletKeyReader(dao, helper, mockk(), mockk(), mockk(relaxed = true), mockk(), pin, prompt,
            WalletUnlockPreference(ApplicationProvider.getApplicationContext()))
        val result = reader.readPrivateKey(org.robolectric.Robolectric.buildActivity(FragmentActivity::class.java).setup().get(), "wallet", "Send", "Authenticate")
        assertFalse("Legacy software key escaped before wallet passphrase setup", result is WalletKeyReader.Result.Success)
    }

    @Test
    fun `missing or invalidated key returns recovery result for signing and material without changing wallet`() = runTest {
        val failures = listOf(
            KeyPermanentlyInvalidatedException(),
            java.security.InvalidKeyException(KeyPermanentlyInvalidatedException()),
            InvalidAlgorithmParameterException(IllegalStateException(
                "Secure lock screen must be enabled to create keys requiring user authentication"
            )),
            IllegalStateException("Secure lock screen must be enabled"),
        )
        for (failure in failures) {
            checkFailure(failure, invalidated = true)
        }
    }

    @Test
    fun `temporary keystore failure returns unavailable without claiming wallet is invalidated`() = runTest {
        checkFailure(KeyStoreException("Keystore unavailable"), invalidated = false)
    }

    private suspend fun checkFailure(failure: Exception, invalidated: Boolean) {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val dao = db.keyMaterialDao()
            val row = KeyMaterialEntity("wallet", byteArrayOf(1, 2, 3), null,
                ByteArray(12), "mnemonic", true, 123L, 2)
            dao.upsert(row)
            val encryption = mockk<KeystoreEncryptionManager>()
            every { encryption.newDecryptCipherV2(any()) } throws failure
            val auth = mockk<AuthManager>()
            val reader = WalletKeyReader(dao, mockk(), encryption, auth, mockk(relaxed = true), mockk(), mockk(), mockk(), WalletUnlockPreference(ApplicationProvider.getApplicationContext()))
            val activity = org.robolectric.Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
            val key = reader.readPrivateKey(activity, "wallet", "Send", "Authenticate")
            val material = reader.readKeyMaterial(activity, "wallet", "Reveal", "Authenticate")
            if (invalidated) {
                assertEquals(WalletKeyReader.Result.KeyInvalidated, key)
                assertEquals(WalletKeyReader.MaterialResult.KeyInvalidated, material)
            } else {
                assertTrue(key is WalletKeyReader.Result.NotAvailable)
                assertTrue(material is WalletKeyReader.MaterialResult.NotAvailable)
            }
            assertEquals(row, dao.getByWalletId("wallet"))
            coVerify(exactly = 0) { auth.authenticateForCipher(any(), any(), any(), any()) }
        } finally {
            db.close()
        }
    }
    @Test fun `replacement PIN cannot authorize reading a legacy software source`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pin = com.rjnr.pocketnode.data.auth.PinManager(context,
            com.rjnr.pocketnode.data.crypto.Blake2b(), NoopLogger).apply {
            testPrefs = context.getSharedPreferences("entry_legacy_pin", Context.MODE_PRIVATE)
            argon2Iterations = 1; argon2MemoryKb = 8; argon2Parallelism = 1
        }
        pin.setPin("123456")
        runCatching { pin.setPin("654321") }
        val dao = mockk<com.rjnr.pocketnode.data.database.dao.KeyMaterialDao>()
        coEvery { dao.getByWalletId("fixture") } returns KeyMaterialEntity(
            "fixture", byteArrayOf(1), null, ByteArray(12), "raw_key", false, 0, 1)
        val legacy = mockk<com.rjnr.pocketnode.data.crypto.KeyStoreMigrationHelper>()
        var readAttempted = false
        coEvery { legacy.readDecryptedKey("fixture") } answers { readAttempted = true; null }
        val prompt = mockk<com.rjnr.pocketnode.data.vault.VaultPrompt>()
        coEvery { prompt.requestLegacyPin(any(), any(), any()) } returns "654321".toCharArray()
        val reader = WalletKeyReader(dao, legacy, mockk(), mockk(), mockk(relaxed = true), mockk(), pin,
            prompt, WalletUnlockPreference(context))
        val activity = org.robolectric.Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val result = reader.readPrivateKey(activity, "fixture", "Fixture", "Fixture")
        assertTrue(result is WalletKeyReader.Result.NotAvailable)
        assertFalse("Replacement PIN passed the legacy source authorization gate", readAttempted)
    }

}
