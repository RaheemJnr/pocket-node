package com.rjnr.pocketnode.data.wallet

import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddressUtilsTest {

    // Standard secp256k1-blake160 lock script params from CLAUDE.md
    private val testScript = Script(
        codeHash = "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
        hashType = "type",
        args = "0x" + "aa".repeat(20) // 20-byte placeholder args
    )

    @Test
    fun encodeMainnetAddressStartsWithCkb1() {
        val address = AddressUtils.encode(testScript, NetworkType.MAINNET)
        assertTrue(address.startsWith("ckb1"), "Mainnet address must start with ckb1, got: $address")
    }

    @Test
    fun encodeTestnetAddressStartsWithCkt1() {
        val address = AddressUtils.encode(testScript, NetworkType.TESTNET)
        assertTrue(address.startsWith("ckt1"), "Testnet address must start with ckt1, got: $address")
    }

    @Test
    fun getNetworkReturnsMainnetForCkb1Address() {
        val address = AddressUtils.encode(testScript, NetworkType.MAINNET)
        assertEquals(NetworkType.MAINNET, AddressUtils.getNetwork(address))
    }

    @Test
    fun getNetworkReturnsTestnetForCkt1Address() {
        val address = AddressUtils.encode(testScript, NetworkType.TESTNET)
        assertEquals(NetworkType.TESTNET, AddressUtils.getNetwork(address))
    }
}
