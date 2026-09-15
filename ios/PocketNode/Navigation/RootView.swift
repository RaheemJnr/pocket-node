import SwiftUI

/// Routes mirror the Android `NavGraph.kt` names so the two apps stay readable
/// side by side. M1 ships Home and NodeStatus only.
enum Route: Hashable {
    case nodeStatus
}

struct RootView: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.scenePhase) private var scenePhase

    @State private var path = NavigationPath()

    private var auth: AuthService { container.auth }

    var body: some View {
        Group {
            // Only a confirmed absence of a PIN opens the gate. Before
            // onboarding (#515) sets one there is no secret to check, so the
            // wallet shell is shown as it was in M1; a store that cannot be
            // read is not that case and stays locked (`AuthService.isGated`).
            if auth.isGated {
                LockView(auth: auth)
            } else {
                wallet
            }
        }
        .onChange(of: colorScheme, initial: true) {
            container.theme = Theme.forScheme(colorScheme)
        }
        // The lock-on-background rule. `.background` only: `.inactive` also
        // fires for a notification banner or a control centre pull, and the app
        // switcher preview itself, none of which should throw the user out.
        .onChange(of: scenePhase) { _, phase in
            auth.handleScenePhase(phase)
        }
        // Step-up auth for a single action (`AuthService.requireAuth`), used by
        // the recovery phrase reveal and later by send confirmation. Dismissing
        // the sheet answers the request with a refusal rather than leaving the
        // caller suspended.
        .sheet(item: Binding(get: { auth.challenge }, set: { if $0 == nil { auth.resolveChallenge(granted: false) } })) { challenge in
            AuthChallengeSheet(auth: auth, challenge: challenge)
        }
    }

    private var wallet: some View {
        NavigationStack(path: $path) {
            HomeView()
                .navigationTitle("Pocket Node")
                .toolbar {
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
                    switch route {
                    case .nodeStatus:
                        NodeStatusView()
                    }
                }
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
