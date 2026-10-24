package com.rjnr.pocketnode.data.wallet

import com.rjnr.pocketnode.data.crypto.KeyMaterial
import com.rjnr.pocketnode.data.crypto.WalletKeyBundle
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity
import com.rjnr.pocketnode.data.vault.WalletEnvelopeCrypto
import com.rjnr.pocketnode.data.vault.WalletVaultStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Test foreground credential boundary; real crypto, readback and atomic storage remain active. */
internal suspend fun persistVerifiedFixture(db: AppDatabase, creation: WalletRepository.Creation,
    bundle: WalletKeyBundle, backedUp: Boolean = false): WalletKeyWriter.Result {
    val crypto = WalletEnvelopeCrypto()
    val phrase = "public test wallet passphrase".toCharArray()
    val material = KeyMaterial(bundle.privateKeyHex, bundle.mnemonic,
        if (bundle.mnemonic == null) "raw_key" else "mnemonic", backedUp)
    val bytes = Json.encodeToString(material).toByteArray()
    return try {
        val envelope = crypto.create(creation.walletId, 0, bytes, phrase)
        WalletVaultStore(db, crypto, kotlinx.coroutines.Dispatchers.Unconfined).activate(KeyMaterialEntity(creation.walletId, byteArrayOf(), null,
            byteArrayOf(), creation.wallet.type, backedUp, 0), null, envelope, phrase, creation)
        WalletKeyWriter.Result.Success
    } finally { bytes.fill(0); phrase.fill('\u0000') }
}
