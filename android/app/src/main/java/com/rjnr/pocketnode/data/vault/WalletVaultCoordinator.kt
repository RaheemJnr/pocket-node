package com.rjnr.pocketnode.data.vault

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.data.crypto.KeyMaterial
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** No caller-owned Room transaction may surround these entry points. Credentials are operation scoped. */
class WalletVaultCoordinator(
    private val db: AppDatabase,
    private val store: WalletVaultStore,
    private val crypto: WalletEnvelopeCrypto,
    private val validator: WalletSecretValidator,
    private val prompt: VaultPrompt,
    private val device: VaultDeviceAuthentication,
    private val cleanup: VaultLegacyCleanup,
) {
    private val json = Json { encodeDefaults = true }

    suspend fun read(activity: FragmentActivity, walletId: String, title: String, subtitle: String, preference: VaultReadPreference = VaultReadPreference.DEFAULT): VaultAccessResult = access(activity) { session ->
        var envelope = store.get(walletId) ?: return@access VaultAccessResult.NoVault
        val material = unlock(activity, session, envelope, title, subtitle, preference) ?: return@access VaultAccessResult.Cancelled
        validateExisting(walletId, material)
        if (walletId in store.snapshot().unrecoveredWalletIds) {
            val current = credential(activity, false) ?: return@access VaultAccessResult.Cancelled
            try {
                checkPhrase(current)
                val replacement = create(walletId, nextGeneration(envelope), material, current)
                store.replaceAuthenticatedWallet(walletId, envelope.generation, replacement, current)
                envelope = store.get(walletId) ?: throw VaultFailure()
            } finally { current.fill('\u0000') }
        }
        if (!finishCleanup(walletId, envelope.generation)) VaultAccessResult.CleanupRequired
        else VaultAccessResult.Success(material)
    }

    suspend fun persistNew(activity: FragmentActivity, walletId: String, material: KeyMaterial): VaultAccessResult =
        enroll(activity, walletId, material, Enrollment.NEW)

    /** Caller must supply freshly authenticated legacy material, never a cached/dashboard-unlocked key. */
    suspend fun protectLegacy(activity: FragmentActivity, walletId: String, material: KeyMaterial): VaultAccessResult =
        enroll(activity, walletId, material, Enrollment.LEGACY)

    /** External recovery must match existing metadata, including both addresses and derivation. */
    suspend fun recoverExisting(activity: FragmentActivity, walletId: String, material: KeyMaterial): VaultAccessResult =
        enroll(activity, walletId, material, Enrollment.RECOVERY)

    private enum class Enrollment { NEW, LEGACY, RECOVERY }
    private suspend fun enroll(activity: FragmentActivity, id: String, material: KeyMaterial, mode: Enrollment): VaultAccessResult = access(activity) { session ->
        val wallet = db.walletDao().getById(id)
        if (mode != Enrollment.NEW && wallet == null) throw VaultFailure()
        if (!validate(material, wallet)) throw VaultFailure()
        val old = store.get(id)
        if (old != null && mode != Enrollment.RECOVERY) throw VaultFailure()
        val source = db.keyMaterialDao().getByWalletId(id)
        if (mode == Enrollment.LEGACY && (source == null || source.kdfVersion !in 1..2)) throw VaultFailure()
        if (mode == Enrollment.NEW && source != null) throw VaultFailure()
        val phrase = credential(activity, !store.hasProfile()) ?: return@access VaultAccessResult.Cancelled
        try {
            checkPhrase(phrase)
            val protectedMaterial = preserveAccessibleMnemonic(old, material, wallet, phrase)
            val candidate = create(id, old?.let(::nextGeneration) ?: 0, protectedMaterial, phrase)
            if (old == null) {
                val metadata = source ?: KeyMaterialEntity(id, byteArrayOf(), null, byteArrayOf(), material.walletType,
                    material.mnemonicBackedUp, System.currentTimeMillis())
                store.activate(metadata, source?.iv, candidate, phrase)
            } else {
                store.replaceAuthenticatedWallet(id, old.generation, candidate, phrase)
            }
            val committed = store.get(id) ?: throw VaultFailure()
            // Verify persisted material rather than the candidate held in memory.
            val verified = decrypt(committed, phrase)
            if (!validate(verified, wallet) || verified.privateKey != protectedMaterial.privateKey || verified.mnemonic != protectedMaterial.mnemonic) throw VaultFailure()
            if (!finishCleanup(id, committed.generation)) return@access VaultAccessResult.CleanupRequired
            if (device.unavailableReason() == null && prompt.choose(activity, "Use device authentication?",
                    "Your wallet passphrase remains required for recovery. Device authentication is optional.", "Enable", "Passphrase only") == true) {
                var ownedKey: ByteArray? = null
                try {
                    withContext(Dispatchers.IO) { ownedKey = crypto.unwrapDataKey(committed, id, phrase) }
                    val key = ownedKey ?: throw VaultFailure()
                    phrase.fill('\u0000')
                    when (val result = authenticateDevice(session) { mode -> device.wrap(activity, committed, key, mode) }) {
                        is DeviceVaultResult.Success -> store.updateDeviceWrap(id, committed.generation, result.value)
                        else -> Unit // Independent protection is already committed and verified.
                    }
                } finally { ownedKey?.fill(0) }
            }
            VaultAccessResult.Success(verified)
        } finally { phrase.fill('\u0000') }
    }

    /** Explicit user-requested re-enrollment; never invoked automatically by a read. */
    suspend fun enrollDevice(activity: FragmentActivity, walletId: String, promptMode: VaultDevicePromptMode? = null): VaultAccessResult = access(activity) { session ->
        val envelope = store.get(walletId) ?: return@access VaultAccessResult.NoVault
        if (walletId in store.snapshot().unrecoveredWalletIds) throw VaultFailure()
        val phrase = credential(activity, false) ?: return@access VaultAccessResult.Cancelled
        var ownedKey: ByteArray? = null
        try {
            if (!store.verifyPassphrase(phrase)) throw VaultFailure()
            val material = decrypt(envelope, phrase)
            validateExisting(walletId, material)
            if (!finishCleanup(walletId, envelope.generation)) return@access VaultAccessResult.CleanupRequired
            device.unavailableReason()?.let { return@access VaultAccessResult.Unavailable(it) }
            withContext(Dispatchers.IO) { ownedKey = crypto.unwrapDataKey(envelope, walletId, phrase) }
            val key = ownedKey ?: throw VaultFailure()
            phrase.fill('\u0000')
            when (val result = authenticateDevice(session, promptMode) { mode -> device.wrap(activity, envelope, key, mode) }) {
                is DeviceVaultResult.Success -> {
                    store.updateDeviceWrap(walletId, envelope.generation, result.value)
                    VaultAccessResult.Success(material)
                }
                DeviceVaultResult.Cancelled -> VaultAccessResult.Cancelled
                is DeviceVaultResult.Unavailable -> VaultAccessResult.Unavailable(result.reason)
            }
        } finally { phrase.fill('\u0000'); ownedKey?.fill(0) }
    }

    suspend fun changePassphrase(activity: FragmentActivity): VaultMaintenanceResult = maintenance(activity) { _ ->
        val old = credential(activity, false) ?: return@maintenance VaultMaintenanceResult.Cancelled
        try {
            if (!store.verifyPassphrase(old)) throw VaultFailure()
            val next = credential(activity, true) ?: return@maintenance VaultMaintenanceResult.Cancelled
            try {
                checkPhrase(next)
                // Validate every wallet before rotating the authenticated set.
                for (envelope in store.listActive()) validateExisting(envelope.walletId, decrypt(envelope, old))
                store.rotate(old, next)
                val snapshot = store.snapshot()
                VaultMaintenanceResult.Completed(snapshot.envelopes.map { it.walletId }.toSet(), snapshot.unrecoveredWalletIds)
            } finally { next.fill('\u0000') }
        } finally { old.fill('\u0000') }
    }

    suspend fun resetPassphrase(activity: FragmentActivity, promptMode: VaultDevicePromptMode? = null): VaultMaintenanceResult = maintenance(activity) { session ->
        val snapshot = store.snapshot()
        if (snapshot.profile == null) throw VaultFailure()
        if (prompt.choose(activity, "Reset forgotten wallet passphrase",
                "Each wallet requires fresh device authentication. Inaccessible wallets keep their old protection and need their previous passphrase or recovery phrase/private key.",
                "Continue", "Cancel") != true) return@maintenance VaultMaintenanceResult.Cancelled
        val next = credential(activity, true) ?: return@maintenance VaultMaintenanceResult.Cancelled
        try {
            checkPhrase(next)
            val replacements = mutableListOf<WalletEnvelope>()
            for (envelope in snapshot.envelopes) {
                if (envelope.deviceWrap == null) continue
                when (val result = authenticateDevice(session, promptMode, discard = { value: DeviceVaultResult<ByteArray> ->
                    if (value is DeviceVaultResult.Success) value.value.fill(0)
                }) { mode -> device.unwrap(activity, envelope, "Authenticate wallet", "Reset this wallet's passphrase", mode) }) {
                    is DeviceVaultResult.Success -> {
                        val material = decryptDevice(envelope, result.value)
                        validateExisting(envelope.walletId, material)
                        replacements += create(envelope.walletId, nextGeneration(envelope), material, next)
                    }
                    DeviceVaultResult.Cancelled -> return@maintenance VaultMaintenanceResult.Cancelled
                    is DeviceVaultResult.Unavailable -> Unit
                }
            }
            if (replacements.isEmpty()) throw VaultFailure()
            store.replaceAfterAuthenticatedReset(snapshot, replacements, next)
            val completed = replacements.map { it.walletId }.toSet()
            val unrecovered = store.snapshot().unrecoveredWalletIds
            prompt.notify(activity, "Passphrase reset", "Updated ${completed.size} wallet(s). ${unrecovered.size} wallet(s) still require recovery.")
            VaultMaintenanceResult.Completed(completed, unrecovered)
        } finally { next.fill('\u0000') }
    }

    private suspend fun unlock(activity: FragmentActivity, session: VaultOperationSession, envelope: WalletEnvelope, title: String, subtitle: String, preference: VaultReadPreference): KeyMaterial? {
        if (envelope.deviceWrap != null && preference != VaultReadPreference.PASSPHRASE) {
            val promptMode = when (preference) {
                VaultReadPreference.BIOMETRIC_ONLY -> VaultDevicePromptMode.BIOMETRIC_ONLY
                VaultReadPreference.CREDENTIAL_ONLY -> VaultDevicePromptMode.CREDENTIAL_ONLY
                else -> null
            }
            when (val result = authenticateDevice(session, promptMode, discard = { value: DeviceVaultResult<ByteArray> ->
                if (value is DeviceVaultResult.Success) value.value.fill(0)
            }) { mode -> device.unwrap(activity, envelope, title, subtitle, mode) }) {
                is DeviceVaultResult.Success -> {
                    return decryptDevice(envelope, result.value)
                }
                DeviceVaultResult.Cancelled -> return null
                is DeviceVaultResult.Unavailable -> Unit
            }
        }
        val phrase = credential(activity, false) ?: return null
        try { return decrypt(envelope, phrase) } finally { phrase.fill('\u0000') }
    }

    private suspend fun <T> authenticateDevice(
        session: VaultOperationSession,
        requested: VaultDevicePromptMode? = null,
        discard: (DeviceVaultResult<T>) -> Unit = {},
        block: suspend (VaultDevicePromptMode) -> DeviceVaultResult<T>,
    ): DeviceVaultResult<T> {
        val available = device.availablePromptModes()
        val mode = requested ?: when {
            VaultDevicePromptMode.BIOMETRIC_ONLY in available -> VaultDevicePromptMode.BIOMETRIC_ONLY
            VaultDevicePromptMode.CREDENTIAL_ONLY in available -> VaultDevicePromptMode.CREDENTIAL_ONLY
            else -> return DeviceVaultResult.Unavailable(device.unavailableReason() ?: "unsupported-device-authentication")
        }
        if (mode !in available) return DeviceVaultResult.Unavailable("unsupported-device-authentication")
        // Capture once. The exact mode authorizes lifecycle handoff and the actual CryptoObject prompt.
        return session.systemAuthentication(mode, discard) { block(mode) }
    }

    private suspend fun preserveAccessibleMnemonic(
        old: WalletEnvelope?, material: KeyMaterial,
        wallet: com.rjnr.pocketnode.data.database.entity.WalletEntity?, phrase: CharArray,
    ): KeyMaterial {
        if (old == null || material.walletType != "raw_key") return material
        val previous = try { decrypt(old, phrase) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { return material }
        return if (previous.mnemonic != null && validate(previous, wallet)) previous else material
    }

    private suspend fun credential(activity: FragmentActivity, setup: Boolean) = prompt.request(activity,
        if (setup) "Set wallet passphrase" else "Wallet passphrase",
        "Use at least 16 characters, including spaces if desired. This is separate from your app PIN, Android PIN and recovery phrase. Keep an external seed/private-key backup; this vault does not survive uninstall or device loss.", setup)
    private fun checkPhrase(phrase: CharArray) { if (!WalletPassphrasePolicy.validate(phrase)) throw VaultFailure() }
    private suspend fun validateExisting(id: String, material: KeyMaterial) {
        val wallet = db.walletDao().getById(id) ?: throw VaultFailure()
        if (!validate(material, wallet)) throw VaultFailure()
    }
    private suspend fun validate(material: KeyMaterial, wallet: com.rjnr.pocketnode.data.database.entity.WalletEntity?) =
        withContext(Dispatchers.IO) { validator.validate(material, wallet) }
    private suspend fun create(id: String, generation: Long, material: KeyMaterial, phrase: CharArray): WalletEnvelope = withContext(Dispatchers.IO) {
        val bytes = json.encodeToString(material).toByteArray()
        try { crypto.create(id, generation, bytes, phrase) } finally { bytes.fill(0) }
    }
    private suspend fun decrypt(envelope: WalletEnvelope, phrase: CharArray): KeyMaterial = withContext(Dispatchers.IO) {
        decode(crypto.decrypt(envelope, envelope.walletId, phrase))
    }
    private suspend fun decryptDevice(envelope: WalletEnvelope, ownedKey: ByteArray): KeyMaterial = try {
        withContext(Dispatchers.IO) { decode(crypto.decryptWithDataKey(envelope, envelope.walletId, ownedKey)) }
    } finally { ownedKey.fill(0) }
    private fun decode(bytes: ByteArray): KeyMaterial = try { json.decodeFromString<KeyMaterial>(bytes.toString(Charsets.UTF_8)) }
        catch (_: Exception) { throw VaultFailure() } finally { bytes.fill(0) }
    private fun nextGeneration(envelope: WalletEnvelope): Long {
        if (envelope.generation == Long.MAX_VALUE) throw VaultFailure()
        return envelope.generation + 1
    }
    private suspend fun finishCleanup(id: String, generation: Long): Boolean = try {
        if (id !in store.pendingLegacyCleanup()) true
        else if (!cleanup.clean(id)) false
        else {
            store.markLegacyCleanupComplete(id, generation)
            true
        }
    } catch (error: CancellationException) { throw error }
      catch (_: Exception) { false }

    private suspend fun access(activity: FragmentActivity, block: suspend (VaultOperationSession) -> VaultAccessResult): VaultAccessResult = try {
        VaultOperationSession.run(activity, block)
    } catch (e: CancellationException) { throw e }
      catch (_: Exception) { VaultAccessResult.Unavailable("Wallet authentication or verification failed") }
    private suspend fun maintenance(activity: FragmentActivity, block: suspend (VaultOperationSession) -> VaultMaintenanceResult): VaultMaintenanceResult = try {
        VaultOperationSession.run(activity, block)
    } catch (e: CancellationException) { throw e }
      catch (_: Exception) { VaultMaintenanceResult.Unavailable("Wallet passphrase operation could not be completed") }
    private class VaultFailure : Exception("Wallet verification failed")
}
