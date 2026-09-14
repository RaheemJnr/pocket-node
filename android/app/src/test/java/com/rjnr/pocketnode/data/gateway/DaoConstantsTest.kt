package com.rjnr.pocketnode.data.gateway

import com.rjnr.pocketnode.data.gateway.models.DaoConstants
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DaoConstantsTest {

    // --- DAO_TYPE_SCRIPT ---

    @Test
    fun `DAO_TYPE_SCRIPT has correct code hash`() {
        assertEquals(
            "0x82d76d1b75fe2fd9a27dfbaa65a039221a380d76c926f378d3f81cf3e7e13f2e",
            DaoConstants.DAO_TYPE_SCRIPT.codeHash
        )
    }

    @Test
    fun `DAO_TYPE_SCRIPT has type hash type`() {
        assertEquals("type", DaoConstants.DAO_TYPE_SCRIPT.hashType)
    }

    @Test
    fun `DAO_TYPE_SCRIPT has empty args`() {
        assertEquals("0x", DaoConstants.DAO_TYPE_SCRIPT.args)
    }

    // --- daoCellDep ---

    @Test
    fun `daoCellDep mainnet returns correct tx hash`() {
        val dep = DaoConstants.daoCellDep(NetworkType.MAINNET)
        assertEquals(
            "0xe2fb199810d49a4d8beec56718ba2593b665db9d52299a0f9e6e75416d73ff5c",
            dep.outPoint.txHash
        )
    }

    @Test
    fun `daoCellDep testnet returns correct tx hash`() {
        val dep = DaoConstants.daoCellDep(NetworkType.TESTNET)
        assertEquals(
            "0x8f8c79eb6671709633fe6a46de93c0fedc9c1b8a6527a18d3983879542635c9f",
            dep.outPoint.txHash
        )
    }

    @Test
    fun `daoCellDep returns index 0x2`() {
        assertEquals("0x2", DaoConstants.daoCellDep(NetworkType.MAINNET).outPoint.index)
        assertEquals("0x2", DaoConstants.daoCellDep(NetworkType.TESTNET).outPoint.index)
    }

    @Test
    fun `daoCellDep returns depType code`() {
        assertEquals("code", DaoConstants.daoCellDep(NetworkType.MAINNET).depType)
        assertEquals("code", DaoConstants.daoCellDep(NetworkType.TESTNET).depType)
    }

    // --- Protocol constants ---

    @Test
    fun `DAO_DEPOSIT_DATA is 8 zero bytes`() {
        assertArrayEquals(ByteArray(8), DaoConstants.DAO_DEPOSIT_DATA)
    }

    @Test
    fun `MIN_DEPOSIT_SHANNONS is 102 CKB`() {
        assertEquals(10_200_000_000L, DaoConstants.MIN_DEPOSIT_SHANNONS)
    }

    @Test
    fun `WITHDRAW_EPOCHS is 180`() {
        assertEquals(180L, DaoConstants.WITHDRAW_EPOCHS)
    }

    @Test
    fun `RESERVE_SHANNONS is 62 CKB`() {
        assertEquals(6_200_000_000L, DaoConstants.RESERVE_SHANNONS)
    }
}
