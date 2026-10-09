import SwiftUI

/// Routes mirror the Android `NavGraph.kt` names so the two apps stay readable
/// side by side. DAO and multi-wallet arrive with M4.
enum Route: Hashable {
    case nodeStatus
    case receive
    /// The send form, its review sheet and its status sheet (#8).
    case send
    /// The wallet's transaction history (#9).
    case activity
    /// The recovery-phrase backup flow outside onboarding, so behind the
    /// re-auth gate. Onboarding shows the same screen inside its own flow.
    case backup
    case settings
}

struct RootView: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.scenePhase) private var scenePhase

    @State private var path = NavigationPath()

    /// What the root is showing. ``Phase/undecided`` lasts for one actor hop,
    /// the time it takes to ask the Keychain whether a wallet is there. It is a
    /// state rather than an assumption on purpose: guessing "no wallet" would
    /// flash onboarding at every returning user, and guessing "wallet" would
    /// flash the lock screen at every new one.
    private enum Phase: Equatable {
        case undecided
        case onboarding
        case wallet
    }

    @State private var phase: Phase = .undecided
    @State private var onboarding: OnboardingViewModel?
    @State private var home: HomeViewModel?
    /// A recovery waiting while a PIN stands in front of the wallet: the
    /// restore flows name the wallet and show its address, and rebuilding the
    /// metadata decrypts the keys, so they wait for the unlock
    /// (``LaunchGate/RestoreRoute/hold(_:)``). Also set while a metadata
    /// rebuild runs, so the stand-in covers it.
    @State private var pendingRecovery: LaunchGate.Recovery?
    /// How many times the stand-in has asked again about the held recovery.
    @State private var heldRestoreRetries = 0
    /// A metadata rebuild is running.
    @State private var isRebuilding = false
    /// The last metadata rebuild failed in a way worth retrying (a dismissed
    /// prompt, say). The next one waits for the user's tap rather than the
    /// retry timer, which would put the system prompt up every two seconds.
    @State private var rebuildNeedsRetry = false
    /// Rebuild attempts that failed in this session (a dismissed prompt not
    /// counted), for the reinstall hint after three.
    @State private var failedRebuilds = 0

    private var auth: AuthService { container.auth }
    private var gate: LaunchGate { container.launchGate }

    var body: some View {
        Group {
            switch phase {
            case .undecided:
                Color(uiColor: .systemBackground)
                    .ignoresSafeArea()
            case .onboarding:
                if let onboarding {
                    // A restore can run for a wallet whose PIN survived, after
                    // the user unlocked with it. Going to the background locks
                    // that session like any other, and the restore screen
                    // (which names the wallet and shows its address) must go
                    // behind the lock with it.
                    if onboarding.isRestoring && auth.isGated {
                        LockView(auth: auth)
                    } else {
                        OnboardingView(
                            model: onboarding,
                            auth: auth,
                            makeBackupViewModel: { container.makeBackupViewModel(isOnboarding: true) }
                        ) {
                            phase = .wallet
                            // Asked again at once rather than on the next
                            // trigger: a recovery that stored the keys but not
                            // the metadata (a failed save) is a wallet with no
                            // record, which the gate sends to the rebuild
                            // instead of an empty shell. The wallet exists only
                            // now, so this is also the first moment the sync
                            // layer can be pointed at it: a reroute that stays
                            // in the wallet phase ends in activateSyncIfAllowed.
                            Task { await reroute() }
                        }
                    }
                }
            case .wallet:
                // Only a confirmed absence of a PIN opens the gate: a PIN store
                // that cannot be read stays locked (`AuthService.isGated`,
                // #513). A wallet with no PIN at all is an onboarding that was
                // cut short, and a wallet waiting to be restored has no keys:
                // neither is a wallet to open, so nothing is drawn for them
                // here, and `reroute()` sends them where they belong.
                if auth.isGated {
                    LockView(auth: auth)
                } else if gate.needsSecuritySetup || pendingRecovery != nil {
                    // Nothing else is guaranteed to change the session from
                    // here, so this re-reads it and routes on, rather than
                    // waiting on an `onChange` that may never fire. Keyed on
                    // the pending restore: the stand-in can already be up when
                    // a reroute starts holding one, and a task that has run
                    // once would leave it blank until the next backgrounding.
                    // A held restore whose key lookup keeps failing has
                    // nothing else to move it on, so it is asked again every
                    // two seconds while it is held.
                    // After about ten seconds of that the user is told what
                    // to do, rather than left watching a spinner.
                    ZStack {
                        Color(uiColor: .systemBackground)
                            .ignoresSafeArea()
                        VStack(spacing: 16) {
                            ProgressView()
                                .accessibilityLabel("Loading")
                            if rebuildNeedsRetry {
                                Text("Could not read your wallet keys to restore its details.")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                    .multilineTextAlignment(.center)
                                    .padding(.horizontal, 32)
                                    .accessibilityIdentifier("root.rebuildFailed")
                                Button(OnboardingViewModel.retryUnlockTitle) {
                                    rebuildNeedsRetry = false
                                    Task { await rebuildMetadata() }
                                }
                                .accessibilityIdentifier("root.rebuildRetry")
                                if OnboardingViewModel.showsReinstallHint(failedAttempts: failedRebuilds) {
                                    Text(OnboardingViewModel.reinstallHint)
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                        .multilineTextAlignment(.center)
                                        .padding(.horizontal, 32)
                                        .accessibilityIdentifier("root.reinstallHint")
                                }
                            } else if pendingRecovery != nil && heldRestoreRetries >= 5 {
                                Text("Could not read your wallet keys. Close the app and open it again.")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                    .multilineTextAlignment(.center)
                                    .padding(.horizontal, 32)
                                    .accessibilityIdentifier("root.keysUnreadable")
                            }
                        }
                    }
                    .task(id: pendingRecovery?.id) {
                        heldRestoreRetries = 0
                        await reroute()
                        while pendingRecovery != nil, !Task.isCancelled {
                            try? await Task.sleep(for: .seconds(2))
                            guard !Task.isCancelled else { return }
                            heldRestoreRetries += 1
                            await reroute()
                        }
                    }
                } else {
                    wallet
                }
            }
        }
        // Decided once per launch, from what is stored rather than from how
        // the last run ended, so a process killed after the wallet was stored
        // but before the PIN was set resumes at the backup or PIN step instead
        // of opening the wallet (`OnboardingViewModel.launchDestination`), and
        // a wallet whose keys did not come across with a device backup goes to
        // the restore flow (`LaunchGate.restoreRoute`). The restore
        // check comes first: a key-less wallet has no phrase to back up and no
        // keys for a PIN to protect. Onboarding stays on screen for the whole
        // flow after that, so finishing one step does not evict the user into
        // the wallet before they have set a PIN.
        .task {
            guard phase == .undecided else { return }
            // Built before the phase is decided, so the wallet shell has it on
            // its first frame whichever way this goes. It reads an empty
            // wallet as empty state and re-reads on every appearance, so
            // building it ahead of onboarding costs nothing.
            home = container.makeHomeViewModel()
            guard apply(await gate.restoreRoute()) else { return }
            route(to: await gate.launchDestination)
        }
        // Re-asked whenever the session changes and whenever the app comes
        // back to the front, while the wallet phase is up: a PIN store that
        // could not be read at launch may turn out to hold no PIN, a Keychain
        // that could not be read may turn out to have no keys, and an unlock
        // is what lets a restore held behind the lock screen go ahead.
        .onChange(of: auth.state) { _, _ in
            Task { await reroute() }
        }
        .onChange(of: scenePhase) { _, newScenePhase in
            guard newScenePhase == .active else { return }
            Task { await reroute() }
        }
        // A decrypt that proved the keys unusable (the phrase reveal) sends
        // the wallet to the restore for invalidated keys.
        .onChange(of: container.keysUnusableSignal) { _, _ in
            Task { await reroute() }
        }
        .onChange(of: colorScheme, initial: true) {
            container.theme = Theme.forScheme(colorScheme)
        }
        // The lock-on-background rule. `.background` only: `.inactive` also
        // fires for a notification banner or a control centre pull, and the app
        // switcher preview itself, none of which should throw the user out.
        // `initial: true` because a cold launch begins in `.active` and never
        // changes phase: without it the watchdog would not start until the
        // user backgrounded the app and came back.
        .onChange(of: scenePhase, initial: true) { _, newScenePhase in
            auth.handleScenePhase(newScenePhase)
            // The broadcast watchdog is foreground-only: its fallback timer
            // would be suspended in the background anyway, and Android gates
            // its own on `ProcessLifecycleOwner` for the same reason.
            if newScenePhase == .active {
                container.sync.startWatchdog()
            } else if newScenePhase == .background {
                container.sync.stopWatchdog()
            }
        }
        // Step-up auth for a single action (`AuthService.requireAuth`), used by
        // the recovery phrase reveal and later by send confirmation. Dismissing
        // the sheet answers the request with a refusal rather than leaving the
        // caller suspended.
        .sheet(item: Binding(get: { auth.challenge }, set: { if $0 == nil { auth.resolveChallenge(granted: false) } })) { challenge in
            AuthChallengeSheet(auth: auth, challenge: challenge)
        }
        // Last, so it covers everything drawn inside the root. A sheet is
        // presented outside the root's own hierarchy and so is not covered;
        // the only one today is the PIN challenge above, which shows dots.
        .privacyShield()
    }

    /// Carries out a ``LaunchGate/RestoreRoute``. Returns true when there is
    /// no restore to deal with and the PIN routing should go on.
    private func apply(_ restoreRoute: LaunchGate.RestoreRoute) -> Bool {
        switch restoreRoute {
        case .restore(let recovery):
            start(recovery)
            return false
        case .hold(let recovery):
            // The wallet phase shows the lock screen first, and the recovery
            // starts once the session is unlocked.
            pendingRecovery = recovery
            phase = .wallet
            return false
        case .none:
            pendingRecovery = nil
            rebuildNeedsRetry = false
            return true
        }
    }

    private func start(_ recovery: LaunchGate.Recovery) {
        let pinService = container.pinService
        let hasPin = { pinService.pinPresence != .absent }
        switch recovery {
        case .keysMissing(let record):
            startOnboarding(OnboardingViewModel(creator: container.walletCreator, restoring: record, hasPin: hasPin))
        case .keysInvalidated(let record):
            startOnboarding(
                OnboardingViewModel(
                    creator: container.walletCreator,
                    restoring: record,
                    keysInvalidated: true,
                    hasPin: hasPin
                )
            )
        case .keysSuspended(let record):
            let creator = container.walletCreator
            startOnboarding(
                OnboardingViewModel(
                    creator: creator,
                    restoring: record,
                    hasPin: hasPin,
                    retryUnlock: { try await creator.retryUnlock(reason: "Unlock your wallet keys", matching: record) }
                )
            )
        case .replaceKeys:
            startOnboarding(.replacingUnusableKeys(creator: container.walletCreator, hasPin: hasPin))
        case .rebuildMetadata:
            // Behind the stand-in while it runs. A failure worth retrying
            // waits for the user's tap instead of the stand-in's timer.
            pendingRecovery = .rebuildMetadata
            phase = .wallet
            guard !isRebuilding, !rebuildNeedsRetry else { return }
            Task { await rebuildMetadata() }
        }
    }

    private func startOnboarding(_ model: OnboardingViewModel) {
        path = NavigationPath()
        pendingRecovery = nil
        rebuildNeedsRetry = false
        onboarding = model
        phase = .onboarding
    }

    /// Writes `wallet.json` again from usable keys, then routes on: to the
    /// PIN routing when it is back, to the import over unusable keys when
    /// the keys turn out not to decrypt, or to a "Try again" button.
    private func rebuildMetadata() async {
        guard !isRebuilding else { return }
        isRebuilding = true
        var failure: Error?
        do {
            try await container.walletCreator.rebuildMetadata(reason: "Unlock your wallet keys to restore its details")
        } catch {
            failure = error
        }
        isRebuilding = false
        let outcome = await gate.rebuildOutcome(error: failure)
        switch outcome {
        case .rebuilt:
            pendingRecovery = nil
            if phase == .wallet {
                await reroute()
            }
        case .keysUnusable:
            start(.replaceKeys)
        case .retryOnRequest:
            if OnboardingViewModel.message(for: failure ?? WalletCreationError.metadataStorageFailed) != nil {
                failedRebuilds += 1
            }
            rebuildNeedsRetry = true
        }
    }

    /// Asks where the wallet phase should go instead, if anywhere: the
    /// restore flow first, as at launch, then ``LaunchGate/reroute()``. The
    /// phase is checked again after each await: another trigger may have
    /// routed already, and a second route would build a second onboarding
    /// view model over the first.
    ///
    /// Async so the stand-in's retry loop waits for each attempt instead of
    /// piling them up; the `onChange` callers wrap it in a `Task`.
    private func reroute() async {
        guard phase == .wallet else { return }
        let restoreRoute = await gate.restoreRoute(pending: pendingRecovery)
        guard phase == .wallet, apply(restoreRoute) else { return }
        if let destination = await gate.reroute() {
            guard phase == .wallet else { return }
            route(to: destination)
            return
        }
        // Staying in the wallet phase. A held recovery that has just cleared
        // (a restore, a rebuilt `wallet.json`, "Try unlocking again"), a
        // finished onboarding, or a wallet that could not be read when the
        // phase was first entered, has not started sync yet, and nothing else
        // would start it before the next launch.
        await activateSyncIfAllowed()
    }

    /// Points the sync layer at the wallet if ``LaunchGate/maySync(pendingRestore:)``
    /// allows it. Called whenever the root lands in, or stays in, the wallet
    /// phase. `activateSync` ignores a repeat for the same wallet, so every
    /// one of those can call it.
    ///
    /// Sync starts behind the lock screen on purpose: catching the chain up
    /// does not read key material, and making the user unlock before the
    /// node starts would waste the first minute of every launch. Android
    /// starts its poll from the repository for the same reason.
    ///
    /// Once started it is not stopped if a later reroute leaves the wallet
    /// phase: `SyncService` has no deactivate short of `shutdown()`, which
    /// closes its database for good, and a running sync reads no keys.
    private func activateSyncIfAllowed() async {
        guard phase == .wallet else { return }
        let allowed = await gate.maySync(pendingRestore: pendingRecovery != nil)
        guard allowed, phase == .wallet, pendingRecovery == nil else { return }
        container.activateSync()
    }

    private func route(to destination: OnboardingViewModel.LaunchDestination) {
        switch destination {
        case .wallet:
            phase = .wallet
            // A launch whose PIN or key lookup could not answer lands here
            // too, on its way to the lock screen, so this asks rather than
            // starting sync outright.
            Task { await activateSyncIfAllowed() }
        case .onboarding(let step):
            let gate = gate
            // A reroute can come from a pushed screen. Onboarding replaces the
            // whole stack, and finishing it must land on Home, not on a stale
            // screen the old path would bring back.
            path = NavigationPath()
            onboarding = OnboardingViewModel(
                creator: container.walletCreator,
                resumingAt: step,
                prepareNewWallet: { await gate.prepareNewWallet() }
            )
            phase = .onboarding
        }
    }

    private var wallet: some View {
        NavigationStack(path: $path) {
            walletHome
                .navigationTitle("Pocket Node")
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            path.append(Route.settings)
                        } label: {
                            Label("Settings", systemImage: "gearshape")
                        }
                        .accessibilityIdentifier("root.settings")
                    }
                    // Kept on the toolbar rather than folded into Settings:
                    // `NodeStatusUITests` taps this identifier from Home, and
                    // two items is still a plain toolbar.
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            path.append(Route.nodeStatus)
                        } label: {
                            Label("Node status", systemImage: "antenna.radiowaves.left.and.right")
                        }
                        .accessibilityIdentifier("root.nodeStatus")
                    }
                }
                .navigationDestination(for: Route.self) { route in
                    destination(for: route)
                }
                .onAppear(perform: pushStartRouteForTestingIfRequested)
        }
    }

    /// Pushes the screen named by `POCKETNODE_START_ROUTE` on the first
    /// appearance of the wallet shell.
    ///
    /// Debug-only, and a no-op on every other launch. It exists because a
    /// screenshot or acceptance run on a simulator cannot tap: driving the UI
    /// from outside the app needs assistive access, which a CI machine and a
    /// headless local run do not have. The same reason
    /// `POCKETNODE_SKIP_ONBOARDING` exists in `AppContainer`.
    private func pushStartRouteForTestingIfRequested() {
        #if DEBUG
        guard path.isEmpty,
              let name = ProcessInfo.processInfo.environment["POCKETNODE_START_ROUTE"]
        else { return }
        switch name {
        case "activity": path.append(Route.activity)
        case "receive": path.append(Route.receive)
        case "send": path.append(Route.send)
        case "nodeStatus": path.append(Route.nodeStatus)
        case "settings": path.append(Route.settings)
        default: break
        }
        #endif
    }

    @ViewBuilder
    private var walletHome: some View {
        if let home {
            HomeView(
                model: home,
                theme: container.theme,
                onReceive: { path.append(Route.receive) },
                onSend: { path.append(Route.send) },
                onActivity: { path.append(Route.activity) },
                onBackUp: { path.append(Route.backup) }
            )
        } else {
            // One frame at most: the root's `task` builds it before the phase
            // that shows this is ever reached.
            Color(uiColor: .systemBackground).ignoresSafeArea()
        }
    }

    @ViewBuilder
    private func destination(for route: Route) -> some View {
        switch route {
        case .nodeStatus:
            NodeStatusView()
        case .receive:
            ReceiveRoute(onBackUp: { path.append(Route.backup) })
        case .send:
            SendRoute(onFinished: { path.removeLast(path.count) })
        case .activity:
            ActivityRoute()
        case .backup:
            BackupRoute()
        case .settings:
            SettingsView(auth: auth, onBackUp: { path.append(Route.backup) })
        }
    }
}

