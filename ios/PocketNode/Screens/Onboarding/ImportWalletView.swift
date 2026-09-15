import SwiftUI
import UIKit

/// Restoring a wallet from a recovery phrase or from a raw private key.
///
/// Both entry modes are secret material, so every field here has autocorrect,
/// autocapitalisation and the content-type hints turned off. Those are not
/// cosmetic: a `textContentType` is what invites iOS to offer AutoFill and to
/// learn the text, and the keyboard's predictive bar is what puts a seed word
/// in front of the next app the user types in.
struct ImportWalletView: View {
    /// Which half of the screen is showing. Not private so a screenshot test
    /// can render the private-key half without driving the segmented control.
    enum Mode: Hashable {
        case phrase
        case privateKey
    }

    let model: OnboardingViewModel

    @State private var mode: Mode
    @State private var wordCount = 12
    @State private var words = [String](repeating: "", count: 24)
    @State private var privateKey = ""
    @State private var name = "Imported Wallet"
    @State private var pasteFailed = false
    @FocusState private var focusedWord: Int?

    init(model: OnboardingViewModel, initialMode: Mode = .phrase) {
        self.model = model
        self._mode = State(initialValue: initialMode)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Picker("What are you importing", selection: $mode) {
                    Text("Recovery phrase").tag(Mode.phrase)
                    Text("Private key").tag(Mode.privateKey)
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("import.mode")

                switch mode {
                case .phrase: phraseSection
                case .privateKey: privateKeySection
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("Wallet name")
                        .font(.headline)
                    TextField("Imported Wallet", text: $name)
                        .textFieldStyle(.roundedBorder)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.words)
                        .accessibilityIdentifier("import.name")
                }

                if let message = model.errorMessage {
                    OnboardingErrorBanner(message: message)
                }

                Button(action: submit) {
                    if model.isBusy {
                        ProgressView().frame(maxWidth: .infinity)
                    } else {
                        Text("Import wallet").frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(model.isBusy || !canSubmit)
                .accessibilityIdentifier("import.submit")
            }
            .padding(24)
        }
        .scrollDismissesKeyboard(.interactively)
        .privacySensitive()
        .navigationTitle("Import wallet")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Recovery phrase

    private var phraseSection: some View {
        VStack(alignment: .leading, spacing: 16) {
            Picker("Recovery phrase length", selection: $wordCount) {
                Text("12 words").tag(12)
                Text("24 words").tag(24)
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("import.wordCount")

            Button {
                paste()
            } label: {
                Label("Paste from clipboard", systemImage: "doc.on.clipboard")
            }
            .accessibilityIdentifier("import.paste")

            if pasteFailed {
                Text("The clipboard has no recovery phrase in it.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                ForEach(0..<wordCount, id: \.self) { index in
                    wordField(index)
                }
            }

            if let firstBadWord {
                Text("Word \(firstBadWord + 1) is not on the recovery phrase word list.")
                    .font(.caption)
                    .foregroundStyle(Color.red)
                    .accessibilityIdentifier("import.wordError")
            }
        }
    }

    private func wordField(_ index: Int) -> some View {
        HStack(spacing: 6) {
            Text("\(index + 1)")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.tertiary)
                .frame(width: 20, alignment: .trailing)
            TextField("", text: binding(for: index))
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .textContentType(nil)
                .keyboardType(.asciiCapable)
                .submitLabel(index == wordCount - 1 ? .done : .next)
                .focused($focusedWord, equals: index)
                .onSubmit { focusedWord = index == wordCount - 1 ? nil : index + 1 }
                .overlay {
                    RoundedRectangle(cornerRadius: 5)
                        .strokeBorder(isBad(index) ? Color.red : Color.clear)
                }
                .accessibilityIdentifier("import.word.\(index)")
        }
    }

    private func binding(for index: Int) -> Binding<String> {
        Binding(
            get: { words[index] },
            set: { words[index] = $0 }
        )
    }

    /// Normalised entries, trimmed to the chosen length.
    private var enteredWords: [String] {
        WalletCreator.normalise(Array(words.prefix(wordCount)))
    }

    private func isBad(_ index: Int) -> Bool {
        let word = enteredWords[index]
        return !word.isEmpty && !WalletCreator.isWord(word)
    }

    private var firstBadWord: Int? {
        (0..<wordCount).first(where: isBad)
    }

    /// Fills the grid from the clipboard, splitting on any whitespace the way
    /// Android's `pasteMnemonic` does, and switches the length picker to match
    /// a 24 word phrase so the extra words are not silently dropped.
    private func paste() {
        let parts = WalletCreator.splitPhrase(UIPasteboard.general.string ?? "")
        guard !parts.isEmpty else {
            pasteFailed = true
            return
        }
        pasteFailed = false
        if parts.count > 12 { wordCount = 24 }
        words = (0..<24).map { parts.indices.contains($0) ? parts[$0] : "" }
        focusedWord = nil
    }

    // MARK: - Private key

    private var privateKeySection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Private key")
                .font(.headline)
            SecureField("0x…", text: $privateKey)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .textContentType(nil)
                .keyboardType(.asciiCapable)
                .accessibilityIdentifier("import.privateKey")
            Text("64 hexadecimal characters, with or without the 0x prefix. A wallet imported this way has no recovery phrase.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Submitting

    private var canSubmit: Bool {
        switch mode {
        case .phrase:
            return enteredWords.allSatisfy { !$0.isEmpty }
        case .privateKey:
            return !privateKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    private func submit() {
        focusedWord = nil
        switch mode {
        case .phrase:
            let entered = enteredWords
            Task { await model.importMnemonic(words: entered, name: name) }
        case .privateKey:
            let key = privateKey
            Task { await model.importPrivateKey(hex: key, name: name) }
        }
    }
}
