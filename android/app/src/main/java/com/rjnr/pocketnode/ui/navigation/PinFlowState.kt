package com.rjnr.pocketnode.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import com.rjnr.pocketnode.ui.screens.auth.onEachReauthLock

/**
 * The PIN typed on the SETUP step, held for the CONFIRM step (#524).
 *
 * Memory only: it lives in a ViewModel of the SETUP back stack entry, never
 * in a SavedStateHandle, so it is never written to the saved-instance-state
 * Bundle (and never survives process death). A re-auth lock clears it.
 */
class PinSetupCandidate : ViewModel() {
    var pin: String? = null
        private set

    init {
        viewModelScope.onEachReauthLock { pin = null }
    }

    fun set(pin: String) {
        this.pin = pin
    }

    companion object {
        private val factory = viewModelFactory { initializer { PinSetupCandidate() } }

        /** The candidate held by the SETUP step's back stack entry [owner]. */
        fun of(owner: ViewModelStoreOwner): PinSetupCandidate =
            ViewModelProvider(owner, factory)[PinSetupCandidate::class.java]
    }
}

/**
 * SavedStateHandle flags PinEntry(verify) leaves on the screen it returns to;
 * that screen then reveals or acts without asking again.
 */
internal val PIN_VERIFIED_RESULT_KEYS = listOf("pin_verified", "send_pin_verified", "dao_pin_verified")

/**
 * Drop every pending PIN-verified flag on the back stack (#524), so a verify
 * that completed just before a re-auth lock does not reveal or act right
 * after the unlock without a fresh PIN.
 */
fun NavController.clearPendingPinVerifications() {
    currentBackStack.value.forEach { entry ->
        runCatching {
            PIN_VERIFIED_RESULT_KEYS.forEach { entry.savedStateHandle.remove<Boolean>(it) }
        }
    }
}
