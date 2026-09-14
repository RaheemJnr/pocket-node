package com.rjnr.pocketnode.data.validation

import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.wallet.AddressUtils

class NetworkValidator {

    fun validateTransferAddresses(
        fromAddress: String,
        toAddress: String,
        expectedNetwork: NetworkType
    ): Result<NetworkType> = runCatching {
        if (!AddressUtils.isValid(fromAddress)) {
            throw IllegalArgumentException("Invalid sender address")
        }
        if (!AddressUtils.isValid(toAddress)) {
            throw IllegalArgumentException("Invalid recipient address")
        }

        val fromNetwork = AddressUtils.getNetwork(fromAddress)
            ?: throw IllegalArgumentException("Cannot determine network for sender address")
        val toNetwork = AddressUtils.getNetwork(toAddress)
            ?: throw IllegalArgumentException("Cannot determine network for recipient address")

        if (fromNetwork != toNetwork) {
            throw IllegalArgumentException(
                "Network mismatch: sender is on ${fromNetwork.name.lowercase()} " +
                        "but recipient is on ${toNetwork.name.lowercase()}"
            )
        }
        if (fromNetwork != expectedNetwork) {
            throw IllegalArgumentException(
                "Address is on ${fromNetwork.name.lowercase()} but wallet is configured for ${expectedNetwork.name.lowercase()}"
            )
        }

        fromNetwork
    }
}
