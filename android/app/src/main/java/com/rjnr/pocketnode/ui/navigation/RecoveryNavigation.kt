package com.rjnr.pocketnode.ui.navigation

import androidx.navigation.NavController

internal fun NavController.exitRecovery(destination: String) {
    navigate(destination) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
