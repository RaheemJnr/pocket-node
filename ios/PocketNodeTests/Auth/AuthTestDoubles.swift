import Foundation

@testable import PocketNode

/// A biometric sensor whose answer the test decides.
///
/// `AuthService`'s branching is the thing under test, and a real `LAContext`
/// cannot be driven from a unit test: the simulator has no sensor, and even
/// with one the prompt needs a human.
final class StubBiometrics: BiometricAuthenticating, @unchecked Sendable {
    private let lock = NSLock()
    private var _availability: BiometricAvailability
    private var _result: Result<Void, BiometricError>
    private var _prompts = 0

    init(
        availability: BiometricAvailability = .faceID,
        result: Result<Void, BiometricError> = .success(Void())
    ) {
        self._availability = availability
        self._result = result
    }

    var availability: BiometricAvailability {
        lock.lock()
        defer { lock.unlock() }
        return _availability
    }

    /// How many times ``authenticate(reason:)`` was called, so a test can prove
    /// a path did not prompt at all.
    var prompts: Int {
        lock.lock()
        defer { lock.unlock() }
        return _prompts
    }

    func set(availability: BiometricAvailability) {
        lock.lock()
        defer { lock.unlock() }
        _availability = availability
    }

    func set(result: Result<Void, BiometricError>) {
        lock.lock()
        defer { lock.unlock() }
        _result = result
    }

    func authenticate(reason: String) async -> Result<Void, BiometricError> {
        // Taken through a synchronous helper: `NSLock.lock()` is unavailable
        // from an async context, since holding one across a suspension would
        // block the cooperative pool.
        recordPromptAndAnswer()
    }

    private func recordPromptAndAnswer() -> Result<Void, BiometricError> {
        lock.lock()
        defer { lock.unlock() }
        _prompts += 1
        return _result
    }
}

/// A clock the test moves by hand, so the lockout schedule can be exercised
/// without waiting out 30 real seconds (or the hour at eight failures).
final class TestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var millis: Int64

    init(millis: Int64 = 1_700_000_000_000) {
        self.millis = millis
    }

    var now: Int64 {
        lock.lock()
        defer { lock.unlock() }
        return millis
    }

    func advance(seconds: Int64) {
        lock.lock()
        defer { lock.unlock() }
        millis += seconds * 1000
    }

    /// Passed to `PinService` as its epoch-millis source.
    var source: @Sendable () -> Int64 {
        { [self] in now }
    }
}

/// A store whose reads all fail with a chosen status, over a real Keychain
/// service that may already hold a PIN.
///
/// This is the `errSecInteractionNotAllowed` case: a
/// `WhenPasscodeSetThisDeviceOnly` item is unreadable until the device has been
/// unlocked once since boot, which a prewarm or background launch can hit. The
/// PIN really is there; the process just cannot see it yet.
final class UnreadableKeyValueStore: KeyValueStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var status: OSStatus?
    private let real: KeychainStore

    init(service: String, status: OSStatus? = errSecInteractionNotAllowed) {
        self.real = KeychainStore(service: service)
        self.status = status
    }

    /// Passing nil lets reads through again, as they would be after the user
    /// unlocks the device.
    func failReads(_ status: OSStatus?) {
        lock.lock()
        defer { lock.unlock() }
        self.status = status
    }

    private var currentStatus: OSStatus? {
        lock.lock()
        defer { lock.unlock() }
        return status
    }

    func set(_ data: Data, account: String) throws {
        try real.set(data, account: account)
    }

    func get(account: String) throws -> Data? {
        if let currentStatus { throw KeychainError(status: currentStatus) }
        return try real.get(account: account)
    }

    func contains(account: String) throws -> Bool {
        if let currentStatus { throw KeychainError(status: currentStatus) }
        return try real.contains(account: account)
    }

    func delete(account: String) throws {
        try real.delete(account: account)
    }

    func deleteAll() throws {
        try real.deleteAll()
    }
}

