import XCTest

@testable import PocketNode

/// Offline round-trip tests for the wallet key store.
///
/// They run against a throwaway Keychain service and a throwaway wrapping-key
/// tag, so nothing here can touch a real wallet on the maintainer's simulator.
/// On the simulator the wrapping key is a software P-256 key (there is no
/// Secure Enclave), which exercises every code path except the biometric
/// prompt. `WalletKeyStoreDeviceTests` covers the hardware side.
final class WalletKeyStoreTests: XCTestCase {
    private let service = "com.rjnr.pocketnode.tests.keys"
    private let tag = "com.rjnr.pocketnode.tests.wrapper"

    private var keychain: KeychainStore!
    private var wrapper: SecureEnclaveKeyWrapper!
    private var store: WalletKeyStore!

    override func setUp() {
        super.setUp()
        keychain = KeychainStore(service: service)
        wrapper = SecureEnclaveKeyWrapper(tag: tag)
        store = WalletKeyStore(keychain: keychain, wrapper: wrapper)
        wipe()
    }

    override func tearDown() {
        wipe()
        store = nil
        wrapper = nil
        keychain = nil
        super.tearDown()
    }

    private func wipe() {
        try? keychain.deleteAll()
        try? wrapper.deleteKey()
    }

    // MARK: - Round trips

    func testStoreThenLoadRoundTripsBundleWithMnemonic() async throws {
        let bundle = WalletKeyBundle(
            privateKeyHex: "9f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8",
            mnemonic: "abandon abandon abandon abandon abandon abandon "
                + "abandon abandon abandon abandon abandon about"
        )

        try await store.store(bundle)
        let loaded = try await store.load(reason: "Unlock your wallet")

        XCTAssertEqual(loaded, bundle)
    }

    func testStoreThenLoadRoundTripsBundleWithoutMnemonic() async throws {
        let bundle = WalletKeyBundle(privateKeyHex: "00112233445566778899aabbccddeeff")

        try await store.store(bundle)
        let loaded = try await store.load(reason: "Unlock your wallet")

        XCTAssertEqual(loaded, bundle)
        XCTAssertNil(loaded.mnemonic)
    }

    /// The JSON written to the Keychain has to stay the shape Android writes, so
    /// #511 can swap in the shared Kotlin model without a data migration.
    func testBundleJSONUsesTheAndroidFieldNames() throws {
        let bundle = WalletKeyBundle(privateKeyHex: "aabb", mnemonic: "one two")
        let json = try XCTUnwrap(
            JSONSerialization.jsonObject(with: JSONEncoder().encode(bundle)) as? [String: Any]
        )

        XCTAssertEqual(json["privateKeyHex"] as? String, "aabb")
        XCTAssertEqual(json["mnemonic"] as? String, "one two")
    }

