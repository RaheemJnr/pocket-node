package com.rjnr.pocketnode.ui.navigation

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController

/**
 * Keeps a restored NavController back stack until the nav graph attaches
 * (#524).
 *
 * While the re-auth lock is up the nav graph is not composed, so a
 * controller restored after process death never gets its graph, and its own
 * saveState() writes an empty back stack: a second process death on the lock
 * screen would lose where the user was. This keeper saves the controller's
 * state next to it; while no graph is attached it saves the bundle it was
 * restored from instead, and hands that bundle back to the controller on the
 * next restore.
 */
class NavStateKeeper(private val navController: NavController) {
    private var pending: Bundle? = null

    fun save(): Bundle? =
        if (navController.currentBackStackEntry == null) {
            pending
        } else {
            pending = null
            navController.saveState()
        }

    /** Restore [state] into the controller if no graph has attached yet. */
    fun restore(state: Bundle) {
        pending = state
        if (navController.currentBackStackEntry == null) navController.restoreState(state)
    }

    companion object {
        fun saver(navController: NavController): Saver<NavStateKeeper, Bundle> = Saver(
            save = { it.save() },
            restore = { NavStateKeeper(navController).apply { restore(it) } },
        )
    }
}

/** [rememberNavController] whose back stack survives process death behind the lock. */
@Composable
fun rememberLockSafeNavController(): NavHostController {
    val navController = rememberNavController()
    rememberSaveable(navController, saver = NavStateKeeper.saver(navController)) {
        NavStateKeeper(navController)
    }
    return navController
}
