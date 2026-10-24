package com.rjnr.pocketnode.ui.security

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.rjnr.pocketnode.core.crypto.hexToByteArray
import com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents
import kotlinx.coroutines.CancellationException
import java.util.Locale
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.restorehint.RestoreHintImporter
import com.rjnr.pocketnode.data.restorehint.RestoreHintSecret
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.WalletKeyWriter
import com.rjnr.pocketnode.data.wallet.WalletRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class MnemonicHintDeriver @Inject constructor() {
    suspend fun derive(words: List<String>): RestoreHintSecret {
        var owned: RestoreHintSecret? = null
        try {
            val result = withContext(Dispatchers.Default) { RestoreHintSecret.fromMnemonic(words).also { owned = it } }
            owned = null
            return result
        } finally { owned?.wipe() }
    }
}

/** Native input and cryptographic import/hint material stay inside one foreground operation. */
class WalletImportOperations @Inject constructor(
    private val input: RecoverySecretDialog,
    private val wallets: WalletRepository,
    private val writer: WalletKeyWriter,
    private val mnemonics: MnemonicManager,
    private val hints: RestoreHintImporter,
    private val hintDeriver: MnemonicHintDeriver,
) {
    sealed interface Outcome {
        data class Completed(val hint: RestoreHintImporter.Verification?) : Outcome
        data class Failed(val message: String) : Outcome
        data object Cancelled : Outcome
    }
    suspend fun import(
        activity: FragmentActivity, privateKey: Boolean, name: String, hintText: String?, network: NetworkType,
        onImported: suspend (WalletEntity, String?) -> Unit,
    ): Outcome {
        val lock = ReauthLockEvents.locks.value
        fun foreground() = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && ReauthLockEvents.locks.value == lock
        if (!foreground()) return Outcome.Cancelled
        val inputChars = input.requestImport(activity, privateKey) ?: return Outcome.Cancelled
        try {
            if (!foreground()) return Outcome.Cancelled
            val text = inputChars.concatToString().trim().lowercase(Locale.ROOT)
            inputChars.fill('\u0000')
            val words = if (privateKey) emptyList() else text.split(Regex("\\s+"))
            val hex = if (privateKey) text.removePrefix("0x") else ""
            if (!privateKey && !mnemonics.validateMnemonic(words)) return Outcome.Failed("Invalid recovery phrase. Check your words and try again.")
            if (privateKey && (hex.length != 64 || hex.any { it !in "0123456789abcdef" })) return Outcome.Failed("Invalid private key. Enter 64 hexadecimal characters.")
            val previous = wallets.getActive()?.walletId
            val persist: suspend (WalletRepository.Creation, com.rjnr.pocketnode.data.crypto.WalletKeyBundle) -> WalletKeyWriter.Result = { creation, bundle ->
                writer.persistCreatedWallet(activity, creation, bundle, if (privateKey) "raw_key" else "mnemonic", !privateKey,
                    "Secure imported wallet", "Protect this wallet with your independent wallet passphrase.")
            }
            val result = if (privateKey) wallets.importRawKey(hex, name, persist)
                else wallets.importFromMnemonic(words, name, persist)
            val wallet = result.getOrThrow()
            // Publish only durable public identity before hint derivation; sync/rollback remains available even if a lock discards the hint.
            onImported(wallet, previous)
            if (hintText == null || !foreground()) return Outcome.Completed(null)
            val hintSecret = if (privateKey) {
                val bytes = hex.hexToByteArray()
                try { RestoreHintSecret.fromPrivateKey(bytes) } finally { bytes.fill(0) }
            } else hintDeriver.derive(words)
            val verification = try {
                if (!foreground()) return Outcome.Completed(null)
                hints.verify(hintText, hintSecret, network)
            } finally { hintSecret.wipe() }
            return Outcome.Completed(verification.takeIf { foreground() })
        } catch (e: CancellationException) { throw e }
        catch (e: WalletKeyWriter.PersistException) {
            return if (e.result == WalletKeyWriter.Result.Cancelled) Outcome.Cancelled
                else Outcome.Failed("Could not protect the imported wallet. Authenticate and try again.")
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            return Outcome.Failed(if (message.startsWith("A wallet named ") || message.startsWith("This wallet is already imported as ")) message
                else "Could not import this wallet. Check your recovery material and try again.")
        } finally { inputChars.fill('\u0000') }
    }
}
