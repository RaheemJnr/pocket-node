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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
    val error: String? = null,
    /**
     * True when a raw_key wallet's private key is gated behind PIN entry
     * (Settings → Backup Wallet path on an install with a PIN set). False
     * when no PIN exists yet (onboarding edge case for raw-key imports
     * pre-PIN-setup) or when the PIN has already been verified.
     */
    val pinRequiredForPrivateKey: Boolean = false,
    /** True once the user has revealed the private key in this session. */
    val privateKeyRevealed: Boolean = false,
    /**
     * True when the recovery phrase is gated behind authentication and has
     * not been revealed yet. The screen renders a "Reveal recovery phrase"
     * button instead of the words; passing the gate populates [words] and
     * clears this flag.
     *
     * Set for every key-material version on every non-onboarding entry
     * point (#488). Before #488 it was set only when the V1-only read path
     * threw `V2KeyMaterialRequiresAuthException`, so a kdfVersion=1 wallet
     * rendered all 12 words with no re-authentication at all.
     */
    val pinRequiredForMnemonic: Boolean = false,
    /**
     * Which gate [pinRequiredForMnemonic] is waiting on: true = the app PIN
     * via `PinEntryScreen` (V1 key material, which decrypts without a
     * CryptoObject), false = a BiometricPrompt driven by
     * `SeedPhraseAuthorizer` (V2 key material, where the prompt *is* the
     * decryption key).
     */
    val mnemonicGateUsesPin: Boolean = false,
)

