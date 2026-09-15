import XCTest
@testable import PocketNode

/// Times the app PIN KDF at the production cost on real hardware. The M2
/// acceptance asks for Argon2id at 64 MiB / t=3 / p=4 to finish in under
/// 1.5 s on an iPhone; the simulator runs a debug Kotlin/Native binary and
/// says nothing useful about that, so this test only runs on a device.
@MainActor
final class PinServiceDeviceTests: XCTestCase {
    private let service = "com.rjnr.pocketnode.pin.devicetest"

    override func setUpWithError() throws {
        #if targetEnvironment(simulator)
        throw XCTSkip("Argon2id timing is only meaningful on a physical iPhone.")
        #endif
        try KeychainStore(service: service).deleteAll()
    }

    override func tearDown() {
        try? KeychainStore(service: service).deleteAll()
    }

    func testArgon2idAtProductionCostStaysUnderTheBudget() async throws {
        let pin = PinService(keychain: KeychainStore(service: service), cost: .production)

        let setStart = Date()
        try await pin.setPin("123456")
        let setMs = Int(Date().timeIntervalSince(setStart) * 1000)

        let verifyStart = Date()
        let ok = try await pin.verify("123456")
        let verifyMs = Int(Date().timeIntervalSince(verifyStart) * 1000)

        print("Argon2id device timing: setPin \(setMs) ms, verify \(verifyMs) ms")
        XCTAssertTrue(ok)
        XCTAssertLessThan(verifyMs, 1500, "verify took \(verifyMs) ms at production cost")
    }
}
