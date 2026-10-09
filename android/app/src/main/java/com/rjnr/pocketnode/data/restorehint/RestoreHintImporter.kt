package com.rjnr.pocketnode.data.restorehint

import com.rjnr.pocketnode.core.log.Logger
import com.rjnr.pocketnode.core.prefs.SyncPreferences
import com.rjnr.pocketnode.data.database.dao.SubAccountCandidateDao
import com.rjnr.pocketnode.data.database.entity.SubAccountCandidateEntity
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.wallet.SubAccountDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uses a restore hint file during a phrase or private-key import (#559).
 *
 * The file is picked on the import screen, before any secret exists, and
 * only its text is held. Once the import has derived the secret, [verify]
 * checks the file against it, turns it into a [RestoreHintPlan], and derives
 * the lock args of the sub-accounts it seeds, so the secret can be wiped
 * straight after. [prepare] then writes those candidates and re-arms the
 * zero-cell rescue rescan; the caller applies the plan's start through the
 * ordinary post-import sync choice (`resyncAccount`), exactly as if the user
 * had typed that custom height.
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

    /** Where the MAC check and the candidate derivation run; a test seam. */
    internal var computeDispatcher: CoroutineDispatcher = Dispatchers.Default

    sealed interface Verification {
        /**
         * A usable hint. [seeds] are the plan's sub-account candidates, public
         * lock args only, already derived so no secret outlives [verify].
         */
        data class Ready(
            val plan: RestoreHintPlan,
            val seeds: List<SubAccountDiscovery.Candidate>,
        ) : Verification

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

    /** Verifies [fileText] against [secret]. Does not wipe [secret]; the caller owns it. */
    suspend fun verify(fileText: String, secret: RestoreHintSecret, network: NetworkType): Verification =
        withContext(computeDispatcher) {
            when (val opened = RestoreHintCodec.open(fileText, secret, network)) {
                is RestoreHintOpenResult.Valid -> {
                    val plan = RestoreHintPlanner.plan(opened.payload, secret.kind)
                    if (plan == null) {
                        Verification.Rejected(Reason.UNREADABLE)
                    } else {
                        Verification.Ready(plan, seedCandidates(plan, secret))
                    }
                }
                is RestoreHintOpenResult.Rejected -> Verification.Rejected(
                    when (opened.error) {
                        RestoreHintError.BAD_MAC -> Reason.NOT_THIS_WALLET
                        RestoreHintError.WRONG_NETWORK -> Reason.OTHER_NETWORK
                        RestoreHintError.MALFORMED, RestoreHintError.UNSUPPORTED_VERSION -> Reason.UNREADABLE
                    }
                )
            }
        }

    private fun seedCandidates(plan: RestoreHintPlan, secret: RestoreHintSecret): List<SubAccountDiscovery.Candidate> {
        if (plan.seedIndices.isEmpty()) return emptyList()
        val seed = secret.copySeed() ?: return emptyList()
        return try {
            subAccountDiscovery.deriveCandidatesFromSeed(seed, plan.seedIndices)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Seeding is an enhancement; the start height is the product.
            logger.w(TAG, "Restore hint candidate derivation failed (non-fatal)", e)
            emptyList()
        } finally {
            seed.fill(0)
        }
    }

    /**
     * Re-arms [walletId]'s zero-cell rescan and seeds the verified hint's
     * sub-account candidates (best effort). No secret involved.
     */
    suspend fun prepare(walletId: String, ready: Verification.Ready) {
        // Belt and braces: resyncAccount re-arms this too. A hint must never
        // leave the rescue rescan disarmed.
        syncPreferences.clearZeroCellRescanDone(walletId)
        if (ready.seeds.isEmpty()) return
        try {
            val now = System.currentTimeMillis()
            // IGNORE on conflict: indices already in the import window keep
            // their row and state.
            subAccountCandidateDao.insertAll(
                ready.seeds.map {
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
        }
    }

    companion object {
        private const val TAG = "RestoreHintImporter"
    }
}
