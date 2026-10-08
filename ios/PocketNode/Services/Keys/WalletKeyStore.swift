import CryptoKit
import Foundation
import Security

/// Why reading or writing the wallet failed. Every case is terminal for the
/// operation: the store never falls back to a weaker path.
enum WalletKeyStoreError: Error, Equatable {
    /// No wallet is stored.
    case notFound
    /// The user failed to authenticate, or biometry is locked out. The wallet is
    /// intact; another attempt can succeed.
    case authenticationFailed
    /// The user dismissed the prompt. Distinct from `.authenticationFailed` so
    /// callers can stay silent instead of showing an error.
    case authenticationCancelled
    /// A wallet is stored but the wrapping key that protects it is absent, so it
    /// can never be decrypted again. Seen if the key is ever lost while the
    /// envelope survives, for example a Keychain restore that dropped the Secure
    /// Enclave key. Recovery means restoring from the mnemonic, so this must
    /// never be confused with `.notFound` (nothing to restore) or
    /// `.authenticationFailed` (worth another attempt).
    case keyInvalidated
    /// The envelope is malformed, the ciphertext did not authenticate, or the
    /// decrypted bytes are not a bundle. Tampering and truncation both land here.
    case corrupt
    /// The wrapping key is there and the ECIES decrypt refused the wrapped
    /// data key twice in a row (``KeyWrapperError/decryptionFailed(_:)``):
    /// most likely not the key that wrapped it, but a refusal that may not
    /// repeat, so it never counts as structural.
    case keyRefused
    /// ``WalletKeyStore/replaceUnusableKeys(with:)`` was asked to replace key
    /// material that is not confirmed unusable: absent, still usable, or a
    /// Keychain that could not be read. Nothing was written.
    case notReplaceable
    /// The Keychain refused an operation.
    case keychain(OSStatus)
    /// The Secure Enclave refused an operation for a reason that is neither
    /// authentication nor corruption.
    case wrapping(String)
}

extension WalletKeyStoreError {
    /// Whether this failure, from decrypting the envelope, proves the keys
    /// will never decrypt on this device. An allowlist:
    /// - ``keyInvalidated``: the wrapping key is confirmed absent
    ///   (`errSecItemNotFound` while the envelope exists).
    /// - ``keyRefused``: the ECIES decrypt refused the wrapped data key twice
    ///   (``KeyWrapperError/decryptionFailed(_:)``). Proof enough to retire
    ///   the envelope as ``WalletKeyEnvelope/RetireReason/keyRefused``, which
    ///   only ever offers a retry or the wallet's own phrase.
    /// - ``corrupt``: the envelope does not parse, or the data key came out
    ///   but the AES-GCM box did not authenticate or the bundle did not
    ///   decode.
    ///
    /// Nothing else is proof: no other Keychain status, no error from
    /// another domain (CryptoTokenKit, say), no `LAError`, no cancellation,
    /// no refused interaction. Those are worth another try.
    var provesKeysUnusable: Bool {
        switch self {
        case .keyInvalidated, .corrupt, .keyRefused:
            return true
        case .notFound, .authenticationFailed, .authenticationCancelled, .notReplaceable, .keychain, .wrapping:
            return false
        }
    }
}

/// Whether the wallet's key envelope is in the Keychain.
enum KeyMaterialPresence: Equatable, Sendable {
    case present
    /// The Keychain was readable and holds no envelope.
    case absent
    /// The Keychain could not be read, so nothing is known either way.
    case unknown
}

