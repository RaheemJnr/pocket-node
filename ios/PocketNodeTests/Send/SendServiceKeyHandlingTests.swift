import PocketNodeCore
import XCTest

@testable import PocketNode

/// The hand-off of the private key from Swift to Kotlin, without a Keychain.
///
/// `SendService.send` cannot be exercised here: it needs the Secure Enclave,
/// a live pipeline and a node. What it does with the key once it has it is a
/// pure function, so that part is tested directly, and it is the part where a
/// mistake leaves a wallet's private key sitting in memory.
final class SendServiceKeyHandlingTests: XCTestCase {

    /// The copy lands and the source is scrubbed.
    func testTheKeyIsCopiedOutAndTheSwiftBufferZeroed() {
        var source: [UInt8] = [0xDE, 0xAD, 0xBE, 0xEF]

        let copied = SendService.moveKeyBytes(&source)

        XCTAssertEqual([UInt8](copied.data), [0xDE, 0xAD, 0xBE, 0xEF])
        XCTAssertEqual(source, [0, 0, 0, 0], "the Swift buffer must not survive the hand-off")
    }

    func testAnEmptyBufferIsHandledWithoutTrapping() {
        var source: [UInt8] = []

        let copied = SendService.moveKeyBytes(&source)

        XCTAssertEqual(copied.size, 0)
        XCTAssertTrue(source.isEmpty)
    }

    /// Why `send` drops its other reference before wiping.
    ///
    /// `Array.withUnsafeMutableBytes` triggers copy-on-write when the buffer is
    /// not uniquely referenced, so `memset_s` scrubs a fresh copy and the real
    /// bytes live on wherever the second reference is. This asserts that
    /// hazard rather than the fix, because the hazard is invisible from the
    /// call site and is exactly what a `let decoded` that outlived the wipe
    /// reintroduced.
    func testASecondReferenceIsWhatTheWipeCannotReach() {
        var source: [UInt8] = [0xDE, 0xAD, 0xBE, 0xEF]
        let shadow = source

        _ = SendService.moveKeyBytes(&source)

        XCTAssertEqual(source, [0, 0, 0, 0])
        XCTAssertEqual(
            shadow,
            [0xDE, 0xAD, 0xBE, 0xEF],
            "a second reference keeps the real key readable, which is why `send` releases its own first"
        )
    }

    /// A 32-byte scalar, the size a send actually hands over.
    func testARealSizedScalarIsFullyScrubbed() {
        var source = [UInt8](repeating: 0x7F, count: 32)

        let copied = SendService.moveKeyBytes(&source)

        XCTAssertEqual(copied.size, 32)
        XCTAssertEqual([UInt8](copied.data), [UInt8](repeating: 0x7F, count: 32))
        XCTAssertTrue(source.allSatisfy { $0 == 0 })
    }
}
