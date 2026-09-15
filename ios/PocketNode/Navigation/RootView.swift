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
            // Only a configured PIN gates anything. Before onboarding (#515)
            // sets one there is no secret to check, so the wallet shell is
            // shown as it was in M1 rather than behind an unusable lock.
            if auth.state == .locked {
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

    var body: some View {
        NavigationStack {
            PinEntryView(
                title: "Enter PIN",
                subtitle: challenge.reason,
                footnote: nil,
                error: errorMessage,
                errorToken: errorToken,
                isBusy: isVerifying,
                isEnabled: !auth.pin.isLockedOut,
                digits: $digits,
                onComplete: { entered in Task { await submit(entered) } }
            )
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
    }

    private func submit(_ entered: String) async {
        guard !isVerifying else { return }
        isVerifying = true
        let granted = await auth.answerChallenge(pin: entered)
        isVerifying = false
        digits = ""
        guard !granted else { return }
        errorToken += 1
        errorMessage = auth.pin.isLockedOut
            ? "Too many attempts. Try again in \(auth.pin.lockoutRemainingSeconds)s"
            : "Wrong PIN. \(auth.pin.remainingAttempts) attempts remaining."
    }
}