/// What a prompt-free look at the stored key material says about it.
///
/// Read by ``WalletKeyStore/keyHealth`` without decrypting or signing
/// anything, so it never asks for Face ID, Touch ID or the passcode, and
/// only from what is stored: the envelope records its wrapping key's label,
/// so a different key under the same tag reads as ``invalidated`` at once;
/// and a decrypt that proves the keys unusable
/// (``WalletKeyStoreError/provesKeysUnusable``) retires the envelope in
/// place with its reason, so that proof reads from storage, across
/// launches, with the original data kept: ``invalidated`` for a structural
/// proof, ``suspended`` for a decrypt refusal that may not repeat.
enum KeyHealth: Equatable, Sendable {
    /// The envelope is there and parses, and its Secure Enclave wrapping key
    /// is there too.
    case usable
    /// The Keychain answered and holds no envelope.
    case absent
    /// The envelope is there but can never be decrypted again: its wrapping
    /// key is confirmed gone or is not the key it was made with, the
    /// envelope does not parse, or it was retired (a decrypt proved it
    /// unusable, or a replacement is under way).
    case invalidated
    /// The envelope was retired after a decrypt refusal that may not repeat
    /// (or for no recorded reason), and its wrapping key is present with a
    /// readable label. Restorable with the wallet's own phrase, or by a
    /// decrypt that works (which binds it back), but never enough to replace
    /// the keys with no record to check a phrase against.
    case suspended
    /// The Keychain refused a lookup (for example before the first device
    /// unlock), so nothing is known either way. Never treated as absent or
    /// invalidated.
    case unknown
}

