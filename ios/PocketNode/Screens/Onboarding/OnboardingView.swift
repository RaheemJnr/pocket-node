import SwiftUI

/// The first-run flow. Owns nothing: ``OnboardingViewModel`` holds the state
/// machine and this renders whichever step it is on.
///
/// Copy mirrors Android's `OnboardingScreen`, down to the footer line, so the
/// two apps read the same to a user who has seen both.
struct OnboardingView: View {
    @Bindable var model: OnboardingViewModel
    let auth: AuthService
    /// Called once, when the flow reaches ``OnboardingViewModel/Step/done``.
    let onFinished: () -> Void

    var body: some View {
        NavigationStack {
            step
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .toolbar { toolbar }
        }
        .onChange(of: model.step) { _, new in
            if new == .done { onFinished() }
        }
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
            BackupPlaceholderView(wordCount: model.pendingMnemonic.count) {
                model.finishBackup()
            }
        case .pinSetup:
            PinSetupView(auth: auth) { model.finishPinSetup() }
                .navigationTitle("Secure your wallet")
                .navigationBarTitleDisplayMode(.inline)
        case .done:
            // One frame at most: `onChange` above hands over to the wallet.
            Color.clear
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        if model.step == .create || model.step == .importWallet {
            ToolbarItem(placement: .topBarLeading) {
                Button("Back") { model.backToWelcome() }
                    .disabled(model.isBusy)
                    .accessibilityIdentifier("onboarding.back")
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

/// Stands in for the real backup and verification screen, which is #516.
///
/// It deliberately does not show the phrase: revealing it is the other issue's
/// job, and half of it here would leave secret material on screen with none of
/// the verification that is supposed to follow. Continuing leaves the wallet
/// recorded as not backed up, which is what the nag in a later build reads.
private struct BackupPlaceholderView: View {
    let wordCount: Int
    let onContinue: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "doc.text.magnifyingglass")
                .font(.system(size: 44))
                .foregroundStyle(.secondary)

            Text("Back up your recovery phrase")
                .font(.title2.weight(.semibold))
                .multilineTextAlignment(.center)

            Text("Your wallet is ready and its \(wordCount) word recovery phrase is stored on this device. Writing it down comes in the next build.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Button("Continue", action: onContinue)
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("onboarding.backupContinue")

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 32)
        .padding(.top, 48)
        .privacySensitive()
        .navigationTitle("Recovery phrase")
        .navigationBarTitleDisplayMode(.inline)
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
