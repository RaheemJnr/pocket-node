import XCTest

@testable import PocketNode

/// Recovery for key material that is there but unusable, and for keys whose
/// metadata is missing, through the same `LaunchGate`, `WalletCreator` and
/// `OnboardingViewModel` calls `RootView` makes. Over throwaway Keychain
/// services, wrapping-key tag, metadata directory and defaults suite.
///
/// An invalidated Secure Enclave key is simulated the way the device sees
/// it: the wallet is stored for real, then its wrapping key is deleted while
/// the envelope stays.
@MainActor
final class KeyHealthRecoveryTests: XCTestCase {
    private let keyService = "com.rjnr.pocketnode.tests.keyhealth.keys"
    private let pinService = "com.rjnr.pocketnode.tests.keyhealth.pin"
    private let tag = "com.rjnr.pocketnode.tests.keyhealth.wrapper"
    private let suiteName = "com.rjnr.pocketnode.tests.keyhealth.prefs"

    private var keyKeychain: KeychainStore!
    private var pinKeychain: KeychainStore!
    private var wrapper: SecureEnclaveKeyWrapper!
    private var directory: URL!
    private var walletStore: WalletStore!
    private var defaults: UserDefaults!

    /// The BIP-39 "legal winner" vector: a second valid phrase, for another
    /// wallet than ``WalletCreatorTests/testPhrase``.
    private static let otherPhrase = [
        "legal", "winner", "thank", "year", "wave", "sausage",
        "worth", "useful", "legal", "winner", "thank", "yellow",
    ]

