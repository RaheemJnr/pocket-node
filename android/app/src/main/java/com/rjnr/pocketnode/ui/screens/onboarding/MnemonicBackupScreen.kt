package com.rjnr.pocketnode.ui.screens.onboarding

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.composables.icons.lucide.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.R
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.util.toHex
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

// -- ViewModel --

data class MnemonicBackupUiState(
    val currentStep: Int = 1,
    val words: List<String> = emptyList(),
    val privateKeyHex: String? = null,
    val walletType: String = "",       // "mnemonic", "raw_key", or empty
    val isSubAccount: Boolean = false,
    val verifyPositions: List<Int> = emptyList(),
    val verifyOptions: Map<Int, List<String>> = emptyMap(),
    val userSelections: Map<Int, String> = emptyMap(),
    val error: String? = null
)

@HiltViewModel
class MnemonicBackupViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val walletRepository: com.rjnr.pocketnode.data.wallet.WalletRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MnemonicBackupUiState())
    val uiState: StateFlow<MnemonicBackupUiState> = _uiState.asStateFlow()

    init {
        loadMnemonic()
    }

    private fun loadMnemonic() {
        viewModelScope.launch {
            // Detect wallet type for the active wallet
            val activeWallet = walletRepository.getActive()
            val walletType = activeWallet?.type ?: ""
            val isSubAccount = activeWallet?.parentWalletId != null
            _uiState.update { it.copy(walletType = walletType, isSubAccount = isSubAccount) }

            val words = repository.getMnemonic()
            if (words.isNullOrEmpty()) {
                // For raw_key or sub-account wallets, this is expected — not an error.
                // Only raw-key wallets need the private key for the dedicated raw-key backup screen.
                val privateKeyHex = if (walletType == "raw_key" && !isSubAccount) {
                    try {
                        repository.getPrivateKey().toHex()
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                _uiState.update { it.copy(privateKeyHex = privateKeyHex) }
                return@launch
            }
            val random = java.util.Random(System.nanoTime())
            val positions = words.indices.toList().shuffled(random).take(3).sorted()
            val options = positions.associateWith { pos ->
                val correct = words[pos]
                val decoys = words.filterIndexed { i, _ -> i != pos }
                    .distinct()
                    .filter { it != correct }
                    .shuffled(random)
                    .take(3)
                val choices = mutableListOf(correct).apply { addAll(decoys) }
                choices.apply { shuffle(random) }.toList()
            }
            _uiState.update {
                it.copy(words = words, verifyPositions = positions, verifyOptions = options)
            }
        }
    }

    fun advanceToVerify() {
        _uiState.update { it.copy(currentStep = 2, error = null) }
    }

    fun selectWord(position: Int, word: String) {
        _uiState.update {
            it.copy(userSelections = it.userSelections + (position to word), error = null)
        }
    }

    fun verify() {
        val state = _uiState.value
        val allCorrect = state.verifyPositions.all { pos ->
            state.userSelections[pos] == state.words[pos]
        }
        if (allCorrect) {
            viewModelScope.launch {
                repository.setMnemonicBackedUp(true)
                _uiState.update { it.copy(currentStep = 3) }
            }
        } else {
            _uiState.update {
                it.copy(error = "Some words are incorrect. Please try again.", userSelections = emptyMap())
            }
        }
    }

    fun markBackedUpAndComplete() {
        viewModelScope.launch {
            repository.setMnemonicBackedUp(true)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

// -- Screen --

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MnemonicBackupScreen(
    onNavigateToHome: () -> Unit,
    onNavigateBack: () -> Unit,
    simplified: Boolean = false,
    viewModel: MnemonicBackupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // FLAG_SECURE to prevent screenshots of mnemonic
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            simplified -> "Save Your Seed Phrase"
                            uiState.currentStep == 1 -> "Back Up Your Wallet"
                            uiState.currentStep == 2 -> "Verify Your Backup"
                            else -> "Backup Complete"
                        }
                    )
                },
                navigationIcon = {
                    if (uiState.currentStep < 3) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Lucide.ChevronLeft, "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when {
            // Sub-account wallet — no independent backup
            uiState.isSubAccount -> {
                SubAccountBackupInfo(
                    onNavigateBack = onNavigateBack,
                    modifier = Modifier.padding(padding)
                )
            }
            // Raw key wallet — show private key instead of mnemonic
            uiState.walletType == "raw_key" -> {
                RawKeyBackupInfo(
                    privateKeyHex = uiState.privateKeyHex,
                    snackbarHostState = snackbarHostState,
                    onNavigateBack = onNavigateBack,
                    modifier = Modifier.padding(padding)
                )
            }
            // Simplified mode (post-creation)
            simplified -> {
                MnemonicDisplayStep(
                    words = uiState.words,
                    onNext = {
                        viewModel.markBackedUpAndComplete()
                        onNavigateBack()
                    },
                    nextButtonLabel = "I've saved my seed phrase",
                    modifier = Modifier.padding(padding)
                )
            }
            // Normal mnemonic backup flow
            else -> {
                when (uiState.currentStep) {
                    1 -> MnemonicDisplayStep(
                        words = uiState.words,
                        onNext = { viewModel.advanceToVerify() },
                        modifier = Modifier.padding(padding)
                    )
                    2 -> MnemonicVerifyStep(
                        verifyPositions = uiState.verifyPositions,
                        verifyOptions = uiState.verifyOptions,
                        userSelections = uiState.userSelections,
                        onSelectWord = { pos, word -> viewModel.selectWord(pos, word) },
                        onVerify = { viewModel.verify() },
                        modifier = Modifier.padding(padding)
                    )
                    3 -> MnemonicSuccessStep(
                        onComplete = onNavigateToHome,
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }
    }
}

@Composable
private fun MnemonicDisplayStep(
    words: List<String>,
    onNext: () -> Unit,
    nextButtonLabel: String = "I've Written Them Down",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = com.rjnr.pocketnode.ui.util.screenHorizontalPadding(), vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Warning
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Lucide.TriangleAlert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Write these 12 words down in order. Never share them with anyone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // Word grid: 3 cols on phones (~110dp each), more on Medium/Expanded.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(words) { index, word ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${index + 1}.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.width(24.dp)
                        )
                        Text(
                            word,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Continue button
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
            enabled = words.isNotEmpty()
        ) {
            Text(nextButtonLabel)
        }
    }
}

@Composable
private fun MnemonicVerifyStep(
    verifyPositions: List<Int>,
    verifyOptions: Map<Int, List<String>>,
    userSelections: Map<Int, String>,
    onSelectWord: (Int, String) -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = com.rjnr.pocketnode.ui.util.screenHorizontalPadding(), vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                "Select the correct word for each position to verify your backup.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            verifyPositions.forEach { position ->
                val options = verifyOptions[position] ?: return@forEach
                val selected = userSelections[position]

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Word #${position + 1}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    // 2x2 grid of options
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (row in options.chunked(2)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                row.forEach { word ->
                                    val isSelected = selected == word
                                    OutlinedButton(
                                        onClick = { onSelectWord(position, word) },
                                        modifier = Modifier.weight(1f),
                                        colors = if (isSelected) {
                                            ButtonDefaults.outlinedButtonColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer
                                            )
                                        } else {
                                            ButtonDefaults.outlinedButtonColors()
                                        }
                                    ) {
                                        Text(word)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Button(
            onClick = onVerify,
            modifier = Modifier.fillMaxWidth(),
            enabled = verifyPositions.all { userSelections.containsKey(it) }
        ) {
            Text(stringResource(R.string.mnemonic_backup_verify))
        }
    }
}

@Composable
private fun MnemonicSuccessStep(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Lucide.CircleCheck,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "Backup Complete!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Your wallet recovery phrase is safely backed up.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.mnemonic_backup_continue))
        }
    }
}

// -- Raw key wallet backup info --

@Composable
private fun RawKeyBackupInfo(
    privateKeyHex: String?,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "No seed phrase available",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "This wallet was imported using a private key, so there is no seed phrase to back up. You can copy your private key below to store it safely.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (privateKeyHex != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Private Key",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(8.dp))
                    val masked = privateKeyHex.take(8) + "••••••••••••••••" + privateKeyHex.takeLast(8)
                    Text(
                        text = masked,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(privateKeyHex))
                            scope.launch {
                                snackbarHostState.showSnackbar("Private key copied to clipboard")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.mnemonic_backup_copy_private_key))
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        OutlinedButton(
            onClick = onNavigateBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.mnemonic_backup_done))
        }
    }
}

// -- Sub-account backup info --

@Composable
private fun SubAccountBackupInfo(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Sub-accounts don't have their own seed phrase",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "This account is derived from its parent wallet's seed phrase. To back up this account, go to the parent wallet's settings and back up its seed phrase. The parent's seed phrase can recover all of its sub-accounts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.weight(1f))

        OutlinedButton(
            onClick = onNavigateBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.mnemonic_backup_got_it))
        }
    }
}
