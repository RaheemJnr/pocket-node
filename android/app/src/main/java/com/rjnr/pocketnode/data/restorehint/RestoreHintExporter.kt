package com.rjnr.pocketnode.data.restorehint

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.prefs.SyncPreferences
import com.rjnr.pocketnode.data.auth.AuthManager
import com.rjnr.pocketnode.data.crypto.KeystoreEncryptionManager
import com.rjnr.pocketnode.data.database.dao.KeyMaterialDao
import com.rjnr.pocketnode.data.database.dao.SubAccountCandidateDao
import com.rjnr.pocketnode.data.database.dao.SyncProgressDao
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.LightClientReadOnly
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.wallet.KeyManager
import com.rjnr.pocketnode.data.wallet.WalletKeyReader
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the restore hint file for the active wallet (#559).
 *
 * The file covers the active wallet's root (the wallet itself, or its parent
 * when a sub-account is active) and the root's sub-accounts, on the current
 * network. It holds block heights and indices only; see [RestoreHintPayload].
 *
 * Exporting asks for the same authentication as revealing the recovery
 * phrase: the hint is MAC'd with a key derived from the seed (or the private
 * key of a raw-key wallet), so the secret has to be read, and a V2 wallet can
 * only be read through its BiometricPrompt. A V1 wallet decrypts without one,
 * so it gets the same device-credential prompt the phrase reveal shows; with
 * no screen lock at all the export is refused rather than run ungated. Nothing
 * here writes key material.
 */