/// Holds the Receive screen's view model for as long as the screen is pushed.
///
/// A `navigationDestination` closure runs again on every re-render, so
/// building the view model inline would hand `ReceiveView` a new one each
/// time. The `@State` here is what keeps it to one.
private struct ReceiveRoute: View {
    @Environment(AppContainer.self) private var container

    /// Navigation to the backup flow, for the "Back up now" prompt.
    let onBackUp: () -> Void

    @State private var viewModel: ReceiveViewModel?

    var body: some View {
        Group {
            if let viewModel {
                ReceiveView(viewModel: viewModel)
            } else {
                Color(uiColor: .systemBackground)
            }
        }
        .navigationTitle("Receive")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            guard viewModel == nil else { return }
            viewModel = container.makeReceiveViewModel(onBackUp: onBackUp)
        }
    }
}

/// Holds the Send screen's view model for as long as the screen is pushed.
///
/// The same `@State` arrangement `ReceiveRoute` uses, and for a sharper
/// reason here: a `navigationDestination` closure runs again on every
/// re-render, and a view model rebuilt mid-send would throw away the typed
/// amount and the review the user was looking at.
private struct SendRoute: View {
    @Environment(AppContainer.self) private var container

    /// Pops back to Home once a confirmed send has been dismissed.
    let onFinished: () -> Void

