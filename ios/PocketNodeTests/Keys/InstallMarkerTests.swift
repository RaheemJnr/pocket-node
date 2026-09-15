import XCTest

@testable import PocketNode

/// The reinstall wipe, driven directly rather than through `AppContainer`, so no
/// light client starts up during the test.
final class InstallMarkerTests: XCTestCase {
    private let suiteName = "com.rjnr.pocketnode.tests.installMarker"
    private let service = "com.rjnr.pocketnode.tests.installMarker.keys"
    private let tag = "com.rjnr.pocketnode.tests.installMarker.wrapper"

    private var defaults: UserDefaults!
    private var keychain: FailingKeyValueStore!
    private var wrapper: StubKeyWrapper!
    private var marker: InstallMarker!

    override func setUp() {
        super.setUp()
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
        defaults = UserDefaults(suiteName: suiteName)
        keychain = FailingKeyValueStore(service: service)
        wrapper = StubKeyWrapper(tag: tag)
        marker = InstallMarker(defaults: defaults)
        try? keychain.deleteAll()
        try? wrapper.deleteKey()
    }

    override func tearDown() {
        keychain.failDeletes(false)
        wrapper.fail(with: nil)
        try? keychain.deleteAll()
        try? wrapper.deleteKey()
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        keychain = nil
        wrapper = nil
        marker = nil
        super.tearDown()
    }

    private func makeStore() -> WalletKeyStore {
        WalletKeyStore(keychain: keychain, wrapper: wrapper)
    }

    /// Keychain items outlive the app, `UserDefaults` does not. An install with
    /// items but no marker is therefore a reinstall over someone else's wallet.
    func testFreshInstallWipesLeftoverItems() async throws {
        let store = makeStore()
        try await store.store(WalletKeyBundle(privateKeyHex: "aabb", mnemonic: "one two"))
        let hadWallet = await store.hasWallet
        XCTAssertTrue(hadWallet)

        let wiped = marker.wipeIfFreshInstall(keychain: keychain, wrapper: wrapper)

        XCTAssertTrue(wiped)
        XCTAssertNil(try keychain.get(account: WalletKeyAccount.envelope))
        XCTAssertFalse(wrapper.hasKey)
        let hasWallet = await store.hasWallet
        XCTAssertFalse(hasWallet)
    }

    /// The marker is a version, not a flag, so a later release can re-run the
    /// wipe deliberately by bumping it.
    func testFreshInstallRecordsTheCurrentMarkerVersion() {
        XCTAssertFalse(marker.isRecorded)
        XCTAssertEqual(marker.storedVersion, 0)

        marker.wipeIfFreshInstall(keychain: keychain, wrapper: wrapper)

        XCTAssertTrue(marker.isRecorded)
        XCTAssertEqual(marker.storedVersion, InstallMarker.currentVersion)
        XCTAssertEqual(defaults.integer(forKey: InstallMarker.defaultsKey), InstallMarker.currentVersion)
    }

    /// Nothing stored means nothing to delete: the marker is recorded and the
    /// wipe reports that it did not run.
    func testFreshInstallWithNothingStoredDeletesNothing() {
        let wiped = marker.wipeIfFreshInstall(keychain: keychain, wrapper: wrapper)

        XCTAssertFalse(wiped)
        XCTAssertTrue(marker.isRecorded)
    }

    /// Every launch after the first must leave the wallet alone.
    func testSubsequentLaunchesKeepTheWallet() async throws {
        marker.wipeIfFreshInstall(keychain: keychain, wrapper: wrapper)

        let store = makeStore()
        let bundle = WalletKeyBundle(privateKeyHex: "aabb", mnemonic: "one two")
        try await store.store(bundle)

        let wiped = marker.wipeIfFreshInstall(keychain: keychain, wrapper: wrapper)

        XCTAssertFalse(wiped)
        let loaded = try await store.load(reason: "Unlock your wallet")
        XCTAssertEqual(loaded, bundle)
    }

    /// A failed wipe must not be recorded as done. Recording it would leave the
    /// previous install's wallet in place with nothing left to clean it up.
    func testFailedWipeDoesNotRecordTheMarker() async throws {
        let store = makeStore()
        try await store.store(WalletKeyBundle(privateKeyHex: "aabb"))
        keychain.failDeletes(true)

        let wiped = marker.wipeIfFreshInstall(keychain: keychain, wrapper: wrapper)

        XCTAssertFalse(wiped)
        XCTAssertFalse(marker.isRecorded)
        XCTAssertNotNil(try keychain.get(account: WalletKeyAccount.envelope))
    }

    /// And the retry on the next launch has to work.
    func testWipeIsRetriedAfterAFailure() async throws {
        let store = makeStore()
        try await store.store(WalletKeyBundle(privateKeyHex: "aabb"))
        keychain.failDeletes(true)
        marker.wipeIfFreshInstall(keychain: keychain, wrapper: wrapper)

        keychain.failDeletes(false)
        let wiped = marker.wipeIfFreshInstall(keychain: keychain, wrapper: wrapper)

        XCTAssertTrue(wiped)
        XCTAssertTrue(marker.isRecorded)
        XCTAssertNil(try keychain.get(account: WalletKeyAccount.envelope))
    }

    /// A wrapping key with no envelope is still someone else's leftover.
    func testWipeRunsWhenOnlyTheWrappingKeyRemains() async throws {
        _ = try wrapper.wrap(Data(repeating: 0x01, count: 32))
        XCTAssertTrue(wrapper.hasKey)

        let wiped = marker.wipeIfFreshInstall(keychain: keychain, wrapper: wrapper)

        XCTAssertTrue(wiped)
        XCTAssertFalse(wrapper.hasKey)
    }
}
