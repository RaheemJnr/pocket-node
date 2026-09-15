import Foundation
import XCTest

@testable import PocketNode

/// A wrapping key that can be told to fail, so the store's fail-closed paths can
/// be exercised without a real biometric prompt.
private final class StubKeyWrapper: KeyWrapping, @unchecked Sendable {
    /// Guards `failure`: `KeyWrapping` is `Sendable` and the store calls this
    /// from its actor's executor.
    private let lock = NSLock()
    private var failure: KeyWrapperError?
    private let real: SecureEnclaveKeyWrapper

    init(tag: String) {
        self.real = SecureEnclaveKeyWrapper(tag: tag)
    }

    func fail(with error: KeyWrapperError?) {
        lock.lock()
        defer { lock.unlock() }
        failure = error
    }

    private var currentFailure: KeyWrapperError? {
        lock.lock()
        defer { lock.unlock() }
        return failure
    }

    var isHardwareBacked: Bool { real.isHardwareBacked }

    func wrap(_ dataKey: Data) throws -> Data {
        if let currentFailure { throw currentFailure }
        return try real.wrap(dataKey)
    }

    func unwrap(_ wrapped: Data, reason: String) throws -> Data {
        if let currentFailure { throw currentFailure }
        return try real.unwrap(wrapped, reason: reason)
    }

    func deleteKey() throws {
        try real.deleteKey()
    }
}

/// What happens when the Enclave says no.
final class WalletKeyStoreFailureTests: XCTestCase {
    private let service = "com.rjnr.pocketnode.tests.failure.keys"
    private let tag = "com.rjnr.pocketnode.tests.failure.wrapper"

    private var keychain: KeychainStore!
    private var wrapper: StubKeyWrapper!
    private var store: WalletKeyStore!

    override func setUp() {
        super.setUp()
        keychain = KeychainStore(service: service)
        wrapper = StubKeyWrapper(tag: tag)
        store = WalletKeyStore(keychain: keychain, wrapper: wrapper)
        try? keychain.deleteAll()
        try? wrapper.deleteKey()
    }

    override func tearDown() {
        try? keychain.deleteAll()
        try? wrapper.deleteKey()
        store = nil
        wrapper = nil
        keychain = nil
        super.tearDown()
    }

    /// A cancelled prompt is not an error to shout about, so it has to stay
    /// distinguishable from a failed one.
    func testCancelledPromptSurfacesAsCancellation() async throws {
        try await store.store(WalletKeyBundle(privateKeyHex: "aabb"))
        wrapper.fail(with: .authenticationCancelled)

        await assertThrows(.authenticationCancelled) {
            _ = try await self.store.load(reason: "Unlock your wallet")
        }
    }

    func testFailedAuthenticationSurfacesAsFailure() async throws {
        try await store.store(WalletKeyBundle(privateKeyHex: "aabb"))
        wrapper.fail(with: .authenticationFailed)

        await assertThrows(.authenticationFailed) {
            _ = try await self.store.load(reason: "Unlock your wallet")
        }
    }

    /// `biometryCurrentSet` invalidates the wrapping key when the enrolled
    /// biometrics change; the wallet is then unreadable and must say so rather
    /// than look like a transient error.
    func testMissingWrappingKeySurfacesAsNotFound() async throws {
        try await store.store(WalletKeyBundle(privateKeyHex: "aabb"))
        wrapper.fail(with: .keyNotFound)

        await assertThrows(.notFound) {
            _ = try await self.store.load(reason: "Unlock your wallet")
        }
    }

    /// The critical one: a write that cannot complete must leave the wallet
    /// that was already there intact and loadable. Replacing it with half a new
    /// one would lose the user's keys.
    func testFailedStoreLeavesThePreviousWalletIntact() async throws {
        let original = WalletKeyBundle(privateKeyHex: "aabb", mnemonic: "one two")
        try await store.store(original)
        wrapper.fail(with: .authenticationFailed)

        do {
            try await store.store(WalletKeyBundle(privateKeyHex: "ccdd"))
            XCTFail("the store should have failed")
        } catch {
            // expected
        }

        wrapper.fail(with: nil)
        let loaded = try await store.load(reason: "Unlock your wallet")
        XCTAssertEqual(loaded, original)
    }

    private func assertThrows(
        _ expected: WalletKeyStoreError,
        file: StaticString = #filePath,
        line: UInt = #line,
        _ body: () async throws -> Void
    ) async {
        do {
            try await body()
            XCTFail("expected \(expected) but the call succeeded", file: file, line: line)
        } catch let error as WalletKeyStoreError {
            XCTAssertEqual(error, expected, file: file, line: line)
        } catch {
            XCTFail("expected \(expected) but got \(error)", file: file, line: line)
        }
    }
}
