package com.rjnr.pocketnode.ui.navigation

import android.content.Context
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.NavHostController
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RecoveryNavigationTest {
    private fun controller(start: String): NavHostController = NavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
        graph = createGraph(startDestination = start) {
            listOf(Screen.Main.route, Screen.Recovery.route, Screen.SecuritySettings.route, Screen.SecurityChecklist.route).forEach { route -> composable(route) {} }
        }
    }
    @Test fun `repeated settings recovery exits leave a single home without stale settings`() {
        val nav = controller(Screen.Main.route)
        repeat(3) {
            nav.navigate(Screen.SecuritySettings.route)
            nav.navigate(Screen.Recovery.route)
            nav.exitRecovery(Screen.Main.route)
            assertEquals(Screen.Main.route, nav.currentDestination?.route)
            assertNull(nav.previousBackStackEntry)
        }
    }
    @Test fun `startup recovery exits safely to requested completion destination`() {
        for (destination in listOf(Screen.Main.route, Screen.SecurityChecklist.route)) {
            val nav = controller(Screen.Recovery.route)
            nav.exitRecovery(destination)
            assertEquals(destination, nav.currentDestination?.route)
            assertNull(nav.previousBackStackEntry)
        }
    }
    @Test fun `settings recovery completion keeps the chosen destination without old history`() {
        val nav = controller(Screen.Main.route)
        nav.navigate(Screen.SecuritySettings.route)
        nav.navigate(Screen.Recovery.route)
        nav.exitRecovery(Screen.SecurityChecklist.route)
        assertEquals(Screen.SecurityChecklist.route, nav.currentDestination?.route)
        assertNull(nav.previousBackStackEntry)
    }
}
