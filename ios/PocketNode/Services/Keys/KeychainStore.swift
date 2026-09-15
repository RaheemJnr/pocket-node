import Foundation
import Security

/// A Keychain failure, carrying the raw `OSStatus` so callers can fail closed on
/// a specific code rather than on a string.
struct KeychainError: Error, Equatable, CustomStringConvertible {
    let status: OSStatus

    var message: String {
        SecCopyErrorMessageString(status, nil) as String? ?? "unknown Keychain error"
    }

    var description: String { "KeychainError(\(status)): \(message)" }
}

/// The two generic-password items that make up a stored wallet.
///
/// Split in two so the wrapped data key (which the Secure Enclave has to unwrap,
/// with a biometric prompt) and the bundle ciphertext (which is readable without
/// a prompt) can be handled independently: `hasWallet` only touches the latter.
enum WalletKeyAccount {
    static let wrappedDataKey = "wallet.wrappedDataKey"
    static let bundleCiphertext = "wallet.bundleCiphertext"
}

/// Generic-password storage for the wallet's encrypted blobs.
///
/// Every item is `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`: it never
/// leaves the device, is never part of an iTunes/iCloud backup, and is
/// unreadable until the user has unlocked the device at least once since boot.
/// Synchronization is explicitly off so nothing reaches the iCloud Keychain.
///
/// Nothing stored here is plaintext key material: the bundle is AES-256-GCM
/// ciphertext and the data key is wrapped by the Secure Enclave. The Keychain
/// attributes are defence in depth, not the only protection.
struct KeychainStore: Sendable {
    static let defaultService = "com.rjnr.pocketnode.keys"

    let service: String

    /// Overridable so tests can use a throwaway service and never touch the real
    /// one. Production always uses ``defaultService``.
    init(service: String = KeychainStore.defaultService) {
        self.service = service
    }

    /// Writes `data` for `account`, replacing any existing item.
    func set(_ data: Data, account: String) throws {
        let update: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
        ]
        let updateStatus = SecItemUpdate(query(account: account) as CFDictionary, update as CFDictionary)
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else { throw KeychainError(status: updateStatus) }

        var add = query(account: account)
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        guard addStatus == errSecSuccess else { throw KeychainError(status: addStatus) }
    }

    /// Reads `account`, or nil when there is no such item. Never prompts.
    func get(account: String) throws -> Data? {
        var lookup = query(account: account)
        lookup[kSecReturnData as String] = true
        lookup[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(lookup as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            return result as? Data
        case errSecItemNotFound:
            return nil
        default:
            throw KeychainError(status: status)
        }
    }

    /// Whether `account` exists, without copying its data out.
    func contains(account: String) throws -> Bool {
        var lookup = query(account: account)
        lookup[kSecMatchLimit as String] = kSecMatchLimitOne

        let status = SecItemCopyMatching(lookup as CFDictionary, nil)
        switch status {
        case errSecSuccess:
            return true
        case errSecItemNotFound:
            return false
        default:
            throw KeychainError(status: status)
        }
    }

    /// Removes `account`. Deleting something that is not there is a success.
    func delete(account: String) throws {
        let status = SecItemDelete(query(account: account) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError(status: status)
        }
    }

    /// Removes every item for this service. Used by the reinstall wipe.
    func deleteAll() throws {
        let all: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrSynchronizable as String: false,
        ]
        let status = SecItemDelete(all as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError(status: status)
        }
    }

    private func query(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: false,
        ]
    }
}
