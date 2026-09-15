import Foundation
import LocalAuthentication
import Security

/// Why a wrap or unwrap failed. Separate from ``WalletKeyStoreError`` so the
/// store can decide what each one means for the caller.
enum KeyWrapperError: Error, Equatable {
    /// No wrapping key exists yet (or it was invalidated, for instance by the
    /// user enrolling a new finger or face, which `biometryCurrentSet` kills).
    case keyNotFound
    /// The user dismissed the biometric or passcode prompt.
    case authenticationCancelled
    /// The user failed to authenticate, or biometry is locked out.
    case authenticationFailed
    /// Creating the key pair failed.
    case keyCreationFailed(OSStatus)
    /// Anything else the Security framework reported.
    case operationFailed(String)
}

/// Wraps and unwraps a symmetric data key with a hardware key.
///
/// The indirection exists for the simulator, which has no Secure Enclave, and
/// for tests that want a stub.
protocol KeyWrapping: Sendable {
    /// Whether the wrapping key actually lives in the Secure Enclave. False on
    /// the simulator, and false if no key exists yet.
    var isHardwareBacked: Bool { get }

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
/// - `biometryCurrentSet` invalidates the key if the enrolled biometrics change,
///   so adding a face or finger cannot silently grant access to the wallet.
/// - `.or .devicePasscode` keeps the wallet reachable for users without biometry
///   and after a biometric lockout.
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

        var error: Unmanaged<CFError>?
        guard let dataKey = SecKeyCreateDecryptedData(
            privateKey,
            Self.algorithm,
            wrapped as CFData,
            &error
        ) as Data? else {
            throw Self.classify(error?.takeRetainedValue())
        }
        return dataKey
    }

    func deleteKey() throws {
        let status = SecItemDelete(baseQuery() as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeyWrapperError.keyCreationFailed(status)
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
            // swiftlint:disable:next force_cast
            return (item as! SecKey)
        case errSecItemNotFound:
            return nil
        case errSecUserCanceled:
            throw KeyWrapperError.authenticationCancelled
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
