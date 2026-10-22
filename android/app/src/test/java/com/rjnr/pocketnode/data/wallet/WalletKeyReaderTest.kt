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
            val reader = WalletKeyReader(dao, mockk(), encryption, auth, mockk(), NoopLogger)
            val activity = mockk<FragmentActivity>()
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
}
