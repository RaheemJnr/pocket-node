package com.rjnr.pocketnode.ui.security

import com.rjnr.pocketnode.data.restorehint.RestoreHintImporter
import com.rjnr.pocketnode.data.restorehint.RestoreHintSecret
import com.rjnr.pocketnode.data.wallet.MnemonicManager
import com.rjnr.pocketnode.data.wallet.WalletKeyWriter
import com.rjnr.pocketnode.data.wallet.WalletRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ensureActive

/** Test-only input device. Public fixture text never enters the production ViewModel. */
class NativeImportFixture {
    private var phrase = List(12) { "" }.toMutableList()
    private var key = ""
    val issued = mutableListOf<CharArray>()
    var deriveMnemonicHintSecret: suspend (List<String>) -> RestoreHintSecret = { RestoreHintSecret.fromMnemonic(it) }
    private val input = mockk<RecoverySecretDialog>()
    private val deriver = mockk<MnemonicHintDeriver>()
    init {
        coEvery { input.requestImport(any(), any()) } coAnswers {
            (if (secondArg<Boolean>()) key else phrase.joinToString(" ")).toCharArray().also { issued += it }
        }
        coEvery { deriver.derive(any()) } coAnswers {
            var owned: RestoreHintSecret? = null
            try {
                val result = deriveMnemonicHintSecret(firstArg()).also { owned = it }
                // Model the production deriver's cancellation-safe dispatcher handoff:
                // MockK must not discard a newly allocated secret as a cancelled return.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                owned = null
                result
            } finally { owned?.wipe() }
        }
    }
    fun word(index: Int, text: String) { phrase[index] = text }
    fun phrase(text: String) { phrase = text.trim().split(Regex("\\s+")).toMutableList() }
    fun key(text: String) { key = text }
    fun operations(wallets: WalletRepository, writer: WalletKeyWriter, mnemonics: MnemonicManager, hints: RestoreHintImporter) =
        WalletImportOperations(input, wallets, writer, mnemonics, hints, deriver)
}
