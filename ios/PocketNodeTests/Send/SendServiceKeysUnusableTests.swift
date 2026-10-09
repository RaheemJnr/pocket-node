import XCTest

@testable import PocketNode

/// A send whose key read proves the keys unusable tells the root at once.
///
/// `SendService.send` cannot be driven here (it needs the Secure Enclave, a
/// live pipeline and a node; see `SendServiceKeyHandlingTests`), so its key
/// failure branch goes through `keyLoadFailure`, which is tested directly.
/// Before it, a send that retired the envelope said "Could not read this
/// wallet's keys" and left the user on the Send screen; only the phrase
/// reveal raised the signal that routes to the restore.
@MainActor
final class SendServiceKeysUnusableTests: XCTestCase {

    private func signalCount(for error: Error) -> (Int, SendError) {
        var count = 0
        let mapped = SendService.keyLoadFailure(error, onKeysUnusable: { count += 1 })
        return (count, mapped)
    }

    func testAnInvalidatedKeyRaisesTheSignal() {
        let (count, mapped) = signalCount(for: WalletKeyStoreError.keyInvalidated)

        XCTAssertEqual(count, 1)
        XCTAssertFalse(mapped.isCancellation)
        XCTAssertEqual(
            mapped.message,
            "Could not read this wallet's keys. Restore it from your recovery phrase."
        )
    }

    func testARefusedDecryptRaisesTheSignal() {
        XCTAssertEqual(signalCount(for: WalletKeyStoreError.keyRefused).0, 1)
    }

    func testACorruptEnvelopeRaisesTheSignal() {
        XCTAssertEqual(signalCount(for: WalletKeyStoreError.corrupt).0, 1)
    }

    /// Worth another try, so no reroute: the same allowlist the backup uses.
    func testFailuresThatProveNothingStayOnTheSendScreen() {
        let retryable: [Error] = [
            WalletKeyStoreError.authenticationFailed,
            WalletKeyStoreError.notFound,
            WalletKeyStoreError.keychain(-25_308),
            NSError(domain: "CryptoTokenKit", code: -3),
        ]
        for error in retryable {
            let (count, mapped) = signalCount(for: error)
            XCTAssertEqual(count, 0, "\(error)")
            XCTAssertEqual(mapped.message.isEmpty, false, "\(error) is still reported")
        }
    }
}
