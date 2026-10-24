package com.rjnr.pocketnode.data.vault

import androidx.room.withTransaction
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity
import com.rjnr.pocketnode.data.database.entity.WalletPassphraseProfileEntity
import com.rjnr.pocketnode.data.database.entity.WalletVaultEntity
import com.rjnr.pocketnode.data.database.entity.WalletVaultLegacyEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Immutable CAS token. Getters decode fresh DTOs, so callers cannot mutate the retained bytes. */
class WalletVaultSnapshot internal constructor(
    profile: ByteArray?,
    envelopes: List<ByteArray>,
    unrecovered: Set<String>,
) {
    private val profileBytes = profile?.copyOf()
    private val envelopeBytes = envelopes.map { it.copyOf() }
    val profile: WalletEnvelope? get() = profileBytes?.let(WalletEnvelopeCodec::decode)
    val envelopes: List<WalletEnvelope> get() = envelopeBytes.map(WalletEnvelopeCodec::decode)
    val unrecoveredWalletIds: Set<String> = unrecovered.toSet()
    override fun toString() = "WalletVaultSnapshot([redacted])"
}

class WalletVaultConflictException : IllegalStateException("Wallet vault changed")
class WalletVaultStorageException : IllegalStateException("Wallet vault storage failed")

/**
 * Persists only canonical, bounded encrypted envelopes. All public mutations snapshot mutable
 * caller buffers before suspension and serialize on IO; the Room transaction also protects against
 * other store instances. No secret-bearing provider/database exception escapes this boundary.
 * Never invoke these APIs from a caller-owned Room transaction: lock order is store mutex then DB.
 * For repository deletion already inside a Room transaction, use WalletVaultDao.deleteVault and
 * deleteLegacy alongside KeyMaterialDao.delete in that transaction rather than nesting this store.
 *
 * Activation callers must authenticate the source material and validate its address/derivation
 * against wallet metadata. This layer verifies persisted decryption, not wallet address semantics.
 */
