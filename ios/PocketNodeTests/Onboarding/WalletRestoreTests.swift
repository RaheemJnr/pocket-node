import XCTest

@testable import PocketNode

/// A backup restored onto a new phone brings `wallet.json` back but not the
/// `ThisDeviceOnly` Keychain envelope or the Secure Enclave key. These drive
/// that state over a throwaway Keychain service, wrapping-key tag and
/// metadata directory: the wallet is created for real, then its keys are
/// deleted the way a new device would never have had them.
@MainActor
final class WalletRestoreTests: XCTestCase {
    private let service = "com.rjnr.pocketnode.tests.restore"
    private let tag = "com.rjnr.pocketnode.tests.restore.wrapper"

    private var keychain: KeychainStore!
    private var wrapper: SecureEnclaveKeyWrapper!
    private var keyStore: WalletKeyStore!
    private var walletStore: WalletStore!
    private var directory: URL!
    private var creator: WalletCreator!

    override func setUp() async throws {
        keychain = KeychainStore(service: service)
        wrapper = SecureEnclaveKeyWrapper(tag: tag)
        dropKeys()
        keyStore = WalletKeyStore(keychain: keychain, wrapper: wrapper)
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(service)-\(UUID().uuidString)")
        walletStore = WalletStore(directory: directory)
        creator = WalletCreator(keyStore: keyStore, walletStore: walletStore, now: { 1_700_000_000_000 })
    }

    override func tearDown() async throws {
        dropKeys()
        try? FileManager.default.removeItem(at: directory)
        creator = nil
        walletStore = nil
        keyStore = nil
        wrapper = nil
        keychain = nil
        directory = nil
    }

    /// What a new device restored from a backup has: none of the key material.
    private func dropKeys() {
        try? keychain.deleteAll()
        try? wrapper.deleteKey()
    }

    /// A mnemonic wallet whose keys did not come across, as a restored
    /// device sees it.
    private func restoredMnemonicWallet() async throws -> WalletRecord {
        let created = try await creator.importMnemonic(words: WalletCreatorTests.testPhrase, name: "Savings")
        dropKeys()
        return created.record
    }

    private static let otherPhrase = [
        "legal", "winner", "thank", "year", "wave", "sausage",
        "worth", "useful", "legal", "winner", "thank", "yellow",
    ]

    // MARK: - Detection

    func testMetadataWithoutKeysIsReportedAsNeedingRestore() async throws {
        let record = try await restoredMnemonicWallet()

        XCTAssertTrue(walletStore.hasWallet, "the metadata came back with the backup")
        let envelope = await keyStore.envelopePresence
        XCTAssertEqual(envelope, .absent)
        let needing = await creator.walletNeedingRestore()
        XCTAssertEqual(needing, record)
    }

    func testAWalletWithItsKeysDoesNotNeedRestore() async throws {
        _ = try await creator.importMnemonic(words: WalletCreatorTests.testPhrase, name: "Savings")

        let needing = await creator.walletNeedingRestore()
        XCTAssertNil(needing)
    }

    func testNoWalletAtAllDoesNotNeedRestore() async {
        let needing = await creator.walletNeedingRestore()
        XCTAssertNil(needing)
    }

    /// A launch before the first device unlock cannot read the Keychain. That
    /// is not a missing key, and must not send a working wallet to restore.
    func testAnUnreadableKeychainIsNotReportedAsMissingKeys() async throws {
        let record = try await creator.importMnemonic(words: WalletCreatorTests.testPhrase, name: "Savings").record
        let unreadable = UnreadableKeyValueStore(service: service)
        let lockedKeyStore = WalletKeyStore(keychain: unreadable, wrapper: wrapper)
        let lockedCreator = WalletCreator(keyStore: lockedKeyStore, walletStore: walletStore)

        let envelope = await lockedKeyStore.envelopePresence
        XCTAssertEqual(envelope, .unknown)
        let needing = await lockedCreator.walletNeedingRestore()
        XCTAssertNil(needing)
        XCTAssertNotNil(record)
    }

    // MARK: - Restoring

    /// The bug: before the fix, import refused because a wallet was "already
    /// there", and the user was stuck with an address they could not spend.
    func testTheMatchingPhraseReplacesTheKeylessEntryAndMakesItUsable() async throws {
        let record = try await restoredMnemonicWallet()

        let restored = try await creator.restoreMnemonic(words: WalletCreatorTests.testPhrase, replacing: record)

        XCTAssertEqual(restored.record.id, record.id, "the same wallet, not a new one")
        XCTAssertEqual(restored.record.name, "Savings")
        XCTAssertEqual(restored.record.mainnetAddress, WalletCreatorTests.testMainnetAddress)
        XCTAssertEqual(restored.record.testnetAddress, WalletCreatorTests.testTestnetAddress)
        XCTAssertEqual(restored.record.createdAt, record.createdAt)
        XCTAssertTrue(restored.record.mnemonicBackedUp, "the user just typed the phrase in")
        XCTAssertEqual(walletStore.load(), restored.record)

        let envelope = await keyStore.envelopePresence
        XCTAssertEqual(envelope, .present)
        let needing = await creator.walletNeedingRestore()
        XCTAssertNil(needing)
        let bundle = try await keyStore.load(reason: "test")
        XCTAssertEqual(bundle.privateKeyHex, WalletCreatorTests.testPrivateKeyHex)
        XCTAssertEqual(bundle.mnemonic, WalletCreatorTests.testPhrase.joined(separator: " "))
    }

