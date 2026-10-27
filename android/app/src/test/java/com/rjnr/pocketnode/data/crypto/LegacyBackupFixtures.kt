package com.rjnr.pocketnode.data.crypto

import com.rjnr.pocketnode.core.crypto.Argon2id
import kotlinx.serialization.json.Json
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Synthetic historical ciphertext only. No production backup writer remains. */
fun KeyBackupManager.writeBackup(walletId: String, material: KeyMaterial, pin: CharArray) {
    val directory = KeyBackupManager::class.java.getDeclaredField("backupDir").let {
        it.isAccessible = true; it.get(this) as File
    }
    val boundary = KeyBackupManager::class.java.getDeclaredField("walletStorage").let {
        it.isAccessible = true; it.get(this) as com.rjnr.pocketnode.data.wallet.WalletStorageBoundary
    }
    boundary.readOrWriteFiles {
        val salt = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val password = pin.concatToString().toByteArray()
        val key = try { Argon2id.hash(password, salt, Argon2id.Params(argon2Iterations, argon2MemoryKb, argon2Parallelism, 32)) }
            finally { password.fill(0) }
        val plain = Json.encodeToString(KeyMaterial.serializer(), material).toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            File(directory, "$walletId.enc").writeBytes(KeyBackupManager.MAGIC + byteArrayOf(2) + salt + iv + cipher.doFinal(plain))
        } finally { key.fill(0); plain.fill(0) }
    }
}
