package com.rjnr.pocketnode.ui.screens.auth

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for #524: before this fix, backgrounding the app navigated
 * to `Screen.Auth` with `popUpTo(Screen.Main) { inclusive = true }`,
 * discarding the back stack, so a successful unlock always landed on Home.
 *
 * The fix (see [ReauthGate], drawn as an overlay in MainActivity) has no
 * `NavController` reference at all in its public API. This test drives its
 * full lock / pin-fallback / back-press / unlock state machine next to a
 * real `NavController` parked on a non-Home route and asserts the route
 * never moves: the exact property the old navigate(Screen.Auth) call broke.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReauthGateNavigationTest {

    private lateinit var navController: TestNavHostController

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        navController = TestNavHostController(context)
        navController.navigatorProvider.addNavigator(ComposeNavigator())
        navController.graph = navController.createGraph(startDestination = Screen.Main.route) {
            composable(Screen.Main.route) {}
            composable(Screen.Send.route) {}
            composable(Screen.Auth.route) {}
        }
    }

    @Test
    fun `locking and unlocking the gate never navigates away from a non-Home route`() {
        navController.navigate(Screen.Send.route)
        assertEquals(Screen.Send.route, navController.currentBackStackEntry?.destination?.route)

        val gate = ReauthGate(mutableStateOf(false), mutableStateOf(false))

        // The full sequence a background/foreground cycle (plus a detour
        // through the PIN fallback and a back press) can drive.
        gate.lock()
        gate.showPinFallback()
        gate.onBackPressed()
        gate.unlock()
        gate.lock()

        assertEquals(Screen.Send.route, navController.currentBackStackEntry?.destination?.route)
        assertEquals(Screen.Send.route, navController.currentDestination?.route)
    }
}
