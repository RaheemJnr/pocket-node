package com.rjnr.pocketnode.ui.screens.auth

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import com.composables.icons.lucide.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rjnr.pocketnode.data.auth.PinManager
import com.rjnr.pocketnode.ui.util.uaTestTag

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinEntryScreen(
    mode: PinMode,
    setupPin: String? = null,
    onPinComplete: (enteredPin: String) -> Unit = {},
    onForgotPin: () -> Unit = {},
    onNavigateBack: () -> Unit = {},
    viewModel: PinViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val shakeOffset = remember { Animatable(0f) }
    // #304: tactile feedback on every keypad press + on PIN mismatch.
    // performHapticFeedback respects the system "Touch feedback" setting,
    // so no app-level toggle is needed.
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(mode, setupPin) {
        viewModel.setMode(mode)
        if (mode == PinMode.CONFIRM && setupPin != null) {
            viewModel.setSetupPin(setupPin)
        }
    }

    LaunchedEffect(uiState.pinComplete) {
        if (uiState.pinComplete) {
            val pin = viewModel.getEnteredPin()
            viewModel.consumePinComplete()
            onPinComplete(pin)
        }
    }

    LaunchedEffect(uiState.isError) {
        if (uiState.isError) {
            haptic.performHapticFeedback(HapticFeedbackType.Reject)
            for (offset in listOf(10f, -10f, 8f, -8f, 4f, -4f, 0f)) {
                shakeOffset.animateTo(offset, animationSpec = tween(50))
            }
        }
    }

    val showBackArrow = mode == PinMode.SETUP || mode == PinMode.CONFIRM

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (showBackArrow) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Lucide.ArrowLeft, "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = uiState.title,
                style = MaterialTheme.typography.headlineMedium
            )

            uiState.subtitle?.let { subtitle ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Dot indicators
            Row(
                modifier = Modifier.offset { IntOffset(shakeOffset.value.toInt(), 0) },
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                repeat(PinManager.PIN_LENGTH) { index ->
                    val filled = index < uiState.enteredDigits.length
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(
                                if (filled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Error / lockout / verifying messages
            when {
                uiState.isVerifying -> {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Verifying...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                uiState.isLockedOut -> {
                    Text(
                        text = "Too many attempts. Try again in ${uiState.lockoutRemainingSeconds}s",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                uiState.errorMessage != null -> {
                    Text(
                        text = uiState.errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                mode == PinMode.VERIFY && uiState.remainingAttempts < PinManager.MAX_ATTEMPTS -> {
                    Text(
                        text = "${uiState.remainingAttempts} attempts remaining",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Number pad — disabled during lockout AND while the KDF is computing.
            val buttonsEnabled = !uiState.isLockedOut && !uiState.isVerifying
            val digits = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("", "0", "del")
            )
            digits.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { key ->
                        when (key) {
                            "" -> Spacer(modifier = Modifier.size(72.dp))
                            "del" -> {
                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                                        viewModel.onDeleteDigit()
                                    },
                                    modifier = Modifier.size(72.dp),
                                    enabled = buttonsEnabled && uiState.enteredDigits.isNotEmpty()
                                ) {
                                    Icon(
                                        Lucide.Delete,
                                        contentDescription = "Delete"
                                    )
                                }
                            }
                            else -> {
                                FilledTonalButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                                        viewModel.onDigitEntered(key[0])
                                    },
                                    modifier = Modifier.size(72.dp).uaTestTag("pin-keypad-$key"),
                                    shape = CircleShape,
                                    enabled = buttonsEnabled
                                ) {
                                    Text(
                                        text = key,
                                        style = MaterialTheme.typography.headlineSmall
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Forgot PIN
            if (mode == PinMode.VERIFY) {
                TextButton(onClick = onForgotPin) {
                    Text("Forgot PIN?")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