    @State private var viewModel: SendViewModel?

    var body: some View {
        Group {
            if let viewModel {
                SendView(
                    model: viewModel,
                    theme: container.theme,
                    makeScanner: { onScanned in
                        container.makeQrScannerViewModel(onScanned: onScanned)
                    },
                    onFinished: onFinished,
                    isSeededTestWallet: container.isSeededTestWallet
                )
            } else {
                Color(uiColor: .systemBackground)
            }
        }
        .onAppear {
            guard viewModel == nil else { return }
            viewModel = container.makeSendViewModel()
        }
    }
}

/// Holds the activity list's view model for as long as the screen is pushed,
/// for the same reason `ReceiveRoute` does: a `navigationDestination` closure
/// runs again on every re-render, and a view model rebuilt each time would
/// throw the list back to page 0 and lose the scroll position.
private struct ActivityRoute: View {
    @Environment(AppContainer.self) private var container

    @State private var viewModel: ActivityViewModel?

    var body: some View {
        Group {
            if let viewModel {
                ActivityView(model: viewModel, theme: container.theme)
            } else {
                // Nil only when no wallet is loaded, which the wallet shell
                // cannot be reached without.
                Color(uiColor: .systemBackground)
            }
        }
        .onAppear {
            guard viewModel == nil else { return }
            viewModel = container.makeActivityViewModel()
        }
    }
}

