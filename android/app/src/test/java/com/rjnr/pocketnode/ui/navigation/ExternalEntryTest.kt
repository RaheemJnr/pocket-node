package com.rjnr.pocketnode.ui.navigation

import android.content.Intent
import android.os.Bundle
import com.rjnr.pocketnode.sanitizedEntryIntent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Navigation 2.9.6 graph attachment, the same automatic intent handling used by NavHost. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ExternalEntryTest {
    private fun controller(intent: Intent = Intent(), restore: Bundle? = null, sanitize: Boolean = true): NavHostController {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java, if (sanitize) sanitizedEntryIntent(intent) else intent).setup().get()
        return NavHostController(activity).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            setViewModelStore(ViewModelStore())
            setLifecycleOwner(activity)
            if (restore != null) restoreState(restore)
            graph = createGraph(startDestination = Screen.Auth.route) {
                composable(Screen.Auth.route) {}
                composable(Screen.Main.route) {}
                composable(Screen.Send.route) {}
                composable(Screen.Scanner.route) {}
                composable(Screen.InitialPinSetup.route) {}
                composable(Screen.MnemonicBackup.route) {}
            }
        }
    }

    private fun attack(route: String): Intent {
        val graph = controller().graph
        val id = graph.findNode(route)!!.id
        println("actual generated route=$route root=${graph.id} id=$id")
        return Intent().putExtra("android-support-nav:controller:deepLinkIds", intArrayOf(graph.id, id))
    }

    @Test fun `exported fresh entry must not route to setup from framework extras`() {
        assertEquals(Screen.Auth.route, controller(attack(Screen.InitialPinSetup.route)).currentDestination?.route)
    }

    @Test fun `exported fresh entry must not route to main from framework extras`() {
        assertEquals(Screen.Auth.route, controller(attack(Screen.Main.route)).currentDestination?.route)
    }

    @Test fun `wrong route hash does not reproduce generated route attack`() {
        val intent = Intent().putExtra("android-support-nav:controller:deepLinkIds", intArrayOf(0, "initial_pin_setup".hashCode()))
        assertEquals(Screen.Auth.route, controller(intent, sanitize = false).currentDestination?.route)
    }

    @Test fun `saved navigation state restores protected destination independently of start route`() {
        val first = controller()
        first.navigate(Screen.Main.route)
        val restored = controller(restore = first.saveState())
        assertEquals(Screen.Main.route, restored.currentDestination?.route)
    }

    @Test fun `a new intent does not automatically navigate an attached controller`() {
        val first = controller()
        val activity = first.context as FragmentActivity
        activity.intent = attack(Screen.InitialPinSetup.route)
        assertEquals(Screen.Auth.route, first.currentDestination?.route)
    }
    @Test fun `sanitization preserves launcher and URI envelope but removes all route payload`() {
        val uri = android.net.Uri.parse("joyid://wallet/ckb1example")
        val incoming = attack(Screen.InitialPinSetup.route).apply {
            action = Intent.ACTION_VIEW
            data = uri
            addCategory(Intent.CATEGORY_BROWSABLE)
            putExtra("android-support-nav:controller:deepLinkExtras", Bundle().apply { putString("mode", "setup") })
            putExtra("android-support-nav:controller:deepLinkIntent", Intent())
            putExtra("android-support-nav:controller:deepLinkArgs", arrayOf(Bundle()))
        }
        val safe = sanitizedEntryIntent(incoming)
        assertEquals(Intent.ACTION_VIEW, safe.action)
        assertEquals(uri, safe.data)
        assertTrue(safe.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(safe.extras)
        assertNotNull(incoming.extras)
        assertEquals(Screen.Auth.route, controller(safe).currentDestination?.route)
    }

    @Test fun `new intent delivery cannot leave a payload for later graph recreation`() {
        val first = controller()
        val activity = first.context as FragmentActivity
        activity.intent = sanitizedEntryIntent(attack(Screen.InitialPinSetup.route))
        val next = controller(activity.intent)
        assertEquals(Screen.Auth.route, next.currentDestination?.route)
    }

    @Test fun `unsanitized Navigation control reproduces actual generated route IDs`() {
        assertEquals(Screen.InitialPinSetup.route,
            controller(attack(Screen.InitialPinSetup.route), sanitize = false).currentDestination?.route)
        assertEquals(Screen.Main.route,
            controller(attack(Screen.Main.route), sanitize = false).currentDestination?.route)
    }

    @Test fun `in app QR URI result survives normal navigation and lock restoration`() {
        val nav = controller()
        nav.navigate(Screen.Send.route)
        nav.navigate(Screen.Scanner.route)
        val address = com.rjnr.pocketnode.util.extractCkbAddress(
            "joyid://ckb1qzda0cr08m85hc8jyu3z3fuhgxk23ghrb64kzk3r")
        nav.previousBackStackEntry!!.savedStateHandle["scanned_address"] = address
        nav.popBackStack()
        val restored = controller(restore = nav.saveState())
        assertEquals(Screen.Send.route, restored.currentDestination?.route)
        assertEquals("ckb1qzda0cr08m85hc8jyu3z3fuhgxk23ghrb64kzk3r",
            restored.currentBackStackEntry!!.savedStateHandle.get<String>("scanned_address"))
        assertNull(com.rjnr.pocketnode.util.extractCkbAddress("joyid://ckt1AbC"))
    }

    @Test fun `URI envelope cannot address private navigation routes`() {
        for (uri in listOf("android-app://androidx.navigation/initial_pin_setup",
            "android-app://androidx.navigation/main", "ckb:ckb1example")) {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
            assertEquals(Screen.Auth.route, controller(intent).currentDestination?.route)
        }
    }

}
