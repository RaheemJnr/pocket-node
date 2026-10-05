import XCTest

@testable import PocketNode

/// A PIN left behind with no wallet (a fresh-install PIN wipe that failed after
/// the install marker was recorded) must not lock the next onboarding out of
/// setting a PIN, and must never be cleared while a wallet might still exist.
@MainActor
final class OrphanedPinTests: XCTestCase {
    private let keyService = "com.rjnr.pocketnode.tests.orphan.keys"
    private let pinService = "com.rjnr.pocketnode.tests.orphan.pin"
    private let tag = "com.rjnr.pocketnode.tests.orphan.wrapper"
    private let suiteName = "com.rjnr.pocketnode.tests.orphan.prefs"

    private var keyKeychain: KeychainStore!
    private var pinKeychain: KeychainStore!
    private var wrapper: SecureEnclaveKeyWrapper!
    private var directory: URL!
    private var walletStore: WalletStore!
    private var defaults: UserDefaults!

    override func setUp() async throws {
        keyKeychain = KeychainStore(service: keyService)
        pinKeychain = KeychainStore(service: pinService)
        wrapper = SecureEnclaveKeyWrapper(tag: tag)
        try? keyKeychain.deleteAll()
        try? pinKeychain.deleteAll()
        try? wrapper.deleteKey()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(keyService)-\(UUID().uuidString)")
        walletStore = WalletStore(directory: directory)
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() async throws {
        try? keyKeychain.deleteAll()
        try? pinKeychain.deleteAll()
        try? wrapper.deleteKey()
        try? FileManager.default.removeItem(at: directory)
        defaults.removePersistentDomain(forName: suiteName)
        keyKeychain = nil
        pinKeychain = nil
        wrapper = nil
        directory = nil
        walletStore = nil
        defaults = nil
    }

    private var preferences: UserDefaultsPreferences {
        UserDefaultsPreferences(defaults: defaults)
    }

    private func makeAuth() -> AuthService {
        AuthService(
            pin: PinService(keychain: pinKeychain, cost: .testing),
            biometrics: StubBiometrics(availability: .unavailable),
            preferences: preferences
        )
    }

    private func storeOldPin() async throws {
        try await PinService(keychain: pinKeychain, cost: .testing).setPin("111111")
        XCTAssertEqual(KeychainPinStore.pinPresence(keychain: pinKeychain), .present)
    }

    private func removeIfOrphaned(keyStore: (any KeyValueStoring)? = nil, pinStore: (any KeyValueStoring)? = nil) -> Bool {
        OrphanedPin.removeIfOrphaned(
            walletMetadataExists: walletStore.hasWallet,
            keyKeychain: keyStore ?? keyKeychain,
            pinKeychain: pinStore ?? pinKeychain,
            preferences: preferences
        )
    }

    // MARK: - Launch cleanup

    func testAPinWithNoWalletIsRemoved() async throws {
        try await storeOldPin()

        XCTAssertTrue(removeIfOrphaned())

        XCTAssertEqual(KeychainPinStore.pinPresence(keychain: pinKeychain), .absent)
        XCTAssertEqual(makeAuth().state, .noPin, "the next launch starts with no PIN to answer")
    }

    func testAPinInFrontOfWalletMetadataIsUntouched() async throws {
        try await storeOldPin()
        try walletStore.save(
            WalletRecord(
                id: "w", name: "n", type: WalletCreator.typeMnemonic,
                mainnetAddress: "ckb1", testnetAddress: "ckt1", createdAt: 0
            )
        )

        XCTAssertFalse(removeIfOrphaned())
        XCTAssertEqual(KeychainPinStore.pinPresence(keychain: pinKeychain), .present)
    }

    func testAPinInFrontOfAKeyEnvelopeIsUntouched() async throws {
        try await storeOldPin()
        try await WalletKeyStore(keychain: keyKeychain, wrapper: wrapper)
            .store(WalletKeyBundle(privateKeyHex: WalletCreatorTests.testPrivateKeyHex, mnemonic: nil))

        XCTAssertFalse(removeIfOrphaned())
        XCTAssertEqual(KeychainPinStore.pinPresence(keychain: pinKeychain), .present)
    }

    /// A Keychain that cannot be read (a launch before the first device
    /// unlock) is not proof there is no wallet.
    func testAnUnreadableKeyStoreLeavesThePinAlone() async throws {
        try await storeOldPin()

        XCTAssertFalse(removeIfOrphaned(keyStore: UnreadableKeyValueStore(service: keyService)))
        XCTAssertEqual(KeychainPinStore.pinPresence(keychain: pinKeychain), .present)
    }

    func testAnUnreadablePinStoreIsLeftAlone() async throws {
        try await storeOldPin()

        XCTAssertFalse(removeIfOrphaned(pinStore: UnreadableKeyValueStore(service: pinService)))
        XCTAssertEqual(KeychainPinStore.pinPresence(keychain: pinKeychain), .present)
    }

    func testRemovingAnOrphanedPinAlsoDropsTheBiometricOptIn() async throws {
        try await storeOldPin()
        preferences.isBiometricEnabled = true

        XCTAssertTrue(removeIfOrphaned())

        XCTAssertFalse(preferences.isBiometricEnabled)
    }

    /// A delete the Keychain refuses is reported, not assumed: the caller must
    /// not go on as if the PIN were gone.
    func testARefusedDeleteIsReportedAndLeavesThePinAndOptIn() async throws {
        try await storeOldPin()
        preferences.isBiometricEnabled = true
        let refusing = FailingKeyValueStore(service: pinService)
        refusing.failDeletes(true)

        XCTAssertFalse(removeIfOrphaned(pinStore: refusing))

        XCTAssertEqual(KeychainPinStore.pinPresence(keychain: pinKeychain), .present)
        XCTAssertTrue(preferences.isBiometricEnabled)
    }
}
