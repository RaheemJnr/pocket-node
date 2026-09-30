package com.rjnr.pocketnode.ui.navigation

import android.content.Context
import android.os.Bundle
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S-d (#524): a back stack restored while the lock keeps the nav graph
 * detached must survive a second process death. Tested at the helper level
 * (the NavController save/restore cycle MainActivity's rememberSaveable
 * runs), not through a full ActivityScenario.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NavStateKeeperTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun controller() = NavHostController(context).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
    }

    private fun NavHostController.attachGraph() {
        graph = createGraph(startDestination = "home") {
            composable("home") {}
            composable("send") {}
        }
    }

    @Test
    fun `a restored back stack survives a save while no graph is attached`() {
        // First process: on Send.
        val first = controller()
        first.attachGraph()
        first.navigate("send")
        val firstSave: Bundle = NavStateKeeper(first).save()!!

        // Second process: restored behind the lock, graph never attached.
        val second = controller()
        second.restoreState(firstSave)
        val keeper = NavStateKeeper(second)
        keeper.restore(firstSave)
        // The controller alone would save an empty back stack here.
        assertNull(second.currentBackStackEntry)
        val secondSave = keeper.save()
        assertSame(firstSave, secondSave)

        // Third process: unlocked, graph attaches, user is back on Send.
        val third = controller()
        third.restoreState(secondSave)
        NavStateKeeper(third).restore(secondSave!!)
        third.attachGraph()
        assertEquals("send", third.currentDestination?.route)
        assertEquals(listOf("home", "send"), third.currentBackStack.value.mapNotNull { it.destination.route })
    }

    @Test
    fun `once the graph is attached the keeper saves the live state`() {
        val c = controller()
        val keeper = NavStateKeeper(c)
        keeper.restore(Bundle())
        c.attachGraph()
        c.navigate("send")

        val saved = keeper.save()!!
        val restored = controller()
        restored.restoreState(saved)
        restored.attachGraph()
        assertEquals("send", restored.currentDestination?.route)
    }
}