@Singleton
class RestoreHintExporter @Inject constructor(
    private val walletDao: WalletDao,
    private val syncProgressDao: SyncProgressDao,
    private val transactionDao: TransactionDao,
    private val subAccountCandidateDao: SubAccountCandidateDao,
    private val syncPreferences: SyncPreferences,
    private val lightClient: LightClientReadOnly,
    private val walletKeyReader: WalletKeyReader,
    private val keyMaterialDao: KeyMaterialDao,
    private val authManager: AuthManager,
    private val encryptionManager: KeystoreEncryptionManager,
    private val json: Json,
    private val logger: Logger,
) {

    sealed interface ExportResult {
        /** The file is ready; save [text] under [fileName]. */
        data class Ready(val fileName: String, val text: String) : ExportResult
        object Cancelled : ExportResult
        /** No screen lock: there is nothing to authenticate the export with. */
        object NeedsScreenLock : ExportResult
        /** The light client has no tip yet, so the file would have no anchor. */
        object NoTip : ExportResult
        object KeyInvalidated : ExportResult
        data class Failed(val reason: String) : ExportResult
    }

    suspend fun export(
        activity: FragmentActivity,
        network: NetworkType,
        nowMs: Long = System.currentTimeMillis(),
    ): ExportResult {
        val active = walletDao.getActive() ?: return ExportResult.Failed("No active wallet")
        val root = active.parentWalletId?.let { walletDao.getById(it) } ?: active
        val tip = readTip() ?: return ExportResult.NoTip

        val kdf = runCatching { keyMaterialDao.getKdfVersion(root.walletId) }.getOrNull()
        // V2: the BiometricPrompt inside readKeyMaterial is the gate.
        if (kdf != 2) deviceAuthGate(activity)?.let { return it }

        val material = walletKeyReader.readKeyMaterial(
            activity = activity,
            walletId = root.walletId,
            promptTitle = PROMPT_TITLE,
            promptSubtitle = PROMPT_SUBTITLE,
        )
        val secret = when (material) {
            is WalletKeyReader.MaterialResult.Success -> try {
                secretFor(root, material) ?: return ExportResult.Failed("Recovery phrase unavailable for this wallet")
            } finally {
                material.privateKey.fill(0)
            }
            is WalletKeyReader.MaterialResult.Cancelled -> return ExportResult.Cancelled
            is WalletKeyReader.MaterialResult.KeyInvalidated -> return ExportResult.KeyInvalidated
            is WalletKeyReader.MaterialResult.AuthError -> return ExportResult.Failed(material.message.toString())
            is WalletKeyReader.MaterialResult.NotAvailable -> return ExportResult.Failed(material.reason)
        }

        return try {
            val payload = buildPayload(root, network, tip.first, tip.second, nowMs)
            ExportResult.Ready(RestoreHintFormat.fileName(network.name), RestoreHintCodec.seal(payload, secret))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Restore hint export failed", e)
            ExportResult.Failed(e.message ?: "export failed")
        } finally {
            secret.wipe()
        }
    }

    /**
     * The payload for [root] on [network], read from Room. Sub-accounts the
     * source never synced on this network are left out: the source covered
     * nothing for them, and the restore never has to cover more than the
     * source did. The root is always present; with no progress row its
     * coverage is 0, the conservative answer.
     */
    suspend fun buildPayload(
        root: WalletEntity,
        network: NetworkType,
        tipHeight: Long,
        tipHash: String,
        nowMs: Long,
    ): RestoreHintPayload {
        val isMnemonic = root.type == KeyManager.WALLET_TYPE_MNEMONIC
        val subAccounts = if (isMnemonic) walletDao.getSubAccountsList(root.walletId) else emptyList()

        val accounts = buildList {
            add(accountFor(root, index = 0, network) ?: RestoreHintAccount(
                index = 0,
                coverageStart = 0L,
                firstActivity = earliestActivity(root.walletId, network),
                syncMode = syncPreferences.getSyncMode(network, root.walletId).name,
            ))
            subAccounts.forEach { sub -> accountFor(sub, sub.accountIndex, network)?.let(::add) }
        }

        val discovery = if (isMnemonic) {
            val accountAxis = subAccountCandidateDao.getForParent(root.walletId).filter { it.accountIndex > 0 }
            RestoreHintDiscovery(
                found = accountAxis
                    .filter {
                        it.state == SubAccountCandidateEntity.STATE_FOUND ||
                            it.state == SubAccountCandidateEntity.STATE_RESTORED
                    }
                    .map { it.accountIndex }
                    .distinct()
                    .sorted(),
                highestScanned = accountAxis
                    .filter { it.registeredFromBlock > 0 || it.state != SubAccountCandidateEntity.STATE_PENDING }
                    .maxOfOrNull { it.accountIndex } ?: 0,
            )
        } else {
            RestoreHintDiscovery()
        }

        return RestoreHintPayload(
            network = network.name,
            createdAtMs = nowMs,
            tipHeight = tipHeight,
            tipHash = tipHash,
            kind = if (isMnemonic) RestoreHintKind.MNEMONIC else RestoreHintKind.RAW_KEY,
            accounts = accounts,
            discovery = discovery,
        )
    }

    private suspend fun accountFor(wallet: WalletEntity, index: Int, network: NetworkType): RestoreHintAccount? {
        val progress = syncProgressDao.get(wallet.walletId, network.name) ?: return null
        return RestoreHintAccount(
            index = index,
            coverageStart = progress.lightStartBlockNumber.coerceAtLeast(0L),
            firstActivity = earliestActivity(wallet.walletId, network),
            syncMode = syncPreferences.getSyncMode(network, wallet.walletId).name,
        )
    }

    private suspend fun earliestActivity(walletId: String, network: NetworkType): Long? =
        transactionDao.getBlockNumbers(walletId, network.name)
            .mapNotNull { it.removePrefix("0x").toLongOrNull(16) }
            .filter { it > 0L }
            .minOrNull()

    private fun secretFor(root: WalletEntity, material: WalletKeyReader.MaterialResult.Success): RestoreHintSecret? =
        if (root.type == KeyManager.WALLET_TYPE_MNEMONIC) {
            val words = material.mnemonic?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+"))
            words?.let { RestoreHintSecret.fromMnemonic(it) }
        } else {
            RestoreHintSecret.fromPrivateKey(material.privateKey)
        }

    /** Null when the prompt passed; otherwise the result to return. */
    private suspend fun deviceAuthGate(activity: FragmentActivity): ExportResult? {
        if (!authManager.isBiometricEnrolled() && !authManager.hasDeviceCredential()) {
            return ExportResult.NeedsScreenLock
        }
        val cipher = try {
            encryptionManager.newEncryptCipherV2()
        } catch (e: Throwable) {
            logger.e(TAG, "Auth cipher creation failed", e)
            return ExportResult.Failed(e.message ?: "cipher")
        }
        return when (val auth = authManager.authenticateForCipher(activity, cipher, PROMPT_TITLE, PROMPT_SUBTITLE)) {
            is AuthManager.CipherAuthResult.Success -> null
            is AuthManager.CipherAuthResult.Cancelled -> ExportResult.Cancelled
            is AuthManager.CipherAuthResult.Error -> ExportResult.Failed(auth.errString.toString())
        }
    }

    private suspend fun readTip(): Pair<Long, String>? = try {
        lightClient.getTipHeader()?.let { raw ->
            val header = json.decodeFromString<JniHeaderView>(raw)
            val number = header.number.removePrefix("0x").toLong(16)
            if (number > 0L) number to header.hash else null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(TAG, "Tip header unavailable: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "RestoreHintExporter"
        private const val PROMPT_TITLE = "Export restore hint"
        private const val PROMPT_SUBTITLE = "Verify your identity to sign the restore hint with this wallet's key"
    }
}
