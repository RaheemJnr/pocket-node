package com.rjnr.pocketnode.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.rjnr.pocketnode.data.database.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for the M4 Phase 2 address book (#190).
 *
 * ## Scoping
 *
 * Every read query takes a `walletId` and returns rows where
 * `walletId = :walletId OR walletId IS NULL`. The null branch surfaces
 * global contacts that should appear regardless of which wallet is
 * active. Writes always set the walletId explicitly — there's no
 * "current wallet" fallback at this layer.
 *
 * ## Insertion conflict
 *
 * `insert` uses `OnConflictStrategy.ABORT` so duplicate PKs surface as
 * a `SQLiteConstraintException`. The repository layer (#191) treats
 * this as a `DuplicateAddress` validation error and offers the user an
 * update-existing flow rather than silently replacing the row.
 */
@Dao
interface ContactDao {

    /**
     * Observe every contact visible to [walletId] — both wallet-scoped
     * rows and globally-scoped (`walletId IS NULL`) rows — sorted by
     * name (case-insensitive) for the address book list view.
     */
    @Query(
        "SELECT * FROM contacts " +
            "WHERE walletId = :walletId OR walletId IS NULL " +
            "ORDER BY name COLLATE NOCASE ASC"
    )
    fun observeAll(walletId: String): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun getById(id: String): ContactEntity?

    /** Used by the Send flow to check whether a recipient address is already saved. */
    @Query("SELECT * FROM contacts WHERE address = :address LIMIT 1")
    suspend fun getByAddress(address: String): ContactEntity?

    /**
     * Substring search across name and address. `q` is expected to
     * already include `%` wildcards on both sides — the caller controls
     * the match shape (prefix-only, infix, etc.). Capped at 20 results
     * to keep dropdown UIs responsive.
     */
    @Query(
        "SELECT * FROM contacts " +
            "WHERE (walletId = :walletId OR walletId IS NULL) " +
            "AND (name LIKE :q OR address LIKE :q) " +
            "ORDER BY lastUsedAt DESC NULLS LAST " +
            "LIMIT 20"
    )
    suspend fun search(walletId: String, q: String): List<ContactEntity>

    /**
     * Top 5 most-used contacts for the active wallet. Drives the
     * "recent recipients" section of the Send picker.
     */
    @Query(
        "SELECT * FROM contacts " +
            "WHERE walletId = :walletId AND useCount > 0 " +
            "ORDER BY useCount DESC, lastUsedAt DESC " +
            "LIMIT 5"
    )
    suspend fun recentlyUsed(walletId: String): List<ContactEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ContactEntity)

    @Update
    suspend fun update(entity: ContactEntity)

    @Delete
    suspend fun delete(entity: ContactEntity)

    /**
     * Atomic counter bump called after a successful send. Both the
     * count and the timestamp move in the same write so the
     * `recentlyUsed` ordering stays consistent.
     */
    @Query("UPDATE contacts SET useCount = useCount + 1, lastUsedAt = :now WHERE id = :id")
    suspend fun markUsed(id: String, now: Long)

    @Query("SELECT COUNT(*) FROM contacts")
    suspend fun count(): Int
}
