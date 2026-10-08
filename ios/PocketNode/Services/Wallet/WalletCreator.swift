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
    /// A restore was given a valid phrase or key, but it is not the one for
    /// the wallet being restored: it derives a different address, or it is a
    /// phrase for a raw-key wallet (or the reverse).
    case doesNotMatchWallet
    /// Rebuilding the metadata could not read the key material. Carries why,
    /// so the caller can tell keys that will never decrypt again
    /// (`.keyInvalidated`, `.corrupt`) from a prompt that was dismissed or
    /// failed and is worth another try.
    case keyReadFailed(WalletKeyStoreError)
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

    // MARK: - Restoring a wallet whose keys are missing

    /// Puts the keys back under a wallet whose metadata survived without them,
    /// which is what a backup restored onto a new device leaves: `wallet.json`
    /// comes back, the `ThisDeviceOnly` Keychain envelope does not.
    ///
    /// Only the phrase for that exact wallet is accepted: it must derive both
    /// of `record`'s addresses, so a restore can never swap in a different
    /// wallet under the old name. The record keeps its id, name, addresses
    /// and creation time; `mnemonicBackedUp` becomes true, since the user has
    /// just typed the phrase in.
    ///
    /// Also the way back for a wallet whose envelope is still here but can
    /// never be decrypted again (``KeyHealth/invalidated``: its Secure Enclave
    /// key is gone or is not the key it was made with).
    /// The same exact-address check applies, and the new key material then
    /// replaces the unusable envelope in place
    /// (``WalletKeyStore/replaceUnusableKeys(with:)``). Refused for keys that
    /// are usable or that the Keychain cannot read, so this can never
    /// overwrite keys that still work.
    @discardableResult
    func restoreMnemonic(words: [String], replacing record: WalletRecord) async throws -> CreatedWallet {
        let normalised = Self.normalise(words)
        guard Bip39.shared.validate(words: normalised) else {
            throw WalletCreationError.invalidMnemonic
        }
        guard record.type == Self.typeMnemonic else {
            throw WalletCreationError.doesNotMatchWallet
        }
        let health = try await keyHealthAllowingRestore()

        let derived = await Self.derive(words: normalised)
        return try await persistRestored(derived, replacing: record, backedUp: true, health: health)
    }

    /// ``restoreMnemonic(words:replacing:)`` for a raw-key wallet.
    @discardableResult
    func restorePrivateKey(hex: String, replacing record: WalletRecord) async throws -> CreatedWallet {
        guard var bytes = Self.decodePrivateKey(hex) else {
            throw WalletCreationError.invalidPrivateKey
        }
        let privateKey = KotlinByteArray.from(bytes)
        bytes.wipe()
        defer { privateKey.zeroOut() }

        guard record.type == Self.typeRawKey else {
            throw WalletCreationError.doesNotMatchWallet
        }
        let health = try await keyHealthAllowingRestore()

        let derived = Self.describe(privateKey: privateKey, mnemonic: nil)
        return try await persistRestored(
            derived,
            replacing: record,
            backedUp: record.mnemonicBackedUp,
            health: health
        )
    }

    /// The stored wallet, if its metadata is here and its key material is
    /// confirmed absent (the state a backup restored onto a new device
    /// leaves), unusable or suspended. A Keychain that cannot be read is
    /// none of those, so it answers nil.
    func walletNeedingRestore() async -> WalletRecord? {
        guard let record = walletStore.load() else { return nil }
        let health = await keyStore.keyHealth
        guard Self.allowsRestore(health) else { return nil }
        return record
    }

    /// The key health a restore for a known wallet may go ahead on: absent,
    /// invalidated, or suspended. Safe for suspended keys because the
    /// phrase must derive this wallet's own addresses.
    private func keyHealthAllowingRestore() async throws -> KeyHealth {
        let health = await keyStore.keyHealth
        guard Self.allowsRestore(health) else {
            throw WalletCreationError.walletAlreadyExists
        }
        return health
    }

    private static func allowsRestore(_ health: KeyHealth) -> Bool {
        health == .absent || health == .invalidated || health == .suspended
    }

    // MARK: - Trying the keys again

    /// Decrypts the keys once more, for a wallet whose envelope is
    /// ``KeyHealth/suspended`` (retired after a refusal that may not
    /// repeat). A decrypt that works binds the envelope back to its key
    /// (``WalletKeyStore/load(reason:)``).
    ///
    /// Success is reported only if the keys then read as
    /// ``KeyHealth/usable`` (the re-bind landed) and they derive `record`'s
    /// own addresses. Otherwise this throws and the restore screen stays:
    /// ``WalletCreationError/keyReadFailed(_:)`` for a decrypt that failed or
    /// keys that still do not read usable, ``WalletCreationError/doesNotMatchWallet``
    /// for keys that belong to another wallet.
    func retryUnlock(reason: String, matching record: WalletRecord) async throws {
        let bundle: WalletKeyBundle
        do {
            bundle = try await keyStore.load(reason: reason)
        } catch let error as WalletKeyStoreError {
            throw WalletCreationError.keyReadFailed(error)
        }
        let health = await keyStore.keyHealth
        guard health == .usable else {
            throw WalletCreationError.keyReadFailed(.keyRefused)
        }
        guard var bytes = Self.decodePrivateKey(bundle.privateKeyHex) else {
            throw WalletCreationError.keyReadFailed(.corrupt)
        }
        let privateKey = KotlinByteArray.from(bytes)
        bytes.wipe()
        defer { privateKey.zeroOut() }
        let derived = Self.describe(privateKey: privateKey, mnemonic: nil)
        guard derived.mainnetAddress == record.mainnetAddress,
              derived.testnetAddress == record.testnetAddress
        else {
            throw WalletCreationError.doesNotMatchWallet
        }
    }

    // MARK: - Unusable keys with no metadata

    /// Imports any phrase over key material that can never be decrypted
    /// again, on a device whose metadata is missing or undecodable. With no
    /// record there is no address to check a phrase against, so whatever the
    /// user enters becomes the wallet; the old envelope is replaced in place
    /// (``WalletKeyStore/replaceUnusableKeys(with:)``) and the PIN is not
    /// touched. Refused unless the keys are confirmed
    /// ``KeyHealth/invalidated`` and no readable record exists, so it can
    /// never swap a working wallet or a wallet with a record for another one.
    @discardableResult
    func replaceUnusableKeys(words: [String], name: String) async throws -> CreatedWallet {
        let normalised = Self.normalise(words)
        guard Bip39.shared.validate(words: normalised) else {
            throw WalletCreationError.invalidMnemonic
        }
        try await refuseUnlessUnusableWithoutMetadata()

        let derived = await Self.derive(words: normalised)
        let record = try await persistReplacing(derived, name: name, type: Self.typeMnemonic, backedUp: true)
        return CreatedWallet(record: record, mnemonic: [])
    }

    /// ``replaceUnusableKeys(words:name:)`` for a raw private key.
    @discardableResult
    func replaceUnusableKeys(privateKeyHex hex: String, name: String) async throws -> CreatedWallet {
        guard var bytes = Self.decodePrivateKey(hex) else {
            throw WalletCreationError.invalidPrivateKey
        }
        let privateKey = KotlinByteArray.from(bytes)
        bytes.wipe()
        defer { privateKey.zeroOut() }

        try await refuseUnlessUnusableWithoutMetadata()

        let derived = Self.describe(privateKey: privateKey, mnemonic: nil)
        let record = try await persistReplacing(derived, name: name, type: Self.typeRawKey, backedUp: false)
        return CreatedWallet(record: record, mnemonic: [])
    }

    private func refuseUnlessUnusableWithoutMetadata() async throws {
        let health = await keyStore.keyHealth
        guard health == .invalidated, metadataIsMissingOrUndecodable else {
            throw WalletCreationError.walletAlreadyExists
        }
    }

    /// No `wallet.json`, or one that reads but does not decode. A file the
    /// data protection class keeps unreadable is neither: nothing is known
    /// about it.
    private var metadataIsMissingOrUndecodable: Bool {
        !walletStore.hasWallet || walletStore.hasUndecodableRecord
    }

    /// Keys first (replacing the unusable envelope), then the record.
    ///
    /// An undecodable `wallet.json` that cannot be set aside is never written
    /// over: that is ``WalletCreationError/metadataStorageFailed``, with the
    /// new keys in place (the launch gate then rebuilds the record from them
    /// once the file can be moved). A record that cannot be saved does not
    /// roll the keys back either: the old
    /// ones could decrypt nothing, and deleting the new ones would only put
    /// the device back where it was. The launch gate then finds usable keys
    /// with no metadata and rebuilds the record from them
    /// (``rebuildMetadata(reason:)``), so the import still counts.
    private func persistReplacing(
        _ derived: Derived,
        name: String,
        type: String,
        backedUp: Bool
    ) async throws -> WalletRecord {
        let bundle = WalletKeyBundle(privateKeyHex: derived.privateKeyHex, mnemonic: derived.mnemonic)
        do {
            try await keyStore.replaceUnusableKeys(with: bundle)
        } catch let error as WalletKeyStoreError {
            throw WalletCreationError.keyStorageFailed(error)
        }

        let record = makeRecord(derived, name: name, type: type, backedUp: backedUp)
        try setAsideUndecodableRecordIfAny()
        try? walletStore.save(record)
        return record
    }

    // MARK: - Usable keys with no metadata

    /// Writes `wallet.json` again from the key material, for usable keys
    /// whose metadata is missing or does not decode. Nothing is asked of the
    /// user beyond the system prompt `reason` labels (decrypting the bundle
    /// needs Face ID, Touch ID or the passcode on a device), which is why the
    /// launch gate runs this only once a PIN in front of the wallet has been
    /// answered.
    ///
    /// The record gets a new id, ``defaultName``, the type the bundle implies
    /// (a phrase means `mnemonic`, none means `raw_key`), the addresses
    /// derived from the private key, and `mnemonicBackedUp = false`, so the
    /// backup reminder shows again for a phrase. Addresses for both networks
    /// are always recorded, so the app's current network needs no input.
    /// An undecodable `wallet.json` is set aside first, as the launch gate
    /// does for one with no keys; if it cannot be, nothing is written over it.
    /// The metadata is checked again after the prompt, so a record that
    /// appeared meanwhile is never overwritten.
    ///
    /// Refused unless the keys are ``KeyHealth/usable`` or
    /// ``KeyHealth/suspended`` (the rebuild is then the retry: a decrypt
    /// that works binds the envelope back) and there is no readable record.
    /// A failure to read the keys is ``WalletCreationError/keyReadFailed(_:)``.
    @discardableResult
    func rebuildMetadata(reason: String) async throws -> WalletRecord {
        let health = await keyStore.keyHealth
        guard health == .usable || health == .suspended, metadataIsMissingOrUndecodable else {
            throw WalletCreationError.walletAlreadyExists
        }

        let bundle: WalletKeyBundle
        let opened: Data
        do {
            (bundle, opened) = try await keyStore.loadWithEnvelope(reason: reason)
        } catch let error as WalletKeyStoreError {
            throw WalletCreationError.keyReadFailed(error)
        }
        guard var bytes = Self.decodePrivateKey(bundle.privateKeyHex) else {
            // The bundle decrypted but holds no usable key: as unusable as a
            // ciphertext that does not authenticate. Only the envelope that
            // was opened is retired, if it is still the one stored.
            await keyStore.retireUnusableBundle(ifStill: opened)
            throw WalletCreationError.keyReadFailed(.corrupt)
        }
        let privateKey = KotlinByteArray.from(bytes)
        bytes.wipe()
        defer { privateKey.zeroOut() }

        let words = bundle.mnemonic.map { $0.split(separator: " ").map(String.init) }
        let derived = Self.describe(privateKey: privateKey, mnemonic: words)
        let record = makeRecord(
            derived,
            name: Self.defaultName,
            type: words == nil ? Self.typeRawKey : Self.typeMnemonic,
            backedUp: false
        )
        // The prompt can stay up for a while: a record written meanwhile
        // (another path, or a restore) is never overwritten.
        guard metadataIsMissingOrUndecodable else {
            throw WalletCreationError.walletAlreadyExists
        }
        try setAsideUndecodableRecordIfAny()
        do {
            try walletStore.save(record)
        } catch {
            throw WalletCreationError.metadataStorageFailed
        }
        return record
    }

    /// The restore counterpart of ``persist(_:name:type:backedUp:)``: the
    /// addresses are checked against the record before anything is written,
    /// then the keys, then the record.
    ///
    /// Unlike a fresh create, a record that cannot be saved does not roll the
    /// keys back. The `wallet.json` already on disk describes exactly these
    /// keys (the addresses were just checked against it), so the wallet is
    /// whole without the save, which only carries `mnemonicBackedUp`. Deleting
    /// the keys again would risk the opposite failure, an envelope left
    /// behind without its wrapping key, which strands the wallet for good. So
    /// the restore succeeds with the record as it was; at worst the backup
    /// reminder shows for a phrase the user has just typed in.
    private func persistRestored(
        _ derived: Derived,
        replacing record: WalletRecord,
        backedUp: Bool,
        health: KeyHealth
    ) async throws -> CreatedWallet {
        guard derived.mainnetAddress == record.mainnetAddress,
              derived.testnetAddress == record.testnetAddress
        else {
            throw WalletCreationError.doesNotMatchWallet
        }

        if health == .invalidated || health == .suspended {
            let bundle = WalletKeyBundle(privateKeyHex: derived.privateKeyHex, mnemonic: derived.mnemonic)
            do {
                // The phrase has derived this wallet's addresses, so a
                // suspended envelope may be replaced here (never in the
                // import with no record).
                try await keyStore.replaceUnusableKeys(with: bundle, allowingSuspended: true)
            } catch let error as WalletKeyStoreError {
                throw WalletCreationError.keyStorageFailed(error)
            }
        } else {
            try await storeKeys(derived)
        }

        let restored = WalletRecord(
            id: record.id,
            name: record.name,
            type: record.type,
            derivationPath: record.derivationPath,
            mainnetAddress: record.mainnetAddress,
            testnetAddress: record.testnetAddress,
            mnemonicBackedUp: backedUp,
            createdAt: record.createdAt
        )
        do {
            try walletStore.save(restored)
        } catch {
            return CreatedWallet(record: record, mnemonic: [])
        }
        return CreatedWallet(record: restored, mnemonic: [])
    }

    // MARK: - Storage

    private func refuseIfWalletExists() async throws {
        let keysPresent = await keyStore.hasWallet
        if keysPresent || walletStore.hasWallet {
            throw WalletCreationError.walletAlreadyExists
        }
    }

    /// Key material first, then metadata, and all or nothing.
    ///
    /// Key material goes first because metadata with no envelope would be a
    /// wallet the app believes in and can never spend from. But a half-written
    /// wallet is not something to leave behind either: an orphan envelope makes
    /// ``refuseIfWalletExists`` refuse every retry, and `AppContainer.hasWallet`
    /// sends the next launch to the wallet shell, so the user is stuck outside
    /// onboarding with no wallet and no way back in. So a failed metadata write
    /// deletes the envelope it just wrote and reports the failure, leaving the
    /// device exactly as it was before the attempt.
    private func persist(
        _ derived: Derived,
        name: String,
        type: String,
        backedUp: Bool
    ) async throws -> WalletRecord {
        try await storeKeys(derived)

        let record = makeRecord(derived, name: name, type: type, backedUp: backedUp)
        do {
            try walletStore.save(record)
        } catch {
            // Best effort: if the rollback itself fails there is nothing more
            // this can do, and reporting the original failure is still right.
            // The key material is a phrase the user has not seen yet (create)
            // or one they already hold (import), so dropping it loses nothing.
            try? await keyStore.delete()
            throw WalletCreationError.metadataStorageFailed
        }
        return record
    }

    private func makeRecord(_ derived: Derived, name: String, type: String, backedUp: Bool) -> WalletRecord {
        WalletRecord(
            id: UUID().uuidString,
            name: Self.sanitise(name),
            type: type,
            derivationPath: type == Self.typeRawKey ? nil : Self.derivationPath,
            mainnetAddress: derived.mainnetAddress,
            testnetAddress: derived.testnetAddress,
            mnemonicBackedUp: backedUp,
            createdAt: now()
        )
    }

    /// Moves an undecodable `wallet.json` aside before a new record is
    /// written. If it cannot be moved, nothing may be written over it, so
    /// that is ``WalletCreationError/metadataStorageFailed``.
    private func setAsideUndecodableRecordIfAny() throws {
        guard walletStore.hasUndecodableRecord else { return }
        do {
            try walletStore.setAsideUndecodableRecord()
        } catch {
            throw WalletCreationError.metadataStorageFailed
        }
    }

    private func storeKeys(_ derived: Derived) async throws {
        let bundle = WalletKeyBundle(privateKeyHex: derived.privateKeyHex, mnemonic: derived.mnemonic)
        do {
            try await keyStore.store(bundle)
        } catch let error as WalletKeyStoreError {
            throw WalletCreationError.keyStorageFailed(error)
        }
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
    /// The seed and the private-key buffers are zeroed before this returns.
    /// What survives is the hex string and the phrase, and those are Swift
    /// `String`s: their storage is owned by the standard library, copy-on-write,
    /// and has no supported way to be wiped, so they live until they are
    /// released and the memory is reused. The same limitation `WalletKeyStore`
    /// documents. They are handed straight to the store, which encrypts them,
    /// and are never cached.
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

    /// The first entry in `words` that is filled in but not a BIP-39 word.
    ///
    /// The import screen points at it and refuses to submit while it is there.
    /// A word the list does not contain and a phrase whose checksum does not
    /// add up are different mistakes with different fixes, and only the first
    /// can be attributed to a particular word, so it is worth saying which.
    /// Empty entries are not reported: the user has simply not finished.
    @MainActor
    static func offListWordIndex(in words: [String]) -> Int? {
        normalise(words).firstIndex { !$0.isEmpty && !isWord($0) }
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

        // Every character is checked before any of it is decoded. Swift's
        // `UInt8(_:radix:)` is an integer parser, not a hex-pair reader: it
        // accepts a leading sign, so a pasted "+f" repeated 32 times would
        // otherwise pass the length check and silently import as 0x0f0f…,
        // which is a different wallet from the one the user meant.
        var bytes = [UInt8]()
        bytes.reserveCapacity(32)
        var high: UInt8?
        for character in digits {
            // ASCII as well as hex: `hexDigitValue` also answers for fullwidth
            // and other Unicode digit forms, and a private key is written in
            // 0-9 a-f A-F or it is not a private key.
            guard character.isASCII, let nibble = character.hexDigitValue else { return nil }
            if let first = high {
                bytes.append(first << 4 | UInt8(nibble))
                high = nil
            } else {
                high = UInt8(nibble)
            }
        }
        guard high == nil, bytes.count == 32 else { return nil }

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
