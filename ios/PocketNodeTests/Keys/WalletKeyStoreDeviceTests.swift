import XCTest

@testable import PocketNode

/// The hardware half of the key-store acceptance, skipped on the simulator.
///
/// Run it on a physical iPhone with a passcode and Face ID or Touch ID enrolled:
///
/// ```
/// cd ios && xcodegen generate
/// xcodebuild -project PocketNode.xcodeproj -scheme PocketNode \
///   -destination 'platform=iOS,id=<device-udid>' \
///   -only-testing:PocketNodeTests/WalletKeyStoreDeviceTests test
/// ```
///
/// `testLoadPromptsAndReportsSecureEnclaveBacking` is interactive: the device
/// shows the Face ID sheet with our reason text and the test waits for it. That
/// prompt, plus the printed diagnostics line reading `hardwareBacked: true`, is
/// the evidence the issue asks for.
// TODO(device): confirm behaviour after biometric re-enrolment. The access
// control is `biometryCurrentSet OR devicePasscode`, so the passcode branch
// most likely keeps the key valid when a face or finger is added, which would
// mean `.keyInvalidated` is reachable only when the key is lost some other way.
// Enrol a new face on the test device, rerun this suite, and record whether
// `hasKey` and `load` still succeed.
final class WalletKeyStoreDeviceTests: XCTestCase {
    private let service = "com.rjnr.pocketnode.tests.device.keys"
    private let tag = "com.rjnr.pocketnode.tests.device.wrapper"

    private var keychain: KeychainStore!
    private var wrapper: SecureEnclaveKeyWrapper!
    private var store: WalletKeyStore!

    override func setUpWithError() throws {
        try super.setUpWithError()
        #if targetEnvironment(simulator)
        throw XCTSkip("There is no Secure Enclave in the simulator; run this on a physical iPhone.")
        #else
        keychain = KeychainStore(service: service)
        wrapper = SecureEnclaveKeyWrapper(tag: tag)
        store = WalletKeyStore(keychain: keychain, wrapper: wrapper)
        try? keychain.deleteAll()
        try? wrapper.deleteKey()
        #endif
    }

    override func tearDown() {
        try? keychain?.deleteAll()
        try? wrapper?.deleteKey()
        store = nil
        wrapper = nil
        keychain = nil
        super.tearDown()
    }

    /// Stores a bundle, proves the wrapping key really is in the Enclave, then
    /// loads it back through a real biometric prompt.
    func testLoadPromptsAndReportsSecureEnclaveBacking() async throws {
        let bundle = WalletKeyBundle(
            privateKeyHex: "9f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8",
            mnemonic: "abandon abandon abandon abandon abandon abandon "
                + "abandon abandon abandon abandon abandon about"
        )

        try await store.store(bundle)
        XCTAssertTrue(wrapper.isHardwareBacked, "the wrapping key is not in the Secure Enclave")

        let report = await store.diagnostics()
        print(report)
        XCTAssertTrue(report.contains("hardwareBacked: true"))
        XCTAssertTrue(report.contains("envelope present: true"))

        let loaded = try await store.load(reason: "Unlock your Pocket Node wallet")
        XCTAssertEqual(loaded, bundle)
    }

    /// The prompt-free probe on a real Enclave key. Both lookups carry an
    /// `LAContext` with `interactionNotAllowed`, so a lookup that wanted UI
    /// would come back `.unknown` instead of showing it: `.present` and a
    /// label are the proof that none was needed. No sheet should appear
    /// while this runs.
    func testKeyPresenceAndLabelReadWithoutUI() async throws {
        try await store.store(WalletKeyBundle(privateKeyHex: String(repeating: "1", count: 64)))
        XCTAssertTrue(wrapper.isHardwareBacked)

        XCTAssertEqual(wrapper.keyPresence, .present)
        guard case .label(let label) = wrapper.keyLabel else {
            return XCTFail("the label must read without UI")
        }
        XCTAssertEqual(label.count, 20)
        let health = await store.keyHealth
        XCTAssertEqual(health, .usable)
    }
}
