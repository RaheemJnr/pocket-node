package com.rjnr.pocketnode.data.wallet

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class KeyBackupManagerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var manager: KeyBackupManager

    private val testPin = "123456".toCharArray()
    private val wrongPin = "999999".toCharArray()

    private fun mnemonicMaterial() = KeyMaterial(
        privateKey = "a".repeat(64),
        mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        walletType = "mnemonic",
        mnemonicBackedUp = true,
        createdAt = "2026-01-01T00:00:00Z"
    )

    private fun rawKeyMaterial() = KeyMaterial(
        privateKey = "b".repeat(64),
        mnemonic = null,
        walletType = "raw_key",
        mnemonicBackedUp = false,
        createdAt = "2026-01-01T00:00:00Z"
    )

    @Before
    fun setUp() {
        manager = KeyBackupManager(tempDir.root)
        // Use low cost factors for fast tests. Production uses PBKDF2 600_000
        // (legacy reads) and Argon2id 64 MB / t=3 / p=4 (current writes).
        manager.kdfIterations = 1_000
        manager.argon2Iterations = 1
        manager.argon2MemoryKb = 8
        manager.argon2Parallelism = 1
    }

    @Test
    fun `new backups are written with Argon2id (format v2)`() {
        manager.writeBackup("wallet1", mnemonicMaterial(), testPin)
        val versionByte = File(tempDir.root, "wallet1.enc").readBytes()[4]
        assertEquals(KeyBackupManager.FORMAT_VERSION_ARGON2, versionByte)
    }

    @Test
    fun `legacy PBKDF2 (v1) backups remain readable`() {
        // Hand-craft a v1 blob: PBKDF2-HMAC-SHA256 + AES-256-GCM, the exact
        // format prior versions wrote. Proves the version byte dispatch keeps
        // existing user backups recoverable after the Argon2id upgrade.
        val material = mnemonicMaterial()
        val salt = ByteArray(KeyBackupManager.SALT_SIZE) { it.toByte() }
        val iv = ByteArray(KeyBackupManager.IV_SIZE) { (it + 1).toByte() }
        val plaintext = kotlinx.serialization.json.Json
            .encodeToString(KeyMaterial.serializer(), material)
            .toByteArray(Charsets.UTF_8)
        val spec = javax.crypto.spec.PBEKeySpec(testPin, salt, 1_000, KeyBackupManager.KEY_SIZE_BITS)
        val secret = javax.crypto.SecretKeyFactory
            .getInstance(KeyBackupManager.KDF_ALGORITHM).generateSecret(spec)
        val key = javax.crypto.spec.SecretKeySpec(secret.encoded, "AES")
        val cipher = javax.crypto.Cipher.getInstance(KeyBackupManager.CIPHER_TRANSFORM)
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE, key,
            javax.crypto.spec.GCMParameterSpec(KeyBackupManager.GCM_TAG_BITS, iv)
        )
        val ciphertext = cipher.doFinal(plaintext)
        File(tempDir.root, "legacy.enc").outputStream().use { out ->
            out.write(KeyBackupManager.MAGIC)
            out.write(byteArrayOf(KeyBackupManager.FORMAT_VERSION_PBKDF2))
            out.write(salt)
            out.write(iv)
            out.write(ciphertext)
        }

        val result = manager.readBackup("legacy", testPin)
        assertNotNull(result)
        assertEquals(material.privateKey, result!!.privateKey)
        assertEquals(material.mnemonic, result.mnemonic)
    }

    @Test
    fun `writeBackup and readBackup round-trip for mnemonic wallet`() {
        val material = mnemonicMaterial()
        manager.writeBackup("wallet1", material, testPin)

        val result = manager.readBackup("wallet1", testPin)

        assertNotNull(result)
        assertEquals(material.privateKey, result!!.privateKey)
        assertEquals(material.mnemonic, result.mnemonic)
        assertEquals(material.walletType, result.walletType)
        assertEquals(material.mnemonicBackedUp, result.mnemonicBackedUp)
        assertEquals(material.createdAt, result.createdAt)
        assertEquals(material.version, result.version)
    }

    @Test
    fun `writeBackup and readBackup round-trip for raw-key wallet`() {
        val material = rawKeyMaterial()
        manager.writeBackup("wallet_raw", material, testPin)

        val result = manager.readBackup("wallet_raw", testPin)

        assertNotNull(result)
        assertNull(result!!.mnemonic)
        assertEquals("raw_key", result.walletType)
        assertEquals(material.privateKey, result.privateKey)
        assertFalse(result.mnemonicBackedUp)
    }

    @Test
    fun `readBackup with wrong PIN returns null`() {
        manager.writeBackup("wallet1", mnemonicMaterial(), testPin)

        val result = manager.readBackup("wallet1", wrongPin)

        assertNull(result)
    }

    @Test
    fun `readBackup for nonexistent wallet returns null`() {
        val result = manager.readBackup("does_not_exist", testPin)
        assertNull(result)
    }

    @Test
    fun `hasBackup returns false initially`() {
        assertFalse(manager.hasBackup("wallet1"))
    }

    @Test
    fun `hasBackup returns true after write`() {
        manager.writeBackup("wallet1", mnemonicMaterial(), testPin)
        assertTrue(manager.hasBackup("wallet1"))
    }

    @Test
    fun `deleteBackup removes the file`() {
        manager.writeBackup("wallet1", mnemonicMaterial(), testPin)
        assertTrue(manager.hasBackup("wallet1"))

        manager.deleteBackup("wallet1")
        assertFalse(manager.hasBackup("wallet1"))
    }

    @Test
    fun `hasAnyBackups returns false when empty`() {
        assertFalse(manager.hasAnyBackups())
    }

    @Test
    fun `hasAnyBackups returns true when backups exist`() {
        manager.writeBackup("wallet1", mnemonicMaterial(), testPin)
        assertTrue(manager.hasAnyBackups())
    }

    @Test
    fun `reEncryptAll re-encrypts with new PIN`() {
        val newPin = "654321".toCharArray()
        manager.writeBackup("wallet1", mnemonicMaterial(), testPin)
        manager.writeBackup("wallet2", rawKeyMaterial(), testPin)

        val success = manager.reEncryptAll(testPin, newPin)

        assertTrue(success)
        // Old PIN should no longer work
        assertNull(manager.readBackup("wallet1", testPin))
        assertNull(manager.readBackup("wallet2", testPin))
        // New PIN should work
        assertNotNull(manager.readBackup("wallet1", newPin))
        assertNotNull(manager.readBackup("wallet2", newPin))
    }

    @Test
    fun `reEncryptAll returns false if old PIN is wrong`() {
        manager.writeBackup("wallet1", mnemonicMaterial(), testPin)

        val success = manager.reEncryptAll(wrongPin, "newpin".toCharArray())

        assertFalse(success)
        // Original backup should still be readable with the correct PIN
        assertNotNull(manager.readBackup("wallet1", testPin))
    }

    @Test
    fun `cleanupOrphanedTmpFiles removes tmp files`() {
        // Create fake .tmp files
        File(tempDir.root, "wallet1.enc.tmp").writeText("junk")
        File(tempDir.root, "orphan.tmp").writeText("junk")
        // Create a real .enc file that should survive
        manager.writeBackup("wallet1", mnemonicMaterial(), testPin)

        manager.cleanupOrphanedTmpFiles()

        assertFalse(File(tempDir.root, "wallet1.enc.tmp").exists())
        assertFalse(File(tempDir.root, "orphan.tmp").exists())
        assertTrue(manager.hasBackup("wallet1"))
    }

    @Test
    fun `listBackupWalletIds returns correct IDs`() {
        manager.writeBackup("alpha", mnemonicMaterial(), testPin)
        manager.writeBackup("beta", rawKeyMaterial(), testPin)

        val ids = manager.listBackupWalletIds()

        assertEquals(2, ids.size)
        assertTrue(ids.contains("alpha"))
        assertTrue(ids.contains("beta"))
    }

    @Test
    fun `writeBackup overwrites existing backup`() {
        val original = mnemonicMaterial()
        val updated = original.copy(privateKey = "c".repeat(64), mnemonicBackedUp = false)

        manager.writeBackup("wallet1", original, testPin)
        manager.writeBackup("wallet1", updated, testPin)

        val result = manager.readBackup("wallet1", testPin)
        assertNotNull(result)
        assertEquals("c".repeat(64), result!!.privateKey)
        assertFalse(result.mnemonicBackedUp)
    }
}
