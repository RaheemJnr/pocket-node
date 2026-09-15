package com.rjnr.pocketnode.ui.util

import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #489: the wallet switcher listed every account with its mainnet (`ckb1`)
 * encoding even while the app was on testnet. The row now asks for the
 * address of the active network.
 */
class WalletAddressFormatTest {

    private val mainnet =
        "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqdtyq04tvp02wectaumxn0664yw2jd53lqk508kg"
    private val testnet =
        "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqfqyerlanzmnkxtmd6wgr9ylkz0aalst2gq30ehv"

    private fun wallet(
        mainnetAddress: String = mainnet,
        testnetAddress: String = testnet,
    ) = WalletEntity(
        walletId = "w1",
        name = "Main",
        type = "mnemonic",
        derivationPath = "m/44'/309'/0'/0/0",
        parentWalletId = null,
        mainnetAddress = mainnetAddress,
        testnetAddress = testnetAddress,
    )

    @Test
    fun `testnet renders the ckt1 encoding`() {
        val address = wallet().addressFor(NetworkType.TESTNET)
        assertEquals(testnet, address)
        assertTrue(address.startsWith("ckt1"))
    }

    @Test
    fun `mainnet renders the ckb1 encoding`() {
        val address = wallet().addressFor(NetworkType.MAINNET)
        assertEquals(mainnet, address)
        assertTrue(address.startsWith("ckb1"))
    }

    @Test
    fun `testnet falls back to mainnet encoding when testnet address is missing`() {
        assertEquals(mainnet, wallet(testnetAddress = "").addressFor(NetworkType.TESTNET))
    }

    @Test
    fun `mainnet falls back to testnet encoding when mainnet address is missing`() {
        assertEquals(testnet, wallet(mainnetAddress = "").addressFor(NetworkType.MAINNET))
    }

    @Test
    fun `both empty yields empty so the row can be skipped`() {
        assertEquals("", wallet(mainnetAddress = "", testnetAddress = "").addressFor(NetworkType.TESTNET))
    }

    @Test
    fun `truncation keeps the hrp visible and elides the middle`() {
        assertEquals("ckt1qzda0c...q30ehv", testnet.truncateAddress())
        assertEquals("ckb1qzda0c...k508kg", mainnet.truncateAddress())
    }

    @Test
    fun `short strings are returned untouched`() {
        assertEquals("", "".truncateAddress())
        assertEquals("ckt1short", "ckt1short".truncateAddress())
    }
}
