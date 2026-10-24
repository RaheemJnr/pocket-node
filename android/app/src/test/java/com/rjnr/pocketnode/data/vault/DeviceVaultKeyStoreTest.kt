package com.rjnr.pocketnode.data.vault

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.data.auth.AuthManager
import kotlinx.coroutines.runBlocking
import io.mockk.*
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
    private var observedMode: VaultDevicePromptMode? = null
    private val backend = object : VaultDeviceCipherStore {
        override fun unavailableReason(): String? = null
        override fun availablePromptModes() = setOf(VaultDevicePromptMode.BIOMETRIC_ONLY, VaultDevicePromptMode.CREDENTIAL_ONLY)
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
    private val device = DeviceVaultKeyStore(backend, VaultCipherAuthenticator { _, cipher, _, _, mode ->
        authenticated++
        observedMode = mode
        if (authSuccess) AuthManager.CipherAuthResult.Success(if (wrongCipher) Cipher.getInstance("AES/GCM/NoPadding") else cipher) else AuthManager.CipherAuthResult.Cancelled
    })
    @Test fun `AAD and finalization wait for the exact authenticated cipher in both directions`() = runBlocking {
        for (mode in listOf(VaultDevicePromptMode.BIOMETRIC_ONLY, VaultDevicePromptMode.CREDENTIAL_ONLY)) {
            // API26 intentionally rejects credential-only; the API30 override below covers it.
            if (mode == VaultDevicePromptMode.CREDENTIAL_ONLY && android.os.Build.VERSION.SDK_INT < 30) continue
            for (decrypt in listOf(false, true)) {
                for (outcome in listOf("success", "cancel", "different")) {
                    var authorized = false
                    var aadCalls = 0
                    var finalCalls = 0
                    val cipher = mockk<Cipher>()
                    every { cipher.iv } returns ByteArray(12)
                    every { cipher.updateAAD(any<ByteArray>()) } answers {
                        aadCalls++
                        check(authorized) { "AAD submitted before per-use authentication" }
                    }
                    every { cipher.doFinal(any<ByteArray>()) } answers {
                        check(authorized && aadCalls == 1)
                        finalCalls++
                        ByteArray(if (decrypt) 32 else 48)
                    }
                    val gatedBackend = object : VaultDeviceCipherStore {
                        override fun unavailableReason(): String? = null
                        override fun availablePromptModes() = setOf(mode)
                        override fun encryptionCipher(alias: String) = cipher
                        override fun decryptionCipher(alias: String, nonce: ByteArray) = cipher
                    }
                    val gated = DeviceVaultKeyStore(gatedBackend, VaultCipherAuthenticator { _, actual, _, _, _ ->
                        assertSame(cipher, actual)
                        when (outcome) {
                            "cancel" -> AuthManager.CipherAuthResult.Cancelled
                            "different" -> AuthManager.CipherAuthResult.Success(mockk<Cipher>())
                            else -> { authorized = true; AuthManager.CipherAuthResult.Success(cipher) }
                        }
                    })
                    val result = if (decrypt) gated.unwrap(activity,
                        envelope.copy(deviceWrap = DeviceKeyWrap("public-fixture", ByteArray(48), ByteArray(12))), "Read", "Auth", mode)
                    else gated.wrap(activity, envelope, dataKey, mode)
                    if (outcome == "success") {
                        assertTrue("Authenticated $mode decrypt=$decrypt must succeed", result is DeviceVaultResult.Success)
                        assertEquals(1, aadCalls); assertEquals(1, finalCalls)
                    } else {
                        assertFalse(result is DeviceVaultResult.Success)
                        assertEquals(0, aadCalls); assertEquals(0, finalCalls)
                    }
                }
            }
        }
    }

    @Test @Config(sdk = [30]) fun `credential ciphers enforce authenticated AAD ordering`() {
        `AAD and finalization wait for the exact authenticated cipher in both directions`()
    }

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
    @Test @Config(sdk = [30]) fun `explicit credential mode reaches the exact crypto authentication prompt`() = runBlocking {
        val wrap = (device.wrap(activity, envelope, dataKey, VaultDevicePromptMode.CREDENTIAL_ONLY) as DeviceVaultResult.Success).value
        assertEquals(VaultDevicePromptMode.CREDENTIAL_ONLY, observedMode)
        val key = (device.unwrap(activity, envelope.copy(deviceWrap = wrap), "Read", "Auth", VaultDevicePromptMode.CREDENTIAL_ONLY) as DeviceVaultResult.Success).value
        try { assertArrayEquals(dataKey, key); assertEquals(VaultDevicePromptMode.CREDENTIAL_ONLY, observedMode) }
        finally { key.fill(0) }
    }
    @Test fun `API26 rejects explicit credential mode without creating or authenticating a key`() = runBlocking {
        assertTrue(device.wrap(activity, envelope, dataKey, VaultDevicePromptMode.CREDENTIAL_ONLY) is DeviceVaultResult.Unavailable)
        assertEquals(0, created); assertEquals(0, authenticated)
    }
    @Test fun `missing decrypt key never generates replacement and cancelled crypto auth cannot wrap`() = runBlocking {
        val missing = envelope.copy(deviceWrap = DeviceKeyWrap("missing", ByteArray(48), ByteArray(12)))
        assertTrue(device.unwrap(activity, missing, "Read", "Auth") is DeviceVaultResult.Unavailable)
        assertEquals(0, created); assertEquals(0, authenticated)
        authSuccess = false
        assertEquals(DeviceVaultResult.Cancelled, device.wrap(activity, envelope, dataKey))
    }
}