/// The backup flow outside onboarding: `isOnboarding: false`, so
/// `BackupViewModel` runs the re-auth gate before it decrypts anything.
///
/// Reached from the Home banner, the Receive prompt and Settings. "Done" pops
/// back to whichever of those pushed it.
private struct BackupRoute: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss

    @State private var viewModel: BackupViewModel?

    var body: some View {
        Group {
            if let viewModel {
                BackupView(viewModel: viewModel) { dismiss() }
            } else {
                Color(uiColor: .systemBackground)
            }
        }
        .navigationTitle("Recovery phrase")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            guard viewModel == nil else { return }
            viewModel = container.makeBackupViewModel(isOnboarding: false)
        }
    }
}

/// The PIN prompt raised by ``AuthService/requireAuth(reason:)``.
///
/// Separate from ``LockView`` because it guards one action rather than the
/// session: a correct PIN here resolves the caller's request and does not
/// unlock the app, and cancelling returns the user to what they were doing
/// instead of leaving them on a lock screen.
private struct AuthChallengeSheet: View {
    let auth: AuthService
    let challenge: AuthChallenge

    @State private var digits = ""
    @State private var errorMessage: String?
    @State private var errorToken = 0
    @State private var isVerifying = false
    @State private var ticker: Task<Void, Never>?
    /// Set once the first refresh has been attempted (see ``PinGateContent``).
    @State private var didAttemptLoad = false

