import Foundation
import PocketNodeCore
import XCTest

@testable import PocketNode

/// Restore hint files cross platforms (#559): files the Android app exported
/// verify here, the shared known-answer vector holds from Swift, and every
/// rejection maps to the copy the user reads.
final class RestoreHintInteropTests: XCTestCase {

    // MARK: - Android-exported files

    func testAnAndroidExportVerifiesWithTheAbandonPhraseOnTestnet() throws {
        let text = try RestoreHintFixtures.text(RestoreHintFixtures.androidExport)

        let result = RestoreHintVerifier.verify(
            fileText: text,
            secret: RestoreHintFixtures.abandonSecret(),
            network: .testnet
        )

        // start = coverageStart 22,580,000 - the 1,000-block margin.
        XCTAssertEqual(result, .ready(RestoreHintStart(startBlock: 22_579_000, sourceCoverageStart: 22_580_000)))
    }

    func testASecondAndroidExportVerifiesToo() throws {
        let text = try RestoreHintFixtures.text(RestoreHintFixtures.androidSecondExport)

        let result = RestoreHintVerifier.verify(
            fileText: text,
            secret: RestoreHintFixtures.abandonSecret(),
            network: .testnet
        )

        XCTAssertEqual(result, .ready(RestoreHintStart(startBlock: 22_569_000, sourceCoverageStart: 22_570_000)))
    }

    func testTheAsyncPathBuildsTheSecretFromTheWords() async throws {
        let text = try RestoreHintFixtures.text(RestoreHintFixtures.androidExport)

        let result = await RestoreHintVerifier.verify(
            fileText: text,
            material: .mnemonic(RestoreHintFixtures.abandonWords),
            network: .testnet
        )

        XCTAssertEqual(result, .ready(RestoreHintStart(startBlock: 22_579_000, sourceCoverageStart: 22_580_000)))
    }

    // MARK: - Rejections

    func testAnotherPhraseIsRejectedAsNotThisWallet() throws {
        let text = try RestoreHintFixtures.text(RestoreHintFixtures.androidExport)
        let other = RestoreHintSecretBox(
            RestoreHintSecret.companion.fromMnemonic(words: RestoreHintFixtures.legalWinnerWords, passphrase: "")
        )

        let result = RestoreHintVerifier.verify(fileText: text, secret: other, network: .testnet)

        XCTAssertEqual(result, .rejected(.notThisWallet))
        XCTAssertEqual(RestoreHintRejection.notThisWallet.message, "This file does not belong to this recovery phrase.")
    }

    func testTheOtherNetworkIsRejected() throws {
        let text = try RestoreHintFixtures.text(RestoreHintFixtures.androidExport)

        let result = RestoreHintVerifier.verify(
            fileText: text,
            secret: RestoreHintFixtures.abandonSecret(),
            network: .mainnet
        )

        XCTAssertEqual(result, .rejected(.otherNetwork))
        XCTAssertEqual(RestoreHintRejection.otherNetwork.message, "This file is for the other network.")
    }

    func testMalformedFilesAreRejectedAsUnreadable() throws {
        let valid = try RestoreHintFixtures.text(RestoreHintFixtures.androidExport)
        let samples = [
            "",
            "{}",
            "not json",
            String(valid.dropLast(10)),
            valid.replacingOccurrences(of: "\"mac\":\"", with: "\"mac\":\"0x"),
            String(repeating: "a", count: RestoreHintFile.maxChars + 1),
        ]

        for sample in samples {
            let result = RestoreHintVerifier.verify(
                fileText: sample,
                secret: RestoreHintFixtures.abandonSecret(),
                network: .testnet
            )
            XCTAssertEqual(result, .rejected(.unreadable), "sample of length \(sample.count)")
        }
        XCTAssertEqual(RestoreHintRejection.unreadable.message, "This file could not be read.")
    }

