package com.rjnr.pocketnode.data.restorehint

import androidx.fragment.app.FragmentActivity
import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.prefs.SyncPreferences
import com.rjnr.pocketnode.data.database.dao.SubAccountCandidateDao
import com.rjnr.pocketnode.data.database.dao.SyncProgressDao
import com.rjnr.pocketnode.data.database.dao.TransactionDao
import com.rjnr.pocketnode.data.database.dao.WalletDao
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.gateway.LightClientReadOnly
import com.rjnr.pocketnode.data.gateway.historicalStartBlock
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
 * Exporting always authenticates through the central wallet reader. The hint MAC uses
 * the actual protected seed (or raw key); device authentication is optional.
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
    private val json: Json,
    private val logger: Logger,
    private val verificationCacheGate: com.rjnr.pocketnode.data.gateway.VerificationCacheGate,
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
        /**
         * Anything else. [reason] is for logs and tests only; the user sees a
         * generic message, never internal text such as a missing-row reason.
         */
        data class Failed(val reason: String) : ExportResult
    }

    suspend fun export(
        activity: FragmentActivity,
        network: NetworkType,
        nowMs: Long = System.currentTimeMillis(),
    ): ExportResult = com.rjnr.pocketnode.data.vault.VaultOperationSession.run(activity) owned@ {
        val revision = verificationCacheGate.currentRevision()
        if (!verificationCacheGate.isReady(network.name)) return@owned ExportResult.Failed("Chain cache verification is not ready")
        val active = walletDao.getActive() ?: return@owned ExportResult.Failed("No active wallet")
        val root = active.parentWalletId?.let { walletDao.getById(it) } ?: active
        val tip = readTip() ?: return@owned ExportResult.NoTip

        val material = try {
            walletKeyReader.readKeyMaterial(
                activity = activity,
                walletId = root.walletId,
                promptTitle = PROMPT_TITLE,
                promptSubtitle = PROMPT_SUBTITLE,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Restore hint key read failed", e)
            return@owned ExportResult.Failed("key read threw ${e::class.simpleName}")
        }
        val secret = when (material) {
            is WalletKeyReader.MaterialResult.Success -> try {
                if (walletDao.getActive() != active || walletDao.getById(root.walletId) != root) {
                    return@owned ExportResult.Failed("Active wallet changed. Try again.")
                }
                secretFor(root, material) ?: return@owned ExportResult.Failed("recovery phrase unavailable")
            } finally {
                material.privateKey.fill(0)
            }
            is WalletKeyReader.MaterialResult.Cancelled -> return@owned ExportResult.Cancelled
            is WalletKeyReader.MaterialResult.KeyInvalidated -> return@owned ExportResult.KeyInvalidated
            is WalletKeyReader.MaterialResult.AuthError -> return@owned ExportResult.Failed("auth error ${material.errorCode}")
            is WalletKeyReader.MaterialResult.NotAvailable -> return@owned ExportResult.Failed(material.reason)
        }

        return@owned try {
            val payload = buildPayload(root, network, tip.first, tip.second, nowMs)
            check(verificationCacheGate.currentRevision() == revision && verificationCacheGate.isReady(network.name)) { "Chain cache verification changed" }
            ExportResult.Ready(RestoreHintFormat.fileName(network.name), RestoreHintCodec.seal(payload, secret))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Restore hint export failed", e)
            ExportResult.Failed("build threw ${e::class.simpleName}")
        } finally {
            secret.wipe()
        }
    }

    /**
     * The payload for [root] on [network], read from Room and the per-wallet
     * sync prefs.
     *
     * Coverage is NOT just `sync_progress.lightStartBlockNumber`: every
     * registration rewrites that column to the block it resumed from, so
     * after a restart it sits near the tip. Each account's coverage is the
     * lower of that column and the start its own sync mode implies
     * ([historicalStartBlock], FULL_HISTORY = 0). A sub-account with no mode
     * of its own falls back to its root's mode-derived start, and a root with
     * none to 0. Heights are then rounded down to
     * [RestoreHintFormat.HEIGHT_GRANULARITY]; every step only moves a start
     * earlier.
     */
    suspend fun buildPayload(
        root: WalletEntity,
        network: NetworkType,
        tipHeight: Long,
        tipHash: String,
        nowMs: Long,
    ): RestoreHintPayload {
        val revision = verificationCacheGate.currentRevision()
        check(verificationCacheGate.isReady(network.name)) { "Chain cache verification is not ready" }
        val isMnemonic = root.type == KeyManager.WALLET_TYPE_MNEMONIC
        val subAccounts = if (isMnemonic) walletDao.getSubAccountsList(root.walletId) else emptyList()

        val rootModeStart = modeDerivedStart(root.walletId, network, tipHeight)
        val accounts = buildList {
            add(accountFor(root, index = 0, network, tipHeight, fallbackModeStart = 0L))
            subAccounts.forEach { sub ->
                add(accountFor(sub, sub.accountIndex, network, tipHeight, fallbackModeStart = rootModeStart ?: 0L))
            }
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

        check(verificationCacheGate.isReady(network.name) && verificationCacheGate.currentRevision() == revision) { "Chain cache verification changed" }
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

    private suspend fun accountFor(
        wallet: WalletEntity,
        index: Int,
        network: NetworkType,
        tipHeight: Long,
        fallbackModeStart: Long,
    ): RestoreHintAccount {
        val registeredFrom = syncProgressDao.get(wallet.walletId, network.name)?.lightStartBlockNumber
        val modeStart = modeDerivedStart(wallet.walletId, network, tipHeight) ?: fallbackModeStart
        val coverage = minOf(registeredFrom ?: Long.MAX_VALUE, modeStart).coerceAtLeast(0L)
        return RestoreHintAccount(
            index = index,
            coverageStart = RestoreHintFormat.coarsen(coverage),
            firstActivity = earliestActivity(wallet.walletId, network)?.let(RestoreHintFormat::coarsen),
            syncMode = syncPreferences.getSyncMode(network, wallet.walletId).name,
        )
    }

    /** The start this wallet's own saved sync mode implies, or null when it has none on [network]. */
    private fun modeDerivedStart(walletId: String, network: NetworkType, tipHeight: Long): Long? {
        val mode = syncPreferences.getSyncModeOrNull(network, walletId) ?: return null
        return historicalStartBlock(
            mode,
            syncPreferences.getCustomBlockHeight(network, walletId),
            tipHeight,
            network,
        ).coerceAtLeast(0L)
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
