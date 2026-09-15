import Foundation
import XCTest

@testable import PocketNode

/// What happens when the Enclave or the Keychain says no.
final class WalletKeyStoreFailureTests: XCTestCase {
    private let service = "com.rjnr.pocketnode.tests.failure.keys"
    private let tag = "com.rjnr.pocketnode.tests.failure.wrapper"

    private var keychain: FailingKeyValueStore!
    private var wrapper: StubKeyWrapper!
    private var store: WalletKeyStore!

    override func setUp() {
        super.setUp()
        keychain = FailingKeyValueStore(service: service)
        wrapper = StubKeyWrapper(tag: tag)
        store = WalletKeyStore(keychain: keychain, wrapper: wrapper)
        try? keychain.deleteAll()
        try? wrapper.deleteKey()
    }

    override func tearDown() {
        keychain.failWrites(false)
        keychain.failDeletes(false)
        wrapper.fail(with: nil)
        try? keychain.deleteAll()
        try? wrapper.deleteKey()
        store = nil
        wrapper = nil
        keychain = nil
        super.tearDown()
    }

    // MARK: - Authentication

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

    // MARK: - Invalidated wrapping key

    /// `biometryCurrentSet` destroys the wrapping key when the enrolled
    /// biometrics change. The wallet is then unreadable forever, which is a
    /// different conversation with the user than "no wallet here" or "try
    /// again", so it must not collapse into `.notFound`.
    func testInvalidatedKeyOnLoadIsNotReportedAsMissingWallet() async throws {
        try await store.store(WalletKeyBundle(privateKeyHex: "aabb"))
        wrapper.simulateInvalidatedKey(true)

        await assertThrows(.keyInvalidated) {
            _ = try await self.store.load(reason: "Unlock your wallet")
        }
    }

    /// Storing over a wallet whose key is gone would mint a fresh key and leave
    /// the old envelope permanently unreadable, with nothing to tell the user
    /// their previous wallet is stranded. Refuse instead.
    func testStoreRefusesToMintAKeyOverAnExistingWallet() async throws {
        try await store.store(WalletKeyBundle(privateKeyHex: "aabb"))
        wrapper.simulateInvalidatedKey(true)

        await assertThrows(.keyInvalidated) {
            try await self.store.store(WalletKeyBundle(privateKeyHex: "ccdd"))
        }
    }

    /// With no wallet stored there is nothing to strand, so the first store on a
    /// clean device creates the key as usual.
    func testStoreCreatesTheKeyWhenThereIsNoWallet() async throws {
        let hasWallet = await store.hasWallet
        XCTAssertFalse(hasWallet)

        try await store.store(WalletKeyBundle(privateKeyHex: "aabb"))

        let loaded = try await store.load(reason: "Unlock your wallet")
        XCTAssertEqual(loaded.privateKeyHex, "aabb")
    }

    // MARK: - Keychain failures

    /// The critical one: a write that cannot complete must leave the wallet that
    /// was already there intact and loadable.
    func testFailedKeychainWriteLeavesThePreviousWalletIntact() async throws {
        let original = WalletKeyBundle(privateKeyHex: "aabb", mnemonic: "one two")
        try await store.store(original)
        keychain.failWrites(true)

        await assertThrows(.keychain(errSecIO)) {
            try await self.store.store(WalletKeyBundle(privateKeyHex: "ccdd"))
        }

        keychain.failWrites(false)
        let loaded = try await store.load(reason: "Unlock your wallet")
        XCTAssertEqual(loaded, original)
    }

    /// Same for a wrap that fails before anything is written.
    func testFailedWrapLeavesThePreviousWalletIntact() async throws {
        let original = WalletKeyBundle(privateKeyHex: "aabb", mnemonic: "one two")
        try await store.store(original)
        wrapper.fail(with: .authenticationFailed)

        await assertThrows(.authenticationFailed) {
            try await self.store.store(WalletKeyBundle(privateKeyHex: "ccdd"))
        }

        wrapper.fail(with: nil)
        let loaded = try await store.load(reason: "Unlock your wallet")
        XCTAssertEqual(loaded, original)
    }

    func testFailedDeleteSurfacesTheKeychainStatus() async throws {
        try await store.store(WalletKeyBundle(privateKeyHex: "aabb"))
        keychain.failDeletes(true)

        await assertThrows(.keychain(errSecIO)) { try await self.store.delete() }
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
