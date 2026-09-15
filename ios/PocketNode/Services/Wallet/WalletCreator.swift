import Foundation
import PocketNodeCore

/// Why a wallet could not be created or imported.
///
/// Every case is terminal for the attempt and carries no secret material: the
/// bad word, the bad key and the phrase itself never reach an error value, so
/// nothing sensitive can escape through a message, a log or a crash report.
enum WalletCreationError: Error, Equatable {
    /// A wallet is already on this device. iOS is single-wallet until M3, and
    /// creating a second one would strand the first (one Keychain envelope,
    /// one `wallet.json`).
    case walletAlreadyExists
    /// Not 12 or 24 words. Only those two lengths can be generated.
    case invalidWordCount
    /// Not a BIP-39 phrase: wrong length, an off-list word, or a checksum
    /// that does not match.
    case invalidMnemonic
    /// Not a usable secp256k1 private key: not 32 bytes of hex, zero, or at or
    /// above the curve order.
    case invalidPrivateKey
    /// The Keychain or the Secure Enclave refused to store the key material.
    case keyStorageFailed(WalletKeyStoreError)
    /// The key material was stored but the metadata file was not.
    case metadataStorageFailed
}

/// A wallet that has just been created or imported.
///
/// ``mnemonic`` is non-empty only for a freshly generated wallet, and only so
/// the backup step can show it once. Nothing else may retain it.
struct CreatedWallet: Equatable, Sendable {
    let record: WalletRecord
    let mnemonic: [String]
}

/// Creates and imports the single wallet iOS supports before M3.
///
/// The three entry points mirror Android's `WalletRepository.createWallet`,
/// `importFromMnemonic` and `importRawKey`, down to the `type`,
/// `derivationPath` and `mnemonicBackedUp` each one records:
///
/// | path             | type       | derivationPath      | mnemonicBackedUp |
/// |------------------|------------|---------------------|------------------|
/// | create           | `mnemonic` | `m/44'/309'/0'/0/0` | `false`          |
/// | import phrase    | `mnemonic` | `m/44'/309'/0'/0/0` | `true`           |
/// | import raw key   | `raw_key`  | `nil`               | `false`          |
///
/// A phrase the user has just typed in is by definition one they hold, so the
/// import path skips the backup step; a generated one has not been written
/// down yet, which is what `false` there means.
///
/// `@MainActor` so it can hold the main-actor ``WalletStore``, but every byte
/// of derivation happens in ``derive(words:)`` / ``derive(privateKey:)``, which
/// are `nonisolated async` and so run off the main actor. Only `Sendable`
/// values cross back: strings and addresses, never a `KotlinByteArray`.
@MainActor
final class WalletCreator {
    /// Android's `KeyManager.WALLET_TYPE_MNEMONIC`.
    static let typeMnemonic = "mnemonic"
    /// Android's `KeyManager.WALLET_TYPE_RAW_KEY`.
    static let typeRawKey = "raw_key"
    /// The BIP-44 path `Bip32.deriveCkbPrivateKey` walks with its defaults.
    static let derivationPath = "m/44'/309'/0'/0/0"
    /// Used when the user leaves the name field empty, matching Android's
    /// `OnboardingViewModel.createNewWallet`.
    static let defaultName = "My Wallet"

    private let keyStore: WalletKeyStore
    private let walletStore: WalletStore
    private let now: @Sendable () -> Int64

    init(
        keyStore: WalletKeyStore,
        walletStore: WalletStore,
        now: @escaping @Sendable () -> Int64 = { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }
    ) {
        self.keyStore = keyStore
        self.walletStore = walletStore
        self.now = now
    }

    /// Generates a wallet and stores it.
    ///
    /// The returned words are the only copy outside the Keychain. They exist
    /// for the backup screen to show once and must not be retained past it.
    func createWallet(wordCount: Int, name: String) async throws -> CreatedWallet {
        guard wordCount == 12 || wordCount == 24 else {
            throw WalletCreationError.invalidWordCount
        }
        try await refuseIfWalletExists()

        let words = Bip39.shared.generate(wordCount: Int32(wordCount), entropy: SecureRandomEntropySource())
        let derived = await Self.derive(words: words)
        let record = try await persist(derived, name: name, type: Self.typeMnemonic, backedUp: false)
        return CreatedWallet(record: record, mnemonic: words)
    }

