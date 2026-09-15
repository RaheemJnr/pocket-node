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
        result: Result<Void, BiometricError> = .success(())
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
