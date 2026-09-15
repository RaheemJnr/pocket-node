import Foundation

@testable import PocketNode

/// A key store whose answer the test controls, so `BackupViewModel` can be
/// exercised without a Keychain, a Secure Enclave, or a Face ID prompt.
final class StubWalletKeyReader: WalletKeyReading, @unchecked Sendable {
    private let lock = NSLock()
    private var _result: Result<WalletKeyBundle, Error>
    private var _loadCount = 0

    init(result: Result<WalletKeyBundle, Error> = .success(WalletKeyBundle(privateKeyHex: "00", mnemonic: nil))) {
        self._result = result
    }

    /// How many times `load(reason:)` was called, so a test can prove the
    /// onboarding exemption skipped the gate but still read the bundle (or
    /// the reverse: the gate blocked before this was ever reached).
    var loadCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return _loadCount
    }

    func set(result: Result<WalletKeyBundle, Error>) {
        lock.lock()
        defer { lock.unlock() }
        _result = result
    }

    func load(reason: String) async throws -> WalletKeyBundle {
        try recordLoadAndAnswer()
    }

    /// Taken through a synchronous helper: `NSLock.lock()` is unavailable from
    /// an async context, since holding one across a suspension would block
    /// the cooperative pool.
    private func recordLoadAndAnswer() throws -> WalletKeyBundle {
        lock.lock()
        defer { lock.unlock() }
        _loadCount += 1
        return try _result.get()
    }
}

enum StubWalletKeyReaderError: Error {
    case unreadable
}

/// An `AuthGating` stub whose answer and call count the test controls, so the
/// gate can be exercised without a PIN pad or a biometric prompt.
@MainActor
final class StubAuthGate: AuthGating {
    private(set) var requestCount = 0
    private(set) var lastReason: String?
    var granted = true

    func requireAuth(reason: String) async -> Bool {
        requestCount += 1
        lastReason = reason
        return granted
    }
}
