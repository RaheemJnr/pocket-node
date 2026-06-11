package com.rjnr.pocketnode.data.wallet

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class KeyMaterial(
    val privateKey: String,
    val mnemonic: String? = null,
    val walletType: String,
    val mnemonicBackedUp: Boolean,
    val createdAt: String = java.time.Instant.now().toString(),
    val version: Int = 1
)

@Singleton
class KeyBackupManager @Inject constructor(
    private val backupDir: File
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // PBKDF2 iteration count — only used when READING legacy v1 backups.
    @VisibleForTesting
    internal var kdfIterations: Int = KDF_ITERATIONS

    // Argon2id parameters for v2 backups. Match PinManager's PIN-hash params
    // (OWASP ASVS baseline: 64 MB, t=3, p=4). Overridable for fast tests.
    @VisibleForTesting
    internal var argon2Iterations: Int = 3

    @VisibleForTesting
    internal var argon2MemoryKb: Int = 64 * 1024

    @VisibleForTesting
    internal var argon2Parallelism: Int = 4

    init {
        backupDir.mkdirs()
    }

    fun writeBackup(walletId: String, material: KeyMaterial, pin: CharArray) {
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        // New backups are always written with the strongest KDF (Argon2id, v2).
        val key = deriveKey(pin, salt, FORMAT_VERSION_ARGON2)

        // Serialize straight to bytes — no String intermediate for the
        // plaintext key material — and zero the buffer once encrypted (#321).
        // Serializer-internal buffers and the Strings inside KeyMaterial
        // itself are beyond reach on the JVM; see #335 for the full rewrite.
        val plaintext = ByteArrayOutputStream().use { baos ->
            @OptIn(ExperimentalSerializationApi::class)
            json.encodeToStream(material, baos)
            baos.toByteArray()
        }
        val ciphertext = try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(plaintext)
        } finally {
            plaintext.fill(0)
        }

        val file = backupFile(walletId)
        val tmpFile = File(file.parent, "${file.name}.tmp")

        tmpFile.outputStream().use { out ->
            out.write(MAGIC)
            out.write(byteArrayOf(FORMAT_VERSION_ARGON2))
            out.write(salt)
            out.write(iv)
            out.write(ciphertext)
        }

        tmpFile.renameTo(file)
    }

    fun readBackup(walletId: String, pin: CharArray): KeyMaterial? {
        val file = backupFile(walletId)
        if (!file.exists()) return null

        return try {
            val bytes = file.readBytes()
            if (bytes.size < HEADER_SIZE || !bytes.sliceArray(0 until 4).contentEquals(MAGIC)) {
                Log.w(TAG, "Backup file for $walletId has invalid magic header")
                return null
            }
            // Byte 4 selects the KDF: v1 = PBKDF2 (legacy), v2 = Argon2id.
            val formatVersion = bytes[4]
            val salt = bytes.sliceArray(HEADER_SIZE until HEADER_SIZE + SALT_SIZE)
            val iv = bytes.sliceArray(HEADER_SIZE + SALT_SIZE until HEADER_SIZE + SALT_SIZE + IV_SIZE)
            val ciphertext = bytes.sliceArray(HEADER_SIZE + SALT_SIZE + IV_SIZE until bytes.size)

            val key = deriveKey(pin, salt, formatVersion)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)

            // Decode from the byte buffer directly (no plaintext String of the
            // key material) and zero it after parsing (#321).
            try {
                @OptIn(ExperimentalSerializationApi::class)
                json.decodeFromStream<KeyMaterial>(ByteArrayInputStream(plaintext))
            } finally {
                plaintext.fill(0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read backup for $walletId", e)
            null
        }
    }

    fun hasBackup(walletId: String): Boolean = backupFile(walletId).exists()

    fun hasAnyBackups(): Boolean = backupDir.listFiles()?.any { it.extension == "enc" } == true

    fun deleteBackup(walletId: String) {
        backupFile(walletId).delete()
    }

    fun listBackupWalletIds(): List<String> {
        return backupDir.listFiles()
            ?.filter { it.extension == "enc" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    fun reEncryptAll(oldPin: CharArray, newPin: CharArray): Boolean {
        val backupFiles = backupDir.listFiles()?.filter { it.extension == "enc" } ?: return true

        // Phase 1: decrypt all and re-encrypt to .tmp files
        val tmpFiles = mutableListOf<Pair<File, File>>()
        for (file in backupFiles) {
            val walletId = file.nameWithoutExtension
            val material = readBackup(walletId, oldPin) ?: return false
            val tmpFile = File(file.parent, "${file.name}.tmp")

            val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
            val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
            // Re-encryption upgrades the blob to the current KDF (Argon2id, v2).
            val key = deriveKey(newPin, salt, FORMAT_VERSION_ARGON2)

            val plaintext = json.encodeToString(material).toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val ciphertext = cipher.doFinal(plaintext)

            tmpFile.outputStream().use { out ->
                out.write(MAGIC)
                out.write(byteArrayOf(FORMAT_VERSION_ARGON2))
                out.write(salt)
                out.write(iv)
                out.write(ciphertext)
            }
            tmpFiles.add(tmpFile to file)
        }

        // Phase 2: atomic rename all .tmp → .enc
        for ((tmp, original) in tmpFiles) {
            tmp.renameTo(original)
        }

        return true
    }

    fun cleanupOrphanedTmpFiles() {
        backupDir.listFiles()
            ?.filter { it.extension == "tmp" }
            ?.forEach { it.delete() }
    }

    private fun backupFile(walletId: String): File = File(backupDir, "$walletId.enc")

    /** Derives the AES key for a backup blob using the KDF for [formatVersion]. */
    private fun deriveKey(pin: CharArray, salt: ByteArray, formatVersion: Byte): SecretKeySpec =
        when (formatVersion) {
            FORMAT_VERSION_ARGON2 -> deriveKeyArgon2(pin, salt)
            FORMAT_VERSION_PBKDF2 -> deriveKeyPbkdf2(pin, salt)
            else -> throw IllegalStateException("Unknown backup format version $formatVersion")
        }

    private fun deriveKeyPbkdf2(pin: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
        val spec = PBEKeySpec(pin, salt, kdfIterations, KEY_SIZE_BITS)
        val secret = factory.generateSecret(spec)
        val key = SecretKeySpec(secret.encoded, "AES")
        spec.clearPassword()
        return key
    }

    private fun deriveKeyArgon2(pin: CharArray, salt: ByteArray): SecretKeySpec {
        // Avoid String interning of the PIN.
        val pinBytes = String(pin).toByteArray(Charsets.UTF_8)
        try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(argon2Iterations)
                .withMemoryAsKB(argon2MemoryKb)
                .withParallelism(argon2Parallelism)
                .withSalt(salt)
                .build()
            val gen = Argon2BytesGenerator().also { it.init(params) }
            val out = ByteArray(KEY_SIZE_BITS / 8)
            gen.generateBytes(pinBytes, out)
            return SecretKeySpec(out, "AES")
        } finally {
            pinBytes.fill(0)
        }
    }

    companion object {
        private const val TAG = "KeyBackupManager"
        val MAGIC = byteArrayOf('P'.code.toByte(), 'N'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
        // v1 = PBKDF2-HMAC-SHA256 (read-only legacy); v2 = Argon2id (current).
        const val FORMAT_VERSION_PBKDF2: Byte = 1
        const val FORMAT_VERSION_ARGON2: Byte = 2
        const val HEADER_SIZE = 5
        const val SALT_SIZE = 16
        const val IV_SIZE = 12
        const val GCM_TAG_BITS = 128
        const val KEY_SIZE_BITS = 256
        const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val KDF_ITERATIONS = 600_000
        const val CIPHER_TRANSFORM = "AES/GCM/NoPadding"
    }
}