/// A store where reads fail for some accounts and work for others.
///
/// One field of the PIN state going unreadable while the rest is fine is not a
/// contrived case: Keychain items are independent, and a partial failure is
/// what a transient error looks like. The salt is the one that matters, because
/// the shared policy silently replaces a `nil` one.
final class SelectiveReadKeyValueStore: KeyValueStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var failingAccounts: Set<String> = []
    private let real: KeychainStore

    init(service: String) {
        self.real = KeychainStore(service: service)
    }

    func failReads(to accounts: Set<String>) {
        lock.lock()
        defer { lock.unlock() }
        failingAccounts = accounts
    }

    /// Reads straight through, ignoring the failure script, so a test can check
    /// what is really stored while reads are still being refused.
    func rawGet(account: String) throws -> Data? {
        try real.get(account: account)
    }

    private func shouldFail(_ account: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return failingAccounts.contains(account)
    }

    func set(_ data: Data, account: String) throws {
        try real.set(data, account: account)
    }

    func get(account: String) throws -> Data? {
        if shouldFail(account) { throw KeychainError(status: errSecInteractionNotAllowed) }
        return try real.get(account: account)
    }

    func contains(account: String) throws -> Bool {
        if shouldFail(account) { throw KeychainError(status: errSecInteractionNotAllowed) }
        return try real.contains(account: account)
    }

    func delete(account: String) throws {
        try real.delete(account: account)
    }

    func deleteAll() throws {
        try real.deleteAll()
    }
}

/// A store that records the order in which accounts are written, so the
/// ordering rules in `KeychainPinStore.apply` can be asserted directly instead
/// of inferred from fault injection.
final class RecordingKeyValueStore: KeyValueStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var _writes: [String] = []
    private let real: KeychainStore

    init(service: String) {
        self.real = KeychainStore(service: service)
    }

    /// Accounts written or deleted, oldest first. The write probe is filtered
    /// out: it is not part of the edit being asserted.
    var writes: [String] {
        lock.lock()
        defer { lock.unlock() }
        return _writes.filter { $0 != PinAccount.writeProbe }
    }

    func reset() {
        lock.lock()
        defer { lock.unlock() }
        _writes = []
    }

    private func record(_ account: String) {
        lock.lock()
        defer { lock.unlock() }
        _writes.append(account)
    }

    func set(_ data: Data, account: String) throws {
        record(account)
        try real.set(data, account: account)
    }

    func get(account: String) throws -> Data? {
        try real.get(account: account)
    }

    func contains(account: String) throws -> Bool {
        try real.contains(account: account)
    }

    func delete(account: String) throws {
        record(account)
        try real.delete(account: account)
    }

    func deleteAll() throws {
        try real.deleteAll()
    }
}

/// A store that refuses one nominated write, and optionally every delete.
///
/// Used for the partial-write cases: exactly one field of an edit is lost,
/// which is what an interrupted `SecItemAdd` would look like, and the cleanup
/// path can then be tested both when it works and when it does not.
final class ScriptedKeyValueStore: KeyValueStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var failingAccounts: Set<String> = []
    private var deletesFail = false
    private let real: KeychainStore

    init(service: String) {
        self.real = KeychainStore(service: service)
    }

    func failWrites(to accounts: Set<String>) {
        lock.lock()
        defer { lock.unlock() }
        failingAccounts = accounts
    }

    func failDeletes(_ fail: Bool) {
        lock.lock()
        defer { lock.unlock() }
        deletesFail = fail
    }

    private func shouldFailWrite(_ account: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return failingAccounts.contains(account)
    }

    private var shouldFailDelete: Bool {
        lock.lock()
        defer { lock.unlock() }
        return deletesFail
    }

    func set(_ data: Data, account: String) throws {
        if shouldFailWrite(account) { throw KeychainError(status: errSecIO) }
        try real.set(data, account: account)
    }

    func get(account: String) throws -> Data? {
        try real.get(account: account)
    }

    func contains(account: String) throws -> Bool {
        try real.contains(account: account)
    }

    func delete(account: String) throws {
        if shouldFailDelete { throw KeychainError(status: errSecIO) }
        try real.delete(account: account)
    }

    func deleteAll() throws {
        if shouldFailDelete { throw KeychainError(status: errSecIO) }
        try real.deleteAll()
    }
}

extension Argon2Cost {
    /// Argon2id at the floor the RFC allows for `p = 1`, so a test can afford
    /// dozens of verifies. Production cost (64 MiB, t=3) takes over a second
    /// per hash in a debug build, which would put the lockout tests in the
    /// minutes.
    ///
    /// It changes the derived hash, so a store written with these parameters
    /// only verifies against these parameters. That is fine here: every test
    /// starts from an empty throwaway Keychain service.
    static let testing = Argon2Cost(iterations: 1, memoryKib: 8, parallelism: 1, tagLength: 32)
}
