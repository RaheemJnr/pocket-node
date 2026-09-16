import SwiftUI

/// Restoring a wallet from a recovery phrase or from a raw private key.
///
/// Both entry modes are secret material, so autocorrect, autocapitalisation
/// and the predictive bar are off on every field. That is not cosmetic: the
/// keyboard's suggestion strip is what puts a seed word in front of whatever
/// the user types in next, and the ASCII keyboard keeps a phrase out of the
/// emoji and dictation paths. The private key goes in a `SecureField`, which
/// iOS excludes from the keyboard's learning and from screenshots of the
/// field's own text.
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

            // A `PasteButton` hands over the clipboard contents on the
            // user's own tap, with no system "pasted from" banner and without
            // this view ever reading `UIPasteboard` itself.
            PasteButton(payloadType: String.self) { strings in
                fill(from: strings.first ?? "")
            }
            .labelStyle(.titleAndIcon)
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
        // Bounds-checked rather than subscripted directly. `enteredWords`
        // tracks the word-count picker, so switching 24 to 12 shortens it
        // while SwiftUI can still evaluate the bodies of rows 12 to 23 with
        // the indices they were built with. Indexing there traps
        // (`Array._checkSubscript`), which is exactly how the activity list
        // crashed. An index that is gone is not a bad word, it is no word.
        guard enteredWords.indices.contains(index) else { return false }
        let word = enteredWords[index]
        return !word.isEmpty && !WalletCreator.isWord(word)
    }

    private var firstBadWord: Int? {
        WalletCreator.offListWordIndex(in: enteredWords)
    }

    /// Fills the grid from pasted text, splitting on any whitespace the way
    /// Android's `pasteMnemonic` does, and switching the length picker to match
    /// a 24 word phrase so the extra words are not silently dropped.
    private func fill(from text: String) {
        let parts = WalletCreator.splitPhrase(text)
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
            // An off-list word is reported at the word that is wrong, so the
            // button stays disabled rather than letting the attempt come back
            // as the vaguer "invalid recovery phrase" a bad checksum earns.
            return firstBadWord == nil && enteredWords.allSatisfy { !$0.isEmpty }
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
