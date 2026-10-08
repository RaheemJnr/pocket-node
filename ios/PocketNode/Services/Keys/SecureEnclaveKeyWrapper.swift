import Foundation
import LocalAuthentication
import Security

/// Why a wrap or unwrap failed. Separate from ``WalletKeyStoreError`` so the
/// store can decide what each one means for the caller.
enum KeyWrapperError: Error, Equatable {
    /// No wrapping key exists: none has been created yet, or one is gone from
    /// the Keychain while a wallet envelope is still there (a Keychain restore
    /// that dropped the Secure Enclave key, for example).
    case keyNotFound
    /// The user dismissed the biometric or passcode prompt.
    case authenticationCancelled
    /// The user failed to authenticate, or biometry is locked out.
    case authenticationFailed
    /// Creating the key pair failed.
    case keyCreationFailed(OSStatus)
    /// Removing the key pair failed, so key material may still be on the device.
    case deleteFailed(OSStatus)
    /// Anything else the Security framework reported.
    case operationFailed(String)
    /// The key was found and used, and the ECIES decrypt itself refused the
    /// wrapped data key: `errSecDecode`, or `errSecParam`, which is what an
    /// AES-GCM tag mismatch inside ECIES reports when the key is not the one
    /// that wrapped it (deterministic: the same inputs fail the same way,
    /// see `UnusableKeyProofTests`). This, and only this, of the decrypt's
    /// failures says the key and the envelope do not belong together.
    case decryptionFailed(OSStatus)
}

/// The wrapping key's identity as a prompt-free lookup sees it.
enum WrappingKeyLabel: Equatable, Sendable {
    /// The key is there, with this `kSecAttrApplicationLabel` (for a P-256
    /// key, a hash of its public half).
    case label(Data)
    /// The Keychain answered and holds no key.
    case absent
    /// The Keychain refused the lookup, or the key carries no label.
    case unknown
}

/// Wraps and unwraps a symmetric data key with a hardware key.
///
/// The indirection exists for the simulator, which has no Secure Enclave, and
/// for tests that want a stub.
protocol KeyWrapping: Sendable {
    /// Whether the wrapping key actually lives in the Secure Enclave. False on
    /// the simulator, and false if no key exists yet.
    var isHardwareBacked: Bool { get }

    /// Whether a wrapping key exists at all. Never prompts: it only looks the
    /// key up, it does not use it.
    var hasKey: Bool { get }

    /// Whether a wrapping key exists, keeping a lookup the Keychain refused
    /// apart from a confirmed absence. ``hasKey`` folds both into false. An
    /// existence query only: no key reference, no private-key use, and an
    /// authentication context that forbids any UI, so it can never prompt.
    var keyPresence: KeyMaterialPresence { get }

    /// The wrapping key's label, read the same prompt-free way as
    /// ``keyPresence`` (attributes only, never the key itself). The envelope
    /// records it so a different key under the same tag can be recognised.
    var keyLabel: WrappingKeyLabel { get }

    /// Encrypts `dataKey` to the wrapping key's public half, creating the key
    /// pair on first use. Never prompts: the public key is not access
    /// controlled.
    func wrap(_ dataKey: Data) throws -> Data

    /// Decrypts a wrapped data key. Uses the private half, so this is what
    /// triggers the Face ID / Touch ID / passcode prompt, with `reason` as the
    /// text the system shows.
    func unwrap(_ wrapped: Data, reason: String) throws -> Data

    /// Removes the key pair. Deleting a key that is not there succeeds.
    func deleteKey() throws
}

/// The wallet's key-wrapping key: a P-256 private key generated inside the
/// Secure Enclave and never extractable from it.
///
/// This mirrors the Android Keystore V2 threat model. The wallet bundle is
/// encrypted with a random AES-256 data key; that data key is the only thing
/// wrapped by this key, and unwrapping it requires the current biometric set or
/// the device passcode. An attacker with the Keychain contents but not the
/// device cannot unwrap anything: the private key exists only in the Enclave.
///
/// Access control is `[.privateKeyUsage, .biometryCurrentSet, .or, .devicePasscode]`
/// over `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`:
/// - `biometryCurrentSet` binds the biometric branch to the enrolment set that
///   existed when the key was made, so adding a face or finger cannot silently
///   grant access through biometry alone.
/// - `.or .devicePasscode` keeps the wallet reachable for users without biometry
///   and after a biometric lockout. It also means the key most likely survives a
///   re-enrolment, since the passcode branch is unaffected; the exact behaviour
///   of the combination is unverified on hardware (see `WalletKeyStoreDeviceTests`).
///
/// ### Simulator
/// There is no Enclave in the simulator, so the same P-256 key is created in
/// software with `[.privateKeyUsage]` only. That path is compiled out of device
/// builds and ``isHardwareBacked`` reports false, which the tests assert.
final class SecureEnclaveKeyWrapper: KeyWrapping {
    /// Application tag of the key pair in the Keychain.
    static let defaultTag = "com.rjnr.pocketnode.keys.wrapper"

