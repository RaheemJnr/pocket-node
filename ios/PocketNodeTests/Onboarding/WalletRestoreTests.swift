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

    // MARK: - A wallet type this version cannot restore

    /// Neither a phrase nor a raw key: the restore screen shows a message and
    /// a way back instead of fields that would refuse everything.
    func testAnUnknownWalletTypeIsAnUnsupportedRestore() {
        func model(type: String) -> OnboardingViewModel {
            OnboardingViewModel(
                creator: creator,
                restoring: WalletRecord(
                    id: "w", name: "n", type: type,
                    mainnetAddress: "ckb1", testnetAddress: "ckt1", createdAt: 0
                ),
                hasPin: { false }
            )
        }

        XCTAssertTrue(model(type: "watch_only").isUnsupportedRestore)
        XCTAssertFalse(model(type: WalletCreator.typeMnemonic).isUnsupportedRestore)
        XCTAssertFalse(model(type: WalletCreator.typeRawKey).isUnsupportedRestore)
        XCTAssertFalse(OnboardingViewModel(creator: creator).isUnsupportedRestore, "a first run is not a restore")
    }

    /// A restore has no way to the welcome step or to create: both would be
    /// refused, since the device already has this wallet.
    func testARestoreNeverReachesWelcomeOrCreate() {
        let model = OnboardingViewModel(
            creator: creator,
            restoring: WalletRecord(
                id: "w", name: "n", type: "watch_only",
                mainnetAddress: "ckb1", testnetAddress: "ckt1", createdAt: 0
            ),
            hasPin: { false }
        )

        model.backToWelcome()
        XCTAssertEqual(model.step, .importWallet)
        model.beginCreate()
        XCTAssertEqual(model.step, .importWallet)
    }

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

    /// The metadata save failing after the keys are back must not delete the
    /// keys again: the record already on disk describes them, and a rollback
    /// can strand the wallet with an envelope and no wrapping key.
    func testARecordSaveFailureAfterRestoreKeepsTheRestoredKeys() async throws {
        let record = try await restoredMnemonicWallet()
        let fileManager = FileManager.default
        try fileManager.setAttributes([.posixPermissions: 0o555], ofItemAtPath: directory.path)
        defer { try? fileManager.setAttributes([.posixPermissions: 0o755], ofItemAtPath: directory.path) }
        XCTAssertThrowsError(try walletStore.save(record), "the directory must refuse writes for this test to mean anything")

        let restored = try await creator.restoreMnemonic(words: WalletCreatorTests.testPhrase, replacing: record)

        XCTAssertEqual(restored.record, record, "the record on disk is the one that stands")
        XCTAssertEqual(walletStore.load(), record)
        let envelope = await keyStore.envelopePresence
        XCTAssertEqual(envelope, .present, "the keys stay")
        XCTAssertTrue(wrapper.hasKey, "with their wrapping key")
        let bundle = try await keyStore.load(reason: "test")
        XCTAssertEqual(bundle.privateKeyHex, WalletCreatorTests.testPrivateKeyHex)
        let needing = await creator.walletNeedingRestore()
        XCTAssertNil(needing, "the wallet is whole")
    }

    // MARK: - Waiting behind the lock

    /// The restore screen names the wallet and shows its address, so a PIN
    /// that survived has to be answered first.
    func testARestoreWaitsForTheUnlockWhenAPinExists() async throws {
        let pinKeychain = KeychainStore(service: "\(service).pin")
        try? pinKeychain.deleteAll()
        defer { try? pinKeychain.deleteAll() }
        let suite = "\(service).prefs"
        UserDefaults.standard.removePersistentDomain(forName: suite)
        defer { UserDefaults.standard.removePersistentDomain(forName: suite) }
        let preferences = UserDefaultsPreferences(defaults: UserDefaults(suiteName: suite)!)
        func makeAuth() -> AuthService {
            AuthService(
                pin: PinService(keychain: pinKeychain, cost: .testing),
                biometrics: StubBiometrics(availability: .unavailable),
                preferences: preferences
            )
        }

        let fresh = makeAuth()
        XCTAssertTrue(
            LaunchGate.mayStartRestore(
                pinPresence: fresh.pin.pinPresence,
                sessionUnlocked: fresh.state == .unlocked
            ),
            "no PIN, nothing to wait for"
        )

        try await fresh.setPin("123456")
        let cold = makeAuth()
        XCTAssertEqual(cold.state, .locked)
        XCTAssertFalse(
            LaunchGate.mayStartRestore(
                pinPresence: cold.pin.pinPresence,
                sessionUnlocked: cold.state == .unlocked
            ),
            "locked: the lock screen comes first"
        )

        let unlocked = await cold.unlock(pin: "123456")
        XCTAssertTrue(unlocked)
        XCTAssertTrue(
            LaunchGate.mayStartRestore(
                pinPresence: cold.pin.pinPresence,
                sessionUnlocked: cold.state == .unlocked
            )
        )

        XCTAssertFalse(
            LaunchGate.mayStartRestore(pinPresence: .unknown, sessionUnlocked: false),
            "an unreadable PIN store waits too"
        )
    }

    /// A launch that could not read the Keychain is not a restore; once the
    /// Keychain reads again (the re-check on unlock and on coming back to the
    /// front) the missing keys show.
    func testMissingKeysShowOnceAnUnreadableKeychainReadsAgain() async throws {
        let record = try await restoredMnemonicWallet()
        let unreadable = UnreadableKeyValueStore(service: service)
        let lockedCreator = WalletCreator(
            keyStore: WalletKeyStore(keychain: unreadable, wrapper: wrapper),
            walletStore: walletStore
        )

        let before = await lockedCreator.walletNeedingRestore()
        XCTAssertNil(before)

        unreadable.failReads(nil)
        let after = await lockedCreator.walletNeedingRestore()
        XCTAssertEqual(after, record)
    }

    // MARK: - Backup exclusion

    func testWalletMetadataIsExcludedFromBackups() async throws {
        _ = try await creator.importMnemonic(words: WalletCreatorTests.testPhrase, name: "Savings")

        XCTAssertTrue(walletStore.isExcludedFromBackup)
        XCTAssertTrue(walletStore.isDirectoryExcludedFromBackup)
    }

    /// The launch-time call covers an existing install before its next save:
    /// the directory is flagged even with no file yet, and it keeps the flag
    /// across the atomic replace a save does.
    func testTheLaunchTimeExclusionFlagsTheDirectory() throws {
        let fresh = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(service)-launch-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: fresh) }
        let store = WalletStore(directory: fresh)
        XCTAssertFalse(store.isDirectoryExcludedFromBackup)

        store.excludeFromBackup()
        XCTAssertTrue(store.isDirectoryExcludedFromBackup)

        try store.save(
            WalletRecord(
                id: "w", name: "n", type: WalletCreator.typeMnemonic,
                mainnetAddress: "ckb1", testnetAddress: "ckt1", createdAt: 0
            )
        )
        XCTAssertTrue(store.isDirectoryExcludedFromBackup)
        XCTAssertTrue(store.isExcludedFromBackup)
    }
}