    override func setUp() async throws {
        keyKeychain = KeychainStore(service: keyService)
        pinKeychain = KeychainStore(service: pinService)
        wrapper = SecureEnclaveKeyWrapper(tag: tag)
        try? keyKeychain.deleteAll()
        try? pinKeychain.deleteAll()
        try? wrapper.deleteKey()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(keyService)-\(UUID().uuidString)")
        walletStore = WalletStore(directory: directory)
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() async throws {
        try? keyKeychain.deleteAll()
        try? pinKeychain.deleteAll()
        try? wrapper.deleteKey()
        try? FileManager.default.removeItem(at: directory)
        defaults.removePersistentDomain(forName: suiteName)
        keyKeychain = nil
        pinKeychain = nil
        wrapper = nil
        directory = nil
        walletStore = nil
        defaults = nil
    }

    // MARK: - Helpers

    private var preferences: UserDefaultsPreferences {
        UserDefaultsPreferences(defaults: defaults)
    }

    private func keyStore(
        keychain: (any KeyValueStoring)? = nil,
        wrapper: (any KeyWrapping)? = nil
    ) -> WalletKeyStore {
        WalletKeyStore(keychain: keychain ?? keyKeychain, wrapper: wrapper ?? self.wrapper)
    }

    private func creator(_ keyStore: WalletKeyStore? = nil) -> WalletCreator {
        WalletCreator(keyStore: keyStore ?? self.keyStore(), walletStore: walletStore)
    }

    private func makeGate(
        keychain: (any KeyValueStoring)? = nil,
        wrapper: (any KeyWrapping)? = nil
    ) -> LaunchGate {
        LaunchGate(
            walletStore: walletStore,
            walletKeyStore: keyStore(keychain: keychain, wrapper: wrapper),
            keyKeychain: keychain ?? keyKeychain,
            pinKeychain: pinKeychain,
            preferences: preferences,
            biometrics: StubBiometrics(availability: .unavailable),
            pinCost: .testing
        )
    }

    private func storePin() async throws {
        try await PinService(keychain: pinKeychain, cost: .testing).setPin("111111")
    }

    private func pinPresence() async -> PinPresence {
        let service = PinService(keychain: pinKeychain, cost: .testing)
        await service.refresh()
        return service.pinPresence
    }

    /// A phrase wallet, stored for real.
    @discardableResult
    private func phraseWallet() async throws -> WalletRecord {
        try await creator().importMnemonic(words: WalletCreatorTests.testPhrase, name: "Savings").record
    }

    /// The Secure Enclave key is gone and the envelope is still there.
    private func invalidateKeys() throws {
        try wrapper.deleteKey()
        XCTAssertTrue(try keyKeychain.contains(account: WalletKeyAccount.envelope), "the envelope stays")
    }

    private var envelope: Data? {
        try? keyKeychain.get(account: WalletKeyAccount.envelope)
    }

    private var walletFile: URL { directory.appendingPathComponent("wallet.json") }

    // MARK: - The probe

    func testKeyHealthReadsEachState() async throws {
        let store = keyStore()
        var health = await store.keyHealth
        XCTAssertEqual(health, .absent)

        try await phraseWallet()
        health = await store.keyHealth
        XCTAssertEqual(health, .usable)

        try invalidateKeys()
        health = await store.keyHealth
        XCTAssertEqual(health, .invalidated, "envelope present, wrapping key confirmed gone")

        let unreadable = UnreadableKeyValueStore(service: keyService)
        health = await keyStore(keychain: unreadable).keyHealth
        XCTAssertEqual(health, .unknown, "a Keychain that refuses the read is never absent or invalidated")
    }

    func testAnEnvelopeThatDoesNotParseIsInvalidatedEvenWithItsKey() async throws {
        try await phraseWallet()
        try keyKeychain.set(Data([0x02, 0x00]), account: WalletKeyAccount.envelope)

        let health = await keyStore().keyHealth

        XCTAssertTrue(wrapper.hasKey)
        XCTAssertEqual(health, .invalidated)
    }

    func testAWrappingKeyLookupTheKeychainRefusesIsUnknown() async throws {
        try await phraseWallet()
        let stub = StubKeyWrapper(tag: tag)
        stub.overridePresence(.unknown)

        let health = await keyStore(wrapper: stub).keyHealth

        XCTAssertEqual(health, .unknown)
    }

    func testTheWrapperProbeSeesTheRealKey() throws {
        XCTAssertEqual(wrapper.keyPresence, .absent)
        _ = try wrapper.wrap(Data(repeating: 7, count: 32))
        XCTAssertEqual(wrapper.keyPresence, .present)
        try wrapper.deleteKey()
        XCTAssertEqual(wrapper.keyPresence, .absent)
    }

    // MARK: - The decision table

    func testTheRecoveryDecisionTable() {
        let record = WalletRecord(
            id: "w", name: "n", type: WalletCreator.typeMnemonic,
            mainnetAddress: "ckb1", testnetAddress: "ckt1", createdAt: 0
        )
        func decide(_ metadata: LaunchGate.MetadataState, _ health: KeyHealth) -> LaunchGate.RecoveryDecision {
            LaunchGate.recoveryDecision(metadata: metadata, health: health)
        }

        XCTAssertEqual(decide(.record(record), .usable), .none)
        XCTAssertEqual(decide(.record(record), .absent), .recover(.keysMissing(record)))
        XCTAssertEqual(decide(.record(record), .invalidated), .recover(.keysInvalidated(record)))
        XCTAssertEqual(decide(.record(record), .unknown), .wait)

        for metadata in [LaunchGate.MetadataState.missing, .undecodable] {
            XCTAssertEqual(decide(metadata, .usable), .recover(.rebuildMetadata))
            XCTAssertEqual(decide(metadata, .absent), .none)
            XCTAssertEqual(decide(metadata, .invalidated), .recover(.replaceKeys))
            XCTAssertEqual(decide(metadata, .unknown), .wait)
        }

        for health in [KeyHealth.usable, .absent, .invalidated, .unknown] {
            XCTAssertEqual(decide(.unreadable, health), .wait)
        }
    }

    func testTheRebuildOutcomes() {
        XCTAssertEqual(LaunchGate.rebuildOutcome(error: nil), .rebuilt)
        XCTAssertEqual(LaunchGate.rebuildOutcome(error: WalletCreationError.keyReadFailed(.keyInvalidated)), .keysUnusable)
        XCTAssertEqual(LaunchGate.rebuildOutcome(error: WalletCreationError.keyReadFailed(.corrupt)), .keysUnusable)
        XCTAssertEqual(LaunchGate.rebuildOutcome(error: WalletCreationError.keyReadFailed(.authenticationCancelled)), .retryOnRequest)
        XCTAssertEqual(LaunchGate.rebuildOutcome(error: WalletCreationError.keyReadFailed(.authenticationFailed)), .retryOnRequest)
        XCTAssertEqual(LaunchGate.rebuildOutcome(error: WalletCreationError.metadataStorageFailed), .retryOnRequest)
    }

    // MARK: - Invalidated keys with metadata (restore behind the PIN)

    /// The finding: an invalidated key used to open the shell on an address
    /// whose key cannot be used, and the restore refused because an envelope
    /// was there. Now: lock, unlock, restore screen; the wrong phrase is
    /// refused, the right one replaces the envelope, and the PIN stays.
    func testInvalidatedKeysBehindAPinRestoreAfterTheUnlockAndKeepThePin() async throws {
        let record = try await phraseWallet()
        try await storePin()
        try invalidateKeys()
        let oldEnvelope = try XCTUnwrap(envelope)
        let gate = makeGate()

        let atLaunch = await gate.restoreRoute()
        XCTAssertEqual(atLaunch, .hold(.keysInvalidated(record)), "behind the lock, not the shell")
        XCTAssertTrue(gate.auth.isGated)

        let unlocked = await gate.auth.unlock(pin: "111111")
        XCTAssertTrue(unlocked)
        let afterUnlock = await gate.restoreRoute(pending: .keysInvalidated(record))
        XCTAssertEqual(afterUnlock, .restore(.keysInvalidated(record)))

        let pinService = gate.pinService
        let model = OnboardingViewModel(
            creator: creator(),
            restoring: record,
            keysInvalidated: true,
            hasPin: { pinService.pinPresence != .absent }
        )
        XCTAssertEqual(model.step, .importWallet)
        XCTAssertTrue(model.restoresInvalidatedKeys)

        await model.importMnemonic(words: Self.otherPhrase, name: "ignored")
        XCTAssertEqual(model.step, .importWallet)
        XCTAssertEqual(
            model.errorMessage,
            "That does not match this wallet. Enter the recovery phrase or private key for the address shown."
        )
        XCTAssertEqual(envelope, oldEnvelope, "the wrong phrase writes nothing")

        await model.importMnemonic(words: WalletCreatorTests.testPhrase, name: "ignored")
        XCTAssertNil(model.errorMessage)
        XCTAssertEqual(model.step, .done, "the PIN that is there stays; no PIN step")

        let newEnvelope = try XCTUnwrap(envelope)
        XCTAssertNotEqual(newEnvelope, oldEnvelope, "the old envelope was replaced")
        let health = await keyStore().keyHealth
        XCTAssertEqual(health, .usable)
        let bundle = try await keyStore().load(reason: "test")
        XCTAssertEqual(bundle.privateKeyHex, WalletCreatorTests.testPrivateKeyHex)
        XCTAssertEqual(walletStore.load()?.id, record.id, "the same wallet")
        let pin = await pinPresence()
        XCTAssertEqual(pin, .present, "PIN kept")
        let route = await gate.restoreRoute()
        XCTAssertEqual(route, LaunchGate.RestoreRoute.none)
    }

    func testInvalidatedKeysWithNoPinRestoreAtOnce() async throws {
        let record = try await phraseWallet()
        try invalidateKeys()

        let route = await makeGate().restoreRoute()

        XCTAssertEqual(route, .restore(.keysInvalidated(record)))
    }

    func testARawKeyWalletWithInvalidatedKeysRestoresFromItsKey() async throws {
        let record = try await creator().importPrivateKey(hex: WalletCreatorTests.testPrivateKeyHex, name: "Key").record
        try invalidateKeys()

        let restored = try await creator().restorePrivateKey(hex: WalletCreatorTests.testPrivateKeyHex, replacing: record)

        XCTAssertEqual(restored.record.id, record.id)
        let bundle = try await keyStore().load(reason: "test")
        XCTAssertEqual(bundle.privateKeyHex, WalletCreatorTests.testPrivateKeyHex)
    }

    func testTheInvalidatedRestoreScreenSaysWhy() {
        XCTAssertEqual(
            OnboardingViewModel.invalidatedPhraseRestoreMessage,
            "This device can no longer unlock this wallet's keys, for example after a change to Face ID or the passcode. Enter the recovery phrase to restore it."
        )
        XCTAssertEqual(
            OnboardingViewModel.replaceUnusableKeysMessage,
            "This device can no longer unlock the wallet that was here. Enter a recovery phrase to restore a wallet."
        )
    }

    // MARK: - Usable keys without metadata (rebuild after the unlock)

    func testUsableKeysWithoutMetadataAreRebuiltAfterTheUnlock() async throws {
        try await phraseWallet()
        try walletStore.delete()
        try await storePin()
        let gate = makeGate()

        let atLaunch = await gate.restoreRoute()
        XCTAssertEqual(atLaunch, .hold(.rebuildMetadata), "the rebuild decrypts the keys, so it waits for the PIN")
        _ = await gate.auth.unlock(pin: "111111")
        let afterUnlock = await gate.restoreRoute(pending: .rebuildMetadata)
        XCTAssertEqual(afterUnlock, .restore(.rebuildMetadata))

        let rebuilt = try await creator().rebuildMetadata(reason: "test")

        XCTAssertEqual(rebuilt.type, WalletCreator.typeMnemonic)
        XCTAssertEqual(rebuilt.derivationPath, WalletCreator.derivationPath)
        XCTAssertEqual(rebuilt.mainnetAddress, WalletCreatorTests.testMainnetAddress)
        XCTAssertEqual(rebuilt.testnetAddress, WalletCreatorTests.testTestnetAddress)
        XCTAssertFalse(rebuilt.mnemonicBackedUp, "the backup prompt shows again")
        XCTAssertEqual(walletStore.load(), rebuilt)

        let home = HomeViewModel(walletStore: walletStore, preferences: preferences)
        preferences.setSelectedNetwork(network: .mainnet)
        home.refresh()
        XCTAssertEqual(home.address, WalletCreatorTests.testMainnetAddress, "the shell shows the derived address")
        XCTAssertTrue(home.needsBackup)

        let route = await gate.restoreRoute()
        XCTAssertEqual(route, LaunchGate.RestoreRoute.none)
        let destination = await gate.launchDestination
        XCTAssertEqual(destination, .wallet)
        let pin = await pinPresence()
        XCTAssertEqual(pin, .present)
    }

    func testRawKeysWithoutMetadataRebuildARawKeyRecord() async throws {
        try await creator().importPrivateKey(hex: WalletCreatorTests.testPrivateKeyHex, name: "Key")
        try walletStore.delete()

        let route = await makeGate().restoreRoute()
        XCTAssertEqual(route, .restore(.rebuildMetadata), "no PIN: at once")
        let rebuilt = try await creator().rebuildMetadata(reason: "test")

        XCTAssertEqual(rebuilt.type, WalletCreator.typeRawKey)
        XCTAssertNil(rebuilt.derivationPath)
        XCTAssertEqual(rebuilt.mainnetAddress, WalletCreatorTests.testMainnetAddress)
    }

    func testUndecodableMetadataWithUsableKeysIsSetAsideAndRebuilt() async throws {
        try await phraseWallet()
        try Data("not json".utf8).write(to: walletFile)

        let route = await makeGate().restoreRoute()
        XCTAssertEqual(route, .restore(.rebuildMetadata))
        let rebuilt = try await creator().rebuildMetadata(reason: "test")

        XCTAssertEqual(walletStore.load(), rebuilt)
        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.appendingPathComponent("wallet.unreadable.json").path))
    }

    func testARebuildIsRefusedWhileARecordIsThere() async throws {
        try await phraseWallet()

        do {
            try await creator().rebuildMetadata(reason: "test")
            XCTFail("a readable record must not be rebuilt over")
        } catch let error as WalletCreationError {
            XCTAssertEqual(error, .walletAlreadyExists)
        }
    }

    // MARK: - Invalidated keys without metadata (import replaces them)

    func testInvalidatedKeysWithoutMetadataImportReplacesThemAndKeepsThePin() async throws {
        try await creator().importPrivateKey(hex: WalletCreatorTests.testPrivateKeyHex, name: "Key")
        try walletStore.delete()
        try await storePin()
        try invalidateKeys()
        let gate = makeGate()

        let atLaunch = await gate.restoreRoute()
        XCTAssertEqual(atLaunch, .hold(.replaceKeys))
        _ = await gate.auth.unlock(pin: "111111")
        let afterUnlock = await gate.restoreRoute(pending: .replaceKeys)
        XCTAssertEqual(afterUnlock, .restore(.replaceKeys))

        let pinService = gate.pinService
        let model = OnboardingViewModel.replacingUnusableKeys(
            creator: creator(),
            hasPin: { pinService.pinPresence != .absent }
        )
        XCTAssertTrue(model.isRestoring)
        XCTAssertTrue(model.replacesUnusableKeys)
        XCTAssertNil(model.restoringRecord)
        model.backToWelcome()
        XCTAssertEqual(model.step, .importWallet, "no welcome: create would be refused")

        // Any phrase: there is nothing to check it against.
        await model.importMnemonic(words: Self.otherPhrase, name: "Fresh")

        XCTAssertNil(model.errorMessage)
        XCTAssertEqual(model.step, .done)
        let health = await keyStore().keyHealth
        XCTAssertEqual(health, .usable)
        let bundle = try await keyStore().load(reason: "test")
        XCTAssertEqual(bundle.mnemonic, Self.otherPhrase.joined(separator: " "))
        let record = try XCTUnwrap(walletStore.load())
        XCTAssertEqual(record.name, "Fresh")
        XCTAssertEqual(record.type, WalletCreator.typeMnemonic)
        let pin = await pinPresence()
        XCTAssertEqual(pin, .present, "PIN kept")
        let route = await gate.restoreRoute()
        XCTAssertEqual(route, LaunchGate.RestoreRoute.none)
    }

    func testAReplaceIsRefusedForUsableKeysOrARecord() async throws {
        let record = try await phraseWallet()
        do {
            try await creator().replaceUnusableKeys(words: Self.otherPhrase, name: "x")
            XCTFail("usable keys must never be replaced")
        } catch let error as WalletCreationError {
            XCTAssertEqual(error, .walletAlreadyExists)
        }

        try invalidateKeys()
        do {
            try await creator().replaceUnusableKeys(words: Self.otherPhrase, name: "x")
            XCTFail("with a record, only that wallet's phrase may restore it")
        } catch let error as WalletCreationError {
            XCTAssertEqual(error, .walletAlreadyExists)
        }
        XCTAssertEqual(walletStore.load(), record)
    }

    // MARK: - Unknown health

    /// A Keychain that refuses a lookup is not evidence of anything: no
    /// restore, no import, nothing deleted, and asked again later.
    func testUnknownHealthNeverRestoresOrDeletesAndIsAskedAgain() async throws {
        let record = try await phraseWallet()
        let before = try XCTUnwrap(envelope)
        let stub = StubKeyWrapper(tag: tag)
        stub.overridePresence(.unknown)
        let gate = makeGate(wrapper: stub)

        let route = await gate.restoreRoute()
        XCTAssertEqual(route, LaunchGate.RestoreRoute.none, "carry on behind the lock as before")
        let held = await gate.restoreRoute(pending: .keysInvalidated(record))
        XCTAssertEqual(held, .hold(.keysInvalidated(record)), "a pending recovery is kept, not started")

        let unknownCreator = creator(keyStore(wrapper: stub))
        do {
            try await unknownCreator.restoreMnemonic(words: WalletCreatorTests.testPhrase, replacing: record)
            XCTFail("unknown is not invalidated")
        } catch let error as WalletCreationError {
            XCTAssertEqual(error, .walletAlreadyExists)
        }
        do {
            try await keyStore(wrapper: stub).replaceUnusableKeys(with: WalletKeyBundle(privateKeyHex: WalletCreatorTests.testPrivateKeyHex))
            XCTFail("unknown is not replaceable")
        } catch let error as WalletKeyStoreError {
            XCTAssertEqual(error, .notReplaceable)
        }
        XCTAssertEqual(envelope, before, "nothing deleted or replaced")
        XCTAssertTrue(wrapper.hasKey)
        XCTAssertEqual(walletStore.load(), record)

        stub.overridePresence(nil)
        let later = await gate.restoreRoute(pending: .keysInvalidated(record))
        XCTAssertEqual(later, LaunchGate.RestoreRoute.none, "the re-check finds the keys usable")
    }

    func testAnUnreadableEnvelopeWithNoMetadataIsNeitherRebuiltNorReplaced() async throws {
        try await phraseWallet()
        try walletStore.delete()
        let unreadable = UnreadableKeyValueStore(service: keyService)

        let route = await makeGate(keychain: unreadable).restoreRoute()

        XCTAssertEqual(route, LaunchGate.RestoreRoute.none)
        XCTAssertNotNil(envelope)
    }

    // MARK: - Replacement failing part way

    func testAFailedEnvelopeWriteLeavesTheOldEnvelopeAndStaysInvalidated() async throws {
        try await phraseWallet()
        try invalidateKeys()
        let before = try XCTUnwrap(envelope)
        let failing = FailingKeyValueStore(service: keyService)
        failing.failWrites(true)
        let store = keyStore(keychain: failing)

        do {
            try await store.replaceUnusableKeys(with: WalletKeyBundle(privateKeyHex: WalletCreatorTests.testPrivateKeyHex))
            XCTFail("the write was refused")
        } catch let error as WalletKeyStoreError {
            XCTAssertEqual(error, .keychain(errSecIO))
        }

        XCTAssertEqual(envelope, before, "the old envelope is still there")
        XCTAssertFalse(wrapper.hasKey, "the fresh key was removed again")
        var health = await store.keyHealth
        XCTAssertEqual(health, .invalidated, "and the state still reads as needing a restore")

        failing.failWrites(false)
        try await store.replaceUnusableKeys(with: WalletKeyBundle(privateKeyHex: WalletCreatorTests.testPrivateKeyHex))
        health = await store.keyHealth
        XCTAssertEqual(health, .usable)
        let bundle = try await store.load(reason: "test")
        XCTAssertEqual(bundle.privateKeyHex, WalletCreatorTests.testPrivateKeyHex)
    }

    func testAFailedWrapLeavesTheOldEnvelope() async throws {
        try await phraseWallet()
        try invalidateKeys()
        let before = try XCTUnwrap(envelope)
        let stub = StubKeyWrapper(tag: tag)
        let store = keyStore(wrapper: stub)
        let health = await store.keyHealth
        XCTAssertEqual(health, .invalidated)
        stub.fail(with: .keyCreationFailed(errSecIO))

        do {
            try await store.replaceUnusableKeys(with: WalletKeyBundle(privateKeyHex: WalletCreatorTests.testPrivateKeyHex))
            XCTFail("the wrap was refused")
        } catch let error as WalletKeyStoreError {
            XCTAssertEqual(error, .keychain(errSecIO))
        }

        XCTAssertEqual(envelope, before)
    }

    func testACorruptEnvelopeIsReplacedUnderAFreshKey() async throws {
        try await phraseWallet()
        try keyKeychain.set(Data([0x02, 0x00]), account: WalletKeyAccount.envelope)
        let store = keyStore()

        try await store.replaceUnusableKeys(with: WalletKeyBundle(privateKeyHex: WalletCreatorTests.testPrivateKeyHex))

        let health = await store.keyHealth
        XCTAssertEqual(health, .usable)
        let bundle = try await store.load(reason: "test")
        XCTAssertEqual(bundle.privateKeyHex, WalletCreatorTests.testPrivateKeyHex)
    }

    func testReplaceIsRefusedForAbsentOrUsableKeys() async throws {
        let store = keyStore()
        let bundle = WalletKeyBundle(privateKeyHex: WalletCreatorTests.testPrivateKeyHex)
        do {
            try await store.replaceUnusableKeys(with: bundle)
            XCTFail("absent is not replaceable")
        } catch let error as WalletKeyStoreError {
            XCTAssertEqual(error, .notReplaceable)
        }

        try await phraseWallet()
        let before = envelope
        do {
            try await store.replaceUnusableKeys(with: bundle)
            XCTFail("usable is not replaceable")
        } catch let error as WalletKeyStoreError {
            XCTAssertEqual(error, .notReplaceable)
        }
        XCTAssertEqual(envelope, before)
    }
}
