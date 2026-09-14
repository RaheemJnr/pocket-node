package com.rjnr.pocketnode.data.wallet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.SyncMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WalletPreferencesMultiWalletTest {

    private lateinit var prefs: WalletPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = WalletPreferences(context, NoopLogger)
    }

    @Test
    fun `sync mode is wallet-scoped`() {
        prefs.setSyncMode(SyncMode.FULL_HISTORY, NetworkType.MAINNET, walletId = "wallet-A")
        prefs.setSyncMode(SyncMode.NEW_WALLET, NetworkType.MAINNET, walletId = "wallet-B")

        assertEquals(SyncMode.FULL_HISTORY, prefs.getSyncMode(NetworkType.MAINNET, walletId = "wallet-A"))
        assertEquals(SyncMode.NEW_WALLET, prefs.getSyncMode(NetworkType.MAINNET, walletId = "wallet-B"))
    }

    @Test
    fun `active wallet id persists`() {
        prefs.setActiveWalletId("wallet-123")
        assertEquals("wallet-123", prefs.getActiveWalletId())
    }
}
