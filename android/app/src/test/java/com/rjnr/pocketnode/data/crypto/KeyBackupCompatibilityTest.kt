package com.rjnr.pocketnode.data.crypto

import com.rjnr.pocketnode.core.crypto.hexToByteArray
import com.rjnr.pocketnode.core.log.NoopLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins a v2 (Argon2id) backup blob produced by the pre-#523 BouncyCastle
 * `Argon2BytesGenerator` KDF, and proves [KeyBackupManager] still restores it
 * after #523 switched `deriveKeyArgon2` to the shared `core.crypto.Argon2id`.
 *
 * Without this, a KDF swap that quietly produces different key bytes at the
 * same (salt, PIN, cost params) would lock every user with an existing v2
 * backup out of their encrypted key material, the differential test in
 * `:shared`'s `Argon2idDifferentialTest` only proves the two KDFs agree in
 * isolation, not that this class's byte-for-byte blob format round-trips.
 *
 * ## Reproducing the fixture
 * [FIXTURE_HEX] was captured once, before the #523 change, by temporarily
 * adding `testImplementation(libs.bouncycastle)` to `app/build.gradle.kts`
 * and running a throwaway JUnit test that:
 * 1. Built [KeyMaterial] with [FIXTURE_PRIVATE_KEY], [FIXTURE_MNEMONIC],
 *    `walletType = "mnemonic"`, `mnemonicBackedUp = true`,
 *    `createdAt = "2026-01-01T00:00:00Z"`, and serialized it with the same
 *    `Json { ignoreUnknownKeys = true; encodeDefaults = true }` config
 *    `KeyBackupManager` uses.
 * 2. Derived the AES-256 key with the OLD `deriveKeyArgon2` body verbatim
 *    (`org.bouncycastle.crypto.generators.Argon2BytesGenerator`,
 *    `Argon2Parameters.ARGON2_id`, version 0x13) at the exact production
 *    cost factors (t=3, m=65536 KiB, p=4) against a fixed 16-byte
 *    [FIXTURE_SALT] (`salt[i] = i * 3 + 1`, matching `SALT_SIZE`).
 * 3. Encrypted the serialized plaintext with `AES/GCM/NoPadding` under a
 *    fixed 12-byte [FIXTURE_IV] (`iv[i] = i * 5 + 2`, matching `IV_SIZE`)
 *    and `GCM_TAG_BITS`.
 * 4. Concatenated `MAGIC + FORMAT_VERSION_ARGON2 + salt + iv + ciphertext`,
 *    exactly as `writeBackup` does, and hex-encoded the result.
 * The salt and IV are fixed inputs chosen for the fixture, not extracted
 * from a real backup; PIN and plaintext are throwaway test values, not
 * secrets from any real wallet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KeyBackupCompatibilityTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private companion object {
        const val FIXTURE_PIN = "204817"
        const val FIXTURE_PRIVATE_KEY = "a1b2c3d4e5f60718293a4b5c6d7e8f90112233445566778899aabbccddeeff"
        const val FIXTURE_MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

        // v2 backup blob for FIXTURE_PIN, produced by the pre-#523 BouncyCastle
        // Argon2id KDF at production cost factors. See the class doc for how
        // this was captured.
        const val FIXTURE_HEX =
            "504e424b020104070a0d101316191c1f2225282b2e02070c11161b20252a2f343983ad681677f9d3c548" +
                "28fab5e9e40658b0a34d84a45cc7420190ee6692ebc00ca190f982ef6c9c0b1c435ebd5c8fe0b8803ba" +
                "e366ffd9eca25b2a93bdec0b2d4bd7270832e182bb2b9e4e1425c0034b7963a0119a96bb70144600c37" +
                "6e95600089b2e75355720131842d4922ac9bc95387ff73c3b5e7984c54d57858835188759462ac9e3ae" +
                "77ff9dc4857b43b4603c3a6a07756ce31b64ae909cd642bd9bc1a4f19c6f3a89bceb7f1311fe520845a5" +
                "b80b5dfb481f90811053e62f5f5955edb6d490d31d7cbb92fb9507e75deee224e8796604bb7a705c6cc" +
                "7ee54e31909c5d515e78c6b3b20fb386d13fc3ff7e81b6d9419a7c5d5ea0fd2517917257085233f3e18" +
                "289154c10a92421568bff2874f68a2dc382dbe0f094f9844276ca3d9d32e0fedf928ff1e8fe80"
    }

    @Test
    fun `restores a v2 backup blob written by the old BouncyCastle KDF`() {
        val manager = KeyBackupManager(tempDir.root, NoopLogger)
        val blob = FIXTURE_HEX.hexToByteArray()
        java.io.File(tempDir.root, "fixture.enc").writeBytes(blob)

        val result = manager.readBackup("fixture", FIXTURE_PIN.toCharArray())

        assertNotNull(result)
        assertEquals(FIXTURE_PRIVATE_KEY, result!!.privateKey)
        assertEquals(FIXTURE_MNEMONIC, result.mnemonic)
        assertEquals("mnemonic", result.walletType)
        assertEquals(true, result.mnemonicBackedUp)
        assertEquals("2026-01-01T00:00:00Z", result.createdAt)
    }
}