    private let tag: Data

    /// Overridable so tests can use a throwaway tag. Production always uses
    /// ``defaultTag``.
    init(tag: String = SecureEnclaveKeyWrapper.defaultTag) {
        self.tag = Data(tag.utf8)
    }

    var isHardwareBacked: Bool {
        guard let key = try? loadKey(context: nil) else { return false }
        guard let attributes = SecKeyCopyAttributes(key) as? [String: Any] else { return false }
        guard let token = attributes[kSecAttrTokenID as String] as? String else { return false }
        return token == (kSecAttrTokenIDSecureEnclave as String)
    }

    var hasKey: Bool {
        (try? loadKey(context: nil)) != nil
    }

    /// The query asks for no reference, data or attributes, only whether the
    /// private key item matches, and carries an `LAContext` with
    /// `interactionNotAllowed`. Looking a Secure Enclave key up never needs
    /// authentication (only using its private half does), and if the system
    /// ever wanted UI for this lookup it fails with
    /// `errSecInteractionNotAllowed` instead of prompting, which lands on
    /// `.unknown` like every other status that is not success or not found.
    var keyPresence: KeyMaterialPresence {
        var query = baseQuery()
        query[kSecAttrKeyClass as String] = kSecAttrKeyClassPrivate
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        let context = LAContext()
        context.interactionNotAllowed = true
        query[kSecUseAuthenticationContext as String] = context

        let status = SecItemCopyMatching(query as CFDictionary, nil)
        switch status {
        case errSecSuccess:
            return .present
        case errSecItemNotFound:
            return .absent
        default:
            return .unknown
        }
    }

