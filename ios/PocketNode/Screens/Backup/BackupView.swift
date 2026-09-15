import SwiftUI

/// The recovery-phrase backup flow: reveal gate, a numbered word grid, a
/// 3-question verify quiz, and a success step (plus an explanatory step for a
/// raw-key wallet, which has no phrase to back up).
///
/// Self-contained: every dependency comes through `viewModel`, and `onFinished`
/// is the only way this view reaches back out, called once from the success
/// step's "Done" and once from the no-phrase step's "Done". A parent screen
/// wires navigation there without this view knowing `RootView` or
/// `AppContainer` exist.
struct BackupView: View {
    let viewModel: BackupViewModel
    var onFinished: () -> Void

    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            switch viewModel.step {
            case .gate:
                gateStep
            case .noPhrase:
                noPhraseStep
            case .display:
                displayStep
            case .verify:
                verifyStep
            case .success:
                successStep
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 24)
        .padding(.bottom, 16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .onChange(of: scenePhase) { _, phase in
            if phase == .background {
                viewModel.onBackgrounded()
            }
        }
        .accessibilityIdentifier("backup.root")
    }

    // MARK: - Gate

    private var gateStep: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("Back up your wallet")
                .font(.title2.weight(.semibold))

            Text("Your recovery phrase is the only way to restore this wallet on another device. Anyone who has it can spend your funds, so keep it private and never share it.")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            if let error = viewModel.errorMessage {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .accessibilityIdentifier("backup.gate.error")
            }

            Spacer(minLength: 0)

            Button {
                Task { await viewModel.reveal() }
            } label: {
                Group {
                    if viewModel.isRevealing {
                        ProgressView()
                    } else {
                        Text("Reveal recovery phrase")
                    }
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(viewModel.isRevealing)
            .accessibilityIdentifier("backup.reveal")
        }
        .accessibilityIdentifier("backup.gate")
    }

    // MARK: - No phrase (raw-key wallet)

    private var noPhraseStep: some View {
        VStack(spacing: 20) {
            Spacer(minLength: 0)

            Image(systemName: "key.fill")
                .font(.system(size: 40))
                .foregroundStyle(.secondary)

            Text("No recovery phrase")
                .font(.title2.weight(.semibold))

            Text("This wallet was imported from a private key and has no recovery phrase.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Spacer(minLength: 0)

            Button("Done", action: onFinished)
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("backup.noPhrase.done")
        }
        .accessibilityIdentifier("backup.noPhrase")
    }

    // MARK: - Display

    private var displayStep: some View {
        VStack(spacing: 16) {
            Label(
                "Write these \(viewModel.words.count) words down in order. Never share them with anyone.",
                systemImage: "exclamationmark.triangle.fill"
            )
            .font(.subheadline)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.red.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))

            ScrollView {
                wordGrid
            }

            Button("I have written it down") {
                viewModel.advanceToVerify()
            }
            .buttonStyle(.borderedProminent)
            .frame(maxWidth: .infinity)
            .accessibilityIdentifier("backup.wroteItDown")
        }
        .privacyShield()
        .accessibilityIdentifier("backup.display")
    }

    private var wordGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
            ForEach(Array(viewModel.words.enumerated()), id: \.offset) { index, word in
                HStack(spacing: 6) {
                    Text("\(index + 1).")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(width: 22, alignment: .trailing)
                    Text(word)
                        .font(.body.weight(.medium))
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 10)
                .background(Color.gray.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
                .accessibilityIdentifier("backup.word.\(index)")
            }
        }
    }

    // MARK: - Verify

    private var verifyStep: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Select the correct word for each position to verify your backup.")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    ForEach(viewModel.quiz, id: \.position) { prompt in
                        verifyPrompt(prompt)
                    }
                }
            }

            if let error = viewModel.errorMessage {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .accessibilityIdentifier("backup.verify.error")
            }

            Button("Verify") {
                viewModel.submitVerify()
            }
            .buttonStyle(.borderedProminent)
            .frame(maxWidth: .infinity)
            .disabled(!viewModel.canSubmitVerify)
            .accessibilityIdentifier("backup.verifyButton")
        }
        .privacyShield()
        .accessibilityIdentifier("backup.verify")
    }

    private func verifyPrompt(_ prompt: BackupQuiz.Prompt) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Word #\(prompt.position + 1)")
                .font(.headline)

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                ForEach(prompt.choices, id: \.self) { choice in
                    let isSelected = viewModel.selections[prompt.position] == choice
                    Button(choice) {
                        viewModel.select(position: prompt.position, word: choice)
                    }
                    .buttonStyle(.bordered)
                    .background(
                        isSelected ? Color.accentColor.opacity(0.18) : Color.clear,
                        in: RoundedRectangle(cornerRadius: 10)
                    )
                    .accessibilityIdentifier("backup.verify.\(prompt.position).\(choice)")
                }
            }
        }
    }

    // MARK: - Success

    private var successStep: some View {
        VStack(spacing: 20) {
            Spacer(minLength: 0)

            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 64))
                .foregroundStyle(.green)

            Text("Backup verified")
                .font(.title2.weight(.semibold))

            Text("Your recovery phrase is confirmed. Keep it somewhere safe and offline.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Spacer(minLength: 0)

            Button("Done", action: onFinished)
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("backup.done")
        }
        .accessibilityIdentifier("backup.success")
    }
}
