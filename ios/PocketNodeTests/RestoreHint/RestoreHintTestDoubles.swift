import Foundation
import PocketNodeCore
import XCTest

@testable import PocketNode

/// Restore hint sources with no Room database or node behind them.
@MainActor
final class StubRestoreHintSources: RestoreHintSources {
    var restoreHintNetwork: NetworkType
    var tip: RestoreHintTip?
    var lightStart: Int64?
    var blockNumbers: [String]

    init(
        network: NetworkType = .testnet,
        tip: RestoreHintTip? = RestoreHintTip(height: 22_700_000, hash: "0x" + String(repeating: "ab", count: 32)),
        lightStart: Int64? = nil,
        blockNumbers: [String] = []
    ) {
        self.restoreHintNetwork = network
        self.tip = tip
        self.lightStart = lightStart
        self.blockNumbers = blockNumbers
    }

    func restoreHintTip() async -> RestoreHintTip? { tip }
    private(set) var lightStartReads = 0
    private(set) var blockNumberReads = 0

    func restoreHintLightStart(walletId: String) async -> Int64? {
        lightStartReads += 1
        return lightStart
    }

    func restoreHintBlockNumbers(walletId: String) async -> [String] {
        blockNumberReads += 1
        return blockNumbers
    }
}

/// The interop fixtures and the public test vectors.
///
/// The phrase is the public BIP-39 test vector (eleven `abandon` then
/// `about`), never a real wallet. The two files were exported by the Android
/// app (#561) from a testnet wallet restored from that phrase.
enum RestoreHintFixtures {
    static let abandonWords = Array(repeating: "abandon", count: 11) + ["about"]
    static let legalWinnerWords =
        "legal winner thank year wave sausage worth useful legal winner thank yellow".split(separator: " ").map(String.init)

    /// coverageStart 22,580,000, no first activity.
    static let androidExport = "pocket-node-restore-hint-testnet-abandon"
    /// coverageStart 22,570,000, no first activity.
    static let androidSecondExport = "pocket-node-restore-hint-testnet-abandon-2ndexport"

    static func text(_ name: String, file: StaticString = #filePath, line: UInt = #line) throws -> String {
        final class Anchor {}
        let bundle = Bundle(for: Anchor.self)
        let url = try XCTUnwrap(bundle.url(forResource: name, withExtension: "json"), "missing fixture \(name)", file: file, line: line)
        return try String(contentsOf: url, encoding: .utf8)
    }

    static func abandonSecret() -> RestoreHintSecretBox {
        RestoreHintSecretBox(RestoreHintSecret.companion.fromMnemonic(words: abandonWords, passphrase: ""))
    }

    static func hex(_ bytes: KotlinByteArray) -> String {
        bytes.data.map { String(format: "%02x", $0) }.joined()
    }
}
