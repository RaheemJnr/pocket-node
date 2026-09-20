package com.rjnr.pocketnode.data.wallet

import com.rjnr.pocketnode.data.gateway.models.Script

/**
 * A wallet's derived public identity: compressed public key, secp256k1-blake160
 * lock script, and the address for each network.
 *
 * `publicKey` is `0x`-prefixed lowercase hex, or an empty string when the value
 * was recovered from cached addresses rather than from key material (see
 * [WalletDerivation.walletInfoFromAddresses]).
 */
data class WalletInfo(
    val publicKey: String,
    val script: Script,
    val testnetAddress: String,
    val mainnetAddress: String
)
