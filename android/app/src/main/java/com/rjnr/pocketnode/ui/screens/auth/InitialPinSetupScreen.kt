package com.rjnr.pocketnode.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import androidx.compose.material3.Icon
import com.rjnr.pocketnode.ui.util.uaTestTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

private enum class Phase { INTRO, SETUP, CONFIRM }

/**
 * Mandatory PIN setup shown once, after wallet creation or on first launch
 * after upgrading from a version without a PIN. Renders an explanation page
 * first, then reuses [PinEntryScreen] in SETUP and CONFIRM modes driven by
 * internal state — no navigation between the three phases, so the back stack
 * stays clean and we can pop to a single destination on completion.
 */
@Composable
fun InitialPinSetupScreen(
    onPinCreated: () -> Unit,
    onForgotPin: () -> Unit,
    viewModel: PinViewModel = hiltViewModel()
) {
    var phase by rememberSaveable { mutableStateOf(Phase.INTRO) }
    var setupPin by remember { mutableStateOf<String?>(null) }
    val pinState by viewModel.uiState.collectAsState()
    // Observe async setup completion, including a credential persisted while stopped.
    // Existence chooses VERIFY; only a live authenticated session can finish setup.
    val hasPin = remember(pinState) { viewModel.hasPersistedPin() }
    var completionSent by remember { mutableStateOf(false) }
    val completeSetup = {
        if (!completionSent && viewModel.canCompleteInitialSetup()) {
            completionSent = true
            onPinCreated()
        }
    }

    if (hasPin) {
        // The gate may have removed CONFIRM and restored its saved phase after fresh
        // overlay auth. Never remount CONFIRM with its discarded candidate PIN.
        LaunchedEffect(Unit) { completeSetup() }
        PinEntryScreen(
            mode = PinMode.VERIFY,
            viewModel = viewModel,
            onPinComplete = { completeSetup() },
            onForgotPin = onForgotPin
        )
        return
    }

    when (phase) {
        Phase.INTRO -> IntroContent(onContinue = { phase = Phase.SETUP })

        Phase.SETUP -> PinEntryScreen(
            mode = PinMode.SETUP,
            viewModel = viewModel,
            onPinComplete = { pin ->
                setupPin = pin
                phase = Phase.CONFIRM
            },
            onNavigateBack = { phase = Phase.INTRO }
        )

        Phase.CONFIRM -> PinEntryScreen(
            mode = PinMode.CONFIRM,
            viewModel = viewModel,
            setupPin = setupPin,
            onPinComplete = {
                completeSetup()
            },
            onNavigateBack = { phase = Phase.SETUP }
        )
    }
}

@Composable
private fun IntroContent(onContinue: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface
    ) { padding ->
        IntroBody(padding, onContinue)
    }
}

@Composable
private fun IntroBody(
    padding: PaddingValues,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))

        Surface(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Lucide.KeyRound,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Create a PIN",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "A 6-digit PIN that only you know.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(32.dp))

        Reason(
            title = "Locks your wallet",
            body = "No one can open your wallet or send your CKB without the PIN — even if they have your phone."
        )
        Spacer(Modifier.height(16.dp))
        Reason(
            title = "Protects a backup of your recovery phrase",
            body = "An encrypted copy of your recovery phrase is stored on this device. Your PIN is the key that unlocks it if something goes wrong."
        )
        Spacer(Modifier.height(16.dp))
        Reason(
            title = "You can still recover your wallet without it",
            body = "Your recovery phrase is the final backup. If you forget the PIN, reinstall the app and restore using your recovery phrase."
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().uaTestTag("pin-intro-continue"),
            colors = ButtonDefaults.buttonColors()
        ) {
            Text("Create PIN")
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Reason(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
