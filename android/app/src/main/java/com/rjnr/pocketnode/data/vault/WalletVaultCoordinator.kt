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

    suspend fun read(activity: FragmentActivity, walletId: String, title: String, subtitle: String, preference: VaultReadPreference = VaultReadPreference.DEFAULT): VaultAccessResult =
        readAuthenticated(activity, walletId, title, subtitle, preference, true)

    private suspend fun readAuthenticated(activity: FragmentActivity, walletId: String, title: String, subtitle: String,
        preference: VaultReadPreference, allowPhraseFallback: Boolean): VaultAccessResult = access(activity) { session ->
        val backupWallet = db.walletDao().getById(walletId)
        val backupMarker = db.keyMaterialDao().getByWalletId(walletId)
        val backupRow = db.walletVaultDao().get(walletId)
        val backupProfile = db.walletVaultDao().profile()
        var envelope = store.get(walletId) ?: return@access VaultAccessResult.NoVault
        val material = unlock(activity, session, envelope, title, subtitle, preference, allowPhraseFallback) ?: return@access VaultAccessResult.Cancelled
        validateExisting(walletId, material)
        if (walletId in store.snapshot().unrecoveredWalletIds) {
            val current = credential(activity, false) ?: return@access VaultAccessResult.Cancelled
            try {
                checkPhrase(current)
                val replacement = create(walletId, nextGeneration(envelope), material, current)
                store.replaceAuthenticatedWallet(walletId, envelope.generation, replacement, current, backupWallet)
                envelope = store.get(walletId) ?: throw VaultFailure()
            } finally { current.fill('\u0000') }
        }
        if (!finishCleanup(walletId, envelope.generation, material)) VaultAccessResult.CleanupRequired
        else VaultAccessResult.Success(material, backupWitness(backupWallet, backupMarker, backupRow, backupProfile, envelope, material))
    }

    private fun backupWitness(
        wallet: com.rjnr.pocketnode.data.database.entity.WalletEntity?,
        marker: KeyMaterialEntity?,
        row: com.rjnr.pocketnode.data.database.entity.WalletVaultEntity?,
        profile: com.rjnr.pocketnode.data.database.entity.WalletPassphraseProfileEntity?,
        authenticated: WalletEnvelope,
        material: KeyMaterial,
    ): com.rjnr.pocketnode.data.wallet.WalletBackupWitness? {
        if (material.mnemonic.isNullOrBlank() || wallet == null || marker == null || row == null || profile == null ||
            marker.kdfVersion != 3 || wallet.walletId != authenticated.walletId || marker.walletId != wallet.walletId ||
            row.walletId != wallet.walletId || row.generation != authenticated.generation ||
            !row.envelope.contentEquals(WalletEnvelopeCodec.encode(authenticated))) return null
        return com.rjnr.pocketnode.data.wallet.WalletBackupWitness(wallet, marker, row, profile)
    }

    /** Recovery-only first attempt: classification must not silently imply consent to another route. */
    suspend fun readForRecovery(activity: FragmentActivity, walletId: String): VaultAccessResult =
        readAuthenticated(activity, walletId, "Preserve recovery phrase", "Authenticate the existing wallet before external recovery", VaultReadPreference.DEFAULT, false)

    internal suspend fun persistCreation(activity: FragmentActivity,
        creation: com.rjnr.pocketnode.data.wallet.WalletRepository.Creation, material: KeyMaterial): VaultAccessResult =
        enroll(activity, creation.walletId, material, Enrollment.NEW, creation = creation)

    /** Caller must supply freshly authenticated legacy material, never a cached/dashboard-unlocked key. */
    suspend fun protectLegacy(activity: FragmentActivity, walletId: String, material: KeyMaterial, expectedLegacyIv: ByteArray): VaultAccessResult =
        enroll(activity, walletId, material, Enrollment.LEGACY, expectedLegacyIv.copyOf())

    /** External recovery must match existing metadata, including both addresses and derivation. */
    suspend fun recoverExisting(activity: FragmentActivity, walletId: String, material: KeyMaterial): VaultAccessResult =
        enroll(activity, walletId, material, Enrollment.RECOVERY)

    internal suspend fun recoverWithLegacyProof(activity: FragmentActivity, walletId: String, material: KeyMaterial, proof: LegacyBackupProof): VaultAccessResult =
        enroll(activity, walletId, material, Enrollment.RECOVERY, proof = proof)

    internal suspend fun recoverWithAcknowledgedSource(activity: FragmentActivity, walletId: String,
        material: KeyMaterial, acknowledgment: LegacySourceAcknowledgment): VaultAccessResult =
        enroll(activity, walletId, material, Enrollment.RECOVERY, acknowledgment = acknowledgment)

    private suspend fun checkAcknowledgment(id: String, acknowledgment: LegacySourceAcknowledgment) {
        val source = acknowledgment.source
        if (source.kdfVersion != 2 || source.walletId != id || acknowledgment.wallet.walletId != id ||
            db.walletDao().getById(id) != acknowledgment.wallet ||
            !LegacyBackupProof.sameEnvelope(db.walletVaultDao().get(id), acknowledgment.original) ||
            (db.keyMaterialDao().getByWalletId(id) != source && db.walletVaultDao().legacy(id)?.source() != source)) throw VaultFailure()
    }

    private suspend fun checkProofSource(id: String, proof: LegacyBackupProof) {
        if (!proof.active || proof.wallet.walletId != id || db.walletDao().getById(id) != proof.wallet ||
            db.keyMaterialDao().getByWalletId(id) != proof.source ||
            !LegacyBackupProof.sameEnvelope(db.walletVaultDao().get(id), proof.original)) throw VaultFailure()
        proof.acknowledgedInaccessibleSources.forEach { checkAcknowledgment(id, it) }
        for ((sourceId, captured) in proof.legacySources) {
            if (db.keyMaterialDao().getByWalletId(sourceId) != captured.first ||
                db.walletVaultDao().legacy(sourceId)?.source() != captured.second) throw VaultFailure()
        }
    }

    private enum class Enrollment { NEW, LEGACY, RECOVERY }
    private suspend fun enroll(activity: FragmentActivity, id: String, material: KeyMaterial, mode: Enrollment, expectedLegacyIv: ByteArray? = null, proof: LegacyBackupProof? = null, acknowledgment: LegacySourceAcknowledgment? = null, creation: com.rjnr.pocketnode.data.wallet.WalletRepository.Creation? = null): VaultAccessResult = access(activity) { session ->
        proof?.let { checkProofSource(id, it) }
        acknowledgment?.let { checkAcknowledgment(id, it) }
        val existingWallet = db.walletDao().getById(id)
        if (creation != null && (mode != Enrollment.NEW || existingWallet != null || creation.walletId != id)) throw VaultFailure()
        val wallet = creation?.wallet ?: existingWallet
        if (mode != Enrollment.NEW && wallet == null) throw VaultFailure()
        if (!validate(material, wallet)) throw VaultFailure()
        val old = store.get(id)
        if (old != null && mode != Enrollment.RECOVERY) throw VaultFailure()
        val source = db.keyMaterialDao().getByWalletId(id)
        if (mode == Enrollment.LEGACY && (source == null || source.kdfVersion !in 1..2 || expectedLegacyIv == null || !source.iv.contentEquals(expectedLegacyIv))) throw VaultFailure()
        if (mode == Enrollment.NEW && source != null) throw VaultFailure()
        val hasProfile = store.hasProfile()
        val reset = if (mode == Enrollment.RECOVERY && hasProfile) {
            val snapshot = store.snapshot()
            when (prompt.choose(activity, "Reset wallet passphrase?",
                "Use the current wallet passphrase, or explicitly set a new one for this verified recovery. Other wallets keep their old protection and will require their old passphrase or separate recovery. Retained legacy copies still require authentication before cleanup.",
                "Reset passphrase", "Use current passphrase")) {
                true -> snapshot
                false -> null
                null -> return@access VaultAccessResult.Cancelled
            }
        } else null
        val phrase = credential(activity, !hasProfile || reset != null) ?: return@access VaultAccessResult.Cancelled
        try {
            checkPhrase(phrase)
            val protectedMaterial = preserveAccessibleMnemonic(old, material, wallet, phrase)
            val candidate = create(id, old?.let(::nextGeneration) ?: 0, protectedMaterial, phrase)
            proof?.let { checkProofSource(id, it) }
            acknowledgment?.let { checkAcknowledgment(id, it) }
            if (old == null) {
                val metadata = source ?: KeyMaterialEntity(id, byteArrayOf(), null, byteArrayOf(), creation?.wallet?.type ?: material.walletType,
                    material.mnemonicBackedUp, System.currentTimeMillis())
                store.activate(metadata, if (mode == Enrollment.LEGACY) expectedLegacyIv else source?.iv, candidate, phrase, creation, wallet, reset)
            } else if (reset != null) {
                store.replaceAfterAuthenticatedReset(reset, listOf(candidate), phrase, mapOf(id to (wallet ?: throw VaultFailure())))
            } else {
                store.replaceAuthenticatedWallet(id, old.generation, candidate, phrase, wallet)
            }
            val committed = store.get(id) ?: throw VaultFailure()
            // Verify persisted material rather than the candidate held in memory.
            val verified = decrypt(committed, phrase)
            if (!validate(verified, wallet) || verified.privateKey != protectedMaterial.privateKey || verified.mnemonic != protectedMaterial.mnemonic) throw VaultFailure()
            val committedRow = db.walletVaultDao().get(id) ?: throw VaultFailure()
            val backupMarker = db.keyMaterialDao().getByWalletId(id)
            val backupProfile = db.walletVaultDao().profile()
            if (creation == null && !finishCleanup(id, committed.generation, verified, proof?.let { BoundLegacyBackupProof(it, committedRow) },
                    when {
                        mode == Enrollment.LEGACY -> source?.let { BoundLegacySourceProof(it, committedRow, LegacySourceAuthority.AUTHENTICATED) }
                        acknowledgment != null -> BoundLegacySourceProof(acknowledgment.source, committedRow, LegacySourceAuthority.ACKNOWLEDGED_INACCESSIBLE)
                        else -> null
                    })) return@access VaultAccessResult.CleanupRequired
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
            VaultAccessResult.Success(verified, backupWitness(wallet, backupMarker, committedRow, backupProfile, committed, verified))
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
            if (!finishCleanup(walletId, envelope.generation, material)) return@access VaultAccessResult.CleanupRequired
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
        val wallets = snapshot.envelopes.associate { it.walletId to (db.walletDao().getById(it.walletId) ?: throw VaultFailure()) }
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
                        if (!validate(material, wallets.getValue(envelope.walletId))) throw VaultFailure()
                        replacements += create(envelope.walletId, nextGeneration(envelope), material, next)
                    }
                    DeviceVaultResult.Cancelled -> return@maintenance VaultMaintenanceResult.Cancelled
                    is DeviceVaultResult.Unavailable -> Unit
                }
            }
            if (replacements.isEmpty()) throw VaultFailure()
            store.replaceAfterAuthenticatedReset(snapshot, replacements, next, wallets)
            val completed = replacements.map { it.walletId }.toSet()
            val unrecovered = store.snapshot().unrecoveredWalletIds
            prompt.notify(activity, "Passphrase reset", "Updated ${completed.size} wallet(s). ${unrecovered.size} wallet(s) still require recovery.")
            VaultMaintenanceResult.Completed(completed, unrecovered)
        } finally { next.fill('\u0000') }
    }

    private suspend fun unlock(activity: FragmentActivity, session: VaultOperationSession, envelope: WalletEnvelope, title: String, subtitle: String, preference: VaultReadPreference, allowPhraseFallback: Boolean): KeyMaterial? {
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
                is DeviceVaultResult.Unavailable -> if (!allowPhraseFallback) throw VaultAccessFailure(deviceFailureKind(result.reason))
            }
        }
        if (!allowPhraseFallback) throw VaultAccessFailure(VaultFailureKind.NO_DEVICE_WRAP)
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
        val key = crypto.unwrapDataKey(envelope, envelope.walletId, phrase)
        try {
            try { decode(crypto.decryptWithDataKey(envelope, envelope.walletId, key)) }
            catch (_: WalletEnvelopeAuthenticationException) { throw VaultAccessFailure(VaultFailureKind.PAYLOAD_INTEGRITY) }
        } finally { key.fill(0) }
    }
    private suspend fun decryptDevice(envelope: WalletEnvelope, ownedKey: ByteArray): KeyMaterial = try {
        withContext(Dispatchers.IO) {
            try { decode(crypto.decryptWithDataKey(envelope, envelope.walletId, ownedKey)) }
            catch (_: WalletEnvelopeAuthenticationException) { throw VaultAccessFailure(VaultFailureKind.PAYLOAD_INTEGRITY) }
        }
    } finally { ownedKey.fill(0) }
    private fun decode(bytes: ByteArray): KeyMaterial = try { json.decodeFromString<KeyMaterial>(bytes.toString(Charsets.UTF_8)) }
        catch (_: Exception) { throw VaultFailure() } finally { bytes.fill(0) }
    private fun nextGeneration(envelope: WalletEnvelope): Long {
        if (envelope.generation == Long.MAX_VALUE) throw VaultFailure()
        return envelope.generation + 1
    }
    private suspend fun finishCleanup(id: String, generation: Long, material: KeyMaterial, proof: BoundLegacyBackupProof? = null, sourceAuthorization: BoundLegacySourceProof? = null): Boolean = try {
        if (id !in store.pendingLegacyCleanup()) true
        else if (!(when {
            proof != null -> cleanup.cleanWithProof(id, generation, material, proof)
            sourceAuthorization != null -> cleanup.cleanWithSource(id, generation, material, sourceAuthorization)
            else -> cleanup.clean(id, generation, material)
        })) false
        else {
            store.markLegacyCleanupComplete(id, generation)
            true
        }
    } catch (error: CancellationException) { throw error }
      catch (_: Exception) { false }

    private suspend fun access(activity: FragmentActivity, block: suspend (VaultOperationSession) -> VaultAccessResult): VaultAccessResult = try {
        VaultOperationSession.run(activity, block)
    } catch (e: CancellationException) { throw e }
      catch (error: VaultAccessFailure) { VaultAccessResult.Unavailable("Wallet access requires another deliberate recovery route", error.kind) }
      catch (_: WalletEnvelopeFormatException) { VaultAccessResult.Unavailable("Wallet envelope is malformed or unsupported", VaultFailureKind.MALFORMED_OR_UNSUPPORTED) }
      catch (_: WalletEnvelopeAuthenticationException) { VaultAccessResult.Unavailable("Passphrase or encrypted wrap authentication failed", VaultFailureKind.CREDENTIAL_OR_WRAP_INTEGRITY) }
      catch (_: VaultFailure) { VaultAccessResult.Unavailable("Wallet identity verification failed", VaultFailureKind.VERIFICATION_FAILED) }
      catch (_: Exception) { VaultAccessResult.Unavailable("Wallet authentication or verification failed") }
    private suspend fun maintenance(activity: FragmentActivity, block: suspend (VaultOperationSession) -> VaultMaintenanceResult): VaultMaintenanceResult = try {
        VaultOperationSession.run(activity, block)
    } catch (e: CancellationException) { throw e }
      catch (_: Exception) { VaultMaintenanceResult.Unavailable("Wallet passphrase operation could not be completed") }
    private class VaultAccessFailure(val kind: VaultFailureKind) : Exception("Wallet access unavailable")
    private class VaultFailure : Exception("Wallet verification failed")
}
