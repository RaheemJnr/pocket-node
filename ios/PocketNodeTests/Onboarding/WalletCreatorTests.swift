import PocketNodeCore
import XCTest

@testable import PocketNode

/// Creation and import, against a throwaway Keychain service, a throwaway
/// wrapping-key tag and a temporary metadata directory, so nothing here can
/// touch a real wallet on the maintainer's simulator.
@MainActor
final class WalletCreatorTests: XCTestCase {
    /// The BIP-39 test phrase every wallet implementation carries. It is
    /// public, and anything sent to the addresses below is anyone's to take.
    static let testPhrase = Array(repeating: "abandon", count: 11) + ["about"]

    /// `m/44'/309'/0'/0/0` for ``testPhrase``.
    ///
    /// Derived independently of the app: a standalone BIP-39 / BIP-32 /
    /// secp256k1 / blake2b / bech32m implementation, not `PocketNodeCore`, so
    /// agreeing with it is evidence rather than a tautology. Its BIP-39 seed
    /// for this phrase is the one pinned in the shared module's `Bip39Test`
    /// (`5eb00bbd…9e38e4`), which anchors the first step against the published
    /// reference vector. Cross-checked against Android in #517.
    static let testPrivateKeyHex = "b217d9a18ff657c99872cc11a2fa2aa3e970cef8c6faa7d6e424bf057cb3707b"
    static let testTestnetAddress =
        "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqenlarn"
    static let testMainnetAddress =
        "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqhp5jft"

    private let service = "com.rjnr.pocketnode.tests.creator"
    private let tag = "com.rjnr.pocketnode.tests.creator.wrapper"

    private var keychain: KeychainStore!
    private var wrapper: SecureEnclaveKeyWrapper!
    private var keyStore: WalletKeyStore!
    private var walletStore: WalletStore!
    private var directory: URL!
    private var creator: WalletCreator!

    override func setUp() async throws {
        try await super.setUp()
        keychain = KeychainStore(service: service)
        wrapper = SecureEnclaveKeyWrapper(tag: tag)
        wipe()
        keyStore = WalletKeyStore(keychain: keychain, wrapper: wrapper)
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("com.rjnr.pocketnode.tests.creator-\(UUID().uuidString)")
        walletStore = WalletStore(directory: directory)
        creator = WalletCreator(keyStore: keyStore, walletStore: walletStore, now: { 1_700_000_000_000 })
    }

    override func tearDown() async throws {
        wipe()
        try? FileManager.default.removeItem(at: directory)
        creator = nil
        walletStore = nil
        keyStore = nil
        wrapper = nil
        keychain = nil
        directory = nil
        try await super.tearDown()
    }

    private func wipe() {
        try? keychain.deleteAll()
        try? wrapper.deleteKey()
    }

    // MARK: - Create

    func testCreateTwelveWordWalletProducesAValidPhraseAndBothAddresses() async throws {
        let created = try await creator.createWallet(wordCount: 12, name: "Main")

        XCTAssertEqual(created.mnemonic.count, 12)
        XCTAssertTrue(
            Bip39.shared.validate(words: created.mnemonic),
            "generate() must produce a phrase that validate() accepts"
        )
        XCTAssertTrue(created.record.testnetAddress.hasPrefix("ckt1"))
        XCTAssertTrue(created.record.mainnetAddress.hasPrefix("ckb1"))
        XCTAssertEqual(created.record.name, "Main")
        XCTAssertEqual(created.record.type, WalletCreator.typeMnemonic)
        XCTAssertEqual(created.record.derivationPath, "m/44'/309'/0'/0/0")
        XCTAssertFalse(
            created.record.mnemonicBackedUp,
            "a generated phrase has not been written down yet"
        )
        XCTAssertEqual(created.record.createdAt, 1_700_000_000_000)
    }

    func testCreateTwentyFourWordWalletProducesAValidPhrase() async throws {
        let created = try await creator.createWallet(wordCount: 24, name: "Main")

        XCTAssertEqual(created.mnemonic.count, 24)
        XCTAssertTrue(Bip39.shared.validate(words: created.mnemonic))
        XCTAssertTrue(created.record.testnetAddress.hasPrefix("ckt1"))
    }

    func testCreateStoresTheKeyBundleAndTheMetadataRecord() async throws {
        let created = try await creator.createWallet(wordCount: 12, name: "Main")

        let stored = try await keyStore.load(reason: "test")
        XCTAssertEqual(stored.mnemonic, created.mnemonic.joined(separator: " "))
        XCTAssertEqual(stored.privateKeyHex.count, 64)
        XCTAssertEqual(walletStore.load(), created.record)
    }

