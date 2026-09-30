package com.rjnr.pocketnode.ui.navigation

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.ui.screens.auth.ReauthLockEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #524 R5-2: the change-PIN candidate lives in memory only and a lock clears
 * it; N5-2: a lock drops pending PIN-verified flags.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PinFlowStateTest {

    private val candidatePin = "482915"
    private lateinit var nav: NavHostController

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        nav = NavHostController(context).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            setViewModelStore(ViewModelStore())
        }
        nav.graph = nav.createGraph(startDestination = "security") {
            composable("security") {}
            composable("send") {}
            composable(Screen.PinEntry.route) {}
        }
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    /** Every value stored anywhere in [bundle], nested bundles included. */
    private fun values(bundle: Bundle?): List<Any?> = bundle?.keySet()?.flatMap { key ->
        @Suppress("DEPRECATION")
        when (val v = bundle.get(key)) {
            is Bundle -> values(v)
            is ArrayList<*> -> v.flatMap { if (it is Bundle) values(it) else listOf(it) }
            is Array<*> -> v.flatMap { if (it is Bundle) values(it) else listOf(it) }
            else -> listOf(v)
        }
    } ?: emptyList()

    @Test
    fun `the setup PIN is never written to a SavedStateHandle or the saved state`() {
        nav.navigate(Screen.PinEntry.createRoute("setup"))
        val setupEntry = nav.currentBackStackEntry!!
        PinSetupCandidate.of(setupEntry).set(candidatePin)
        nav.navigate(Screen.PinEntry.createRoute("confirm"))

        // CONFIRM reads it back from the SETUP entry, in memory.
        assertEquals(candidatePin, PinSetupCandidate.of(nav.previousBackStackEntry!!).pin)
        assertSame(PinSetupCandidate.of(setupEntry), PinSetupCandidate.of(nav.previousBackStackEntry!!))

        for (entry in nav.currentBackStack.value) {
            val handle = entry.savedStateHandle
            handle.keys().forEach { key -> assertFalse(handle.get<Any?>(key) == candidatePin) }
        }
        assertFalse(values(nav.saveState()).any { it == candidatePin })
    }

    @Test
    fun `a lock clears the setup PIN`() {
        nav.navigate(Screen.PinEntry.createRoute("setup"))
        val candidate = PinSetupCandidate.of(nav.currentBackStackEntry!!)
        candidate.set(candidatePin)
        idleMain()

        ReauthLockEvents.onLocked()
        idleMain()

        assertNull(candidate.pin)
    }

    @Test
    fun `a lock drops pending PIN-verified flags on the back stack (N-5-2)`() {
        nav.navigate("send")
        nav.getBackStackEntry("security").savedStateHandle["pin_verified"] = true
        nav.currentBackStackEntry!!.savedStateHandle["send_pin_verified"] = true
        nav.currentBackStackEntry!!.savedStateHandle["dao_pin_verified"] = true
        nav.currentBackStackEntry!!.savedStateHandle["unrelated"] = "kept"

        nav.clearPendingPinVerifications()

        assertNull(nav.getBackStackEntry("security").savedStateHandle.get<Boolean>("pin_verified"))
        assertNull(nav.currentBackStackEntry!!.savedStateHandle.get<Boolean>("send_pin_verified"))
        assertNull(nav.currentBackStackEntry!!.savedStateHandle.get<Boolean>("dao_pin_verified"))
        assertEquals("kept", nav.currentBackStackEntry!!.savedStateHandle.get<String>("unrelated"))
    }
}
