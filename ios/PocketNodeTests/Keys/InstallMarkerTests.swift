import XCTest

@testable import PocketNode

/// The reinstall wipe, driven directly rather than through `AppContainer`, so no
/// light client starts up during the test.
final class InstallMarkerTests: XCTestCase {
    private let suiteName = "com.rjnr.pocketnode.tests.installMarker"
    private let service = "com.rjnr.pocketnode.tests.installMarker.keys"
    private let tag = "com.rjnr.pocketnode.tests.installMarker.wrapper"

    private var defaults: UserDefaults!
    private var keychain: KeychainStore!
    private var wrapper: SecureEnclaveKeyWrapper!

    override func setUp() {
        super.setUp()
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
        defaults = UserDefaults(suiteName: suiteName)
        keychain = KeychainStore(service: service)
        wrapper = SecureEnclaveKeyWrapper(tag: tag)
        try? keychain.deleteAll()
        try? wrapper.deleteKey()
    }

    override func tearDown() {
        try? keychain.deleteAll()
        try? wrapper.deleteKey()
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        keychain = nil
        wrapper = nil
        super.tearDown()
    }

    /// Keychain items outlive the app, `UserDefaults` does not. An install with
    /// items but no marker is therefore a reinstall over someone else's wallet.
    func testFreshInstallWipesLeftoverItems() async throws {
        let store = WalletKeyStore(keychain: keychain, wrapper: wrapper)
        try await store.store(WalletKeyBundle(privateKeyHex: "aabb", mnemonic: "one two"))
        let hadWallet = await store.hasWallet
        XCTAssertTrue(hadWallet)

        let wiped = InstallMarker(defaults: defaults).wipeIfFreshInstall(
            keychain: keychain,
            wrapper: wrapper
        )

        XCTAssertTrue(wiped)
        XCTAssertNil(try keychain.get(account: WalletKeyAccount.bundleCiphertext))
        XCTAssertNil(try keychain.get(account: WalletKeyAccount.wrappedDataKey))
        let hasWallet = await store.hasWallet
        XCTAssertFalse(hasWallet)
    }

    func testFreshInstallRecordsTheMarker() {
        let marker = InstallMarker(defaults: defaults)
        XCTAssertFalse(marker.isRecorded)

        marker.wipeIfFreshInstall(keychain: keychain, wrapper: wrapper)

        XCTAssertTrue(marker.isRecorded)
        XCTAssertTrue(defaults.bool(forKey: InstallMarker.defaultsKey))
    }

    /// Every launch after the first must leave the wallet alone.
    func testSubsequentLaunchesKeepTheWallet() async throws {
        let marker = InstallMarker(defaults: defaults)
        marker.wipeIfFreshInstall(keychain: keychain, wrapper: wrapper)

        let store = WalletKeyStore(keychain: keychain, wrapper: wrapper)
        let bundle = WalletKeyBundle(privateKeyHex: "aabb", mnemonic: "one two")
        try await store.store(bundle)

        let wiped = marker.wipeIfFreshInstall(keychain: keychain, wrapper: wrapper)

        XCTAssertFalse(wiped)
        let loaded = try await store.load(reason: "Unlock your wallet")
        XCTAssertEqual(loaded, bundle)
    }
}
