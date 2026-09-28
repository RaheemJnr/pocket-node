package com.rjnr.pocketnode.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/** Which screen the re-auth overlay is showing. */
enum class ReauthLevel { BIOMETRIC, PIN, FORGOT_PIN }

/**
 * The re-auth lock shown after the app returns from the background (#524).
 *
 * Three levels, all inside the overlay: BIOMETRIC ([AuthScreen]), PIN
 * ([PinEntryScreen] in VERIFY mode) and FORGOT_PIN ([ForgotPinScreen]). Back
 * moves PIN to BIOMETRIC and FORGOT_PIN to PIN; back on BIOMETRIC calls
 * [onLeave]. Nothing here navigates, and the only way out is a genuine
 * biometric or PIN success, which calls [onUnlocked] with the [session] it
 * was earned in. A completed Forgot PIN reset wipes the wallet and restarts
 * the process itself, so it never unlocks.
 *
 * The ViewModels are keyed by [session] so each lock starts from a clean
 * state: a success left in a ViewModel by an earlier lock can never open this
 * one.
 */
@Composable
fun ReauthOverlay(
    session: Int,
    onUnlocked: (session: Int) -> Unit,
    onLeave: () -> Unit,
    authViewModel: AuthViewModel = hiltViewModel(key = "reauth-auth-$session"),
    pinViewModel: PinViewModel = hiltViewModel(key = "reauth-pin-$session"),
    forgotPinViewModel: ForgotPinViewModel = hiltViewModel(key = "reauth-forgot-pin-$session"),
) {
    var level by rememberSaveable(session) { mutableStateOf(ReauthLevel.BIOMETRIC) }
    val activity = LocalContext.current as? FragmentActivity

    // Second line of defence: drop any success already sitting in the
    // ViewModel before AuthScreen first reads it. Runs once per session
    // (saveable, so a configuration change cannot drop a success earned in
    // this session).
    rememberSaveable(session) {
        authViewModel.consumeAuthSuccess()
        true
    }

    // Consume the success and clear the gate for this session only.
    fun onAuthenticated() {
        authViewModel.consumeAuthSuccess()
        onUnlocked(session)
    }

    // PIN success: run the same migration check AuthScreen runs after a
    // biometric success, then unlock. Does not depend on AuthScreen being
    // composed.
    fun onPinVerified() {
        if (activity != null) {
            authViewModel.runMigrationIfNeeded(activity) { onAuthenticated() }
        } else {
            onAuthenticated()
        }
    }

    BackHandler(enabled = true) {
        when (level) {
            ReauthLevel.BIOMETRIC -> onLeave()
            ReauthLevel.PIN -> level = ReauthLevel.BIOMETRIC
            ReauthLevel.FORGOT_PIN -> level = ReauthLevel.PIN
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (level) {
            ReauthLevel.BIOMETRIC -> AuthScreen(
                viewModel = authViewModel,
                // AuthScreen's own effect has already run the migration check.
                onAuthSuccess = { onAuthenticated() },
                onNavigateToPinVerify = { level = ReauthLevel.PIN },
            )
            ReauthLevel.PIN -> PinEntryScreen(
                mode = PinMode.VERIFY,
                onPinComplete = { onPinVerified() },
                onForgotPin = { level = ReauthLevel.FORGOT_PIN },
                viewModel = pinViewModel,
            )
            ReauthLevel.FORGOT_PIN -> ForgotPinScreen(
                onBack = { level = ReauthLevel.PIN },
                viewModel = forgotPinViewModel,
            )
        }
    }
}
