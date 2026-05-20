package com.rjnr.pocketnode.data.contacts

import android.util.Log
import com.rjnr.pocketnode.data.database.dao.ContactDao
import com.rjnr.pocketnode.data.database.entity.ContactEntity
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.wallet.AddressUtils
import com.rjnr.pocketnode.data.wallet.WalletRepository
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validation and lifecycle wrapper around [ContactDao] for the M4 Phase 2
 * address book (#191).
 *
 * ## Responsibilities
 *
 * - Normalize and validate user input (name length, address parseability,
 *   network agreement with the active wallet, duplicate detection).
 * - Translate sealed [ContactError] outcomes from primitive Room
 *   exceptions, so call-site error handling stays exhaustive.
 * - Bump `lastUsedAt` / `useCount` after a successful send via
 *   [markUsed], driving the smart-suggestion ranking that the Send
 *   picker reads through [recentlyUsed].
 *
 * The repository is wallet-aware: scope decisions are made from
 * [WalletRepository.activeWalletIdSnapshot]. Callers do not need to
 * supply walletId — that's the lesson from the WalletRepository wiring
 * in M3.
 */
@Singleton
class ContactRepository @Inject constructor(
    private val contactDao: ContactDao,
    private val walletRepository: WalletRepository,
) {

    /**
     * Clock injection point for unit tests. Hilt always uses the default
     * (system clock); tests overwrite this via [setClockForTest] to make
     * `createdAt`/`updatedAt`/`lastUsedAt` assertions deterministic.
     */
    @VisibleForTesting
    internal var nowProvider: () -> Long = { System.currentTimeMillis() }

    @VisibleForTesting
    internal fun setClockForTest(provider: () -> Long) { nowProvider = provider }

    sealed class ContactError(message: String) : Exception(message) {
        object InvalidAddress : ContactError("Address cannot be decoded")
        data class WrongNetwork(val expected: NetworkType, val actual: NetworkType) :
            ContactError("Address is on $actual but active network is $expected")
        data class DuplicateAddress(val existingId: String) :
            ContactError("Address already saved as contact $existingId")
        object InvalidName : ContactError("Name must be 1-64 characters")
        object NotesTooLong : ContactError("Notes must be 256 characters or fewer")
        object NoActiveWallet : ContactError("No active wallet to scope contact to")
        data class NotFound(val id: String) : ContactError("Contact $id not found")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<List<ContactEntity>> {
        val walletId = walletRepository.activeWalletIdSnapshot()
        return if (walletId.isNullOrEmpty()) flowOf(emptyList())
        else contactDao.observeAll(walletId)
    }

    /**
     * Snapshot of every contact visible to the active wallet, sorted by
     * name. Differs from [observe] in that callers get a single
     * suspending read rather than a Flow — useful for one-shot UI
     * loads like the Send picker sheet.
     */
    suspend fun listAll(): List<ContactEntity> {
        val walletId = walletRepository.activeWalletIdSnapshot()?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        return contactDao.observeAll(walletId).firstOrNull() ?: emptyList()
    }

    suspend fun get(id: String): ContactEntity? = contactDao.getById(id)

    suspend fun getByAddress(address: String): ContactEntity? =
        contactDao.getByAddress(address)

    /**
     * Add a new contact. Validation order:
     *
     * 1. Name length (cheapest)
     * 2. Notes length
     * 3. Address parseability via [AddressUtils.decode] (canonical
     *    bech32 check)
     * 4. Network agreement with the active wallet's network — refuses a
     *    mainnet address on testnet and vice versa
     * 5. Duplicate-address check across the user's contacts (regardless
     *    of wallet scope) — if a contact already exists for this
     *    address, the UI offers an "update existing" flow rather than
     *    silently shadowing it.
     */
    suspend fun add(
        name: String,
        address: String,
        notes: String? = null,
        tags: List<String>? = null,
        scopedToActiveWallet: Boolean = true,
        activeNetwork: NetworkType,
    ): Result<ContactEntity> {
        return runCatching {
            val trimmedName = name.trim()
            if (trimmedName.isEmpty() || trimmedName.length > 64) throw ContactError.InvalidName
            val trimmedNotes = notes?.trim()?.ifEmpty { null }
            if (trimmedNotes != null && trimmedNotes.length > 256) throw ContactError.NotesTooLong

            // getNetwork wraps the CKB SDK's full Address.decode call. If
            // it returns a non-null NetworkType, the bech32 payload + checksum
            // are valid; we don't need a second decode pass.
            val addressNetwork = AddressUtils.getNetwork(address)
                ?: throw ContactError.InvalidAddress
            if (addressNetwork != activeNetwork) {
                throw ContactError.WrongNetwork(expected = activeNetwork, actual = addressNetwork)
            }

            contactDao.getByAddress(address)?.let { existing ->
                throw ContactError.DuplicateAddress(existing.id)
            }

            val walletId = if (scopedToActiveWallet) {
                walletRepository.activeWalletIdSnapshot()?.takeIf { it.isNotEmpty() }
                    ?: throw ContactError.NoActiveWallet
            } else null

            val now = nowProvider()
            val entity = ContactEntity(
                id = UUID.randomUUID().toString(),
                walletId = walletId,
                name = trimmedName,
                address = address,
                network = addressNetwork.name.lowercase(),
                notes = trimmedNotes,
                tags = tags?.joinToString(","),
                createdAt = now,
                updatedAt = now,
                lastUsedAt = null,
                useCount = 0,
            )
            contactDao.insert(entity)
            entity
        }
    }

    /**
     * Update mutable fields on an existing contact. Address and walletId
     * are immutable — to change either, delete and re-create. This
     * keeps `useCount` / `lastUsedAt` history pegged to the address
     * rather than the label.
     */
    suspend fun update(
        id: String,
        name: String?,
        notes: String?,
        tags: List<String>?,
    ): Result<Unit> = runCatching {
        val existing = contactDao.getById(id) ?: throw ContactError.NotFound(id)
        val newName = name?.trim() ?: existing.name
        if (newName.isEmpty() || newName.length > 64) throw ContactError.InvalidName
        val newNotes = notes?.trim()?.ifEmpty { null } ?: existing.notes
        if (newNotes != null && newNotes.length > 256) throw ContactError.NotesTooLong

        contactDao.update(
            existing.copy(
                name = newName,
                notes = newNotes,
                tags = tags?.joinToString(",") ?: existing.tags,
                updatedAt = nowProvider(),
            )
        )
    }

    suspend fun delete(id: String): Result<Unit> = runCatching {
        val existing = contactDao.getById(id) ?: throw ContactError.NotFound(id)
        contactDao.delete(existing)
    }

    /**
     * Substring search (case-insensitive, infix). Empty query returns
     * empty list — the UI shows recent contacts in that state instead.
     */
    suspend fun search(query: String): List<ContactEntity> {
        val walletId = walletRepository.activeWalletIdSnapshot()?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return contactDao.search(walletId, "%${trimmed}%")
    }

    suspend fun recentlyUsed(): List<ContactEntity> {
        val walletId = walletRepository.activeWalletIdSnapshot()?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        return contactDao.recentlyUsed(walletId)
    }

    /**
     * Bump usage counters for the contact at [address] if one exists.
     * Silent no-op for unsaved recipients — the post-send "save?"
     * prompt covers that path.
     */
    suspend fun markUsed(address: String) {
        try {
            val match = contactDao.getByAddress(address) ?: return
            contactDao.markUsed(match.id, nowProvider())
        } catch (e: Exception) {
            Log.w(TAG, "markUsed failed for address=$address", e)
        }
    }

    companion object {
        private const val TAG = "ContactRepository"
    }
}
