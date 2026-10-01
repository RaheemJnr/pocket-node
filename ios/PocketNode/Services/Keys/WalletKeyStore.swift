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
    /// The Keychain refused an operation.
    case keychain(OSStatus)
    /// The Secure Enclave refused an operation for a reason that is neither
    /// authentication nor corruption.
    case wrapping(String)
}

/// Whether the wallet's key envelope is in the Keychain.
enum KeyMaterialPresence: Equatable, Sendable {
    case present
    /// The Keychain was readable and holds no envelope.
    case absent
    /// The Keychain could not be read, so nothing is known either way.
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

        var dataKey = try Self.randomDataKey()
        defer { dataKey.secureZero() }

        var plaintext = try Self.encode(bundle)
        defer { plaintext.secureZero() }

        let ciphertext: Data
        do {
            let box = try AES.GCM.seal(plaintext, using: SymmetricKey(data: dataKey), nonce: AES.GCM.Nonce())
            guard let combined = box.combined else { throw WalletKeyStoreError.corrupt }
            ciphertext = combined
        } catch let error as WalletKeyStoreError {
            throw error
        } catch {
            throw WalletKeyStoreError.corrupt
        }

        let wrappedDataKey = try Self.mapWrapperErrors { try wrapper.wrap(dataKey) }
        let envelope = WalletKeyEnvelope.encode(wrappedDataKey: wrappedDataKey, ciphertext: ciphertext)

        do {
            try keychain.set(envelope, account: WalletKeyAccount.envelope)
        } catch {
            throw Self.map(error)
        }
    }

    /// Decrypts and returns the stored wallet, prompting for biometrics or the
    /// device passcode with `reason` as the system prompt's text.
    func load(reason: String) throws -> WalletKeyBundle {
        guard let stored = try read(WalletKeyAccount.envelope) else {
            throw WalletKeyStoreError.notFound
        }

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
