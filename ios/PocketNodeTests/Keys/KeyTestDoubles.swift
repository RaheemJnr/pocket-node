import Foundation

@testable import PocketNode

/// A wrapping key that can be told to fail, so the store's fail-closed paths can
/// be exercised without a real biometric prompt. Everything it is not told to
/// fail is delegated to a real wrapper, so the crypto stays real.
final class StubKeyWrapper: KeyWrapping, @unchecked Sendable {
    /// Guards the mutable state: `KeyWrapping` is `Sendable` and the store calls
    /// this from its actor's executor.
    private let lock = NSLock()
    private var failure: KeyWrapperError?
    private var pretendKeyIsMissing = false
    private let real: SecureEnclaveKeyWrapper

    init(tag: String) {
        self.real = SecureEnclaveKeyWrapper(tag: tag)
    }

    func fail(with error: KeyWrapperError?) {
        lock.lock()
        defer { lock.unlock() }
        failure = error
    }

    /// Simulates the key the system invalidates when the enrolled biometrics
    /// change: `hasKey` goes false and using it reports `keyNotFound`.
    func simulateInvalidatedKey(_ invalidated: Bool) {
        lock.lock()
        defer { lock.unlock() }
        pretendKeyIsMissing = invalidated
        failure = invalidated ? .keyNotFound : nil
    }

    private var currentFailure: KeyWrapperError? {
        lock.lock()
        defer { lock.unlock() }
        return failure
    }

    private var keyIsMissing: Bool {
        lock.lock()
        defer { lock.unlock() }
        return pretendKeyIsMissing
    }

    var isHardwareBacked: Bool { real.isHardwareBacked }

    var hasKey: Bool { keyIsMissing ? false : real.hasKey }

    func wrap(_ dataKey: Data) throws -> Data {
        if let currentFailure { throw currentFailure }
        return try real.wrap(dataKey)
    }

    func unwrap(_ wrapped: Data, reason: String) throws -> Data {
        if let currentFailure { throw currentFailure }
        return try real.unwrap(wrapped, reason: reason)
    }

    func deleteKey() throws {
        if let currentFailure, case .deleteFailed = currentFailure { throw currentFailure }
        try real.deleteKey()
    }
}

/// A store that can be told to refuse writes or deletes, to prove the wallet
/// already on the device survives a failed one. Reads always work, so the test
/// can check what is still there.
final class FailingKeyValueStore: KeyValueStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var writesFail = false
    private var deletesFail = false
    private let real: KeychainStore

    init(service: String) {
        self.real = KeychainStore(service: service)
    }

    func failWrites(_ fail: Bool) {
        lock.lock()
        defer { lock.unlock() }
        writesFail = fail
    }

    func failDeletes(_ fail: Bool) {
        lock.lock()
        defer { lock.unlock() }
        deletesFail = fail
    }

    private var isWriteFailing: Bool {
        lock.lock()
        defer { lock.unlock() }
        return writesFail
    }

    private var isDeleteFailing: Bool {
        lock.lock()
        defer { lock.unlock() }
        return deletesFail
    }

    func set(_ data: Data, account: String) throws {
        if isWriteFailing { throw KeychainError(status: errSecIO) }
        try real.set(data, account: account)
    }

    func get(account: String) throws -> Data? {
        try real.get(account: account)
    }

    func contains(account: String) throws -> Bool {
        try real.contains(account: account)
    }

    func delete(account: String) throws {
        if isDeleteFailing { throw KeychainError(status: errSecIO) }
        try real.delete(account: account)
    }

    func deleteAll() throws {
        if isDeleteFailing { throw KeychainError(status: errSecIO) }
        try real.deleteAll()
    }
}
