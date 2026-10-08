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
    private var presenceOverride: KeyMaterialPresence?
    private var _beforeUnwrap: (@Sendable () -> Void)?
    private var _afterWrap: (@Sendable () -> Void)?

    /// Runs inside `unwrap`, before the real one: stands in for whatever
    /// happens while the system prompt is up.
    var beforeUnwrap: (@Sendable () -> Void)? {
        get { lock.lock(); defer { lock.unlock() }; return _beforeUnwrap }
        set { lock.lock(); defer { lock.unlock() }; _beforeUnwrap = newValue }
    }

    /// Runs right after a successful `wrap`.
    var afterWrap: (@Sendable () -> Void)? {
        get { lock.lock(); defer { lock.unlock() }; return _afterWrap }
        set { lock.lock(); defer { lock.unlock() }; _afterWrap = newValue }
    }
    private let real: SecureEnclaveKeyWrapper

    init(tag: String) {
        self.real = SecureEnclaveKeyWrapper(tag: tag)
    }

    func fail(with error: KeyWrapperError?) {
        lock.lock()
        defer { lock.unlock() }
        failure = error
    }

    /// Simulates a wrapping key that has gone missing while a wallet envelope
    /// is still stored: `hasKey` goes false and using it reports `keyNotFound`.
    func simulateMissingKey(_ missing: Bool) {
        lock.lock()
        defer { lock.unlock() }
        pretendKeyIsMissing = missing
        failure = missing ? .keyNotFound : nil
    }

    /// Forces what ``keyPresence`` answers, for a lookup the Keychain refuses.
    /// Nil goes back to the real key.
    func overridePresence(_ presence: KeyMaterialPresence?) {
        lock.lock()
        defer { lock.unlock() }
        presenceOverride = presence
    }

    private var currentPresenceOverride: KeyMaterialPresence? {
        lock.lock()
        defer { lock.unlock() }
        return presenceOverride
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

    var keyPresence: KeyMaterialPresence {
        if let currentPresenceOverride { return currentPresenceOverride }
        return keyIsMissing ? .absent : real.keyPresence
    }

    var keyLabel: WrappingKeyLabel {
        if let currentPresenceOverride {
            switch currentPresenceOverride {
            case .absent: return .absent
            case .unknown: return .unknown
            case .present: break
            }
        }
        return keyIsMissing ? .absent : real.keyLabel
    }

    func wrap(_ dataKey: Data) throws -> Data {
        if let currentFailure { throw currentFailure }
        let wrapped = try real.wrap(dataKey)
        afterWrap?()
        return wrapped
    }

    func unwrap(_ wrapped: Data, reason: String) throws -> Data {
        beforeUnwrap?()
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