    /// Imports a BIP-39 phrase.
    ///
    /// `words` is normalised the way Android's `AddWalletViewModel` does it,
    /// trim then lowercase, before validation: a phrase pasted out of a notes
    /// app routinely arrives capitalised or padded.
    @discardableResult
    func importMnemonic(words: [String], name: String) async throws -> CreatedWallet {
        let normalised = Self.normalise(words)
        guard Bip39.shared.validate(words: normalised) else {
            throw WalletCreationError.invalidMnemonic
        }
        try await refuseIfWalletExists()

        let derived = await Self.derive(words: normalised)
        // The user just typed the phrase in, so they hold it. Same reasoning as
        // Android's `MnemonicImportScreen`, which passes mnemonicBackedUp: true.
        let record = try await persist(derived, name: name, type: Self.typeMnemonic, backedUp: true)
        return CreatedWallet(record: record, mnemonic: [])
    }

    /// Imports a raw 32-byte private key, with or without a `0x` prefix.
    ///
    /// The wallet has no phrase, so there is nothing to back up and no
    /// derivation path to record.
    @discardableResult
    func importPrivateKey(hex: String, name: String) async throws -> CreatedWallet {
        guard var bytes = Self.decodePrivateKey(hex) else {
            throw WalletCreationError.invalidPrivateKey
        }
        // `from` copies the values out, and `bytes` is uniquely referenced by
        // the time it returns, so this wipe reaches the real buffer rather than
        // a copy-on-write duplicate of it.
        let privateKey = KotlinByteArray.from(bytes)
        bytes.wipe()
        defer { privateKey.zeroOut() }

        try await refuseIfWalletExists()

        // Not moved off the main actor, unlike the phrase path: this is one
        // scalar multiply and one blake2b, with no PBKDF2 in front of it.
        let derived = Self.describe(privateKey: privateKey, mnemonic: nil)
        let record = try await persist(derived, name: name, type: Self.typeRawKey, backedUp: false)
        return CreatedWallet(record: record, mnemonic: [])
    }

    // MARK: - Storage

    private func refuseIfWalletExists() async throws {
        if await keyStore.hasWallet || walletStore.hasWallet {
            throw WalletCreationError.walletAlreadyExists
        }
    }

    /// Key material first, then metadata.
    ///
    /// That order matters on failure: an envelope with no `wallet.json` is a
    /// wallet the next launch re-offers onboarding for while the keys are still
    /// recoverable, whereas metadata with no envelope would be a wallet the app
    /// believes in and can never spend from.
    private func persist(
        _ derived: Derived,
        name: String,
        type: String,
        backedUp: Bool
    ) async throws -> WalletRecord {
        let bundle = WalletKeyBundle(privateKeyHex: derived.privateKeyHex, mnemonic: derived.mnemonic)
        do {
            try await keyStore.store(bundle)
        } catch let error as WalletKeyStoreError {
            throw WalletCreationError.keyStorageFailed(error)
        }

        let record = WalletRecord(
            id: UUID().uuidString,
            name: Self.sanitise(name),
            type: type,
            derivationPath: type == Self.typeRawKey ? nil : Self.derivationPath,
            mainnetAddress: derived.mainnetAddress,
            testnetAddress: derived.testnetAddress,
            mnemonicBackedUp: backedUp,
            createdAt: now()
        )
        do {
            try walletStore.save(record)
        } catch {
            throw WalletCreationError.metadataStorageFailed
        }
        return record
    }

    // MARK: - Derivation

    /// Everything derivation produces that is safe to hand back to the main
    /// actor: `Sendable` values only, with the key bytes already gone.
    private struct Derived: Sendable {
        let privateKeyHex: String
        let mnemonic: String?
        let mainnetAddress: String
        let testnetAddress: String
    }

    /// Phrase to seed to key to addresses, off the main actor.
    ///
    /// The seed and the private key are zeroed before this returns; the hex
    /// string that survives is what ``WalletKeyStore`` immediately encrypts.
    private nonisolated static func derive(words: [String]) async -> Derived {
        let seed = Bip39.shared.toSeed(words: words, passphrase: "")
        defer { seed.zeroOut() }
        let privateKey = Bip32.shared.deriveCkbPrivateKey(
            seed: seed,
            accountIndex: 0,
            chainIndex: 0,
            addressIndex: 0
        )
        defer { privateKey.zeroOut() }
        return describe(privateKey: privateKey, mnemonic: words)
    }

