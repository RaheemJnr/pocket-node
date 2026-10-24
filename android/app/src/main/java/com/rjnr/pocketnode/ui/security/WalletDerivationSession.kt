package com.rjnr.pocketnode.ui.security

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.data.vault.VaultOperationSession
import com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents
import kotlinx.coroutines.CancellationException

/** Nested owners delegate only the bounded credential prompt, never the whole writer operation. */
internal class WalletDerivationSession private constructor() {
    suspend fun <T> persist(block: suspend () -> T): T = block()
    companion object {
        suspend fun <T> run(activity: FragmentActivity, lock: Int, block: suspend (WalletDerivationSession) -> T): T =
            VaultOperationSession.run(activity) {
                if (ReauthLockEvents.locks.value != lock) throw CancellationException("Wallet operation locked")
                block(WalletDerivationSession())
            }
    }
}