    /// Kotlin's serializer emits an explicit null for an absent mnemonic; Swift
    /// omits the key. Both must decode.
    func testBundleDecodesExplicitNullMnemonic() throws {
        let json = Data(#"{"privateKeyHex":"aabb","mnemonic":null}"#.utf8)

        let bundle = try JSONDecoder().decode(WalletKeyBundle.self, from: json)

        XCTAssertEqual(bundle.privateKeyHex, "aabb")
        XCTAssertNil(bundle.mnemonic)
    }

    // MARK: - Presence

    func testHasWalletFlipsWithStoreAndDelete() async throws {
        var has = await store.hasWallet
        XCTAssertFalse(has)

        try await store.store(WalletKeyBundle(privateKeyHex: "aabb"))
        has = await store.hasWallet
        XCTAssertTrue(has)

        try await store.delete()
        has = await store.hasWallet
        XCTAssertFalse(has)
    }

    func testDeleteLeavesNoKeychainItems() async throws {
        try await store.store(WalletKeyBundle(privateKeyHex: "aabb", mnemonic: "one two"))

        try await store.delete()

        XCTAssertNil(try keychain.get(account: WalletKeyAccount.bundleCiphertext))
        XCTAssertNil(try keychain.get(account: WalletKeyAccount.wrappedDataKey))
    }

    func testLoadWithoutAStoredWalletFailsClosed() async {
        await assertThrows(.notFound) { try await self.store.load(reason: "Unlock your wallet") }
    }

    /// Half a wallet is no wallet: a ciphertext whose wrapped key is gone can
    /// never be decrypted, so it must not look loadable.
    func testLoadWithOnlyTheCiphertextFailsClosed() async throws {
        try await store.store(WalletKeyBundle(privateKeyHex: "aabb"))
        try keychain.delete(account: WalletKeyAccount.wrappedDataKey)

        await assertThrows(.notFound) { try await self.store.load(reason: "Unlock your wallet") }
    }

    // MARK: - Integrity

    func testTamperedCiphertextFailsAsCorrupt() async throws {
        try await store.store(WalletKeyBundle(privateKeyHex: "aabb", mnemonic: "one two"))

        var ciphertext = try XCTUnwrap(try keychain.get(account: WalletKeyAccount.bundleCiphertext))
        let index = ciphertext.count / 2
        ciphertext[index] ^= 0x01
        try keychain.set(ciphertext, account: WalletKeyAccount.bundleCiphertext)

        await assertThrows(.corrupt) { try await self.store.load(reason: "Unlock your wallet") }
    }

    /// A data key from a different wrapping key must not open the bundle: the
    /// GCM tag check is what fails closed here, not a length or format check.
    func testCiphertextFromAnotherWalletFailsAsCorrupt() async throws {
        try await store.store(WalletKeyBundle(privateKeyHex: "aabb"))
        let foreignCiphertext = try XCTUnwrap(
            try keychain.get(account: WalletKeyAccount.bundleCiphertext)
        )

        try await store.delete()
        try await store.store(WalletKeyBundle(privateKeyHex: "ccdd"))
        try keychain.set(foreignCiphertext, account: WalletKeyAccount.bundleCiphertext)

        await assertThrows(.corrupt) { try await self.store.load(reason: "Unlock your wallet") }
    }

    func testSecondStoreReplacesTheFirst() async throws {
        let first = WalletKeyBundle(privateKeyHex: "aabb", mnemonic: "one two")
        let second = WalletKeyBundle(privateKeyHex: "ccdd")

        try await store.store(first)
        try await store.store(second)
        let loaded = try await store.load(reason: "Unlock your wallet")

        XCTAssertEqual(loaded, second)
    }

    /// Every write uses a fresh data key and nonce, so storing the same bundle
    /// twice must not produce the same bytes.
    func testEachStoreUsesFreshRandomness() async throws {
        let bundle = WalletKeyBundle(privateKeyHex: "aabb")

        try await store.store(bundle)
        let firstCiphertext = try keychain.get(account: WalletKeyAccount.bundleCiphertext)
        let firstWrappedKey = try keychain.get(account: WalletKeyAccount.wrappedDataKey)

        try await store.store(bundle)

        XCTAssertNotEqual(try keychain.get(account: WalletKeyAccount.bundleCiphertext), firstCiphertext)
        XCTAssertNotEqual(try keychain.get(account: WalletKeyAccount.wrappedDataKey), firstWrappedKey)
    }

    /// Nothing recognisable may sit in the Keychain in the clear.
    func testStoredCiphertextDoesNotContainThePlaintext() async throws {
        let bundle = WalletKeyBundle(privateKeyHex: "deadbeef", mnemonic: "one two")

        try await store.store(bundle)

        let ciphertext = try XCTUnwrap(try keychain.get(account: WalletKeyAccount.bundleCiphertext))
        XCTAssertNil(String(data: ciphertext, encoding: .utf8)?.range(of: "privateKeyHex"))
        XCTAssertFalse(ciphertext.range(of: Data("deadbeef".utf8)) != nil)
    }

    // MARK: - Hardware backing

    /// There is no Secure Enclave in the simulator, so the wrapping key is a
    /// software key and this must report false. The device counterpart in
    /// `WalletKeyStoreDeviceTests` asserts the opposite on real hardware.
    func testIsHardwareBackedIsFalseOnTheSimulator() async throws {
        try XCTSkipUnless(isSimulator, "Hardware backing is asserted by WalletKeyStoreDeviceTests")

        try await store.store(WalletKeyBundle(privateKeyHex: "aabb"))

        XCTAssertFalse(wrapper.isHardwareBacked)
    }

    func testDiagnosticsReportsItemPresenceWithoutKeyMaterial() async throws {
        try await store.store(WalletKeyBundle(privateKeyHex: "deadbeef"))

        let report = await store.diagnostics()

        XCTAssertTrue(report.contains("bundleCiphertext present: true"))
        XCTAssertTrue(report.contains("wrappedDataKey present: true"))
        XCTAssertFalse(report.contains("deadbeef"))
    }

    // MARK: - Helpers

    private var isSimulator: Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
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
