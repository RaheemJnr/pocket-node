import SwiftUI

/// The first-run flow. Owns nothing: ``OnboardingViewModel`` holds the state
/// machine and this renders whichever step it is on.
///
/// Copy mirrors Android's `OnboardingScreen`, down to the footer line, so the
/// two apps read the same to a user who has seen both.
struct OnboardingView: View {
    @Bindable var model: OnboardingViewModel
    let auth: AuthService
    /// Builds the backup step's view model, in onboarding mode. A closure
    /// rather than the container itself, so this view keeps knowing nothing
    /// about the object graph (`AppContainer.makeBackupViewModel`).
    let makeBackupViewModel: () -> BackupViewModel
    /// Called once, when the flow reaches ``OnboardingViewModel/Step/done``.
    let onFinished: () -> Void

    /// Built when the backup step appears and dropped when it is left, so the
    /// revealed phrase does not outlive the screen that shows it.
    @State private var backup: BackupViewModel?

    var body: some View {
        NavigationStack {
            step
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .toolbar { toolbar }
        }
        .onChange(of: model.step) { _, new in
            if new == .done { onFinished() }
        }
        // See `HomeView`: a bare identifier on the flow would be pushed down
        // onto every button inside it, including the backup step's.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("onboarding.root")
    }

    @ViewBuilder
    private var step: some View {
        switch model.step {
        case .welcome:
            WelcomeView(model: model)
        case .create:
            CreateWalletView(model: model)
        case .importWallet:
            ImportWalletView(model: model)
        case .backup:
            backupStep
        case .pinSetup:
            PinSetupView(auth: auth) { model.finishPinSetup() }
                .navigationTitle("Secure your wallet")
                .navigationBarTitleDisplayMode(.inline)
        case .done:
            // One frame at most: `onChange` above hands over to the wallet.
            Color.clear
        }
    }

    /// The real backup and verification screen, in onboarding mode: the reveal
    /// gate is exempt while no PIN exists yet (`BackupViewModel` re-checks that
    /// on every reveal), and the verify quiz is what sets `mnemonicBackedUp`.
    ///
    /// There is no way past it but through, matching Android, whose onboarding
    /// `MnemonicBackupScreen` offers no skip either.
    private var backupStep: some View {
        Group {
            if let backup {
                BackupView(viewModel: backup) {
                    model.finishBackup()
                    self.backup = nil
                }
            } else {
                Color.clear
            }
        }
        .navigationTitle("Recovery phrase")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            guard backup == nil else { return }
            backup = makeBackupViewModel()
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        if (model.step == .create || model.step == .importWallet) && !model.isRestoring {
            ToolbarItem(placement: .topBarLeading) {
                Button("Back") { model.backToWelcome() }
                    .disabled(model.isBusy)
                    .accessibilityIdentifier("onboarding.back")
            }
        }
        // A restore that cannot go ahead offers a way back behind the lock
        // when a PIN stands in front of the wallet. With no PIN there is
        // nowhere to go back to: welcome would only offer a create that the
        // existing wallet refuses, so the screen's own copy (update the app)
        // is the way out.
        if model.step == .importWallet && model.isUnsupportedRestore && auth.state == .unlocked {
            ToolbarItem(placement: .topBarLeading) {
                Button("Lock") { auth.lock() }
                    .accessibilityIdentifier("onboarding.lock")
            }
        }
    }
}

/// Create or import, and nothing else. The two options are the same pair, in
/// the same order, as Android's welcome screen.
private struct WelcomeView: View {
    let model: OnboardingViewModel

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 24)

            Image(systemName: "wallet.bifold")
                .font(.system(size: 64))
                .foregroundStyle(Color.accentColor)

            Text("Welcome to Pocket Node")
                .font(.largeTitle.weight(.bold))
                .multilineTextAlignment(.center)
                .padding(.top, 24)

            Text("Secure, private, and localized CKB management.")
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.top, 8)

            Spacer(minLength: 32)

            OnboardingOption(
                title: "Create new wallet",
                description: "Generate a new wallet with a 12 or 24 word recovery phrase.",
                symbol: "plus",
                identifier: "onboarding.create",
                action: model.beginCreate
            )

            OnboardingOption(
                title: "Import existing wallet",
                description: "Restore with your recovery phrase or a private key.",
                symbol: "key.horizontal",
                identifier: "onboarding.import",
                action: model.beginImport
            )
            .padding(.top, 16)

            Spacer(minLength: 24)

            Text("Your keys, your crypto. Data stays on your device.")
                .font(.caption)
                .foregroundStyle(.tertiary)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 24)
    }
}

/// One of the two welcome choices: a title, a line of explanation, and the
/// whole card is the tap target.
private struct OnboardingOption: View {
    let title: String
    let description: String
    let symbol: String
    let identifier: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: symbol)
                    .font(.title2)
                    .frame(width: 32)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.headline)
                    Text(description)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
        }
        .buttonStyle(.bordered)
        .accessibilityIdentifier(identifier)
    }
}

/// The error banner every onboarding step shares.
struct OnboardingErrorBanner: View {
    let message: String

    var body: some View {
        Text(message)
            .font(.subheadline)
            .foregroundStyle(Color.red)
            .multilineTextAlignment(.leading)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("onboarding.error")
    }
}
