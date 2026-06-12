package com.rjnr.pocketnode.data.gateway.models

import com.rjnr.pocketnode.data.auth.AuthMethod
import kotlinx.serialization.Serializable

// -- 7-state enum (modeled after Neuron's getDAOCellStatus.ts) --

enum class DaoCellStatus {
    DEPOSITING,    // deposit tx sent, awaiting confirmation
    DEPOSITED,     // confirmed on-chain, withdraw available
    WITHDRAWING,   // phase 1 tx sent, awaiting confirmation
    LOCKED,        // phase 1 confirmed, lock period not met
    UNLOCKABLE,    // lock period met, ready to unlock
    UNLOCKING,     // phase 2 tx sent, awaiting confirmation
    COMPLETED      // fully unlocked, funds returned
}

// -- Epoch arithmetic --

data class EpochInfo(
    val number: Long,
    val index: Long,
    val length: Long
) {
    val value: Double get() = number + index.toDouble() / length

    companion object {
        /** Parse CKB epoch hex (e.g. "0x7080018000001") into EpochInfo */
        fun fromHex(epochHex: String): EpochInfo {
            val epoch = epochHex.removePrefix("0x").toLong(16)
            val number = epoch and 0xFFFFFFL
            val index = (epoch shr 24) and 0xFFFFL
            val length = (epoch shr 40) and 0xFFFFL
            return EpochInfo(number = number, index = index, length = length)
        }
    }
}

// -- Cycle phase for compensation progress bar --

enum class CyclePhase {
    NORMAL,     // 0-80%
    SUGGESTED,  // 80-95%
    ENDING      // 95-100%
}

fun cyclePhaseFromProgress(progress: Float): CyclePhase = when {
    progress >= 0.95f -> CyclePhase.ENDING
    progress >= 0.80f -> CyclePhase.SUGGESTED
    else -> CyclePhase.NORMAL
}

// -- Per-deposit record --

data class DaoDeposit(
    val outPoint: OutPoint,
    val capacity: Long,                   // deposited shannons
    val status: DaoCellStatus,
    val depositBlockNumber: Long,
    val depositBlockHash: String = "",
    val depositEpoch: EpochInfo? = null,
    val withdrawBlockNumber: Long? = null,
    val withdrawBlockHash: String? = null,
    val withdrawEpoch: EpochInfo? = null,
    val compensation: Long = 0L,          // earned shannons
    val unlockEpoch: EpochInfo? = null,   // earliest epoch for phase 2
    val lockRemainingHours: Int? = null,
    val compensationCycleProgress: Float = 0f,
    val cyclePhase: CyclePhase = CyclePhase.NORMAL,
    val depositTimestamp: Long = 0L,  // Unix millis from block header
    val apc: Double = 0.0,            // annualized per-deposit APC %
    // #332: deposit known from the Room cache but created BEFORE the script's
    // current sync window — the light client cannot see it, so compensation/
    // status may be stale until the user runs a deeper rescan.
    val outsideSyncWindow: Boolean = false
)

// -- Aggregate overview --

data class DaoOverview(
    val totalLocked: Long = 0L,
    val totalCompensation: Long = 0L,
    val currentApc: Double = 0.0,
    val activeCount: Int = 0,
    val completedCount: Int = 0
)

// -- ViewModel UI state --

enum class DaoTab { ACTIVE, COMPLETED }

sealed class DaoAction {
    data class Depositing(val amount: Long) : DaoAction()
    data class Withdrawing(val outPoint: OutPoint) : DaoAction()
    data class Unlocking(val outPoint: OutPoint) : DaoAction()
}

data class DaoUiState(
    val overview: DaoOverview = DaoOverview(),
    val activeDeposits: List<DaoDeposit> = emptyList(),
    val completedDeposits: List<DaoDeposit> = emptyList(),
    val selectedTab: DaoTab = DaoTab.ACTIVE,
    // Initial-load spinner. Driven once on first refresh; subsequent refreshes
    // surface through `isRefreshing` (the pull-to-refresh indicator) instead.
    val isLoading: Boolean = true,
    // Pull-to-refresh indicator. Distinct from `isLoading` so the user can
    // refresh without the screen blanking out into a fullscreen spinner.
    val isRefreshing: Boolean = false,
    val error: com.rjnr.pocketnode.ui.util.UiMessage? = null,
    val pendingAction: DaoAction? = null,
    val requiresAuth: Boolean = false,
    val authMethod: AuthMethod? = null,
    // #332: cached deposits that predate the sync window — drives the
    // deeper-rescan banner on DaoScreen.
    val outsideWindowCount: Int = 0,
    val isDeepRescanning: Boolean = false
)

// -- DAO header field extraction result --

@Serializable
data class DaoFields(
    val c: String,   // total capacity
    val ar: String,  // accumulated rate
    val s: String,   // secondary issuance
    val u: String    // occupied capacity
)

// -- Status determination function --

fun determineDaoStatus(
    isWithdrawingCell: Boolean,
    hasPendingWithdraw: Boolean,
    hasPendingUnlock: Boolean,
    hasPendingDeposit: Boolean,
    currentEpoch: EpochInfo?,
    unlockEpoch: EpochInfo?
): DaoCellStatus = when {
    hasPendingUnlock -> DaoCellStatus.UNLOCKING
    isWithdrawingCell && unlockEpoch != null && currentEpoch != null
        && currentEpoch.value >= unlockEpoch.value -> DaoCellStatus.UNLOCKABLE
    isWithdrawingCell -> DaoCellStatus.LOCKED
    hasPendingWithdraw -> DaoCellStatus.WITHDRAWING
    hasPendingDeposit -> DaoCellStatus.DEPOSITING
    else -> DaoCellStatus.DEPOSITED
}
