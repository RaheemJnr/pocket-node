package com.rjnr.pocketnode.ui.util

import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.NetworkType

/**
 * The wallet's address encoded for [network].
 *
 * A [WalletEntity] stores both encodings of the same lock script, so any UI
 * that renders an address has to pick the one matching the network the app is
 * actually pointed at. The wallet switcher rendered `mainnetAddress`
 * unconditionally and showed `ckb1...` rows while the app ran on testnet
 * (#489).
 *
 * Falls back to the other encoding when the preferred one is empty — rows
 * written by older versions can carry only one.
 */
fun WalletEntity.addressFor(network: NetworkType): String = when (network) {
    NetworkType.MAINNET -> mainnetAddress.ifEmpty { testnetAddress }
    NetworkType.TESTNET -> testnetAddress.ifEmpty { mainnetAddress }
}

/**
 * Middle-elided address, e.g. `ckt1qzda0c...50xwsq`. Blank in, blank out so
 * callers can skip the row instead of rendering a lone ellipsis.
 */
fun String.truncateAddress(head: Int = 10, tail: Int = 6): String = when {
    isEmpty() -> ""
    length <= head + tail -> this
    else -> "${take(head)}...${takeLast(tail)}"
}