/// The wallet's key material at rest.
///
/// Layout, mirroring the Android Keystore V2 design:
/// 1. The bundle JSON is encrypted with AES-256-GCM under a random 32-byte data
///    key, with a fresh 12-byte nonce per write.
/// 2. The data key is wrapped by a Secure Enclave P-256 key. Unwrapping it is
///    what asks for Face ID, Touch ID or the device passcode.
/// 3. Both halves go into one ``WalletKeyEnvelope`` written as a single Keychain
///    item, so a write either lands whole or not at all.
///
/// Nothing in the Keychain is usable without the Enclave, and the Enclave key
/// cannot be exported, so a Keychain dump off the device yields nothing.
///
/// An actor because two callers must not interleave a write with a read, and the
/// Keychain and Enclave calls block; they have no business on the main actor.
actor WalletKeyStore {
    private let keychain: any KeyValueStoring
    private let wrapper: any KeyWrapping

    /// The points in ``replaceUnusableKeys(with:)`` where a test can make
    /// the process "die": the replacement stops there with no rollback.
    enum ReplacementStep: Sendable {
        case retired
        case oldKeyDeleted
        case wrapped
    }

    #if DEBUG
    struct SimulatedKill: Error {}

    private var killAfter: ReplacementStep?

    /// Test hook: the next replacement stops after `step`, as a killed
    /// process would. Compiled out of release builds.
    func simulateKill(after step: ReplacementStep?) {
        killAfter = step
    }
    #endif

    init(keychain: any KeyValueStoring = KeychainStore(), wrapper: any KeyWrapping = SecureEnclaveKeyWrapper()) {
        self.keychain = keychain
        self.wrapper = wrapper
    }

    /// Whether a wallet is stored. Checks only that the envelope item exists, so
    /// it never prompts.
    var hasWallet: Bool {
        (try? keychain.contains(account: WalletKeyAccount.envelope)) ?? false
    }

    /// Whether the envelope is stored, keeping a lookup the Keychain refused
    /// apart from a confirmed absence. ``hasWallet`` folds both into false,
    /// which is right for "may a new wallet be created" but not for "have this
    /// wallet's keys gone missing": a launch before the first device unlock
    /// cannot read the Keychain, and that must not be mistaken for a wallet
    /// whose keys were left behind by a backup restore. Never prompts.
    var envelopePresence: KeyMaterialPresence {
        do {
            return try keychain.contains(account: WalletKeyAccount.envelope) ? .present : .absent
        } catch {
            return .unknown
        }
    }

    /// The stored key material's state, without a prompt: the envelope is
    /// read as plain Keychain data (it carries no access control of its own)
    /// and only parsed, and the wrapping key's label is read as an attribute
    /// (``KeyWrapping/keyLabel``), never used. Any Keychain status other
    /// than success or not found is ``KeyHealth/unknown``.
    ///
    /// | envelope | wrapping key | health |
    /// |---|---|---|
    /// | none | any | absent |
    /// | does not parse | any | invalidated |
    /// | version 2, label L | label L | usable |
    /// | version 2, label L | another real label | invalidated |
    /// | retired, structural reason | present | invalidated |
    /// | retired, key refused or no recorded reason | present | suspended |
    /// | version 1 (no label) | present | usable |
    /// | any that parses | absent | invalidated |
    /// | any that parses | lookup refused | unknown |
    var keyHealth: KeyHealth {
        let stored: Data?
        do {
            stored = try keychain.get(account: WalletKeyAccount.envelope)
        } catch {
            return .unknown
        }
        guard let stored else { return .absent }
        guard let envelope = try? WalletKeyEnvelope.decode(stored) else { return .invalidated }
        switch wrapper.keyLabel {
        case .absent:
            return .invalidated
        case .unknown:
            // A version 1 envelope only ever asked whether the key exists,
            // and a key with no readable label still exists.
            guard envelope.keyLabel == nil, wrapper.keyPresence == .present else { return .unknown }
            return .usable
        case .label(let label):
            guard let recorded = envelope.keyLabel else { return .usable }
            if recorded == label { return .usable }
            switch WalletKeyEnvelope.retireReason(of: recorded) {
            case .structural:
                return .invalidated
            case .keyRefused, .unspecified:
                return .suspended
            case nil:
                return .invalidated
            }
        }
    }

    /// Retires `envelope` for a caller that decrypted it (through
    /// ``loadWithEnvelope(reason:)``) and found the bundle unusable: no valid
    /// private key in it, the same structural proof as a bundle that does not
    /// decode. Compare and retire: nothing happens if the stored envelope is
    /// no longer the one that failed (the caller awaited in between).
    func retireUnusableBundle(ifStill envelope: Data) {
        retire(envelope, reason: .structural)
    }

    /// Rewrites `expected` in place under the retired label for `reason`,
    /// keeping its wrapped key and ciphertext, if it is still the stored
    /// envelope. Best effort: an envelope that does not parse already reads
    /// as invalidated, and a failed write leaves the state as it was (a
    /// retry, never a replace).
    private func retire(_ expected: Data, reason: WalletKeyEnvelope.RetireReason) {
        guard let current = try? keychain.get(account: WalletKeyAccount.envelope), current == expected,
              let retired = WalletKeyEnvelope.retired(expected, reason: reason), retired != expected
        else { return }
        try? keychain.set(retired, account: WalletKeyAccount.envelope)
    }

    /// After a successful decrypt: an envelope that does not carry the label
    /// of the key that just opened it (version 1, or one retired by mistake)
    /// is rewritten in place with that label, read prompt-free. Only when the
    /// label reads; on any other answer it is left as it is.
    ///
    /// Returns the envelope as stored afterwards: the rebound bytes if the
    /// write landed, otherwise `stored`, so a caller comparing against the
    /// stored envelope later compares against what is really there.
    private func rebindIfNeeded(_ stored: Data) -> Data {
        guard let decoded = try? WalletKeyEnvelope.decode(stored),
              case .label(let current) = wrapper.keyLabel,
              decoded.keyLabel != current
        else { return stored }
        let rebound = WalletKeyEnvelope.encode(
            wrappedDataKey: decoded.wrappedDataKey,
            ciphertext: decoded.ciphertext,
            keyLabel: current
        )
        do {
            try keychain.set(rebound, account: WalletKeyAccount.envelope)
            return rebound
        } catch {
            return stored
        }
    }

    /// Replaces key material that ``keyHealth`` confirms is
    /// ``KeyHealth/invalidated`` with `bundle`, under a fresh wrapping key.
    ///
    /// The order keeps an envelope in the Keychain at every step, and makes
    /// a process killed at any point leave a state ``keyHealth`` reads as
    /// invalidated, never as a usable pair that cannot decrypt:
    /// 1. The old envelope is retired in place: rewritten with
    ///    ``WalletKeyEnvelope/retiredKeyLabel``, which matches no key. (An
    ///    envelope that does not parse is left as it is; it already reads as
    ///    invalidated.)
    /// 2. The old wrapping key is deleted. It can decrypt nothing, and a
    ///    second key under the same tag would make every later lookup
    ///    ambiguous.
    /// 3. A fresh key is created and wraps the new data key.
    /// 4. The new envelope, carrying the fresh key's label, overwrites the
    ///    retired one in a single Keychain update.
    ///
    /// Stopping after 1, 2 or 3 leaves the retired (or unparseable) envelope,
    /// so the restore is offered again: a structural state is retired as
    /// structural and reads as invalidated whatever key is under the tag; a
    /// suspended one keeps its key-refused (or unrecorded) reason and reads
    /// as suspended while a key is present, invalidated once it is gone. A
    /// failed write at 4 deletes the fresh key as well, best effort; the
    /// retired envelope reads the same way even if that delete fails.
    ///
    /// Only on structural invalidation (key absent, a real label mismatch, a
    /// structural retirement, or an envelope that does not parse), re-checked
    /// here inside the actor. `allowingSuspended` also accepts
    /// ``KeyHealth/suspended``, and only the restore for a known wallet
    /// passes it, after its phrase has derived that wallet's addresses: the
    /// import with no record never does.
    func replaceUnusableKeys(with bundle: WalletKeyBundle, allowingSuspended: Bool = false) throws {
        let health = keyHealth
        guard health == .invalidated || (allowingSuspended && health == .suspended) else {
            throw WalletKeyStoreError.notReplaceable
        }

        var dataKey = try Self.randomDataKey()
        defer { dataKey.secureZero() }
        var plaintext = try Self.encode(bundle)
        defer { plaintext.secureZero() }
        let ciphertext = try Self.seal(plaintext, with: dataKey)

        // A structural state stays structural if the replacement stops part
        // way; a suspended one keeps the reason it already carries.
        if let current = try read(WalletKeyAccount.envelope),
           let retired = WalletKeyEnvelope.retired(current, reason: Self.retireReason(keeping: current, health: health)),
           retired != current {
            do {
                try keychain.set(retired, account: WalletKeyAccount.envelope)
            } catch {
                throw Self.map(error)
            }
        }
        try checkpoint(.retired)

        do {
            try wrapper.deleteKey()
        } catch {
            throw Self.map(error)
        }
        try checkpoint(.oldKeyDeleted)

        let wrappedDataKey = try Self.mapWrapperErrors { try wrapper.wrap(dataKey) }
        try checkpoint(.wrapped)
        let envelope = WalletKeyEnvelope.encode(
            wrappedDataKey: wrappedDataKey,
            ciphertext: ciphertext,
            keyLabel: currentKeyLabel
        )

        do {
            try keychain.set(envelope, account: WalletKeyAccount.envelope)
        } catch {
            try? wrapper.deleteKey()
            throw Self.map(error)
        }
    }

    private static func retireReason(keeping current: Data, health: KeyHealth) -> WalletKeyEnvelope.RetireReason {
        guard health == .suspended else { return .structural }
        let recorded = (try? WalletKeyEnvelope.decode(current))?.keyLabel
        return WalletKeyEnvelope.retireReason(of: recorded) ?? .keyRefused
    }

    /// Where ``simulateKill(after:)`` stops a replacement. A no-op outside
    /// debug builds.
    private func checkpoint(_ step: ReplacementStep) throws {
        #if DEBUG
        if let killAfter, killAfter == step {
            self.killAfter = nil
            throw SimulatedKill()
        }
        #endif
    }

    /// The label of the key that just wrapped, for the envelope to record.
    /// Nil if it cannot be read, which writes a version 1 envelope (the
    /// existence check) rather than failing the write.
    private var currentKeyLabel: Data? {
        guard case .label(let label) = wrapper.keyLabel else { return nil }
        return label
    }

    /// Encrypts and stores `bundle`, replacing any wallet already there.
    ///
    /// One Keychain write, so there is no half-stored state to roll back: either
    /// the new envelope is in place or the old one still is.
    func store(_ bundle: WalletKeyBundle) throws {
        // Minting a fresh wrapping key while an envelope is still there would
        // silently strand that wallet. Only a device with no wallet may create
        // a key.
        if !wrapper.hasKey && hasWallet {
            throw WalletKeyStoreError.keyInvalidated
        }
        // With no envelope, a wrapping key under the tag belongs to nothing
        // (left over from an earlier wallet, or a write that never landed).
        // A new wallet gets a key of its own rather than inheriting it.
        if envelopePresence == .absent {
            do {
                try wrapper.deleteKey()
            } catch {
                throw Self.map(error)
            }
        }

        var dataKey = try Self.randomDataKey()
        defer { dataKey.secureZero() }

        var plaintext = try Self.encode(bundle)
        defer { plaintext.secureZero() }

        let ciphertext = try Self.seal(plaintext, with: dataKey)

        let wrappedDataKey = try Self.mapWrapperErrors { try wrapper.wrap(dataKey) }
        let envelope = WalletKeyEnvelope.encode(
            wrappedDataKey: wrappedDataKey,
            ciphertext: ciphertext,
            keyLabel: currentKeyLabel
        )

        do {
            try keychain.set(envelope, account: WalletKeyAccount.envelope)
        } catch {
            throw Self.map(error)
        }
    }

    /// Decrypts and returns the stored wallet, prompting for biometrics or the
    /// device passcode with `reason` as the system prompt's text.
    ///
    /// A ciphertext or bundle that fails after a good unwrap retires the
    /// envelope as structural, and a repeated decrypt refusal as key refused,
    /// so ``keyHealth`` reads the proof from storage and the launch gate
    /// offers the restore. An absent key is left to ``keyHealth``'s live
    /// check. Any other failure changes nothing. A success binds a version 1 (or retired)
    /// envelope to the key that just opened it.
    func load(reason: String) throws -> WalletKeyBundle {
        try loadWithEnvelope(reason: reason).bundle
    }

    /// ``load(reason:)``, also returning the envelope bytes it opened, for a
    /// caller that may find the bundle unusable and retire exactly that
    /// envelope (``retireUnusableBundle(ifStill:)``).
    func loadWithEnvelope(reason: String) throws -> (bundle: WalletKeyBundle, envelope: Data) {
        guard let stored = try read(WalletKeyAccount.envelope) else {
            throw WalletKeyStoreError.notFound
        }
        let bundle: WalletKeyBundle
        do {
            bundle = try decrypt(stored, reason: reason)
        } catch let error as WalletKeyStoreError {
            // Only what a stored mark adds anything to is retired. An absent
            // key (`keyInvalidated`) is not: ``keyHealth`` already reads a
            // key that is really gone as invalidated, live, and a lookup that
            // missed once must not mark a working key's envelope for good.
            switch error {
            case .corrupt:
                retire(stored, reason: .structural)
            case .keyRefused:
                retire(stored, reason: .keyRefused)
            default:
                break
            }
            throw error
        }
        let current = rebindIfNeeded(stored)
        return (bundle, current)
    }

    private func decrypt(_ stored: Data, reason: String) throws -> WalletKeyBundle {
        let envelope = try WalletKeyEnvelope.decode(stored)

        // The wallet exists, so an absent wrapping key here is a stranded
        // wallet: `map` turns that into `.keyInvalidated`, never `.notFound`.
        var dataKey = try Self.mapWrapperErrors { try wrapper.unwrap(envelope.wrappedDataKey, reason: reason) }
        defer { dataKey.secureZero() }

        var plaintext: Data
        do {
            let box = try AES.GCM.SealedBox(combined: envelope.ciphertext)
            plaintext = try AES.GCM.open(box, using: SymmetricKey(data: dataKey))
        } catch {
            throw WalletKeyStoreError.corrupt
        }
        defer { plaintext.secureZero() }

        do {
            return try JSONDecoder().decode(WalletKeyBundle.self, from: plaintext)
        } catch {
            throw WalletKeyStoreError.corrupt
        }
    }

    /// Removes the wallet and the Enclave key that protects it.
    ///
    /// The key goes first. If that fails, the envelope is still there and still
    /// loadable, which is a better place to stop than an orphaned key whose
    /// wallet has already been deleted.
    func delete() throws {
        do {
            try wrapper.deleteKey()
            try keychain.delete(account: WalletKeyAccount.envelope)
        } catch {
            throw Self.map(error)
        }
    }

    #if DEBUG
    /// Debug-only summary for the maintainer's on-device check. Reports whether
    /// the wrapping key is really in the Enclave and whether the envelope is
    /// present. Never returns key material, and is not wired to any UI.
    func diagnostics() -> String {
        let envelope = (try? keychain.contains(account: WalletKeyAccount.envelope)) ?? false
        return """
            WalletKeyStore diagnostics
            hardwareBacked: \(wrapper.isHardwareBacked)
            wrappingKey present: \(wrapper.hasKey)
            envelope present: \(envelope)
            """
    }
    #endif

    // MARK: - Internals

    private func read(_ account: String) throws -> Data? {
        do {
            return try keychain.get(account: account)
        } catch {
            throw Self.map(error)
        }
    }

    private static func randomDataKey() throws -> Data {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        guard status == errSecSuccess else { throw WalletKeyStoreError.keychain(OSStatus(status)) }
        let key = Data(bytes)
        // `Data.init` copied the bytes, so wipe the staging array immediately.
        // The caller zeroes `key` itself once it is wrapped.
        bytes.resetBytes(in: 0..<bytes.count)
        return key
    }

    private static func seal(_ plaintext: Data, with dataKey: Data) throws -> Data {
        do {
            let box = try AES.GCM.seal(plaintext, using: SymmetricKey(data: dataKey), nonce: AES.GCM.Nonce())
            guard let combined = box.combined else { throw WalletKeyStoreError.corrupt }
            return combined
        } catch let error as WalletKeyStoreError {
            throw error
        } catch {
            throw WalletKeyStoreError.corrupt
        }
    }

    private static func encode(_ bundle: WalletKeyBundle) throws -> Data {
        do {
            return try JSONEncoder().encode(bundle)
        } catch {
            throw WalletKeyStoreError.corrupt
        }
    }

    private static func mapWrapperErrors<T>(_ body: () throws -> T) throws -> T {
        do {
            return try body()
        } catch {
            throw map(error)
        }
    }

    private static func map(_ error: Error) -> WalletKeyStoreError {
        switch error {
        case let error as WalletKeyStoreError:
            return error
        case let error as KeychainError:
            return .keychain(error.status)
        case let error as KeyWrapperError:
            switch error {
            case .keyNotFound:
                // A wrapping key is only ever looked for because a wallet needs
                // it, so its absence is a stranded wallet, not an absent one.
                return .keyInvalidated
            case .decryptionFailed:
                // The key is there and refused this envelope twice.
                return .keyRefused
            case .authenticationCancelled:
                return .authenticationCancelled
            case .authenticationFailed:
                return .authenticationFailed
            case .keyCreationFailed(let status), .deleteFailed(let status):
                return .keychain(status)
            case .operationFailed(let message):
                return .wrapping(message)
            }
        default:
            return .wrapping(String(describing: type(of: error)))
        }
    }
}

extension Data {
    /// Overwrites this buffer in place.
    ///
    /// It reaches only the bytes this `Data` owns. The decrypted bundle is
    /// zeroed here, but the `String` fields of the ``WalletKeyBundle`` decoded
    /// out of it are not: Swift `String` storage is copy-on-write and owned by
    /// the standard library, with no supported way to wipe it. Those copies live
    /// until they are released and the memory is reused, which is why the
    /// plaintext bundle is handed to callers and never cached.
    mutating func secureZero() {
        withUnsafeMutableBytes { raw in
            guard let base = raw.baseAddress, raw.count > 0 else { return }
            memset_s(base, raw.count, 0, raw.count)
        }
    }
}

extension Array where Element == UInt8 {
    fileprivate mutating func resetBytes(in range: Range<Int>) {
        withUnsafeMutableBytes { raw in
            guard let base = raw.baseAddress, raw.count > 0 else { return }
            memset_s(base.advanced(by: range.lowerBound), range.count, 0, range.count)
        }
    }
}
