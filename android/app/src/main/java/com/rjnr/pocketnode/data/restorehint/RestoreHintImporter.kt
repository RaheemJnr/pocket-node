package com.rjnr.pocketnode.data.restorehint

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.prefs.SyncPreferences
import com.rjnr.pocketnode.data.database.dao.SubAccountCandidateDao
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.wallet.SubAccountDiscovery
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uses a restore hint file during a phrase or private-key import (#559).
 *
 * [verify] checks the file against the secret the user just entered and turns
 * it into a [RestoreHintPlan]. [prepare] then seeds sub-account discovery and
 * re-arms the zero-cell rescue rescan; the caller applies the plan's start
 * through the ordinary post-import sync choice (`resyncAccount`), exactly as
 * if the user had typed that custom height.
 *
 * What a hint never does: copy any flag or progress from the old phone,
 * mark initial sync or the zero-cell rescan as done, or claim a sub-account
 * has activity. Seeded indices go in as PENDING candidates and the chain
 * decides.
 */
@Singleton
class RestoreHintImporter @Inject constructor(
    private val subAccountCandidateDao: SubAccountCandidateDao,
    private val subAccountDiscovery: SubAccountDiscovery,
    private val syncPreferences: SyncPreferences,
    private val logger: Logger,
) {

    sealed interface Verification {
        data class Ready(val plan: RestoreHintPlan) : Verification
        data class Rejected(val reason: Reason) : Verification
    }

    enum class Reason {
        /** "This file does not belong to this recovery phrase." */
        NOT_THIS_WALLET,

        /** "This file is for the other network." */
        OTHER_NETWORK,

        /** "This file could not be read." */
        UNREADABLE,
    }

    fun verify(fileText: String, secret: RestoreHintSecret, network: NetworkType): Verification =
        when (val opened = RestoreHintCodec.open(fileText, secret, network)) {
            is RestoreHintOpenResult.Valid ->
                RestoreHintPlanner.plan(opened.payload, secret.kind)
                    ?.let { Verification.Ready(it) }
                    ?: Verification.Rejected(Reason.UNREADABLE)
            is RestoreHintOpenResult.Rejected -> Verification.Rejected(
                when (opened.error) {
                    RestoreHintError.BAD_MAC -> Reason.NOT_THIS_WALLET
                    RestoreHintError.WRONG_NETWORK -> Reason.OTHER_NETWORK
                    RestoreHintError.MALFORMED, RestoreHintError.UNSUPPORTED_VERSION -> Reason.UNREADABLE
                }
            )
        }

    /**
     * Seeds the plan's sub-account indices for [walletId] (mnemonic secrets
     * only) and re-arms the wallet's zero-cell rescan. Seeding is best effort:
     * the start height is the product, discovery an enhancement.
     */
    suspend fun prepare(walletId: String, plan: RestoreHintPlan, secret: RestoreHintSecret) {
        // Belt and braces: resyncAccount re-arms this too. A hint must never
        // leave the rescue rescan disarmed.
        syncPreferences.clearZeroCellRescanDone(walletId)
        if (plan.seedIndices.isEmpty()) return
        val seed = secret.copySeed() ?: return
        try {
            val now = System.currentTimeMillis()
            val candidates = subAccountDiscovery.deriveCandidatesFromSeed(seed, plan.seedIndices)
            // IGNORE on conflict: indices already in the import window keep
            // their row and state.
            subAccountCandidateDao.insertAll(
                candidates.map {
                    SubAccountCandidateEntity(
                        parentWalletId = walletId,
                        derivationPath = it.derivationPath,
                        accountIndex = it.accountIndex,
                        scriptArgs = it.scriptArgs,
                        createdAt = now,
                    )
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Restore hint discovery seeding failed (non-fatal)", e)
        } finally {
            seed.fill(0)
        }
    }

    companion object {
        private const val TAG = "RestoreHintImporter"
    }
}
