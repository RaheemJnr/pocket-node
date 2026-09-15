import Foundation
import XCTest

@testable import PocketNode

/// The envelope parser on its own, with no Keychain and no Enclave in the way.
///
/// Every case here is a buffer that must not be trusted: the parser reads
/// lengths out of bytes an attacker with the Keychain could have written, and
/// each one is used to slice the buffer. A length that is checked after it is
/// used is a crash at best.
final class WalletKeyEnvelopeTests: XCTestCase {
    private let wrappedKey = Data(repeating: 0xAB, count: 101)
    private let ciphertext = Data(repeating: 0xCD, count: 64)

    // MARK: - Round trip

    func testEncodeThenDecodeReturnsBothHalves() throws {
        let envelope = WalletKeyEnvelope.encode(wrappedDataKey: wrappedKey, ciphertext: ciphertext)

        let decoded = try WalletKeyEnvelope.decode(envelope)

        XCTAssertEqual(decoded.wrappedDataKey, wrappedKey)
        XCTAssertEqual(decoded.ciphertext, ciphertext)
    }

    func testEncodedLayoutIsVersionThenBigEndianLength() {
        let envelope = WalletKeyEnvelope.encode(wrappedDataKey: wrappedKey, ciphertext: ciphertext)

        XCTAssertEqual(envelope[0], WalletKeyEnvelope.version)
        XCTAssertEqual(Array(envelope[1..<5]), [0x00, 0x00, 0x00, 0x65])  // 101
        XCTAssertEqual(envelope.count, 5 + wrappedKey.count + ciphertext.count)
    }

    /// A `Data` handed back by the Keychain, or sliced by a caller, need not
    /// start at index 0. Indexing it as though it does reads the wrong bytes or
    /// traps, so the parser has to rebase first.
    func testDecodeAcceptsASliceWithANonZeroStartIndex() throws {
        var padded = Data([0xFF, 0xFF, 0xFF])
        padded.append(WalletKeyEnvelope.encode(wrappedDataKey: wrappedKey, ciphertext: ciphertext))
        let slice = padded[3...]
        XCTAssertNotEqual(slice.startIndex, 0)

        let decoded = try WalletKeyEnvelope.decode(slice)

        XCTAssertEqual(decoded.wrappedDataKey, wrappedKey)
        XCTAssertEqual(decoded.ciphertext, ciphertext)
    }

    // MARK: - Malformed buffers

    func testDecodeRejectsEmptyData() {
        assertCorrupt(Data())
    }

    func testDecodeRejectsASingleByte() {
        assertCorrupt(Data([WalletKeyEnvelope.version]))
    }

    /// Header and nothing else: there is no wrapped key and no ciphertext.
    func testDecodeRejectsAHeaderWithNoBody() {
        assertCorrupt(Data([WalletKeyEnvelope.version, 0x00, 0x00, 0x00, 0x04]))
    }

    func testDecodeRejectsZeroLength() {
        var envelope = WalletKeyEnvelope.encode(wrappedDataKey: wrappedKey, ciphertext: ciphertext)
        envelope.replaceSubrange(1..<5, with: [0x00, 0x00, 0x00, 0x00])

        assertCorrupt(envelope)
    }

    /// Below the 4096 ceiling but past the end of this buffer, so the bounds
    /// check against the actual byte count is the one that has to fire.
    func testDecodeRejectsALengthPastTheEndOfTheBuffer() {
        var envelope = WalletKeyEnvelope.encode(wrappedDataKey: wrappedKey, ciphertext: ciphertext)
        envelope.replaceSubrange(1..<5, with: [0x00, 0x00, 0x03, 0xE8])  // 1000 > 165 remaining

        assertCorrupt(envelope)
    }

    func testDecodeRejectsALengthAboveTheCeiling() {
        var envelope = WalletKeyEnvelope.encode(wrappedDataKey: wrappedKey, ciphertext: ciphertext)
        envelope.replaceSubrange(1..<5, with: [0x00, 0x01, 0x00, 0x01])  // 65537

        assertCorrupt(envelope)
    }

    /// The wrapped key claiming the whole body leaves no ciphertext at all.
    func testDecodeRejectsALengthEqualToTheRemainingBuffer() {
        let body = wrappedKey.count + ciphertext.count
        var envelope = WalletKeyEnvelope.encode(wrappedDataKey: wrappedKey, ciphertext: ciphertext)
        envelope.replaceSubrange(1..<5, with: withUnsafeBytes(of: UInt32(body).bigEndian) { Array($0) })

        assertCorrupt(envelope)
    }

    /// One byte short of a 12-byte nonce plus a 16-byte tag cannot be a box.
    /// Built by hand because `encode` refuses to produce it.
    func testDecodeRejectsACiphertextShorterThanAnAESGCMBox() {
        var envelope = Data([WalletKeyEnvelope.version])
        envelope.append(contentsOf: withUnsafeBytes(of: UInt32(wrappedKey.count).bigEndian) { Array($0) })
        envelope.append(wrappedKey)
        envelope.append(Data(repeating: 0xCD, count: 27))

        assertCorrupt(envelope)
    }

    func testDecodeAcceptsTheSmallestPossibleCiphertext() throws {
        let envelope = WalletKeyEnvelope.encode(
            wrappedDataKey: wrappedKey,
            ciphertext: Data(repeating: 0xCD, count: 28)
        )

        let decoded = try WalletKeyEnvelope.decode(envelope)

        XCTAssertEqual(decoded.ciphertext.count, 28)
    }

    func testDecodeRejectsAnUnknownVersion() {
        var envelope = WalletKeyEnvelope.encode(wrappedDataKey: wrappedKey, ciphertext: ciphertext)
        envelope[0] = WalletKeyEnvelope.version + 1

        assertCorrupt(envelope)
    }

    private func assertCorrupt(
        _ data: Data,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        do {
            _ = try WalletKeyEnvelope.decode(data)
            XCTFail("expected .corrupt but the envelope parsed", file: file, line: line)
        } catch let error as WalletKeyStoreError {
            XCTAssertEqual(error, .corrupt, file: file, line: line)
        } catch {
            XCTFail("expected .corrupt but got \(error)", file: file, line: line)
        }
    }
}
