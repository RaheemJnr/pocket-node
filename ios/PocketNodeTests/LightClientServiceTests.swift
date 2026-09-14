import CkbLightClient
import XCTest

@testable import PocketNode

final class LightClientServiceTests: XCTestCase {
    /// Nothing has called `initLightClient` in this process, so the bridge must
    /// report the INIT state rather than trap.
    func testStatusIsInitBeforeInitialization() {
        XCTAssertEqual(getStatus(), 0)
    }

    func testHexParsingDecodesPrefixedValue() {
        XCTAssertEqual(Hex.parse("0x1a"), 26)
        XCTAssertEqual(Hex.parse("1a"), 26)
        XCTAssertNil(Hex.parse("0x"))
        XCTAssertNil(Hex.parse("nope"))
    }
}
