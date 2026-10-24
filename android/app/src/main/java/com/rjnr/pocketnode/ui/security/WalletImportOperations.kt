package com.rjnr.pocketnode.ui.security

import androidx.fragment.app.FragmentActivity
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
import kotlinx.coroutines.ensureActive
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
    private data class Prepared(val wallet: WalletEntity?, val previous: String?, val outcome: Outcome)

    suspend fun import(
        activity: FragmentActivity, privateKey: Boolean, name: String, hintText: String?, network: NetworkType,
        onCommitted: (WalletEntity, String?) -> Unit = { _, _ -> },
        onImported: suspend (WalletEntity, String?) -> Unit,
    ): Outcome = com.rjnr.pocketnode.data.vault.VaultOperationSession.run(activity) owned@ {
        val lock = ReauthLockEvents.locks.value
        val inputChars = input.requestImport(activity, privateKey) ?: return@owned Outcome.Cancelled
        try {
            // This function returns only public data. No recovery words or hint key can
            // survive into the potentially long gateway-registration continuation below.
            val prepared = consume(activity, lock, inputChars, privateKey, name, hintText, network, onCommitted)
            val wallet = prepared.wallet ?: return@owned prepared.outcome
            return@owned WalletDerivationSession.run(activity, lock) {
                onImported(wallet, prepared.previous)
                prepared.outcome
            }
        } catch (e: CancellationException) { throw e }
        catch (e: WalletKeyWriter.PersistException) {
            return@owned if (e.result == WalletKeyWriter.Result.Cancelled) Outcome.Cancelled
                else Outcome.Failed("Could not protect the imported wallet. Authenticate and try again.")
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            return@owned Outcome.Failed(if (message.startsWith("A wallet named ") || message.startsWith("This wallet is already imported as ")) message
                else "Could not import this wallet. Check your recovery material and try again.")
        } finally { inputChars.fill('\u0000') }
    }

    private suspend fun consume(
        activity: FragmentActivity, lock: Int, inputChars: CharArray, privateKey: Boolean,
        name: String, hintText: String?, network: NetworkType,
        onCommitted: (WalletEntity, String?) -> Unit,
    ): Prepared = WalletDerivationSession.run(activity, lock) { session ->
        val text = inputChars.concatToString().trim().lowercase(Locale.ROOT)
        inputChars.fill('\u0000')
        val words = if (privateKey) emptyList() else text.split(Regex("\\s+"))
        val hex = if (privateKey) text.removePrefix("0x") else ""
        if (!privateKey && !mnemonics.validateMnemonic(words))
            return@run Prepared(null, null, Outcome.Failed("Invalid recovery phrase. Check your words and try again."))
        if (privateKey && (hex.length != 64 || hex.any { it !in "0123456789abcdef" }))
            return@run Prepared(null, null, Outcome.Failed("Invalid private key. Enter 64 hexadecimal characters."))
        val previous = wallets.getActive()?.walletId
        var committed: WalletEntity? = null
        var published = false
        try {
            val persist: suspend (WalletRepository.Creation, com.rjnr.pocketnode.data.crypto.WalletKeyBundle) -> WalletKeyWriter.Result = { creation, bundle ->
                session.persist {
                    writer.persistCreatedWallet(activity, creation, bundle, if (privateKey) "raw_key" else "mnemonic", !privateKey,
                        "Secure imported wallet", "Protect this wallet with your independent wallet passphrase.").also {
                        if (it == WalletKeyWriter.Result.Success) committed = creation.wallet
                    }
                }
            }
            val wallet = (if (privateKey) wallets.importRawKey(hex, name, persist)
                else wallets.importFromMnemonic(words, name, persist)).getOrThrow()
            onCommitted(wallet, previous)
            published = true
            if (hintText == null) return@run Prepared(wallet, previous, Outcome.Completed(null))
            val hintSecret = if (privateKey) {
                val bytes = hex.hexToByteArray()
                try { RestoreHintSecret.fromPrivateKey(bytes) } finally { bytes.fill(0) }
            } else hintDeriver.derive(words)
            val verification = try {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (ReauthLockEvents.locks.value != lock) throw CancellationException("Wallet operation locked")
                hints.verify(hintText, hintSecret, network)
            } finally { hintSecret.wipe() }
            Prepared(wallet, previous, Outcome.Completed(verification))
        } finally {
            // A lifecycle cancellation after durable activation must not hide rollback
            // identity. This callback is synchronous and accepts public metadata only.
            if (!published) committed?.let { onCommitted(it, previous) }
        }
    }
}
