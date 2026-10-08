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
    /// Two more test-only Enclave keys for the wrong-key test.
    private let tagA = "com.rjnr.pocketnode.tests.device.wrapper.a"
    private let tagB = "com.rjnr.pocketnode.tests.device.wrapper.b"

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
        try? SecureEnclaveKeyWrapper(tag: tagA).deleteKey()
        try? SecureEnclaveKeyWrapper(tag: tagB).deleteKey()
        #endif
    }

    override func tearDown() {
        try? keychain?.deleteAll()
        try? wrapper?.deleteKey()
        try? SecureEnclaveKeyWrapper(tag: tagA).deleteKey()
        try? SecureEnclaveKeyWrapper(tag: tagB).deleteKey()
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
        let stored = try XCTUnwrap(try keychain.get(account: WalletKeyAccount.envelope))
        XCTAssertEqual(stored.first, WalletKeyEnvelope.versionWithKeyLabel, "a fresh store writes version 2")
        XCTAssertEqual(try WalletKeyEnvelope.decode(stored).keyLabel, label, "the envelope records this key's label")
        let health = await store.keyHealth
        XCTAssertEqual(health, .usable)
    }

    /// A data key wrapped by Enclave key A, unwrapped with Enclave key B: the
    /// ECIES decrypt must refuse it the same way every time, and that way must
    /// be one `classifyDecryption` turns into `.decryptionFailed`
    /// (`errSecDecode` or `errSecParam`), which is what lets a wrong key count
    /// as proof the keys are unusable. The raw status of each attempt is
    /// recorded as an activity so the run shows what the Enclave returns.
    ///
    /// Interactive: both keys use the app's real access control
    /// (`biometryCurrentSet OR devicePasscode`), so each unwrap with B and the
    /// final unwrap with A can ask for Face ID or the passcode. Run it with the
    /// device unlocked and Face ID or the passcode available.
    ///
    /// `@MainActor` because `XCTContext.runActivity` is main-actor isolated in
    /// the Xcode 16 SDK CI builds with (Xcode 26 relaxed it).
    @MainActor
    func testAWrongSecureEnclaveKeyFailsDecryptTheSameWayEveryTime() throws {
        let keyA = SecureEnclaveKeyWrapper(tag: tagA)
        let keyB = SecureEnclaveKeyWrapper(tag: tagB)
        let dataKey = Data((0..<32).map { UInt8($0) })

        let wrappedByA = try keyA.wrap(dataKey)
        _ = try keyB.wrap(Data(repeating: 0x5A, count: 32))
        XCTAssertTrue(keyA.isHardwareBacked, "key A is not in the Secure Enclave")
        XCTAssertTrue(keyB.isHardwareBacked, "key B is not in the Secure Enclave")

        var statuses: [OSStatus] = []
        for attempt in 1...5 {
            XCTContext.runActivity(named: "unwrap with key B, attempt \(attempt)") { activity in
                do {
                    _ = try keyB.unwrap(wrappedByA, reason: "Pocket Node test: wrong key, attempt \(attempt)")
                    XCTFail("attempt \(attempt): key B unwrapped a data key wrapped by key A")
                } catch let error as KeyWrapperError {
                    let note = XCTAttachment(string: "attempt \(attempt): \(error)")
                    note.lifetime = .keepAlways
                    activity.add(note)
                    print("WRONG-KEY attempt \(attempt): \(error)")
                    guard case .decryptionFailed(let status) = error else {
                        XCTFail("attempt \(attempt): \(error) is not decryptionFailed (errSecDecode or errSecParam)")
                        return
                    }
                    statuses.append(status)
                } catch {
                    XCTFail("attempt \(attempt): unexpected \(error)")
                }
            }
        }

        XCTAssertEqual(statuses.count, 5, "every attempt must map to decryptionFailed; statuses: \(statuses)")
        XCTAssertEqual(Set(statuses).count, 1, "the Enclave answered differently across attempts: \(statuses)")
        print("WRONG-KEY statuses: \(statuses)")

        let unwrapped = try keyA.unwrap(wrappedByA, reason: "Pocket Node test: right key")
        XCTAssertEqual(unwrapped, dataKey)
    }
}
