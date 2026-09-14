package com.rjnr.pocketnode.data.gateway.models

import com.rjnr.pocketnode.data.auth.AuthMethod
import com.rjnr.pocketnode.ui.util.UiMessage

// Stays in the app module: `DaoUiState` carries a `UiMessage`, which is Compose/Android
// bound, so it cannot follow the rest of the DAO models into commonMain (#455). The
// package is unchanged, so no call site needed an import edit.

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
    val error: UiMessage? = null,
    val pendingAction: DaoAction? = null,
    val requiresAuth: Boolean = false,
    val authMethod: AuthMethod? = null,
    // #332: cached deposits that predate the sync window — drives the
    // deeper-rescan banner on DaoScreen.
    val outsideWindowCount: Int = 0,
    val isDeepRescanning: Boolean = false
)
