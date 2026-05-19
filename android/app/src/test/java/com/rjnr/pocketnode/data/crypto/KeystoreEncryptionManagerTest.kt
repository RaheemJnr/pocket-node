package com.rjnr.pocketnode.data.crypto

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KeystoreEncryptionManagerTest {

    private lateinit var manager: KeystoreEncryptionManager

    @Before
    fun setUp() {
        manager = KeystoreEncryptionManager.createForTest()
    }

    private fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = manager.newEncryptCipher()
        return manager.encryptWithCipher(cipher, plaintext)
    }

    private fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = manager.newDecryptCipher(iv)
        return manager.decryptWithCipher(cipher, ciphertext)
    }

    @Test
    fun `encrypt and decrypt round-trip`() {
        val plaintext = "hello world private key".toByteArray()
        val (ciphertext, iv) = encrypt(plaintext)

        assertFalse(ciphertext.contentEquals(plaintext))
        assertEquals(12, iv.size)

        val decrypted = decrypt(ciphertext, iv)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt produces different ciphertext each time`() {
        val plaintext = "same input".toByteArray()
        val (ct1, _) = encrypt(plaintext)
        val (ct2, _) = encrypt(plaintext)

        assertFalse(ct1.contentEquals(ct2))
    }

    @Test
    fun `decrypt with wrong IV fails`() {
        val plaintext = "secret".toByteArray()
        val (ciphertext, _) = encrypt(plaintext)
        val wrongIv = ByteArray(12) { 0xFF.toByte() }

        try {
            decrypt(ciphertext, wrongIv)
            fail("Should throw on wrong IV")
        } catch (e: Exception) {
            // Expected
        }
    }

    @Test
    fun `encrypt empty data works`() {
        val plaintext = ByteArray(0)
        val (ciphertext, iv) = encrypt(plaintext)
        val decrypted = decrypt(ciphertext, iv)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypt large data works`() {
        val plaintext = ByteArray(10_000) { (it % 256).toByte() }
        val (ciphertext, iv) = encrypt(plaintext)
        val decrypted = decrypt(ciphertext, iv)
        assertArrayEquals(plaintext, decrypted)
    }

    // -- V2 (auth-bound) API --
    //
    // In Robolectric the V2 key is a raw AES key (no real Keystore), so the
    // OS-level auth requirement is not exercised here. These tests cover the
    // structural side: V2 ciphers round-trip correctly, are independent of V1,
    // and reject mismatched ciphertext.

    private fun encryptV2(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = manager.newEncryptCipherV2()
        return manager.encryptWithCipher(cipher, plaintext)
    }

    private fun decryptV2(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = manager.newDecryptCipherV2(iv)
        return manager.decryptWithCipher(cipher, ciphertext)
    }

    @Test
    fun `V2 encrypt and decrypt round-trip`() {
        val plaintext = "v2 wallet key material".toByteArray()
        val (ciphertext, iv) = encryptV2(plaintext)

        assertFalse(ciphertext.contentEquals(plaintext))
        assertEquals(12, iv.size)

        val decrypted = decryptV2(ciphertext, iv)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `V2 key is independent of V1 key`() {
        // A ciphertext produced under V1 must not decrypt under V2, and vice
        // versa. This guards against accidentally using the same key for both
        // aliases (which would silently downgrade V2's security guarantees).
        val plaintext = "isolation check".toByteArray()
        val (ctV1, ivV1) = encrypt(plaintext)
        val (ctV2, ivV2) = encryptV2(plaintext)

        // Cross-decrypt attempts should fail.
        try {
            decryptV2(ctV1, ivV1)
            fail("V2 should not decrypt V1 ciphertext")
        } catch (e: Exception) {
            // Expected — AES-GCM authentication tag mismatch
        }

        try {
            decrypt(ctV2, ivV2)
            fail("V1 should not decrypt V2 ciphertext")
        } catch (e: Exception) {
            // Expected
        }
    }

    @Test
    fun `V2 encrypt produces different ciphertext each time`() {
        val plaintext = "same input".toByteArray()
        val (ct1, _) = encryptV2(plaintext)
        val (ct2, _) = encryptV2(plaintext)

        assertFalse(ct1.contentEquals(ct2))
    }
}