    func testAnEditedPayloadIsRejected() throws {
        let text = try RestoreHintFixtures.text(RestoreHintFixtures.androidExport)
        let data = try XCTUnwrap(text.data(using: .utf8))
        var envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let payload = try XCTUnwrap(Data(base64Encoded: try XCTUnwrap(envelope["payload"] as? String)))
        let edited = try XCTUnwrap(String(data: payload, encoding: .utf8))
            .replacingOccurrences(of: "22580000", with: "22590000")
        envelope["payload"] = Data(edited.utf8).base64EncodedString()
        let tampered = try XCTUnwrap(
            String(data: try JSONSerialization.data(withJSONObject: envelope), encoding: .utf8)
        )

        let result = RestoreHintVerifier.verify(
            fileText: tampered,
            secret: RestoreHintFixtures.abandonSecret(),
            network: .testnet
        )

        XCTAssertEqual(result, .rejected(.notThisWallet))
    }

    // MARK: - Secret hygiene

    func testTheSecretIsWipedAfterAVerifiedFile() throws {
        let text = try RestoreHintFixtures.text(RestoreHintFixtures.androidExport)
        let secret = RestoreHintFixtures.abandonSecret()

        _ = RestoreHintVerifier.verify(fileText: text, secret: secret, network: .testnet)

        XCTAssertTrue(secret.isWiped)
    }

    func testTheSecretIsWipedAfterARejectedFile() {
        let secret = RestoreHintFixtures.abandonSecret()

        _ = RestoreHintVerifier.verify(fileText: "{}", secret: secret, network: .testnet)

        XCTAssertTrue(secret.isWiped)
    }

    func testTheCallersPrivateKeyBufferIsZeroedOnceTheSecretIsBuilt() throws {
        let key = RestoreHintKeyBytes([UInt8](repeating: 7, count: 32))

        let secret = try XCTUnwrap(RestoreHintVerifier.makeSecret(.privateKey(key)))
        defer { secret.wipe() }

        XCTAssertTrue(key.isWiped)
        XCTAssertTrue(key.isZeroed, "the caller's own buffer, not a copy of it")
    }

    @MainActor
    func testTheCallersPrivateKeyBufferIsZeroedAfterTheAsyncCheck() async throws {
        let text = try RestoreHintFixtures.text(RestoreHintFixtures.androidExport)
        let key = RestoreHintKeyBytes(try XCTUnwrap(WalletCreator.decodePrivateKey(WalletCreatorTests.testPrivateKeyHex)))

        _ = await RestoreHintVerifier.verify(fileText: text, material: .privateKey(key), network: .testnet)

        XCTAssertTrue(key.isZeroed)
    }

    func testAWrongLengthKeyIsRefusedAndStillZeroed() {
        let key = RestoreHintKeyBytes([UInt8](repeating: 9, count: 31))

        XCTAssertNil(RestoreHintVerifier.makeSecret(.privateKey(key)))
        XCTAssertTrue(key.isZeroed)
    }

    // MARK: - Known-answer vector (the shared one, from Swift)

    /// The fixed payload from the shared `RestoreHintCodecTest`.
    private func katPayload() -> RestoreHintPayload {
        RestoreHintPayload(
            v: 1,
            network: "TESTNET",
            createdAtMs: 1_760_000_000_000,
            tipHeight: 19_000_000,
            tipHash: "0x" + String(repeating: "11", count: 32),
            kind: RestoreHintKind.shared.MNEMONIC,
            accounts: [
                RestoreHintAccount(index: 0, coverageStart: 18_000_000, firstActivity: KotlinLong(longLong: 18_120_000), syncMode: "CUSTOM"),
                RestoreHintAccount(index: 1, coverageStart: 18_500_000, firstActivity: nil, syncMode: "RECENT"),
            ],
            discovery: RestoreHintDiscovery(found: [KotlinInt(int: 1), KotlinInt(int: 3)], highestScanned: 10)
        )
    }

