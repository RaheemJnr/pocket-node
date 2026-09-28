package com.rjnr.pocketnode.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

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
 * The ViewModels live in [sessionStore]'s store for [session], so each lock
 * starts from clean ones and the previous lock's are cleared (their
 * coroutines cancelled): a success left in a ViewModel by an earlier lock can
 * never open this one. [viewModelFactory] replaces Hilt in tests.
 */
@Composable
fun ReauthOverlay(
    session: Int,
    sessionStore: ReauthSessionStore,
    onUnlocked: (session: Int) -> Unit,
    onLeave: () -> Unit,
    viewModelFactory: ViewModelProvider.Factory? = null,
) {
    val parentOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "ReauthOverlay needs a ViewModelStoreOwner"
    }
    val activity = LocalContext.current as? FragmentActivity
    val sessionOwner = remember(session, sessionStore, parentOwner) {
        SessionViewModelStoreOwner(sessionStore.storeFor(session), parentOwner)
    }
    DisposableEffect(sessionOwner) {
        onDispose {
            // Unlocked (or a new session took over): drop this session's
            // ViewModels. A configuration change keeps them.
            if (activity?.isChangingConfigurations != true) sessionStore.clear(session)
        }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides sessionOwner) {
        // A new lock starts a fresh composition (and a fresh biometric
        // auto-prompt) even when the overlay was already on screen.
        key(session) {
            ReauthLevels(
                session = session,
                activity = activity,
                onUnlocked = onUnlocked,
                onLeave = onLeave,
                authViewModel = sessionViewModel(viewModelFactory),
                pinViewModel = sessionViewModel(viewModelFactory),
                forgotPinViewModel = sessionViewModel(viewModelFactory),
            )
        }
    }
}

@Composable
private inline fun <reified VM : ViewModel> sessionViewModel(factory: ViewModelProvider.Factory?): VM =
    if (factory != null) viewModel(factory = factory) else hiltViewModel()

@Composable
private fun ReauthLevels(
    session: Int,
    activity: FragmentActivity?,
    onUnlocked: (session: Int) -> Unit,
    onLeave: () -> Unit,
    authViewModel: AuthViewModel,
    pinViewModel: PinViewModel,
    forgotPinViewModel: ForgotPinViewModel,
) {
    var level by rememberSaveable { mutableStateOf(ReauthLevel.BIOMETRIC) }

    // Second line of defence: drop any success already sitting in the
    // ViewModel before AuthScreen first reads it. Runs once per session
    // (saveable, so a configuration change cannot drop a success earned in
    // this session).
    rememberSaveable {
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

/**
 * Activity-scoped holder of the current lock session's ViewModelStore
 * (#524). Starting a new session or unlocking clears the previous store, so
 * a stale lockout timer, PIN verify or migration stops with it.
 */
class ReauthSessionStore : ViewModel() {
    private var session: Int? = null
    private var store: ViewModelStore? = null

    fun storeFor(session: Int): ViewModelStore {
        if (session != this.session) {
            clearAll()
            this.session = session
        }
        return store ?: ViewModelStore().also { store = it }
    }

    /** Clear [session]'s store if it is still the current one. */
    fun clear(session: Int) {
        if (session == this.session) clearAll()
    }

    fun clearAll() {
        store?.clear()
        store = null
        session = null
    }

    override fun onCleared() = clearAll()
}

/**
 * Hands out the session store while borrowing the Activity's factory and
 * creation extras, so Hilt's hiltViewModel() still resolves @HiltViewModel
 * classes here.
 */
private class SessionViewModelStoreOwner(
    override val viewModelStore: ViewModelStore,
    private val parent: ViewModelStoreOwner,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = (parent as? HasDefaultViewModelProviderFactory)?.defaultViewModelProviderFactory
            ?: ViewModelProvider.NewInstanceFactory()

    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras(
            (parent as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras
                ?: CreationExtras.Empty
        ).apply { set(VIEW_MODEL_STORE_OWNER_KEY, this@SessionViewModelStoreOwner) }
}
