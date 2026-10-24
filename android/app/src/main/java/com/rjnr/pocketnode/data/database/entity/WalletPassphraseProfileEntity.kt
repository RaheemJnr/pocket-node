package com.rjnr.pocketnode.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wallet_passphrase_profile")
data class WalletPassphraseProfileEntity(@PrimaryKey val id: Int = 1, val envelope: ByteArray) {
    override fun toString() = "WalletPassphraseProfileEntity([redacted])"
}