@Singleton
class WalletVaultStore(
    private val db: AppDatabase,
    private val crypto: WalletEnvelopeCrypto,
    private val io: CoroutineDispatcher,
) {
    @Inject constructor(db: AppDatabase, crypto: WalletEnvelopeCrypto) : this(db, crypto, Dispatchers.IO)

    private val mutex = Mutex()
    private val dao get() = db.walletVaultDao()

    companion object {
        const val PROFILE_ID = "pocketnode.passphrase-profile.v1"
        private val CONFIRMATION = "pocketnode wallet passphrase confirmation v1".toByteArray(Charsets.UTF_8)
    }

    suspend fun get(walletId: String): WalletEnvelope? = boundary {
        walletIdentity(walletId)
        dao.get(walletId)?.let(::decode)
    }

    suspend fun hasProfile(): Boolean = boundary { profile()?.let { true } ?: false }

    /** Wrong passphrase returns false; malformed/unsupported persisted data still fails closed. */
    suspend fun verifyPassphrase(passphrase: CharArray): Boolean {
        val owned = passphrase.copyOf()
        try {
            return boundary {
                val current = profile() ?: return@boundary false
                try { verifyProfile(current, owned); true }
                catch (_: WalletEnvelopeAuthenticationException) { false }
                catch (_: WalletEnvelopeFormatException) {
                    // Invalid input policy also cannot authenticate. Stored format was checked above.
                    if (!WalletPassphrasePolicy.validate(owned)) false else throw WalletEnvelopeFormatException()
                }
            }
        } finally { owned.fill('\u0000') }
    }

    suspend fun listActive(): List<WalletEnvelope> = boundary { dao.all().map(::decode) }

    suspend fun snapshot(): WalletVaultSnapshot = boundary { snapshotInTransaction() }

    suspend fun activate(
        metadata: KeyMaterialEntity,
        expectedLegacyIv: ByteArray?,
        envelope: WalletEnvelope,
        passphrase: CharArray,
        creation: com.rjnr.pocketnode.data.wallet.WalletRepository.Creation? = null,
    ) {
        // Snapshot before the first suspension, including arrays in metadata and the CAS token.
        val candidate = WalletEnvelopeCodec.encode(envelope)
        val meta = metadata.copy(encryptedPrivateKey = metadata.encryptedPrivateKey.copyOf(),
            encryptedMnemonic = metadata.encryptedMnemonic?.copyOf(), iv = metadata.iv.copyOf())
        val expectedIv = expectedLegacyIv?.copyOf()
        val owned = passphrase.copyOf()
        try {
            boundary {
                walletIdentity(meta.walletId)
                val value = WalletEnvelopeCodec.decode(candidate)
                requireEnvelope(value.walletId == meta.walletId)
                conflictUnless(dao.get(meta.walletId) == null)
                val source = db.keyMaterialDao().getByWalletId(meta.walletId)
                conflictUnless(if (expectedIv == null) source == null else source != null && source.iv.contentEquals(expectedIv))
                if (creation != null) {
                    conflictUnless(creation.walletId == meta.walletId && expectedIv == null && source == null &&
                        db.walletDao().getById(meta.walletId) == null && dao.legacy(meta.walletId) == null)
                    conflictUnless(creation.parentMatches(creation.wallet.parentWalletId?.let { db.walletDao().getById(it) }))
                    // This context names a newly generated namespace, never a historical recovery target.
                    db.walletDao().insert(creation.wallet)
                }
                source?.let { requireEnvelope(it.kdfVersion == 1 || it.kdfVersion == 2) }
                requireEnvelope(db.walletDao().getById(PROFILE_ID) == null &&
                    db.keyMaterialDao().getByWalletId(PROFILE_ID) == null)
                val existingProfile = profile()
                val active = dao.all().also { rows -> rows.forEach(::decode) }
                // A missing profile with active envelopes is corruption, not an invitation to enroll.
                requireEnvelope(existingProfile != null || active.isEmpty())
                val nextProfile = existingProfile ?: crypto.create(PROFILE_ID, 0, CONFIRMATION, owned)
                verifyProfile(nextProfile, owned)
                source?.let {
                    dao.archive(WalletVaultLegacyEntity(it.walletId, it.encryptedPrivateKey,
                        it.encryptedMnemonic, it.iv, it.walletType, it.mnemonicBackedUp, it.updatedAt, it.kdfVersion))
                }
                if (existingProfile == null) dao.insertProfile(WalletPassphraseProfileEntity(envelope = WalletEnvelopeCodec.encode(nextProfile)))
                dao.insert(WalletVaultEntity(value.walletId, value.generation, nextProfile.generation, candidate, creation == null))
                // Marker has no legacy-decryptable material. Preserve externally visible backup flags.
                db.keyMaterialDao().upsert((source ?: meta).copy(encryptedPrivateKey = byteArrayOf(),
                    encryptedMnemonic = null, iv = byteArrayOf(), kdfVersion = 3))
                verifyStored(value.walletId, owned)
                verifyProfile(profile() ?: throw WalletEnvelopeFormatException(), owned)
            }
        } finally { owned.fill('\u0000') }
    }

    /** Re-encrypts every payload under increasing AAD generations, dropping stale device wraps. */
    suspend fun rotate(oldPassphrase: CharArray, newPassphrase: CharArray) {
        val old = oldPassphrase.copyOf()
        val next = newPassphrase.copyOf()
        try {
            boundary {
                val previousProfile = profile() ?: throw WalletEnvelopeFormatException()
                verifyProfile(previousProfile, old)
                val rows = dao.all()
                val staged = rows.map { row ->
                    val previous = decode(row)
                    requireEnvelope(row.profileGeneration == previousProfile.generation)
                    val rotated = crypto.rewrap(previous, previous.walletId, old, next, increment(previous.generation))
                    row.copy(generation = rotated.generation, profileGeneration = increment(previousProfile.generation),
                        envelope = WalletEnvelopeCodec.encode(rotated))
                }
                val rotatedProfile = crypto.rewrap(previousProfile, PROFILE_ID, old, next, increment(previousProfile.generation))
                staged.forEach { conflictUnless(dao.update(it) == 1) }
                conflictUnless(dao.updateProfile(WalletPassphraseProfileEntity(envelope = WalletEnvelopeCodec.encode(rotatedProfile))) == 1)
                staged.forEach { verifyStored(it.walletId, next) }
                verifyProfile(profile() ?: throw WalletEnvelopeFormatException(), next)
            }
        } finally { old.fill('\u0000'); next.fill('\u0000') }
    }

    /**
     * Caller must cryptographically authenticate the new device wrap for this exact envelope/AAD.
     * This operation cannot establish wallet/passphrase protection by itself.
     */
    suspend fun updateDeviceWrap(walletId: String, expectedGeneration: Long, wrap: DeviceKeyWrap) {
        val owned = wrap.copy(wrappedKey = wrap.wrappedKey.copyOf(), nonce = wrap.nonce.copyOf())
        boundary {
            walletIdentity(walletId)
            val row = dao.get(walletId) ?: throw WalletVaultConflictException()
            val value = decode(row)
            conflictUnless(value.generation == expectedGeneration)
            val encoded = WalletEnvelopeCodec.encode(value.copy(deviceWrap = owned))
            conflictUnless(dao.update(row.copy(envelope = encoded)) == 1)
            conflictUnless(dao.get(walletId)?.envelope?.contentEquals(encoded) == true)
        }
    }

    /**
     * Complete recovery of one retained older-profile wallet. Caller must freshly authenticate its
     * old material and validate address/derivation. Current profile authentication is required too.
     * Preserves the marker flags and cleanup state; no other wallet or profile is changed.
     */
    suspend fun replaceAuthenticatedWallet(
        walletId: String,
        expectedGeneration: Long,
        replacement: WalletEnvelope,
        currentPassphrase: CharArray,
    ) {
        val candidate = WalletEnvelopeCodec.encode(replacement)
        val owned = currentPassphrase.copyOf()
        try {
            boundary {
                walletIdentity(walletId)
                val currentProfile = profile() ?: throw WalletEnvelopeFormatException()
                verifyProfile(currentProfile, owned)
                val row = dao.get(walletId) ?: throw WalletVaultConflictException()
                decode(row)
                conflictUnless(row.generation == expectedGeneration)
                val value = WalletEnvelopeCodec.decode(candidate)
                requireEnvelope(value.walletId == walletId && value.generation > expectedGeneration)
                conflictUnless(dao.update(row.copy(generation = value.generation,
                    profileGeneration = currentProfile.generation, envelope = candidate)) == 1)
                verifyStored(walletId, owned)
            }
        } finally { owned.fill('\u0000') }
    }

    /** Deletes this wallet's encrypted rows atomically; the installation profile remains enrolled. */
    suspend fun removeWallet(walletId: String) = boundary {
        walletIdentity(walletId)
        dao.deleteVault(walletId)
        db.keyMaterialDao().delete(walletId)
        dao.deleteLegacy(walletId)
    }

    suspend fun pendingLegacyCleanup(): List<String> = boundary { dao.pendingCleanup() }

    /**
     * Call only after weaker backup files and legacy preference cleanup succeeds. Matching verified
     * active generation is mandatory. Retires this wallet's archive and pending flag atomically;
     * never deletes shared legacy aliases, which may still protect unrecovered wallets.
     */
    suspend fun markLegacyCleanupComplete(walletId: String, expectedGeneration: Long) = boundary {
        walletIdentity(walletId)
        val current = dao.get(walletId) ?: throw WalletVaultConflictException()
        decode(current)
        conflictUnless(current.generation == expectedGeneration)
        conflictUnless(dao.markCleanupComplete(walletId, expectedGeneration) == 1)
        dao.deleteLegacy(walletId)
    }

    /**
     * Minimal reset commit boundary, not an authentication bypass. The coordinator must freshly
     * authenticate each replacement using its device key (or external recovery) and verify its
     * plaintext/address against the expected wallet. At least one authenticated replacement is
     * required. All active bytes and the profile are compared against the immutable snapshot.
     * Unrecovered envelopes and their profileGeneration remain unchanged and are reported by the
     * next snapshot. Rotation fails closed while any wallet belongs to an older profile.
     */
    suspend fun replaceAfterAuthenticatedReset(
        expected: WalletVaultSnapshot,
        replacements: List<WalletEnvelope>,
        newPassphrase: CharArray,
    ) {
        val staged = replacements.map(WalletEnvelopeCodec::encode)
        val next = newPassphrase.copyOf()
        try {
            boundary {
                requireEnvelope(staged.isNotEmpty())
                val rows = dao.all()
                val currentProfile = profile() ?: throw WalletEnvelopeFormatException()
                conflictUnless(same(expected.profile, currentProfile))
                val prior = expected.envelopes.associateBy { it.walletId }
                conflictUnless(prior.size == rows.size && rows.all { same(prior[it.walletId], decode(it)) })
                val candidates = staged.map(WalletEnvelopeCodec::decode)
                requireEnvelope(candidates.map { it.walletId }.toSet().size == candidates.size)
                val profileGeneration = increment(currentProfile.generation)
                for ((index, value) in candidates.withIndex()) {
                    walletIdentity(value.walletId)
                    val row = rows.singleOrNull { it.walletId == value.walletId } ?: throw WalletVaultConflictException()
                    requireEnvelope(value.generation > row.generation)
                    conflictUnless(dao.update(row.copy(generation = value.generation, profileGeneration = profileGeneration,
                        envelope = staged[index])) == 1)
                }
                val nextProfile = crypto.create(PROFILE_ID, profileGeneration, CONFIRMATION, next)
                conflictUnless(dao.updateProfile(WalletPassphraseProfileEntity(envelope = WalletEnvelopeCodec.encode(nextProfile))) == 1)
                candidates.forEach { verifyStored(it.walletId, next) }
                verifyProfile(profile() ?: throw WalletEnvelopeFormatException(), next)
            }
        } finally { next.fill('\u0000') }
    }

    private suspend fun snapshotInTransaction(): WalletVaultSnapshot {
        val current = profile()
        val rows = dao.all()
        val values = rows.map(::decode)
        requireEnvelope(current != null || values.isEmpty())
        return WalletVaultSnapshot(current?.let(WalletEnvelopeCodec::encode), values.map(WalletEnvelopeCodec::encode),
            rows.filter { it.profileGeneration != current?.generation }.map { it.walletId }.toSet())
    }

    private fun decode(row: WalletVaultEntity): WalletEnvelope {
        walletIdentity(row.walletId)
        val value = canonical(row.envelope)
        requireEnvelope(value.walletId == row.walletId && value.generation == row.generation && row.profileGeneration >= 0)
        return value
    }

    private suspend fun profile(): WalletEnvelope? = dao.profile()?.let {
        canonical(it.envelope).also { value -> requireEnvelope(value.walletId == PROFILE_ID) }
    }

    private fun canonical(bytes: ByteArray): WalletEnvelope = WalletEnvelopeCodec.decode(bytes).also {
        requireEnvelope(WalletEnvelopeCodec.encode(it).contentEquals(bytes))
    }

    private suspend fun verifyStored(walletId: String, passphrase: CharArray) {
        val stored = dao.get(walletId) ?: throw WalletEnvelopeFormatException()
        val plaintext = crypto.decrypt(decode(stored), walletId, passphrase)
        try { currentCoroutineContext().ensureActive() } finally { plaintext.fill(0) }
    }

    private fun verifyProfile(envelope: WalletEnvelope, passphrase: CharArray) {
        val value = crypto.decrypt(envelope, PROFILE_ID, passphrase)
        try { requireEnvelope(value.contentEquals(CONFIRMATION)) } finally { value.fill(0) }
    }

    private fun walletIdentity(walletId: String) {
        requireText(walletId)
        requireEnvelope(walletId != PROFILE_ID)
    }

    private fun increment(generation: Long): Long {
        requireEnvelope(generation in 0 until Long.MAX_VALUE)
        return generation + 1
    }

    private fun same(first: WalletEnvelope?, second: WalletEnvelope): Boolean =
        first != null && WalletEnvelopeCodec.encode(first).contentEquals(WalletEnvelopeCodec.encode(second))

    private fun conflictUnless(condition: Boolean) { if (!condition) throw WalletVaultConflictException() }

    private suspend fun <T> boundary(block: suspend () -> T): T = withContext(io) {
        mutex.withLock {
            try {
                db.withTransaction {
                    val result = block()
                    currentCoroutineContext().ensureActive()
                    result
                }
            } catch (e: CancellationException) { throw e }
            catch (e: WalletEnvelopeFormatException) { throw e }
            catch (e: WalletEnvelopeAuthenticationException) { throw e }
            catch (e: WalletVaultConflictException) { throw e }
            catch (_: Exception) { throw WalletVaultStorageException() }
        }
    }
}