    func testTwoCreatedWalletsDoNotShareAPhrase() async throws {
        let first = try await creator.createWallet(wordCount: 12, name: "Main")
        wipe()
        try walletStore.delete()

        let second = try await creator.createWallet(wordCount: 12, name: "Main")

        XCTAssertNotEqual(first.mnemonic, second.mnemonic)
    }

    func testCreateRejectsAWordCountItCannotGenerate() async throws {
        await assertFails(.invalidWordCount) {
            _ = try await self.creator.createWallet(wordCount: 15, name: "Main")
        }
        let nothingStored = await keyStore.hasWallet
        XCTAssertFalse(nothingStored)
    }

    func testAnEmptyNameFallsBackToTheAndroidDefault() async throws {
        let created = try await creator.createWallet(wordCount: 12, name: "   ")

        XCTAssertEqual(created.record.name, "My Wallet")
    }

    func testASecondCreateIsRefusedWhileAWalletIsStored() async throws {
        _ = try await creator.createWallet(wordCount: 12, name: "Main")

        await assertFails(.walletAlreadyExists) {
            _ = try await self.creator.createWallet(wordCount: 12, name: "Second")
        }
    }

    func testAnImportIsRefusedWhileAWalletIsStored() async throws {
        _ = try await creator.createWallet(wordCount: 12, name: "Main")

        await assertFails(.walletAlreadyExists) {
            _ = try await self.creator.importMnemonic(words: Self.testPhrase, name: "Second")
        }
        await assertFails(.walletAlreadyExists) {
            _ = try await self.creator.importPrivateKey(hex: Self.testPrivateKeyHex, name: "Second")
        }
    }

    // MARK: - Import a phrase

    func testImportingTheTestPhraseDerivesThePinnedAddresses() async throws {
        let created = try await creator.importMnemonic(words: Self.testPhrase, name: "Restored")

        XCTAssertEqual(created.record.testnetAddress, Self.testTestnetAddress)
        XCTAssertEqual(created.record.mainnetAddress, Self.testMainnetAddress)
        XCTAssertEqual(created.record.type, WalletCreator.typeMnemonic)
        XCTAssertEqual(created.record.derivationPath, "m/44'/309'/0'/0/0")
        XCTAssertTrue(
            created.record.mnemonicBackedUp,
            "the user typed the phrase in, so they hold it (Android does the same)"
        )
        XCTAssertTrue(created.mnemonic.isEmpty, "only a generated phrase is handed back")

        let stored = try await keyStore.load(reason: "test")
        XCTAssertEqual(stored.privateKeyHex, Self.testPrivateKeyHex)
        XCTAssertEqual(stored.mnemonic, Self.testPhrase.joined(separator: " "))
    }

    func testImportNormalisesCaseAndSurroundingWhitespace() async throws {
        let messy = Self.testPhrase.enumerated().map { index, word in
            index.isMultiple(of: 2) ? "  \(word.uppercased()) " : " \(word) "
        }

        let created = try await creator.importMnemonic(words: messy, name: "Restored")

        XCTAssertEqual(created.record.testnetAddress, Self.testTestnetAddress)
    }

    func testImportRejectsABadChecksum() async throws {
        // Twelve valid words whose checksum does not match.
        let words = Array(repeating: "abandon", count: 12)
        XCTAssertFalse(Bip39.shared.validate(words: words))

        await assertFails(.invalidMnemonic) {
            _ = try await self.creator.importMnemonic(words: words, name: "Restored")
        }
        let nothingStored = await keyStore.hasWallet
        XCTAssertFalse(nothingStored)
        XCTAssertFalse(walletStore.hasWallet)
    }

    func testImportRejectsAWordThatIsNotOnTheList() async throws {
        var words = Self.testPhrase
        words[3] = "zzzz"

        await assertFails(.invalidMnemonic) {
            _ = try await self.creator.importMnemonic(words: words, name: "Restored")
        }
    }

    func testImportRejectsAPhraseOfTheWrongLength() async throws {
        await assertFails(.invalidMnemonic) {
            _ = try await self.creator.importMnemonic(
                words: Array(Self.testPhrase.prefix(11)),
                name: "Restored"
            )
        }
    }

