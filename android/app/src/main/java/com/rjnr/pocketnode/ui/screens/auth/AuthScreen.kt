package com.rjnr.pocketnode.ui.screens.auth

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.composables.icons.lucide.*
import com.rjnr.pocketnode.ui.util.resolveString
import com.rjnr.pocketnode.ui.util.uaTestTag
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.R
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit = {},
    onNavigateToPinVerify: () -> Unit = {},
    pinUnlockFlow: androidx.lifecycle.SavedStateHandle? = null,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Returning from PinEntryScreen via the verify-mode pop-back path.
    // NavGraph sets pin_unlock_success=true on AuthScreen's savedStateHandle
    // when previousRoute was Auth, so we mirror the biometric-success
    // entrypoint and flip the VM state — that wakes the migration loop
    // [LaunchedEffect(uiState.authSuccess)] below (#289 follow-up).
    LaunchedEffect(pinUnlockFlow) {
        val ok = pinUnlockFlow?.get<Boolean>("pin_unlock_success") == true
        if (ok) {
            pinUnlockFlow.remove<Boolean>("pin_unlock_success")
            viewModel.onPinUnlockSuccess()
        }
    }

    // Capture localized strings now — the prompt builder runs from a
    // non-@Composable callback site, which can't call stringResource().
    val biometricTitle = stringResource(R.string.auth_biometric_title)
    val biometricSubtitle = stringResource(R.string.auth_biometric_subtitle)
    val biometricNegative = stringResource(R.string.auth_biometric_negative)

    // Hold the in-flight prompt so we can cancel it. The androidx
    // BiometricPrompt binds to the FragmentActivity, not this composable, so
    // it outlives Compose navigation. Without cancelling, the auto-launched
    // prompt kept listening while the user unlocked via PIN and then surfaced
    // over the Home screen after unlock (reported bug). We cancel it when the
    // user opts for PIN and when AuthScreen leaves composition (the successful
    // unlock pops Auth off the back stack, firing onDispose).
    val activeBiometricPrompt = remember { mutableStateOf<BiometricPrompt?>(null) }

    fun cancelBiometric() {
        activeBiometricPrompt.value?.cancelAuthentication()
        activeBiometricPrompt.value = null
    }

    fun launchBiometric() {
        val activity = context as? FragmentActivity ?: return
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(
            activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    activeBiometricPrompt.value = null
                    viewModel.onBiometricSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    activeBiometricPrompt.value = null
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        viewModel.onBiometricFailed(errString.toString())
                    }
                }
            }
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(biometricTitle)
            .setSubtitle(biometricSubtitle)
            .setNegativeButtonText(biometricNegative)
            .build()
        activeBiometricPrompt.value = prompt
        prompt.authenticate(promptInfo)
    }

    LaunchedEffect(Unit) {
        if (viewModel.shouldAutoTriggerBiometric()) {
            launchBiometric()
        }
    }

    // Cancel any pending prompt when AuthScreen is disposed (e.g. after a
    // successful unlock pops it off the back stack) so it can't reappear
    // over the next screen.
    DisposableEffect(Unit) {
        onDispose { cancelBiometric() }
    }

    LaunchedEffect(uiState.authSuccess) {
        if (uiState.authSuccess) {
            // Run the V2 migration (one BiometricPrompt per V1 wallet)
            // before letting the user past the unlock screen (#213 sub-PR 5).
            // The user is already in an "authenticating" mindset, which is
            // the least jarring time to ask for more prompts. No-op for
            // fresh installs / V2-already-complete states.
            val activity = context as? FragmentActivity
            if (activity != null) {
                viewModel.runMigrationIfNeeded(activity) { onAuthSuccess() }
            } else {
                onAuthSuccess()
            }
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { msg ->
            snackbarHostState.showSnackbar(msg.resolveString(context))
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Lucide.Lock,
                contentDescription = stringResource(R.string.auth_locked_cd),
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Pocket Node",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Wallet is locked",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(48.dp))

            if (uiState.showBiometricButton) {
                Button(
                    onClick = { launchBiometric() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.auth_unlock_fingerprint))
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            if (uiState.showPinFallback) {
                OutlinedButton(
                    onClick = {
                        // Tear down the auto-launched prompt before switching to
                        // the PIN path so it can't linger and reappear later.
                        cancelBiometric()
                        onNavigateToPinVerify()
                    },
                    modifier = Modifier.fillMaxWidth().uaTestTag("auth-use-pin")
                ) {
                    Text(stringResource(R.string.auth_use_pin))
                }
            }
        }
    }
}
