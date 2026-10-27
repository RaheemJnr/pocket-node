import Foundation
import PocketNodeCore

@testable import PocketNode

/// A `SendServicing` whose every answer the test decides.
///
/// The real one owns a database, a live pipeline and the Secure Enclave, none
/// of which a test about which alert is on screen has any business touching.
/// The counters are here so a test can assert that nothing was signed: the
/// whole design of the screen is that ``sendCalls`` stays at zero until
/// Confirm.
@MainActor
final class FakeSendService: SendServicing {

    var status = SendStatus()
    var walletId: String? = "test-wallet"
    var fromAddress: String? = SendFixtures.testnetAddress
    var network: NetworkType = NetworkType.testnet
    var availableShannons: Int64 = 1_000 * SendFixtures.ckb

    /// What ``preview(from:recipients:)`` answers, or throws.
    var previewFee: Int64 = 100_000
    var previewError: Error?

    /// What the destination-aware MAX request answers.
    var maxSendable: Int64 = 0
    var maxError: Error?
    var onMax: (@MainActor () async -> Void)?

    /// What ``send(from:to:amount:expectedFee:)`` answers.
    var sendResult: Result<String, SendError> = .success(SendFixtures.txHash)
    var retryResult: Result<String, SendError> = .success(SendFixtures.txHash)

    /// Run while ``preview(from:recipients:)`` is suspended, so a test can
    /// re-enter the view model at exactly the point a second tap would.
    var onPreview: (@MainActor () async -> Void)?

    /// The same, for ``send(from:to:amount:expectedFee:)``.
    var onSend: (@MainActor () async -> Void)?

    private(set) var previewCalls: [(from: String, recipients: [RecipientOutput])] = []
    private(set) var sendCalls: [(from: String, to: String, amount: Int64, expectedFee: Int64)] = []
    private(set) var retryCalls: [String] = []
    private(set) var dismissCalls = 0

    var feeEstimator: ((Int, [String], Bool) -> Int64)?
    func estimateFee(inputCount: Int, recipientAddresses: [String], includeChange: Bool) -> Int64 {
        if let feeEstimator { return feeEstimator(inputCount, recipientAddresses, includeChange) }
        // A stand-in for the real molecule accounting, shaped like it: one
        // output costs less than two, which is what the dust-warning heuristic
        // turns on.
        return Int64(recipientAddresses.count + (includeChange ? 1 : 0)) * 50_000
    }

    private(set) var maxRecipients: [String?] = []
    func maxSendableShannons(recipientAddress: String?) async throws -> Int64 {
        maxRecipients.append(recipientAddress)
        await onMax?()
        if let maxError { throw maxError }
        return maxSendable
    }

    func preview(from: String, recipients: [RecipientOutput]) async throws -> TransferPlan {
        previewCalls.append((from, recipients))
        await onPreview?()
        if let previewError { throw previewError }
        let amount = recipients.reduce(Int64(0)) { $0 + $1.amountShannons }
        return TransferPlan(
            selectedCells: [],
            totalInput: amount + previewFee,
            totalRecipientAmount: amount,
            feeShannons: previewFee,
            changeShannons: 0
        )
    }

    func send(
        from: String,
        to: String,
        amount: Int64,
        expectedFee: Int64
    ) async -> Result<String, SendError> {
        sendCalls.append((from, to, amount, expectedFee))
        await onSend?()
        return sendResult
    }

    func sendReviewed(_ review: ReviewedTransfer, isCurrent: @escaping @Sendable () -> Bool) async -> Result<String, SendError> {
        guard isCurrent() else { return .failure(.cancelled) }
        return await send(from: review.fromAddress, to: review.recipients[0].address,
            amount: review.amountShannons, expectedFee: review.feeShannons)
    }

    func retry(txHash: String) async -> Result<String, SendError> {
        retryCalls.append(txHash)
        return retryResult
    }

    func dismissStatus() {
        dismissCalls += 1
        status = SendStatus()
    }
}

enum SendFixtures {
    static let ckb: Int64 = 100_000_000

    /// The pinned testnet address from `WalletCreatorTests`, so nothing here
    /// invents an address that would not decode.
    static let testnetAddress =
        "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqenlarn"

    /// The same wallet on mainnet.
    static let mainnetAddress =
        "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqhp5jft"

    static let txHash =
        "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
}