    /// Attributes only: `kSecReturnAttributes`, never `kSecReturnRef` or
    /// data, under the same `interactionNotAllowed` context as
    /// ``keyPresence``. Reading an item's attributes needs no authentication
    /// even when its private half is access controlled.
    var keyLabel: WrappingKeyLabel {
        var query = baseQuery()
        query[kSecAttrKeyClass as String] = kSecAttrKeyClassPrivate
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        query[kSecReturnAttributes as String] = true
        let context = LAContext()
        context.interactionNotAllowed = true
        query[kSecUseAuthenticationContext as String] = context

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            guard let attributes = result as? [String: Any],
                  let label = attributes[kSecAttrApplicationLabel as String] as? Data,
                  !label.isEmpty
            else { return .unknown }
            return .label(label)
        case errSecItemNotFound:
            return .absent
        default:
            return .unknown
        }
    }

    func wrap(_ dataKey: Data) throws -> Data {
        let privateKey = try loadKey(context: nil) ?? createKey()
        guard let publicKey = SecKeyCopyPublicKey(privateKey) else {
            throw KeyWrapperError.operationFailed("the wrapping key has no public half")
        }
        guard SecKeyIsAlgorithmSupported(publicKey, .encrypt, Self.algorithm) else {
            throw KeyWrapperError.operationFailed("ECIES encryption is unsupported on this key")
        }

        var error: Unmanaged<CFError>?
        guard let wrapped = SecKeyCreateEncryptedData(
            publicKey,
            Self.algorithm,
            dataKey as CFData,
            &error
        ) as Data? else {
            throw Self.classify(error?.takeRetainedValue())
        }
        return wrapped
    }

    func unwrap(_ wrapped: Data, reason: String) throws -> Data {
        let context = LAContext()
        context.localizedReason = reason

        guard let privateKey = try loadKey(context: context) else {
            throw KeyWrapperError.keyNotFound
        }

        // A refused decrypt is tried once more with the same key reference,
        // which carries the same already-evaluated `LAContext`, so the retry
        // asks for nothing new. Only a refusal that repeats is reported.
        return try Self.retryingDecryptionFailureOnce {
            var error: Unmanaged<CFError>?
            guard let dataKey = SecKeyCreateDecryptedData(
                privateKey,
                Self.algorithm,
                wrapped as CFData,
                &error
            ) as Data? else {
                throw Self.classifyDecryption(error?.takeRetainedValue())
            }
            return dataKey
        }
    }

    /// Runs `attempt`, and once more if it fails with
    /// ``KeyWrapperError/decryptionFailed(_:)``: a refusal counts as one only
    /// if it repeats. Any other failure is thrown at once, untried.
    static func retryingDecryptionFailureOnce(_ attempt: () throws -> Data) throws -> Data {
        do {
            return try attempt()
        } catch KeyWrapperError.decryptionFailed {
            return try attempt()
        }
    }

    func deleteKey() throws {
        let status = SecItemDelete(baseQuery() as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeyWrapperError.deleteFailed(status)
        }
    }

    // MARK: - Keychain plumbing

    /// ECIES with an ephemeral-static cofactor ECDH, X9.63 SHA-256 KDF and
    /// AES-GCM. The Enclave implements it natively, so the data key is only ever
    /// in the clear inside the Enclave and in this process' memory.
    private static let algorithm: SecKeyAlgorithm = .eciesEncryptionCofactorX963SHA256AESGCM

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
        ]
    }

    /// Looks the key pair up. Passing an `LAContext` attaches it to the returned
    /// `SecKey`, so the eventual private-key operation shows our prompt text
    /// instead of the system default.
    private func loadKey(context: LAContext?) throws -> SecKey? {
        var query = baseQuery()
        query[kSecReturnRef as String] = true
        if let context {
            query[kSecUseAuthenticationContext as String] = context
        }

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            // Swift rejects `as?` to a CoreFoundation type as always succeeding,
            // so the type is checked explicitly and only then cast. A Keychain
            // item of the wrong class is a corrupt store, not a usable key.
            guard let item, CFGetTypeID(item) == SecKeyGetTypeID() else {
                throw KeyWrapperError.operationFailed("the stored wrapping key is not a key")
            }
            let key = item as! SecKey
            return key
        case errSecItemNotFound:
            return nil
        // The same mapping ``classify(_:)`` gives these statuses, so a lookup
        // refused for authentication reads as that, not as a Keychain fault.
        case errSecUserCanceled:
            throw KeyWrapperError.authenticationCancelled
        case errSecAuthFailed, errSecInteractionNotAllowed:
            throw KeyWrapperError.authenticationFailed
        default:
            throw KeyWrapperError.keyCreationFailed(status)
        }
    }

    private func createKey() throws -> SecKey {
        var accessError: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(
            kCFAllocatorDefault,
            kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
            Self.accessControlFlags,
            &accessError
        ) else {
            throw Self.classify(accessError?.takeRetainedValue())
        }

        var attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: tag,
                kSecAttrAccessControl as String: access,
            ],
        ]
        #if !targetEnvironment(simulator)
        attributes[kSecAttrTokenID as String] = kSecAttrTokenIDSecureEnclave
        #endif

        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            throw Self.classify(error?.takeRetainedValue())
        }
        return key
    }

    #if targetEnvironment(simulator)
    /// No Enclave and no enrolled biometry in the simulator, so the key is a
    /// plain software P-256 key guarded by `privateKeyUsage` alone. Device
    /// builds never compile this.
    private static let accessControlFlags: SecAccessControlCreateFlags = [.privateKeyUsage]
    #else
    private static let accessControlFlags: SecAccessControlCreateFlags =
        [.privateKeyUsage, .biometryCurrentSet, .or, .devicePasscode]
    #endif

    // MARK: - Error classification

    /// ``classify(_:)`` for a failure of the decrypt itself, after the key
    /// was found: `errSecDecode` and `errSecParam` there mean the key cannot
    /// unwrap this data key (``KeyWrapperError/decryptionFailed(_:)``).
    /// Everything else is classified as any other failure.
    private static func classifyDecryption(_ error: CFError?) -> KeyWrapperError {
        if let error {
            let nsError = error as Error as NSError
            if nsError.domain == NSOSStatusErrorDomain {
                let status = OSStatus(nsError.code)
                if status == errSecDecode || status == errSecParam {
                    return .decryptionFailed(status)
                }
            }
        }
        return classify(error)
    }

    /// Turns a `CFError` from the Security or LocalAuthentication frameworks
    /// into a case the store can act on. Cancellation has to stay
    /// distinguishable from failure so the UI can stay silent when the user
    /// simply dismissed the prompt.
    private static func classify(_ error: CFError?) -> KeyWrapperError {
        guard let error else { return .operationFailed("unknown Security framework error") }
        let nsError = error as Error as NSError

        if nsError.domain == LAError.errorDomain {
            guard let code = LAError.Code(rawValue: nsError.code) else { return .authenticationFailed }
            switch code {
            case .userCancel, .appCancel, .systemCancel:
                return .authenticationCancelled
            default:
                return .authenticationFailed
            }
        }

        if nsError.domain == NSOSStatusErrorDomain {
            switch OSStatus(nsError.code) {
            case errSecUserCanceled:
                return .authenticationCancelled
            case errSecAuthFailed, errSecInteractionNotAllowed:
                return .authenticationFailed
            case errSecItemNotFound:
                return .keyNotFound
            default:
                return .keyCreationFailed(OSStatus(nsError.code))
            }
        }

        return .operationFailed(nsError.localizedDescription)
    }
}
