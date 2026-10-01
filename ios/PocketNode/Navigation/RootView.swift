import SwiftUI

/// Routes mirror the Android `NavGraph.kt` names so the two apps stay readable
/// side by side. Send, activity and DAO arrive with M3 and M4.
enum Route: Hashable {
    case nodeStatus
    case receive
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

    private var auth: AuthService { container.auth }

    var body: some View {
        Group {
            switch phase {
            case .undecided:
                Color(uiColor: .systemBackground)
                    .ignoresSafeArea()
            case .onboarding:
                if let onboarding {
                    OnboardingView(
                        model: onboarding,
                        auth: auth,
                        makeBackupViewModel: { container.makeBackupViewModel(isOnboarding: true) }
                    ) {
                        phase = .wallet
                    }
                }
            case .wallet:
                // Only a confirmed absence of a PIN opens the gate: a PIN store
                // that cannot be read stays locked (`AuthService.isGated`,
                // #513). A wallet with no PIN at all is an onboarding that was
                // cut short, never a wallet to open: nothing is drawn for it
                // here, and the `onChange` below sends it back to the
                // unfinished step.
                if auth.isGated {
                    LockView(auth: auth)
                } else if container.needsSecuritySetup {
                    Color(uiColor: .systemBackground)
                        .ignoresSafeArea()
                } else {
                    wallet
                }
            }
        }
        // Decided once per launch, from what is stored rather than from how
        // the last run ended (`OnboardingViewModel.launchDestination`), so a
        // process killed after the wallet was stored but before the PIN was
        // set resumes at the backup or PIN step instead of opening the wallet.
        // Onboarding stays on screen for the whole flow after that, so
        // finishing one step does not evict the user into the wallet before
        // they have set a PIN.
        .task {
            guard phase == .undecided else { return }
            // Built before the phase is decided, so the wallet shell has it on
            // its first frame whichever way this goes. It reads an empty
            // wallet as empty state and re-reads on every appearance, so
            // building it ahead of onboarding costs nothing.
            home = container.makeHomeViewModel()
            route(to: await container.launchDestination)
        }
        // A PIN store that could not be read at launch sends the wallet to
        // the lock screen; if it then reads as holding no PIN, the wallet is
        // an unfinished onboarding after all and goes back to it.
        .onChange(of: auth.state) { _, _ in
            guard phase == .wallet, container.needsSecuritySetup else { return }
            Task {
                let destination = await container.launchDestination
                // Checked again after the await: another state change may
                // have routed already, and a second route would build a
                // second onboarding view model over the first.
                guard phase == .wallet, container.needsSecuritySetup else { return }
                route(to: destination)
            }
        }
        .onChange(of: colorScheme, initial: true) {
            container.theme = Theme.forScheme(colorScheme)
        }
        // The lock-on-background rule. `.background` only: `.inactive` also
        // fires for a notification banner or a control centre pull, and the app
        // switcher preview itself, none of which should throw the user out.
        .onChange(of: scenePhase) { _, newScenePhase in
            auth.handleScenePhase(newScenePhase)
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

    private func route(to destination: OnboardingViewModel.LaunchDestination) {
        switch destination {
        case .wallet:
            phase = .wallet
        case .onboarding(let step):
            let container = container
            onboarding = OnboardingViewModel(
                creator: container.walletCreator,
                resumingAt: step,
                prepareNewWallet: { await container.removeOrphanedPin() }
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
        }
    }

    @ViewBuilder
    private var walletHome: some View {
        if let home {
            HomeView(
                model: home,
                theme: container.theme,
                onReceive: { path.append(Route.receive) },
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

    private var pin: PinService { auth.pin }

    var body: some View {
        NavigationStack {
            Group {
                if pin.isPermanentlyLocked {
                    permanentLock
                } else {
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
