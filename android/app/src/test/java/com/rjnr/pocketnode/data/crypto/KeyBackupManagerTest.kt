package com.rjnr.pocketnode.data.crypto

import com.rjnr.pocketnode.core.log.NoopLogger
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
        manager = KeyBackupManager(tempDir.root, NoopLogger, com.rjnr.pocketnode.data.wallet.WalletStorageBoundary(androidx.test.core.app.ApplicationProvider.getApplicationContext()))
        // Use low cost factors for fast tests. Production uses PBKDF2 600_000
        // (legacy reads) and Argon2id 64 MB / t=3 / p=4 (legacy reads).
        manager.kdfIterations = 1_000
        manager.argon2Iterations = 1
        manager.argon2MemoryKb = 8
        manager.argon2Parallelism = 1
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
    fun `legacy Argon2id backup remains readable for mnemonic wallet`() {
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
    fun `legacy Argon2id backup remains readable for raw-key wallet`() {
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

        assertTrue(manager.deleteBackupChecked("wallet1"))
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
    fun `startup preserves interrupted backup with independent seed capability`() {
        manager.writeBackup("wallet1", mnemonicMaterial(), testPin)
        val pending = File(tempDir.root, "wallet1.enc.tmp")
        manager.writeBackup("wallet1", rawKeyMaterial(), testPin)
        // Restore a distinct captured seed copy after the old writer's rename.
        manager.writeBackup("seed", mnemonicMaterial(), testPin)
        File(tempDir.root, "seed.enc").copyTo(pending, overwrite = true)
        manager.cleanupOrphanedTmpFiles()
        assertTrue("Interrupted copy may hold unrecovered seed capability", pending.exists())
        assertEquals(mnemonicMaterial().mnemonic, manager.readCapturedBackup(pending.readBytes(), testPin)!!.mnemonic)
    }

    @Test
    fun `checked retirement reports blocked pending file and succeeds on retry`() {
        manager.writeBackup("wallet1", rawKeyMaterial(), testPin)
        val pending = File(tempDir.root, "wallet1.enc.tmp").apply { mkdir() }
        File(pending, "blocker").writeText("synthetic")
        assertFalse(manager.deleteBackupChecked("wallet1"))
        assertTrue(manager.hasAnyBackups())
        pending.deleteRecursively()
        assertTrue(manager.deleteBackupChecked("wallet1"))
        assertFalse(manager.hasAnyBackups())
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

    @Test fun `authenticated malformed payload never forwards serializer exception to logger`() {
        val logger = io.mockk.mockk<com.rjnr.pocketnode.core.log.Logger>(relaxed = true)
        val reader = KeyBackupManager(tempDir.root, logger, com.rjnr.pocketnode.data.wallet.WalletStorageBoundary(androidx.test.core.app.ApplicationProvider.getApplicationContext())).apply { kdfIterations = 1_000 }
        val salt = ByteArray(KeyBackupManager.SALT_SIZE)
        val iv = ByteArray(KeyBackupManager.IV_SIZE)
        val spec = javax.crypto.spec.PBEKeySpec(testPin, salt, 1_000, KeyBackupManager.KEY_SIZE_BITS)
        val raw = javax.crypto.SecretKeyFactory.getInstance(KeyBackupManager.KDF_ALGORITHM).generateSecret(spec).encoded
        val cipher = javax.crypto.Cipher.getInstance(KeyBackupManager.CIPHER_TRANSFORM)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(raw, "AES"),
            javax.crypto.spec.GCMParameterSpec(KeyBackupManager.GCM_TAG_BITS, iv))
        val bytes = KeyBackupManager.MAGIC + byteArrayOf(KeyBackupManager.FORMAT_VERSION_PBKDF2) + salt + iv +
            cipher.doFinal("{malformed-public-secret-sentinel".toByteArray())
        raw.fill(0); spec.clearPassword()
        assertNull(reader.readCapturedBackup(bytes, testPin))
        io.mockk.verify(exactly = 0) { logger.w(any(), any(), match { it != null }) }
        io.mockk.verify { logger.w(any(), "Failed to read legacy backup", null) }
    }

}