    func testAPhraseForADifferentWalletIsRejectedAndNothingIsWritten() async throws {
        let record = try await restoredMnemonicWallet()

        do {
            try await creator.restoreMnemonic(words: Self.otherPhrase, replacing: record)
            XCTFail("a phrase for another wallet must not restore this one")
        } catch let error as WalletCreationError {
            XCTAssertEqual(error, .doesNotMatchWallet)
        }

        XCTAssertEqual(walletStore.load(), record, "the record is untouched")
        let envelope = await keyStore.envelopePresence
        XCTAssertEqual(envelope, .absent, "no keys were stored for the wrong wallet")
    }

    func testAnInvalidPhraseIsRejectedAsInvalid() async throws {
        let record = try await restoredMnemonicWallet()

        do {
            try await creator.restoreMnemonic(words: Array(repeating: "abandon", count: 12), replacing: record)
            XCTFail("expected a checksum failure")
        } catch let error as WalletCreationError {
            XCTAssertEqual(error, .invalidMnemonic)
        }
    }

    /// Restore can only fill in missing keys, never overwrite present ones.
    func testRestoreIsRefusedWhileTheKeysAreStillThere() async throws {
        let record = try await creator.importMnemonic(words: WalletCreatorTests.testPhrase, name: "Savings").record

        do {
            try await creator.restoreMnemonic(words: WalletCreatorTests.testPhrase, replacing: record)
            XCTFail("keys are present, there is nothing to restore")
        } catch let error as WalletCreationError {
            XCTAssertEqual(error, .walletAlreadyExists)
        }
    }

    func testARawKeyWalletRestoresFromItsKey() async throws {
        let record = try await creator.importPrivateKey(hex: WalletCreatorTests.testPrivateKeyHex, name: "Key").record
        dropKeys()

        let restored = try await creator.restorePrivateKey(hex: WalletCreatorTests.testPrivateKeyHex, replacing: record)

        XCTAssertEqual(restored.record.id, record.id)
        XCTAssertEqual(restored.record.type, WalletCreator.typeRawKey)
        let bundle = try await keyStore.load(reason: "test")
        XCTAssertEqual(bundle.privateKeyHex, WalletCreatorTests.testPrivateKeyHex)
        XCTAssertNil(bundle.mnemonic)
    }

    func testAPhraseCannotRestoreARawKeyWallet() async throws {
        let record = try await creator.importPrivateKey(hex: WalletCreatorTests.testPrivateKeyHex, name: "Key").record
        dropKeys()

        do {
            // Same key, but the record says there is no phrase to restore.
            try await creator.restoreMnemonic(words: WalletCreatorTests.testPhrase, replacing: record)
            XCTFail("expected a type mismatch")
        } catch let error as WalletCreationError {
            XCTAssertEqual(error, .doesNotMatchWallet)
        }
    }

    // MARK: - The onboarding flow in restore mode

    func testTheRestoreFlowOpensOnImportAndMovesOnToThePin() async throws {
        let record = try await restoredMnemonicWallet()
        let model = OnboardingViewModel(creator: creator, restoring: record, hasPin: { false })
        XCTAssertEqual(model.step, .importWallet)
        XCTAssertTrue(model.isRestoring)

        await model.importMnemonic(words: WalletCreatorTests.testPhrase, name: "ignored")

        XCTAssertNil(model.errorMessage)
        XCTAssertEqual(model.step, .pinSetup)
        XCTAssertEqual(walletStore.load()?.name, "Savings", "a restore keeps the wallet's own name")
    }

    /// A PIN that survived is left in place: the flow finishes and the lock
    /// screen asks for it, rather than letting the restore choose a new one.
    func testTheRestoreFlowSkipsThePinStepWhenAPinExists() async throws {
        let record = try await restoredMnemonicWallet()
        let model = OnboardingViewModel(creator: creator, restoring: record, hasPin: { true })

        await model.importMnemonic(words: WalletCreatorTests.testPhrase, name: "ignored")

        XCTAssertEqual(model.step, .done)
    }

    func testTheRestoreFlowShowsAMessageForTheWrongPhrase() async throws {
        let record = try await restoredMnemonicWallet()
        let model = OnboardingViewModel(creator: creator, restoring: record, hasPin: { false })

        await model.importMnemonic(words: Self.otherPhrase, name: "ignored")

        XCTAssertEqual(model.step, .importWallet)
        XCTAssertEqual(
            model.errorMessage,
            "That does not match this wallet. Enter the recovery phrase or private key for the address shown."
        )
    }

    // MARK: - Backup exclusion

    func testWalletMetadataIsExcludedFromBackups() async throws {
        _ = try await creator.importMnemonic(words: WalletCreatorTests.testPhrase, name: "Savings")

        XCTAssertTrue(walletStore.isExcludedFromBackup)
    }
}
