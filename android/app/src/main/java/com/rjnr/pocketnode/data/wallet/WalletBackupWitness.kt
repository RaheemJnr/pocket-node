package com.rjnr.pocketnode.data.wallet

import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.database.entity.WalletPassphraseProfileEntity
import com.rjnr.pocketnode.data.database.entity.WalletVaultEntity

/** Nonsecret snapshot of an authenticated read, usable only to mark an external mnemonic backup. */
class WalletBackupWitness internal constructor(
    internal val wallet: WalletEntity,
    marker: KeyMaterialEntity,
    vault: WalletVaultEntity,
    profile: WalletPassphraseProfileEntity,
) {
    private val marker = marker.copy(encryptedPrivateKey = marker.encryptedPrivateKey.copyOf(),
        encryptedMnemonic = marker.encryptedMnemonic?.copyOf(), iv = marker.iv.copyOf())
    private val vault = vault.copy(envelope = vault.envelope.copyOf())
    private val profile = profile.copy(envelope = profile.envelope.copyOf())
    internal fun matches(wallet: WalletEntity?, marker: KeyMaterialEntity?, vault: WalletVaultEntity?, profile: WalletPassphraseProfileEntity?): Boolean =
        this.wallet == wallet && marker != null && marker.kdfVersion == 3 && this.marker == marker &&
            vault != null && !vault.cleanupPending && this.vault.walletId == vault.walletId &&
            this.vault.generation == vault.generation && this.vault.profileGeneration == vault.profileGeneration &&
            this.vault.envelope.contentEquals(vault.envelope) && profile != null && this.profile.id == profile.id &&
            this.profile.envelope.contentEquals(profile.envelope)
    override fun toString() = "WalletBackupWitness([redacted])"
}