@HiltViewModel
class MnemonicBackupViewModel @Inject constructor(
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val repository: GatewayRepository,
    private val walletRepository: com.rjnr.pocketnode.data.wallet.WalletRepository,
    private val pinManager: com.rjnr.pocketnode.data.auth.PinManager,
    private val seedPhraseAuthorizer: com.rjnr.pocketnode.data.wallet.SeedPhraseAuthorizer,
    private val keyMaterialDao: com.rjnr.pocketnode.data.database.dao.KeyMaterialDao,
    private val keyManager: com.rjnr.pocketnode.data.wallet.KeyManager,
) : ViewModel() {

    /**
     * Set by the first-run onboarding hop (Onboarding → backup, which
     * navigates with `onboarding=true`). That single edge runs *before*
     * `InitialPinSetup`, so there is no PIN to verify yet and nothing to
     * re-authenticate against — the wallet was created seconds ago in this
     * same uninterrupted session. Every other entry point (the post-auth
     * `needsMnemonicBackup` enforcement, the security checklist, a wallet
     * added from inside the app, Manage Wallets) reaches this screen on an
     * install that already has a mandatory PIN, so it must authenticate
     * before the words are decrypted (#488).
     *
     * This flag alone does NOT grant the exemption: it is a route argument
     * and therefore attacker-influenceable (a crafted deep link, or any
     * future caller that copies the wrong `createRoute` overload). It only
     * says which flow we think we are in; [isOnboardingExempt] confirms the
     * claim against real state before anything is decrypted.
     */
    private val onboardingArg: Boolean = savedStateHandle.get<Boolean>("onboarding") ?: false

    /**
     * The exemption, verified. The justification for skipping the gate is
     * "there is no PIN yet", so check exactly that rather than trusting the
     * route to have told the truth. Once a PIN exists the wallet is past
     * onboarding and the gate applies no matter what the route claims.
     */
    private fun isOnboardingExempt(): Boolean = onboardingArg && !pinManager.hasPin()

    /**
     * Wallet to back up when the caller named one — Manage Wallets → wallet →
     * "Backup wallet", where the chosen wallet need not be the active one.
     * Null means "the active wallet", which is what every other entry point
     * wants and what this screen did exclusively before. Reads and the
     * backed-up write are scoped to it so opening a non-active wallet's backup
     * neither shows the active wallet's phrase nor marks the wrong wallet
     * backed up.
     */
    private val walletIdArg: String? =
        savedStateHandle.get<String>("walletId")?.takeIf { it.isNotBlank() }

    /**
     * Which gate this screen decided on, remembered across a reveal so
     * [onBackgrounded] can put the same one back. Null means no gate applied
     * (verified onboarding, a sub-account, or a V1 wallet with no PIN), and
     * nothing is re-armed for those — there would be no way back in.
     */
    private var armedGateUsesPin: Boolean? = null

    private val _uiState = MutableStateFlow(MnemonicBackupUiState())
    val uiState: StateFlow<MnemonicBackupUiState> = _uiState.asStateFlow()

    init {
        loadMnemonic()
    }

    /**
     * Called from the screen's lifecycle observer on `ON_STOP`. Revealed
     * secrets must not survive the app going to the background: the words sit
     * in the recents card's process memory and are one app-switch away from
     * whoever picks the phone up next. Drop them and re-arm the gate so
     * coming back costs another PIN or BiometricPrompt.
     *
     * No-op when no gate applied in the first place, and on the final
     * confirmation step, which displays no secrets and whose "backed up"
     * state the user has already earned.
     */
    fun onBackgrounded() {
        val gateUsesPin = armedGateUsesPin ?: return
        if (_uiState.value.currentStep >= 3) {
            _uiState.update { it.copy(words = emptyList(), privateKeyHex = null) }
            return
        }
        _uiState.update {
            it.copy(
                currentStep = 1,
                words = emptyList(),
                verifyPositions = emptyList(),
                verifyOptions = emptyMap(),
                userSelections = emptyMap(),
                privateKeyHex = null,
                privateKeyRevealed = false,
                pinRequiredForMnemonic = it.walletType != "raw_key" && !it.isSubAccount,
                pinRequiredForPrivateKey = it.walletType == "raw_key" && !it.isSubAccount,
                mnemonicGateUsesPin = gateUsesPin,
            )
        }
    }

    private fun loadMnemonic() {
        viewModelScope.launch {
            // Detect wallet type for the wallet being backed up: the one named
            // on the route if there is one, else the active wallet.
            val targetWallet = walletIdArg?.let { walletRepository.getById(it) }
                ?: walletRepository.getActive()
            val walletType = targetWallet?.type ?: ""
            val isSubAccount = targetWallet?.parentWalletId != null
            _uiState.update { it.copy(walletType = walletType, isSubAccount = isSubAccount) }

            // #488 — authenticate BEFORE the phrase is decrypted.
            //
            // The old flow called repository.getMnemonic() eagerly and only
            // raised a gate when the V1-only read path threw
            // V2KeyMaterialRequiresAuthException. A kdfVersion=1 wallet
            // decrypts without an authenticated Cipher, so it never threw and
            // Settings → Backup Wallet rendered all 12 words with no PIN or
            // biometric step at all. The gate now comes first, independent of
            // key-material version, and mirrors the raw-key reveal added in
            // #290/#300: reveal-on-tap, nothing fetched until the user passes
            // the gate.
            //
            // Skipped for a verified onboarding run ([isOnboardingExempt] —
            // the route says onboarding AND no PIN exists yet), for
            // sub-accounts (they render the "backed up with the parent"
            // notice and never show words) and for raw_key wallets (handled
            // by the pinRequiredForPrivateKey gate below).
            if (!isOnboardingExempt() && !isSubAccount && walletType != "raw_key") {
                // A DB error here must not kill the coroutine (which would
                // leave the screen blank with no gate raised and no words).
                // Null means "no key_material row" — an ESP-fallback V1
                // wallet, not a failure.
                val kdfLookup = targetWallet?.walletId?.let { id ->
                    runCatching { keyMaterialDao.getKdfVersion(id) }
                }
                val kdfVersion = kdfLookup?.getOrNull() ?: 1
                if (kdfVersion >= 2) {
                    // The BiometricPrompt CryptoObject *is* the decryption key.
                    armedGateUsesPin = false
                    _uiState.update {
                        it.copy(pinRequiredForMnemonic = true, mnemonicGateUsesPin = false)
                    }
                    return@launch
                }
                if (pinManager.hasPin()) {
                    // V1 key material decrypts silently, so the app PIN is the
                    // gate — same route as the raw-key reveal (#290). This is
                    // also where a failed lookup lands when a PIN exists: the
                    // PIN gate degrades safely either way, since a row that
                    // turns out to be V2 throws on the post-PIN read and
                    // [fetchMnemonicAfterPin] swaps to the biometric gate.
                    armedGateUsesPin = true
                    _uiState.update {
                        it.copy(pinRequiredForMnemonic = true, mnemonicGateUsesPin = true)
                    }
                    return@launch
                }
                if (kdfLookup?.isFailure == true) {
                    // Could not confirm the version and there is no PIN to
                    // fall back on. Fail closed on the biometric gate rather
                    // than revealing on the strength of a failed query.
                    armedGateUsesPin = false
                    _uiState.update {
                        it.copy(pinRequiredForMnemonic = true, mnemonicGateUsesPin = false)
                    }
                    return@launch
                }
                // V1 wallet on an install with no PIN: there is no credential
                // to check, so gating would only lock the user out of their own
                // backup. Falls through to the direct read, as before #488.
                // PIN setup is mandatory after onboarding, so this is reachable
                // only on a legacy/interrupted install.
            }

            // V2 wallets (kdfVersion=2) cannot decrypt without an authenticated
            // Cipher. repository.getMnemonic() routes through the V1-only read
            // path and throws V2KeyMaterialRequiresAuthException, which would
            // crash the app on every cold start for a freshly-created V2
            // mnemonic wallet that hasn't been backed up yet. Catch and gate
            // behind reveal-on-tap: the screen renders a "Reveal recovery
            // phrase" button that routes through PinEntryScreen, and on PIN
            // verify we fetch the words via WalletKeyReader (#289 follow-up).
            val words = try {
                readMnemonic()
            } catch (e: com.rjnr.pocketnode.data.crypto.V2KeyMaterialRequiresAuthException) {
                armedGateUsesPin = false
                _uiState.update { it.copy(pinRequiredForMnemonic = true) }
                return@launch
            }
            if (words.isNullOrEmpty()) {
                // For raw_key or sub-account wallets, this is expected — not an error.
                // Only raw-key wallets need the private key for the dedicated raw-key
                // backup screen, and only behind a PIN gate when a PIN exists (#290).
                if (walletType == "raw_key" && !isSubAccount) {
                    if (pinManager.hasPin()) {
                        // Defer the private-key fetch until the user passes PIN
                        // verification. The screen renders a "Reveal private key"
                        // button that routes through PinEntryScreen; on return
                        // [onPinVerified] is invoked and the key is fetched.
                        armedGateUsesPin = true
                        _uiState.update { it.copy(pinRequiredForPrivateKey = true) }
                    } else {
                        // No PIN set yet (onboarding edge case for raw-key imports).
                        // Same behaviour as pre-#290: fetch and display directly.
                        fetchPrivateKey()
                    }
                }
                return@launch
            }
            showWords(words)
        }
    }

    /**
     * Read the phrase for the wallet this screen is backing up. With no
     * [walletIdArg] this is the repository's active-wallet read, unchanged
     * from before the per-wallet entry point existed (it carries a legacy
     * no-active-wallet fallback this scoped call does not).
     */
    private suspend fun readMnemonic(): List<String>? =
        walletIdArg?.let { keyManager.getMnemonicForWallet(it) } ?: repository.getMnemonic()

    /** Record the backup against the wallet actually being backed up. */
    private suspend fun markBackedUp() {
        walletIdArg?.let { keyManager.setMnemonicBackedUpForWallet(it, true) }
            ?: repository.setMnemonicBackedUp(true)
    }

    /**
     * Populate [words] plus the three randomly-chosen verification slots and
     * clear the reveal gate. Shared by the three ways the phrase can arrive:
     * the un-gated onboarding read, the V2 biometric reveal and the V1
     * post-PIN reveal.
     */
    private fun showWords(words: List<String>) {
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
            it.copy(
                words = words,
                verifyPositions = positions,
                verifyOptions = options,
                pinRequiredForMnemonic = false,
            )
        }
    }

    /**
     * Called from [MnemonicBackupScreen] when the user taps "Reveal recovery
     * phrase" on a wallet whose key material is V2. Drives a BiometricPrompt
     * via [SeedPhraseAuthorizer] (no PinEntryScreen detour — the V2 key is the
     * gate). On success the words are populated and the standard
     * verify+confirm flow continues.
     */
    fun revealMnemonicWithBiometrics(activity: androidx.fragment.app.FragmentActivity) {
        if (!_uiState.value.pinRequiredForMnemonic) return
        viewModelScope.launch {
            val targetWalletId = walletIdArg
                ?: walletRepository.getActive()?.walletId
                ?: return@launch
            val result = seedPhraseAuthorizer.authorize(
                activity = activity,
                walletId = targetWalletId,
                promptTitle = "Reveal recovery phrase",
                promptSubtitle = "Authenticate to view your wallet's seed phrase.",
            )
            when (result) {
                is com.rjnr.pocketnode.data.wallet.SeedPhraseAuthorizer.SeedResult.Words -> {
                    showWords(result.words)
                }
                is com.rjnr.pocketnode.data.wallet.SeedPhraseAuthorizer.SeedResult.Cancelled -> {
                    // Silent — user dismissed the prompt; the reveal button
                    // stays visible so they can retry.
                }
                is com.rjnr.pocketnode.data.wallet.SeedPhraseAuthorizer.SeedResult.KeyInvalidated -> {
                    _uiState.update { it.copy(error = "Your device's biometric enrollment changed and your wallet key was wiped. Re-import from your recovery phrase to recover.") }
                }
                is com.rjnr.pocketnode.data.wallet.SeedPhraseAuthorizer.SeedResult.Failed -> {
                    _uiState.update { it.copy(error = "Recovery phrase not available: ${result.reason}") }
                }
            }
        }
    }

    /**
     * Called from [MnemonicBackupScreen] after the user returns from
     * `PinEntryScreen` with a `pin_verified=true` savedStateHandle flag.
     * Serves both PIN gates: the raw-key private-key reveal (#290) and the
     * V1 recovery-phrase reveal (#488).
     */
    fun onPinVerified() {
        val state = _uiState.value
        if (state.pinRequiredForMnemonic && state.mnemonicGateUsesPin) {
            viewModelScope.launch { fetchMnemonicAfterPin() }
            return
        }
        if (!state.pinRequiredForPrivateKey) return
        viewModelScope.launch { fetchPrivateKey() }
    }

    /**
     * V1 post-PIN read. The key material decrypts without a CryptoObject, so
     * once [PinEntryScreen] has confirmed the app PIN the repository read is
     * the same one that used to run un-gated in `init` before #488.
     */
    private suspend fun fetchMnemonicAfterPin() {
        val words = try {
            readMnemonic()
        } catch (_: com.rjnr.pocketnode.data.crypto.V2KeyMaterialRequiresAuthException) {
            // The row was migrated to V2 between the gate decision and the PIN
            // return (AuthScreen's migration runner can do this). Swap to the
            // biometric gate rather than failing the reveal.
            _uiState.update { it.copy(mnemonicGateUsesPin = false) }
            return
        }
        if (words.isNullOrEmpty()) {
            _uiState.update { it.copy(error = "Recovery phrase not available for this wallet.") }
            return
        }
        showWords(words)
    }

    private suspend fun fetchPrivateKey() {
        val privateKeyHex = try {
            // Scoped like the phrase read: a named wallet must never surface
            // the active wallet's key, so no elvis fallback here. (Manage
            // Wallets only offers "Backup wallet" on mnemonic wallets, so this
            // arm is defensive.)
            if (walletIdArg != null) {
                keyManager.getPrivateKeyForWallet(walletIdArg)?.toHex()
            } else {
                repository.getPrivateKey().toHex()
            }
        } catch (_: Exception) {
            null
        }
        _uiState.update { it.copy(privateKeyHex = privateKeyHex, privateKeyRevealed = true) }
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
                markBackedUp()
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
            markBackedUp()
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
    onNavigateToPinVerify: () -> Unit = {},
    pinVerifiedFlow: androidx.lifecycle.SavedStateHandle? = null,
    simplified: Boolean = false,
    viewModel: MnemonicBackupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val activity = androidx.compose.ui.platform.LocalContext.current as androidx.fragment.app.FragmentActivity

    // Returning from PinEntryScreen: consume the pin_verified flag set by
    // the verify-mode pop-back path (NavGraph route). On true, fetch the
    // raw-key wallet's private key and unmask the reveal UI (#290).
    LaunchedEffect(pinVerifiedFlow) {
        val verified = pinVerifiedFlow?.get<Boolean>("pin_verified") == true
        if (verified) {
            pinVerifiedFlow.remove<Boolean>("pin_verified")
            viewModel.onPinVerified()
        }
    }

    // Re-arm the reveal gate when the app leaves the foreground. FLAG_SECURE
    // keeps the words out of the recents thumbnail, but without this they
    // would still be sitting on screen for whoever resumes the app next
    // (#488 review). ON_STOP rather than ON_PAUSE so the BiometricPrompt and
    // the PinEntry hop, which only pause the activity, do not wipe the state
    // they are in the middle of unlocking.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                viewModel.onBackgrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                    pinRequiredForReveal = uiState.pinRequiredForPrivateKey && !uiState.privateKeyRevealed,
                    onRequestPinVerify = onNavigateToPinVerify,
                    snackbarHostState = snackbarHostState,
                    onNavigateBack = onNavigateBack,
                    modifier = Modifier.padding(padding)
                )
            }
            // Recovery phrase gated behind re-authentication (#488). V2 key
            // material authenticates with a BiometricPrompt CryptoObject; V1
            // key material routes through PinEntryScreen and comes back via
            // [MnemonicBackupViewModel.onPinVerified].
            uiState.pinRequiredForMnemonic -> {
                MnemonicRevealGate(
                    usesPin = uiState.mnemonicGateUsesPin,
                    onReveal = {
                        if (uiState.mnemonicGateUsesPin) {
                            onNavigateToPinVerify()
                        } else {
                            viewModel.revealMnemonicWithBiometrics(activity)
                        }
                    },
                    error = uiState.error,
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

/**
 * Pre-reveal gate for the recovery phrase. Nothing is decrypted until the
 * user taps the button and passes authentication: a BiometricPrompt for V2
 * key material (the authenticated Cipher *is* the decryption key) or the app
 * PIN via `PinEntryScreen` for V1, which would otherwise decrypt silently
 * (#488).
 */
@Composable
private fun MnemonicRevealGate(
    usesPin: Boolean,
    onReveal: () -> Unit,
    error: String?,
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
                    text = "Your recovery phrase is the only way to restore this wallet on another device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (usesPin) {
                        "Tap below and enter your PIN to view the 12 words. Write them down somewhere safe — we cannot recover them for you."
                    } else {
                        "Tap below and authenticate to view the 12 words. Write them down somewhere safe — we cannot recover them for you."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Button(
            onClick = onReveal,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reveal recovery phrase")
        }

        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
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
    pinRequiredForReveal: Boolean,
    onRequestPinVerify: () -> Unit,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // Reveal-on-tap: after PIN verification the key is in `privateKeyHex` but
    // the user must explicitly request it to be displayed on screen (#290).
    var revealedOnScreen by remember { mutableStateOf(false) }

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

        if (pinRequiredForReveal) {
            // PIN gate: user must verify PIN before the VM fetches the key.
            // Tapping the button navigates to PinEntryScreen; on success the
            // savedStateHandle "pin_verified" flag flips and the VM's
            // onPinVerified() is called from the screen's LaunchedEffect.
            Button(
                onClick = onRequestPinVerify,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reveal private key")
            }
        } else if (privateKeyHex != null) {
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
                    // Full mask by default — show only the placeholder until the
                    // user explicitly taps to reveal. Previous behaviour showed
                    // 16 hex chars (8 leading + 8 trailing) which is enough to
                    // narrow a brute-force search (#290).
                    val display = if (revealedOnScreen) privateKeyHex else "•".repeat(privateKeyHex.length)
                    Text(
                        text = display,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        onClick = { revealedOnScreen = !revealedOnScreen },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (revealedOnScreen) "Hide" else "Tap to reveal")
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            // Clipboard timeout to match the mnemonic backup
                            // behaviour added in #181. The clear runs on a
                            // process-lifetime scope so navigating away from
                            // this screen can't cancel it (#317 Codex review).
                            com.rjnr.pocketnode.ui.util.SensitiveClipboard
                                .copyWithTimeout(context, privateKeyHex)
                            scope.launch {
                                snackbarHostState.showSnackbar("Private key copied. Clipboard will clear in 60s.")
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