    private var pin: PinService { auth.pin }

    var body: some View {
        NavigationStack {
            Group {
                // As on `LockView`: no pad before the first refresh, or a
                // permanently locked PIN would show one on the first frame.
                switch PinGateContent.resolve(
                    hasLoadedState: pin.hasLoadedState,
                    didAttemptLoad: didAttemptLoad,
                    isPermanentlyLocked: pin.isPermanentlyLocked
                ) {
                case .loading:
                    ProgressView()
                        .padding(.top, 24)
                        .accessibilityLabel("Loading")
                        .accessibilityIdentifier("authChallenge.loading")
                case .permanentLock:
                    permanentLock
                case .pad:
                    PinEntryView(
                        title: "Enter PIN",
                        subtitle: challenge.reason,
                        footnote: pin.isLockedOut ? AuthCopy.countdown(pin.lockoutRemainingSeconds) : nil,
                        error: errorMessage,
                        errorToken: errorToken,
                        isBusy: isVerifying,
                        isEnabled: !pin.isLockedOut,
                        digits: $digits,
                        onComplete: { entered in Task { await submit(entered) } }
                    )
                }
            }
            .padding(.horizontal, 32)
            .padding(.top, 24)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { auth.resolveChallenge(granted: false) }
                        .accessibilityIdentifier("authChallenge.cancel")
                }
            }
        }
        // See `HomeView`: without this the identifier would be pushed down
        // onto every key of the pin pad, replacing `pin.key.*`.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("authChallenge.root")
        .task {
            await pin.refresh()
            didAttemptLoad = true
            startTickerIfNeeded()
        }
        .onDisappear { stopTicker() }
    }

    private var permanentLock: some View {
        Text(AuthCopy.permanentLockBody)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .padding(.top, 24)
            .accessibilityIdentifier("authChallenge.permanent")
    }

    private func submit(_ entered: String) async {
        guard !isVerifying else { return }
        isVerifying = true
        let granted = await auth.answerChallenge(pin: entered)
        isVerifying = false
        digits = ""
        guard !granted else { return }
        errorToken += 1
        errorMessage = AuthCopy.pinFailure(auth: auth)
        startTickerIfNeeded()
    }

    /// The same one-second refresh `LockView` runs, so a lockout counts down
    /// and the pad comes back without the user cancelling out of the sheet.
    private func startTickerIfNeeded() {
        guard pin.isLockedOut, ticker == nil else { return }
        ticker = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { return }
                await pin.refresh()
                if !pin.isLockedOut {
                    errorMessage = nil
                    stopTicker()
                    return
                }
            }
        }
    }

    private func stopTicker() {
        ticker?.cancel()
        ticker = nil
    }
}
