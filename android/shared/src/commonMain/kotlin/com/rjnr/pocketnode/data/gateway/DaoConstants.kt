package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.CellDep
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.OutPoint
import com.rjnr.pocketnode.data.gateway.models.Script

object DaoConstants {
    // DAO type script — same code hash on both networks
    const val DAO_CODE_HASH = "0x82d76d1b75fe2fd9a27dfbaa65a039221a380d76c926f378d3f81cf3e7e13f2e"
    const val DAO_HASH_TYPE = "type"
    const val DAO_ARGS = "0x"

    // Cell dep tx hashes differ per network
    private const val DAO_CELL_DEP_TX_MAINNET = "0xe2fb199810d49a4d8beec56718ba2593b665db9d52299a0f9e6e75416d73ff5c"
    private const val DAO_CELL_DEP_TX_TESTNET = "0x8f8c79eb6671709633fe6a46de93c0fedc9c1b8a6527a18d3983879542635c9f"
    private const val DAO_CELL_DEP_INDEX = "0x2"

    // Protocol constants
    const val WITHDRAW_EPOCHS = 180L
    const val HOURS_PER_EPOCH = 4
    const val MIN_DEPOSIT_SHANNONS = 10_200_000_000L  // 102 CKB
    const val RESERVE_SHANNONS = 6_200_000_000L       // 62 CKB

    // Occupied capacity of a standard DAO deposit/withdrawing cell:
    // 8 (capacity) + 53 (secp256k1 lock) + 33 (DAO type) + 8 (data) = 102 bytes.
    // This is the non-interest-bearing portion in max-withdraw math; passing
    // anything smaller inflates the computed entitlement and the unlock tx
    // is rejected on-chain (#315, RFC-0023 worked example).
    const val DEPOSIT_OCCUPIED_SHANNONS = 10_200_000_000L // 102 CKB
    val DAO_DEPOSIT_DATA = ByteArray(8)                // 8 zero bytes

    val DAO_TYPE_SCRIPT = Script(
        codeHash = DAO_CODE_HASH,
        hashType = DAO_HASH_TYPE,
        args = DAO_ARGS
    )

    fun daoCellDep(network: NetworkType): CellDep = CellDep(
        outPoint = OutPoint(
            txHash = when (network) {
                NetworkType.MAINNET -> DAO_CELL_DEP_TX_MAINNET
                NetworkType.TESTNET -> DAO_CELL_DEP_TX_TESTNET
            },
            index = DAO_CELL_DEP_INDEX
        ),
        depType = "code"
    )
}
