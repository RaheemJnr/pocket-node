import SwiftUI

/// Choosing the length of a new wallet's recovery phrase and naming it.
///
/// 12 words is the default, as on Android, and is what almost everyone should
/// take: 24 buys 256 bits of entropy over 128, which no attacker is anywhere
/// near, at the cost of twice as much to write down and check.
struct CreateWalletView: View {
    let model: OnboardingViewModel

    @State private var wordCount = 12
    @State private var name = WalletCreator.defaultName
    @FocusState private var isNaming: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text("Your recovery phrase is the only way to restore this wallet. It is generated on this device and never leaves it.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                VStack(alignment: .leading, spacing: 8) {
                    Text("Recovery phrase length")
                        .font(.headline)
                    Picker("Recovery phrase length", selection: $wordCount) {
                        Text("12 words").tag(12)
                        Text("24 words").tag(24)
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("create.wordCount")
                    Text(wordCount == 12
                         ? "12 words. The usual choice, and what most wallets produce."
                         : "24 words. More to write down, for no practical gain in safety.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("Wallet name")
                        .font(.headline)
                    TextField(WalletCreator.defaultName, text: $name)
                        .textFieldStyle(.roundedBorder)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.words)
                        .submitLabel(.done)
                        .focused($isNaming)
                        .accessibilityIdentifier("create.name")
                    Text("Give this wallet a label so you can spot it if you add more later.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                if let message = model.errorMessage {
                    OnboardingErrorBanner(message: message)
                }

                Button {
                    isNaming = false
                    Task { await model.createWallet(wordCount: wordCount, name: name) }
                } label: {
                    if model.isBusy {
                        ProgressView().frame(maxWidth: .infinity)
                    } else {
                        Text("Create wallet").frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(model.isBusy)
                .accessibilityIdentifier("create.submit")
            }
            .padding(24)
        }
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle("Create wallet")
        .navigationBarTitleDisplayMode(.inline)
    }
}
