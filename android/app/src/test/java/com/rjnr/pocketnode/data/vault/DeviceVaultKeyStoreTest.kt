package com.rjnr.pocketnode.data.vault

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.data.auth.AuthManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class DeviceVaultKeyStoreTest {
    private val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
    private val crypto = WalletEnvelopeCrypto()
    private val envelope = crypto.create("wallet", 3, byteArrayOf(1, 2, 3), "long wallet passphrase".toCharArray())
    private val dataKey = crypto.unwrapDataKey(envelope, "wallet", "long wallet passphrase".toCharArray())
    private val aliases = mutableMapOf<String, SecretKeySpec>()
    private var created = 0
    private var authenticated = 0
    private var authSuccess = true
    private var wrongCipher = false
    private val backend = object : VaultDeviceCipherStore {
        override fun unavailableReason(): String? = null
        override fun encryptionCipher(alias: String): Cipher {
            created++; assertFalse(aliases.containsKey(alias))
            val key = SecretKeySpec(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }, "AES")
            aliases[alias] = key
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        }
        override fun decryptionCipher(alias: String, nonce: ByteArray): Cipher? = aliases[alias]?.let {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, it, GCMParameterSpec(128, nonce)) }
        }
    }
    private val device = DeviceVaultKeyStore(backend, VaultCipherAuthenticator { _, cipher, _, _ ->
        authenticated++
        if (authSuccess) AuthManager.CipherAuthResult.Success(if (wrongCipher) Cipher.getInstance("AES/GCM/NoPadding") else cipher) else AuthManager.CipherAuthResult.Cancelled
    })
    @Test fun `device wrap binds payload metadata and new aliases preserve older wrap`() = runBlocking {
        val wrap = (device.wrap(activity, envelope, dataKey) as DeviceVaultResult.Success).value
        val second = (device.wrap(activity, envelope, dataKey) as DeviceVaultResult.Success).value
        assertNotEquals(wrap.alias, second.alias); assertEquals(2, aliases.size)
        val restored = (device.unwrap(activity, envelope.copy(deviceWrap = wrap), "Read", "Auth") as DeviceVaultResult.Success).value
        assertArrayEquals(dataKey, restored); restored.fill(0)
        assertTrue(device.unwrap(activity, envelope.copy(generation = 4, deviceWrap = wrap), "Read", "Auth") is DeviceVaultResult.Unavailable)
        assertEquals(4, authenticated)
    }
    @Test fun `success for an unrelated cipher cannot authorize a wrap`() = runBlocking {
        wrongCipher = true
        assertTrue(device.wrap(activity, envelope, dataKey) is DeviceVaultResult.Unavailable)
    }
    @Test fun `missing decrypt key never generates replacement and cancelled crypto auth cannot wrap`() = runBlocking {
        val missing = envelope.copy(deviceWrap = DeviceKeyWrap("missing", ByteArray(48), ByteArray(12)))
        assertTrue(device.unwrap(activity, missing, "Read", "Auth") is DeviceVaultResult.Unavailable)
        assertEquals(0, created); assertEquals(0, authenticated)
        authSuccess = false
        assertEquals(DeviceVaultResult.Cancelled, device.wrap(activity, envelope, dataKey))
    }
}