    func testImportRejectsAnEmptyWordInAnOtherwiseCompletePhrase() async throws {
        var words = Self.testPhrase
        words[5] = ""

        await assertFails(.invalidMnemonic) {
            _ = try await self.creator.importMnemonic(words: words, name: "Restored")
        }
    }

    // MARK: - Import a private key

    func testImportingThePinnedPrivateKeyDerivesTheSameAddresses() async throws {
        let created = try await creator.importPrivateKey(hex: Self.testPrivateKeyHex, name: "Key")

        XCTAssertEqual(created.record.testnetAddress, Self.testTestnetAddress)
        XCTAssertEqual(created.record.mainnetAddress, Self.testMainnetAddress)
        XCTAssertEqual(created.record.type, WalletCreator.typeRawKey)
        XCTAssertNil(created.record.derivationPath, "a raw key was not derived from a path")
        XCTAssertFalse(created.record.mnemonicBackedUp)

        let stored = try await keyStore.load(reason: "test")
        XCTAssertEqual(stored.privateKeyHex, Self.testPrivateKeyHex)
        XCTAssertNil(stored.mnemonic, "a raw-key wallet has no phrase to store")
    }

    func testAPrivateKeyMayCarryTheHexPrefix() async throws {
        let created = try await creator.importPrivateKey(
            hex: "0x" + Self.testPrivateKeyHex,
            name: "Key"
        )

        XCTAssertEqual(created.record.testnetAddress, Self.testTestnetAddress)
    }

    func testAPrivateKeyMayCarrySurroundingWhitespace() async throws {
        let created = try await creator.importPrivateKey(
            hex: "  \(Self.testPrivateKeyHex)\n",
            name: "Key"
        )

        XCTAssertEqual(created.record.testnetAddress, Self.testTestnetAddress)
    }

    func testImportRejectsAKeyThatIsNotAUsableScalar() async throws {
        let rejected = [
            String(repeating: "0", count: 64),                                    // zero
            "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141",   // the group order
            "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe",   // above it
            String(Self.testPrivateKeyHex.dropLast()),                            // 63 characters
            Self.testPrivateKeyHex + "00",                                        // 66 characters
            String(repeating: "g", count: 64),                                    // not hex
            "",
        ]

        for hex in rejected {
            await assertFails(.invalidPrivateKey, "\(hex.count) chars") {
                _ = try await self.creator.importPrivateKey(hex: hex, name: "Key")
            }
        }
        let nothingStored = await keyStore.hasWallet
        XCTAssertFalse(nothingStored)
    }

    /// The exact edge libsecp256k1 rejects, checked on the Swift side because a
    /// Kotlin `IllegalArgumentException` would terminate the process rather
    /// than reach a `catch` (see `WalletCreator.decodePrivateKey`).
    func testTheScalarRangeCheckStopsAtTheGroupOrder() {
        let belowOrder = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364140"
        let order = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"

        XCTAssertNotNil(WalletCreator.decodePrivateKey(belowOrder))
        XCTAssertNil(WalletCreator.decodePrivateKey(order))
        XCTAssertNotNil(WalletCreator.decodePrivateKey("01" + String(repeating: "0", count: 62)))
    }

    // MARK: - Input helpers

    func testSplitPhraseHandlesTheShapesAClipboardActuallyCarries() {
        XCTAssertEqual(
            WalletCreator.splitPhrase("  Abandon\tABANDON\n about \n"),
            ["abandon", "abandon", "about"]
        )
        XCTAssertEqual(WalletCreator.splitPhrase("   "), [])
    }

    func testWordlistRecognisesOnlyRealWords() {
        XCTAssertTrue(WalletCreator.isWord("abandon"))
        XCTAssertTrue(WalletCreator.isWord("zoo"))
        XCTAssertFalse(WalletCreator.isWord("Abandon"), "callers normalise before asking")
        XCTAssertFalse(WalletCreator.isWord("zzzz"))
        XCTAssertEqual(WalletCreator.wordlist.count, 2048)
    }

    // MARK: - Helpers

    private func assertFails(
        _ expected: WalletCreationError,
        _ context: String = "",
        file: StaticString = #filePath,
        line: UInt = #line,
        _ body: () async throws -> Void
    ) async {
        do {
            try await body()
            XCTFail("expected \(expected) \(context)", file: file, line: line)
        } catch let error as WalletCreationError {
            XCTAssertEqual(error, expected, context, file: file, line: line)
        } catch {
            XCTFail("expected \(expected), got \(error) \(context)", file: file, line: line)
        }
    }
}