    func testTheSharedKnownAnswerAuthKeyAndMacHoldFromSwift() {
        let secret = RestoreHintFixtures.abandonSecret()
        defer { secret.wipe() }

        let authKey = RestoreHintCodec.shared.authKey(secret: secret.secret, network: .testnet)
        XCTAssertEqual(
            RestoreHintFixtures.hex(authKey),
            "5737d9898272e45e12e2a24e16356be52c50087807cf18f9ca1b89519a282b88"
        )

        let payloadBytes = RestoreHintCodec.shared.encodePayload(payload: katPayload())
        let mac = RestoreHintCodec.shared.mac(secret: secret.secret, network: .testnet, payloadBytes: payloadBytes)
        XCTAssertEqual(
            RestoreHintFixtures.hex(mac),
            "53a69a5572012030254563a7272ade108afd4521a5a50e42c631f7de572e2e2a"
        )
    }

    func testAFileSealedOnIosCarriesTheKnownAnswerMacAndOpensAgain() throws {
        let sealer = RestoreHintFixtures.abandonSecret()
        let text = RestoreHintCodec.shared.seal(payload: katPayload(), secret: sealer.secret)
        sealer.wipe()

        let data = try XCTUnwrap(text.data(using: .utf8))
        let envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(envelope["format"] as? String, "pocket-node-restore-hint")
        XCTAssertEqual(envelope["mac"] as? String, "53a69a5572012030254563a7272ade108afd4521a5a50e42c631f7de572e2e2a")

        // Accounts 0 and 1 derive from the phrase; the lower start wins.
        let result = RestoreHintVerifier.verify(
            fileText: text,
            secret: RestoreHintFixtures.abandonSecret(),
            network: .testnet
        )
        XCTAssertEqual(result, .ready(RestoreHintStart(startBlock: 17_999_000, sourceCoverageStart: 18_000_000)))
    }

    func testTheRawKeyKnownAnswerAuthKeyHoldsFromSwift() throws {
        let secret = try XCTUnwrap(RestoreHintVerifier.makeSecret(.privateKey(RestoreHintKeyBytes([UInt8](repeating: 1, count: 32)))))
        defer { secret.wipe() }

        XCTAssertEqual(
            RestoreHintFixtures.hex(RestoreHintCodec.shared.authKey(secret: secret.secret, network: .mainnet)),
            "c10df958fa2749846afea5ab20ce2be570bd9f73977ba088be3090f1376ca455"
        )
    }

    // MARK: - Planner edge: block 0

    func testACoverageOfZeroPlansFullHistoryNotCustomZero() throws {
        let payload = RestoreHintPayload(
            v: 1,
            network: "TESTNET",
            createdAtMs: 1,
            tipHeight: 19_000_000,
            tipHash: "0x" + String(repeating: "22", count: 32),
            kind: RestoreHintKind.shared.MNEMONIC,
            accounts: [RestoreHintAccount(index: 0, coverageStart: 0, firstActivity: nil, syncMode: "FULL_HISTORY")],
            discovery: RestoreHintDiscovery(found: [], highestScanned: 0)
        )
        let sealer = RestoreHintFixtures.abandonSecret()
        let text = RestoreHintCodec.shared.seal(payload: payload, secret: sealer.secret)
        sealer.wipe()

        let result = RestoreHintVerifier.verify(
            fileText: text,
            secret: RestoreHintFixtures.abandonSecret(),
            network: .testnet
        )

        guard case .ready(let start) = result else { return XCTFail("expected a verified hint, got \(result)") }
        XCTAssertEqual(start.startBlock, 0)
        XCTAssertTrue(start.usesFullHistory)
        XCTAssertEqual(start.syncMode, .fullHistory)
        XCTAssertNil(start.customHeight)
    }

    func testTheReadyCopyUsesDigitGrouping() {
        let start = RestoreHintStart(startBlock: 22_579_000, sourceCoverageStart: 22_580_000)
        XCTAssertEqual(
            start.message,
            "Restore will start at block \(Int64(22_579_000).formatted()) (the old phone synced from block \(Int64(22_580_000).formatted()))."
        )
        XCTAssertTrue(start.message.contains(Int64(22_579_000).formatted()))
        XCTAssertNotEqual(Int64(22_579_000).formatted(), "22579000", "the device locale groups digits")
    }
}