    /// Shared tail of both derivations.
    ///
    /// The hex encoding comes from the shared `WalletDerivation` rather than
    /// from a Swift formatter, so the stored bundle is byte-identical to the
    /// one Android writes for the same key.
    private nonisolated static func describe(privateKey: KotlinByteArray, mnemonic: [String]?) -> Derived {
        let info = WalletDerivation.shared.walletInfo(privateKey: privateKey)
        let bundle = WalletDerivation.shared.encodePlaintextBundle(privateKey: privateKey, mnemonic: mnemonic)
        return Derived(
            privateKeyHex: bundle.privateKeyHex,
            mnemonic: bundle.mnemonic,
            mainnetAddress: info.mainnetAddress,
            testnetAddress: info.testnetAddress
        )
    }

    // MARK: - Input handling

    /// The BIP-39 English wordlist as a set, for the import screen's per-word
    /// check. Built once from the shared core rather than shipped again in
    /// Swift, so the two platforms cannot disagree about what a word is.
    ///
    /// Main-actor isolated because it is only ever read while laying out the
    /// import screen, and that keeps the one-time build off any other thread.
    @MainActor
    static let wordlist: Set<String> = Set(Bip39.shared.WORDLIST)

    /// Whether `word`, already normalised, is on the BIP-39 English list.
    @MainActor
    static func isWord(_ word: String) -> Bool {
        wordlist.contains(word)
    }

    /// Trim and lowercase every word, matching Android's `AddWalletViewModel`.
    static func normalise(_ words: [String]) -> [String] {
        words.map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
    }

    /// Splits pasted text on any whitespace, the way Android's `pasteMnemonic`
    /// does, so a phrase copied with newlines or double spaces still fills in.
    static func splitPhrase(_ text: String) -> [String] {
        normalise(text.split(whereSeparator: { $0.isWhitespace }).map(String.init))
            .filter { !$0.isEmpty }
    }

    private static func sanitise(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? defaultName : trimmed
    }

    /// Order of the secp256k1 group, big-endian. A private key must be below it
    /// and non-zero.
    private static let curveOrder: [UInt8] = [
        0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
        0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFE,
        0xBA, 0xAE, 0xDC, 0xE6, 0xAF, 0x48, 0xA0, 0x3B,
        0xBF, 0xD2, 0x5E, 0x8C, 0xD0, 0x36, 0x41, 0x41,
    ]

    /// Decodes `hex` into a private key, or `nil` if it is not one.
    ///
    /// The range check is done here rather than by catching a failure out of
    /// the shared core: `WalletDerivation.publicKey` is not exported with an
    /// `NSError` out-parameter, so the Kotlin `IllegalArgumentException` an
    /// invalid scalar raises would terminate the process instead of reaching a
    /// Swift `catch`. Screening the two values libsecp256k1 rejects (zero, and
    /// at or above the group order) keeps every reachable input valid by the
    /// time it crosses the bridge. It is a plain unsigned byte compare, the
    /// same one `Bip32.isValidPrivateScalar` makes on the Kotlin side.
    static func decodePrivateKey(_ hex: String) -> [UInt8]? {
        var digits = Substring(hex.trimmingCharacters(in: .whitespacesAndNewlines))
        if digits.hasPrefix("0x") || digits.hasPrefix("0X") {
            digits = digits.dropFirst(2)
        }
        guard digits.count == 64 else { return nil }

        var bytes = [UInt8]()
        bytes.reserveCapacity(32)
        var index = digits.startIndex
        while index < digits.endIndex {
            let next = digits.index(index, offsetBy: 2)
            guard let byte = UInt8(digits[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }

        guard bytes.contains(where: { $0 != 0 }) else { return nil }
        for (value, limit) in zip(bytes, curveOrder) where value != limit {
            return value < limit ? bytes : nil
        }
        return nil // exactly the group order, which is not a valid scalar either
    }
}

extension Array where Element == UInt8 {
    /// Overwrites this buffer in place. Named apart from the `Data` and
    /// `KotlinByteArray` helpers so the call site says which buffer it means.
    mutating func wipe() {
        withUnsafeMutableBytes { raw in
            guard let base = raw.baseAddress, raw.count > 0 else { return }
            memset_s(base, raw.count, 0, raw.count)
        }
    }
}
