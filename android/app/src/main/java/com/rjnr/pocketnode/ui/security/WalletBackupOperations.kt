package com.rjnr.pocketnode.ui.security

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import com.rjnr.pocketnode.data.wallet.WalletKeyReader
import com.rjnr.pocketnode.data.wallet.WalletRepository
import javax.inject.Inject

/** Public results only; authentication, native reveal and backup marking share one operation. */
class WalletBackupOperations @Inject constructor(
    private val reader: WalletKeyReader,
    private val wallets: WalletRepository,
    private val dialog: WalletSecretDialog,
) {
    sealed interface Outcome {
        data class Shown(val hasMnemonic: Boolean, val backedUp: Boolean) : Outcome
        data object Cancelled : Outcome
        data class Failed(val message: String) : Outcome
    }
    suspend fun show(activity: FragmentActivity, walletId: String, verify: Boolean): Outcome {
        val original = wallets.getById(walletId) ?: return Outcome.Failed("Wallet unavailable")
        val lock = ReauthLockEvents.locks.value
        fun foreground() = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && ReauthLockEvents.locks.value == lock
        if (!foreground()) return Outcome.Cancelled
        return try {
            when (val material = reader.readKeyMaterial(activity, walletId, "View recovery material", "Authenticate this wallet")) {
                is WalletKeyReader.MaterialResult.Success -> {
                    val hasMnemonic = !material.mnemonic.isNullOrBlank()
                    val secret = try {
                        if (hasMnemonic) material.mnemonic!!.toCharArray()
                        else material.privateKey.joinToString("") { "%02x".format(it) }.toCharArray()
                    } finally { material.privateKey.fill(0) }
                    try {
                        coroutineScope {
                            val operation = coroutineContext[Job]!!
                            val lockObserver = launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
                                ReauthLockEvents.locks.collect { if (it != lock) operation.cancel() }
                            }
                            try {
                                com.rjnr.pocketnode.data.vault.VaultOperationSession.run(activity) {
                                    if (!foreground() || wallets.getById(walletId) != original) return@run Outcome.Cancelled
                                    val verified = dialog.show(activity, secret, hasMnemonic, verify)
                                    if (!foreground() || wallets.getById(walletId) != original) return@run Outcome.Cancelled
                                    val backedUp = verify && hasMnemonic && verified
                                    if (backedUp && (material.backupWitness == null || !wallets.markVerifiedMnemonicBackup(material.backupWitness)))
                                        return@run Outcome.Failed("Wallet protection changed. Authenticate and verify your backup again.")
                                    Outcome.Shown(hasMnemonic, backedUp)
                                }
                            } finally { lockObserver.cancel() }
                        }
                    } finally { secret.fill('\u0000') }
                }
                WalletKeyReader.MaterialResult.Cancelled -> Outcome.Cancelled
                else -> Outcome.Failed("Could not authenticate this wallet. Use wallet recovery if access remains unavailable.")
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Outcome.Failed("Could not complete wallet backup. Please retry.") }
    }
}
