package com.rjnr.pocketnode.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Address book entry for the M4 Phase 2 contacts feature (#189).
 *
 * ## Scope
 *
 * - `walletId == null`: global contact, visible from every wallet.
 * - `walletId != null`: wallet-scoped contact, only visible when that
 *   wallet is active. Used for per-wallet labels (e.g. "Alice's hot
 *   wallet" vs "Alice's cold wallet" stored under different parent
 *   wallets).
 *
 * The `network` field is denormalized from the bech32 prefix on
 * `address` (`ckb1` → mainnet, `ckt1` → testnet) so queries can filter
 * by network without parsing the address. `ContactRepository` rejects
 * any contact whose address prefix disagrees with `network`.
 *
 * ## Smart suggestions
 *
 * `useCount` and `lastUsedAt` are bumped by [com.rjnr.pocketnode.data.database.dao.ContactDao.markUsed]
 * after a successful Send. The Send autocomplete sorts by
 * `lastUsedAt DESC` so frequently-used recipients surface first.
 */
@Entity(
    tableName = "contacts",
    indices = [
        Index(value = ["address"], name = "idx_contacts_address"),
        Index(value = ["walletId"], name = "idx_contacts_walletId"),
    ],
)
data class ContactEntity(
    @PrimaryKey val id: String,
    val walletId: String?,
    val name: String,
    val address: String,
    val network: String,
    val notes: String?,
    val tags: String?,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "NULL") val lastUsedAt: Long?,
    @ColumnInfo(defaultValue = "0") val useCount: Int,
)
