import CryptoKit
import Foundation
import Security

/// Why reading or writing the wallet failed. Every case is terminal for the
/// operation: the store never falls back to a weaker path.
enum WalletKeyStoreError: Error, Equatable {
    /// No wallet is stored, or only half of one is (which is treated the same:
    /// an unusable wallet is an absent wallet).
    case notFound
    /// The user failed authentication, or biometry is locked out, or the
    /// wrapping key was invalidated by a biometric enrolment change.
    case authenticationFailed
    /// The user dismissed the prompt. Distinct from `.authenticationFailed` so
    /// callers can stay silent instead of showing an error.
    case authenticationCancelled
    /// The ciphertext did not authenticate, or the decrypted bytes are not a
    /// bundle. Tampering and truncation both land here.
    case corrupt
    /// The Keychain refused an operation.
    case keychain(OSStatus)
    /// The Secure Enclave refused an operation for a reason that is neither
    /// authentication nor corruption.
    case wrapping(String)
}

/// The wallet's key material at rest.
///
/// Layout, mirroring the Android Keystore V2 design:
/// 1. The bundle JSON is encrypted with AES-256-GCM under a random 32-byte data
///    key, with a fresh 12-byte nonce per write. The combined representation
///    (nonce ‖ ciphertext ‖ tag) is one Keychain item.
/// 2. The data key is wrapped by a Secure Enclave P-256 key and stored as a
///    second Keychain item. Unwrapping it is what asks for Face ID, Touch ID or
///    the device passcode.
///
/// Nothing in the Keychain is usable without the Enclave, and the Enclave key
/// cannot be exported, so a Keychain dump off the device yields nothing. The
/// data key and the plaintext bundle bytes are zeroed as soon as they have been
/// used.
///
/// An actor because two callers must not interleave a write with a read, and the
/// Keychain and Enclave calls block; they have no business on the main actor.
actor WalletKeyStore {
    private let keychain: KeychainStore
    private let wrapper: any KeyWrapping

    init(keychain: KeychainStore = KeychainStore(), wrapper: any KeyWrapping = SecureEnclaveKeyWrapper()) {
        self.keychain = keychain
        self.wrapper = wrapper
    }

    /// Whether a wallet is stored. Reads only the ciphertext item's presence, so
    /// it never prompts.
    var hasWallet: Bool {
        (try? keychain.contains(account: WalletKeyAccount.bundleCiphertext)) ?? false
    }

    /// Encrypts and stores `bundle`, replacing any wallet already there.
    ///
    /// The wrap happens before anything is written, so a failed or cancelled
    /// wrap leaves the previous wallet untouched. If the second Keychain write
    /// fails, both items are rolled back to their previous values, because a
    /// ciphertext paired with the wrong wrapped key is unrecoverable.
    func store(_ bundle: WalletKeyBundle) throws {
        let previousCiphertext = try read(WalletKeyAccount.bundleCiphertext)
        let previousWrappedKey = try read(WalletKeyAccount.wrappedDataKey)

        var dataKey = try Self.randomDataKey()
        defer { dataKey.secureZero() }

        var plaintext = try Self.encode(bundle)
        defer { plaintext.secureZero() }

        let sealed: Data
        do {
            let box = try AES.GCM.seal(plaintext, using: SymmetricKey(data: dataKey), nonce: AES.GCM.Nonce())
            guard let combined = box.combined else { throw WalletKeyStoreError.corrupt }
            sealed = combined
        } catch let error as WalletKeyStoreError {
            throw error
        } catch {
            throw WalletKeyStoreError.corrupt
        }

        let wrappedKey = try Self.mapWrapperErrors { try wrapper.wrap(dataKey) }

        do {
            try keychain.set(sealed, account: WalletKeyAccount.bundleCiphertext)
            try keychain.set(wrappedKey, account: WalletKeyAccount.wrappedDataKey)
        } catch {
            rollBack(ciphertext: previousCiphertext, wrappedKey: previousWrappedKey)
            throw Self.map(error)
        }
    }

    /// Decrypts and returns the stored wallet, prompting for biometrics or the
    /// device passcode with `reason` as the system prompt's text.
    func load(reason: String) throws -> WalletKeyBundle {
        guard let ciphertext = try read(WalletKeyAccount.bundleCiphertext),
              let wrappedKey = try read(WalletKeyAccount.wrappedDataKey)
        else {
            throw WalletKeyStoreError.notFound
        }

        var dataKey = try Self.mapWrapperErrors { try wrapper.unwrap(wrappedKey, reason: reason) }
        defer { dataKey.secureZero() }

        var plaintext: Data
        do {
            let box = try AES.GCM.SealedBox(combined: ciphertext)
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
    func delete() throws {
        do {
            try keychain.delete(account: WalletKeyAccount.bundleCiphertext)
            try keychain.delete(account: WalletKeyAccount.wrappedDataKey)
            try wrapper.deleteKey()
        } catch {
            throw Self.map(error)
        }
    }

    #if DEBUG
    /// Debug-only summary for the maintainer's on-device check. Reports whether
    /// the wrapping key is really in the Enclave and whether both items are
    /// present. Never returns key material, and is not wired to any UI.
    func diagnostics() -> String {
        let ciphertext = (try? keychain.contains(account: WalletKeyAccount.bundleCiphertext)) ?? false
        let wrappedKey = (try? keychain.contains(account: WalletKeyAccount.wrappedDataKey)) ?? false
        return """
            WalletKeyStore diagnostics
            hardwareBacked: \(wrapper.isHardwareBacked)
            bundleCiphertext present: \(ciphertext)
            wrappedDataKey present: \(wrappedKey)
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

    /// Best effort restore after a half-finished write. Nothing useful is left
    /// to do if this itself fails, and throwing here would mask the real error.
    private func rollBack(ciphertext: Data?, wrappedKey: Data?) {
        restore(ciphertext, to: WalletKeyAccount.bundleCiphertext)
        restore(wrappedKey, to: WalletKeyAccount.wrappedDataKey)
    }

    private func restore(_ value: Data?, to account: String) {
        if let value {
            try? keychain.set(value, account: account)
        } else {
            try? keychain.delete(account: account)
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
                return .notFound
            case .authenticationCancelled:
                return .authenticationCancelled
            case .authenticationFailed:
                return .authenticationFailed
            case .keyCreationFailed(let status):
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
    /// Overwrites the buffer in place. Only reaches the bytes this `Data` owns:
    /// anything Swift already copied elsewhere (a `String`, a value passed by
    /// copy) is beyond reach, which is why the plaintext never becomes a
    /// `String` on the way through.
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
