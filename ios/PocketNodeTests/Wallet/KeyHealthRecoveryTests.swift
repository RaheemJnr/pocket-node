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
        XCTAssertEqual(decide(.record(record), .suspended), .recover(.keysSuspended(record)))

        for metadata in [LaunchGate.MetadataState.missing, .undecodable] {
            XCTAssertEqual(decide(metadata, .usable), .recover(.rebuildMetadata))
            XCTAssertEqual(decide(metadata, .absent), .none)
            XCTAssertEqual(decide(metadata, .invalidated), .recover(.replaceKeys))
            XCTAssertEqual(decide(metadata, .unknown), .wait)
            XCTAssertEqual(decide(metadata, .suspended), .recover(.rebuildMetadata), "never the import")
        }

        for health in [KeyHealth.usable, .absent, .invalidated, .suspended, .unknown] {
            XCTAssertEqual(decide(.unreadable, health), .wait)
        }
    }

    func testTheRebuildOutcomes() {
        let anyError = WalletCreationError.keyReadFailed(.corrupt)
        XCTAssertEqual(LaunchGate.rebuildOutcome(error: nil, healthAfter: .usable), .rebuilt)
        XCTAssertEqual(LaunchGate.rebuildOutcome(error: anyError, healthAfter: .invalidated), .keysUnusable)
        XCTAssertEqual(LaunchGate.rebuildOutcome(error: anyError, healthAfter: .usable), .retryOnRequest)
        XCTAssertEqual(LaunchGate.rebuildOutcome(error: anyError, healthAfter: .unknown), .retryOnRequest)
    }

    /// Which decrypt failures count as proof the keys are unusable: an
    /// allowlist of a confirmed-absent or refusing key and a ciphertext or
    /// bundle that fails after the unwrap. Nothing else.
    func testWhichFailuresProveTheKeysUnusable() {
        XCTAssertTrue(WalletKeyStoreError.keyInvalidated.provesKeysUnusable)
        XCTAssertTrue(WalletKeyStoreError.corrupt.provesKeysUnusable)
        XCTAssertFalse(WalletKeyStoreError.wrapping("x").provesKeysUnusable)
        XCTAssertFalse(WalletKeyStoreError.keychain(-50).provesKeysUnusable)
        XCTAssertFalse(WalletKeyStoreError.keychain(errSecAuthFailed).provesKeysUnusable)
        XCTAssertFalse(WalletKeyStoreError.keychain(errSecInteractionNotAllowed).provesKeysUnusable)
        XCTAssertFalse(WalletKeyStoreError.notReplaceable.provesKeysUnusable)
        XCTAssertFalse(WalletKeyStoreError.authenticationCancelled.provesKeysUnusable)
        XCTAssertFalse(WalletKeyStoreError.authenticationFailed.provesKeysUnusable)
        XCTAssertFalse(WalletKeyStoreError.notFound.provesKeysUnusable)
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
            "This device can no longer unlock this wallet's keys. Enter the recovery phrase to restore it."
        )
        XCTAssertEqual(
            OnboardingViewModel.replaceUnusableKeysMessage,
            "This device can no longer unlock the wallet that was here. Enter a recovery phrase or private key to restore a wallet."
        )
        XCTAssertEqual(
            OnboardingViewModel.missingPhraseRestoreMessage,
            "This wallet's keys are no longer on this device. Enter the recovery phrase to restore it."
        )
        for message in [
            OnboardingViewModel.missingPhraseRestoreMessage, OnboardingViewModel.missingKeyRestoreMessage,
            OnboardingViewModel.invalidatedPhraseRestoreMessage, OnboardingViewModel.invalidatedKeyRestoreMessage,
            OnboardingViewModel.replaceUnusableKeysMessage,
        ] {
            XCTAssertFalse(message.contains("Face ID"), "no cause is claimed")
            XCTAssertFalse(message.contains("delete the app"), "no reinstall advice (#557 review)")
        }
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

    // MARK: - Review probes (#557)

    /// The envelope parses and its key is there, but the ciphertext does not
    /// authenticate. The rebuild finds that out; the import it routes to
    /// must then accept a phrase instead of refusing as if the keys worked.
    func testCorruptCiphertextRebuildRoutesToAReplaceThatWorks() async throws {
        try await phraseWallet()
        try walletStore.delete()
        var bytes = try XCTUnwrap(envelope)
        bytes[bytes.count - 1] ^= 0xFF
        try keyKeychain.set(bytes, account: WalletKeyAccount.envelope)
        let store = keyStore()
        let walletCreator = creator(store)
        let gate = LaunchGate(
            walletStore: walletStore,
            walletKeyStore: store,
            keyKeychain: keyKeychain,
            pinKeychain: pinKeychain,
            preferences: preferences,
            biometrics: StubBiometrics(availability: .unavailable),
            pinCost: .testing
        )
        let first = await gate.restoreRoute()
        XCTAssertEqual(first, .restore(.rebuildMetadata))

        var failure: Error?
        do { try await walletCreator.rebuildMetadata(reason: "test") } catch { failure = error }
        let outcome = await gate.rebuildOutcome(error: failure)
        XCTAssertEqual(outcome, .keysUnusable)
        let next = await gate.restoreRoute()
        XCTAssertEqual(next, .restore(.replaceKeys), "the routing agrees the keys are unusable")

        let model = OnboardingViewModel.replacingUnusableKeys(creator: walletCreator, hasPin: { false })
        await model.importMnemonic(words: Self.otherPhrase, name: "Fresh")

        XCTAssertNil(model.errorMessage, "the import must not dead-end")
        XCTAssertEqual(model.step, .pinSetup)
        let health = await store.keyHealth
        XCTAssertEqual(health, .usable)
        let bundle = try await store.load(reason: "test")
        XCTAssertEqual(bundle.mnemonic, Self.otherPhrase.joined(separator: " "))
    }

    /// The process dies after the old key is deleted and the fresh key has
    /// wrapped, before the envelope is written: the old envelope and a key
    /// that cannot unwrap it. That must not read as usable.
    func testCrashBetweenWrapAndEnvelopeWriteLeavesARecoverableState() async throws {
        let record = try await phraseWallet()
        try invalidateKeys()
        try wrapper.deleteKey()
        _ = try wrapper.wrap(Data(repeating: 1, count: 32))

        let health = await keyStore().keyHealth
        XCTAssertEqual(health, .invalidated)
        let route = await makeGate().restoreRoute()
        XCTAssertEqual(route, .restore(.keysInvalidated(record)))
        let restored = try await creator().restoreMnemonic(words: WalletCreatorTests.testPhrase, replacing: record)
        XCTAssertEqual(restored.record.id, record.id)
        let bundle = try await keyStore().load(reason: "test")
        XCTAssertEqual(bundle.privateKeyHex, WalletCreatorTests.testPrivateKeyHex)
    }

    /// A wrapping key under the right tag that is not the one the envelope
    /// was made with (an erase and an encrypted-backup restore onto the same
    /// device can leave this): present, but it unwraps nothing.
    func testPresentButUnusableKeyHasARecoveryRoute() async throws {
        let record = try await phraseWallet()
        try wrapper.deleteKey()
        _ = try wrapper.wrap(Data(repeating: 2, count: 32))
        XCTAssertTrue(wrapper.hasKey)

        let route = await makeGate().restoreRoute()

        XCTAssertEqual(route, .restore(.keysInvalidated(record)))
    }

    // MARK: - (a) The key label in the envelope

    /// Every store records the wrapping key's label, read as an attribute
    /// under a context that forbids UI (on the simulator it answers with no
    /// prompt; `WalletKeyStoreDeviceTests` covers the Enclave).
    func testTheStoredEnvelopeRecordsTheWrappingKeysLabel() async throws {
        try await phraseWallet()
        guard case .label(let label) = wrapper.keyLabel else { return XCTFail("the label reads without a prompt") }
        XCTAssertEqual(label.count, 20)

        let decoded = try WalletKeyEnvelope.decode(try XCTUnwrap(envelope))

        XCTAssertEqual(decoded.keyLabel, label)
        XCTAssertEqual(try XCTUnwrap(envelope).first, WalletKeyEnvelope.versionWithKeyLabel)
    }

    /// An envelope written before the label existed still parses, loads and
    /// reads as usable through the existence check.
    func testALegacyEnvelopeWithoutALabelStillWorks() async throws {
        try await phraseWallet()
        try downgradeEnvelopeToVersion1()

        let store = keyStore()
        let health = await store.keyHealth
        XCTAssertEqual(health, .usable)
        let bundle = try await store.load(reason: "test")
        XCTAssertEqual(bundle.privateKeyHex, WalletCreatorTests.testPrivateKeyHex)
    }

    /// The legacy envelope has no label to compare, so a different key under
    /// the tag passes the existence check; the first decrypt proves it
    /// unusable and the restore is offered from then on.
    func testALegacyEnvelopeUnderAnotherKeyIsCaughtAtTheFirstDecrypt() async throws {
        let record = try await phraseWallet()
        try downgradeEnvelopeToVersion1()
        try wrapper.deleteKey()
        _ = try wrapper.wrap(Data(repeating: 3, count: 32))
        let store = keyStore()
        let gate = LaunchGate(
            walletStore: walletStore,
            walletKeyStore: store,
            keyKeychain: keyKeychain,
            pinKeychain: pinKeychain,
            preferences: preferences,
            biometrics: StubBiometrics(availability: .unavailable),
            pinCost: .testing
        )
        let before = await gate.restoreRoute()
        XCTAssertEqual(before, LaunchGate.RestoreRoute.none, "nothing a prompt-free check can see")

        do {
            _ = try await store.load(reason: "test")
            XCTFail("another key cannot unwrap this envelope")
        } catch let error as WalletKeyStoreError {
            XCTAssertTrue(error.provesKeysUnusable)
        }

        // A refused decrypt only suspends the keys (round 3): the restore
        // with this wallet's phrase, plus a retry.
        let after = await gate.restoreRoute()
        XCTAssertEqual(after, .restore(.keysSuspended(record)))
        try await creator(store).restoreMnemonic(words: WalletCreatorTests.testPhrase, replacing: record)
        let health = await store.keyHealth
        XCTAssertEqual(health, .usable, "a successful replacement clears the evidence")
    }

    /// A replaced envelope is bound to the fresh key too, so a later swap of
    /// that key is caught without a prompt as well.
    func testAReplacedEnvelopeRecordsTheFreshKeysLabel() async throws {
        let record = try await phraseWallet()
        try invalidateKeys()
        try await creator().restoreMnemonic(words: WalletCreatorTests.testPhrase, replacing: record)

        guard case .label(let label) = wrapper.keyLabel else { return XCTFail("no label") }
        let decoded = try WalletKeyEnvelope.decode(try XCTUnwrap(envelope))
        XCTAssertEqual(decoded.keyLabel, label)

        try wrapper.deleteKey()
        _ = try wrapper.wrap(Data(repeating: 4, count: 32))
        let health = await keyStore().keyHealth
        XCTAssertEqual(health, .invalidated)
    }

    /// Rewrites the stored envelope in the version 1 layout, as a build
    /// before the label wrote it.
    private func downgradeEnvelopeToVersion1() throws {
        let decoded = try WalletKeyEnvelope.decode(try XCTUnwrap(envelope))
        let legacy = WalletKeyEnvelope.encode(wrappedDataKey: decoded.wrappedDataKey, ciphertext: decoded.ciphertext)
        try keyKeychain.set(legacy, account: WalletKeyAccount.envelope)
        XCTAssertEqual(try XCTUnwrap(envelope).first, WalletKeyEnvelope.version)
    }

    // MARK: - (b) Runtime evidence

    func testADismissedPromptIsNotEvidenceButAFailedDecryptIs() async throws {
        try await phraseWallet()
        let stub = StubKeyWrapper(tag: tag)
        let store = keyStore(wrapper: stub)

        stub.fail(with: .authenticationCancelled)
        _ = try? await store.load(reason: "test")
        var health = await store.keyHealth
        XCTAssertEqual(health, .usable, "a dismissed prompt says nothing about the keys")
        stub.fail(with: .authenticationFailed)
        _ = try? await store.load(reason: "test")
        health = await store.keyHealth
        XCTAssertEqual(health, .usable)

        stub.fail(with: .operationFailed("unwrap refused"))
        _ = try? await store.load(reason: "test")
        health = await store.keyHealth
        XCTAssertEqual(health, .usable, "an unclassified failure is not proof")

        // A ciphertext that does not authenticate after a good unwrap is.
        stub.fail(with: nil)
        var bytes = try XCTUnwrap(envelope)
        bytes[bytes.count - 1] ^= 0xFF
        try keyKeychain.set(bytes, account: WalletKeyAccount.envelope)
        _ = try? await store.load(reason: "test")
        health = await store.keyHealth
        XCTAssertEqual(health, .invalidated)
    }

    /// The proof is stored, not remembered: the envelope is retired in place
    /// with its data kept, so a fresh launch (a new store) reads it too.
    func testAProofRetiresTheEnvelopeAndSurvivesALaunch() async throws {
        try await phraseWallet()
        var bytes = try XCTUnwrap(envelope)
        bytes[bytes.count - 1] ^= 0xFF
        try keyKeychain.set(bytes, account: WalletKeyAccount.envelope)
        let before = try WalletKeyEnvelope.decode(bytes)

        do {
            _ = try await keyStore().load(reason: "test")
            XCTFail("the ciphertext does not authenticate")
        } catch let error as WalletKeyStoreError {
            XCTAssertEqual(error, .corrupt)
        }

        let after = try WalletKeyEnvelope.decode(try XCTUnwrap(envelope))
        XCTAssertEqual(after.keyLabel, WalletKeyEnvelope.retiredLabel(.structural), "a ciphertext that fails after a good unwrap is structural")
        XCTAssertEqual(after.wrappedDataKey, before.wrappedDataKey, "the original data is kept")
        XCTAssertEqual(after.ciphertext, before.ciphertext)
        let health = await keyStore().keyHealth
        XCTAssertEqual(health, .invalidated, "read from storage on the next launch")
    }

    /// An envelope retired by mistake heals: a decrypt that works binds it
    /// back to the key that opened it.
    func testARetiredEnvelopeThatDecryptsIsBoundBack() async throws {
        try await phraseWallet()
        let retired = try XCTUnwrap(WalletKeyEnvelope.retired(try XCTUnwrap(envelope)))
        try keyKeychain.set(retired, account: WalletKeyAccount.envelope)
        var health = await keyStore().keyHealth
        XCTAssertEqual(health, .suspended, "retired for no recorded reason, key present: the safe side")

        let bundle = try await keyStore().load(reason: "test")

        XCTAssertEqual(bundle.privateKeyHex, WalletCreatorTests.testPrivateKeyHex)
        health = await keyStore().keyHealth
        XCTAssertEqual(health, .usable)
    }

    /// A key that is not the one that wrapped the data key makes the ECIES
    /// decrypt fail with `errSecParam` every time: deterministic, so it is
    /// counted as proof. The right key still opens it.
    func testAWrongKeyDecryptFailsTheSameWayEveryTime() throws {
        let dataKey = Data(repeating: 5, count: 32)
        let wrapped = try wrapper.wrap(dataKey)
        XCTAssertEqual(try wrapper.unwrap(wrapped, reason: "test"), dataKey)

        try wrapper.deleteKey()
        _ = try wrapper.wrap(Data(repeating: 6, count: 32))
        for _ in 0..<5 {
            XCTAssertThrowsError(try wrapper.unwrap(wrapped, reason: "test")) { error in
                XCTAssertEqual(error as? KeyWrapperError, .decryptionFailed(errSecParam))
            }
        }
    }

    // MARK: - Version 1 upgrade and a fresh key per wallet (review round 2)

    func testASuccessfulLoadUpgradesAVersion1EnvelopeToVersion2() async throws {
        try await phraseWallet()
        try downgradeEnvelopeToVersion1()
        guard case .label(let label) = wrapper.keyLabel else { return XCTFail("no label") }

        _ = try await keyStore().load(reason: "test")

        let upgraded = try XCTUnwrap(envelope)
        XCTAssertEqual(upgraded.first, WalletKeyEnvelope.versionWithKeyLabel)
        XCTAssertEqual(try WalletKeyEnvelope.decode(upgraded).keyLabel, label)
        let bundle = try await keyStore().load(reason: "test")
        XCTAssertEqual(bundle.privateKeyHex, WalletCreatorTests.testPrivateKeyHex)
    }

    func testAVersion1EnvelopeStaysWhenTheLabelCannotBeRead() async throws {
        try await phraseWallet()
        try downgradeEnvelopeToVersion1()
        let stub = StubKeyWrapper(tag: tag)
        stub.overridePresence(.unknown)

        _ = try await keyStore(wrapper: stub).load(reason: "test")

        XCTAssertEqual(try XCTUnwrap(envelope).first, WalletKeyEnvelope.version)
    }

    /// A wrapping key left under the tag with no envelope is not inherited
    /// by the next wallet.
    func testANewWalletGetsAFreshKeyRatherThanAnOrphan() async throws {
        _ = try wrapper.wrap(Data(repeating: 8, count: 32))
        guard case .label(let orphan) = wrapper.keyLabel else { return XCTFail("no orphan key") }

        try await phraseWallet()

        guard case .label(let current) = wrapper.keyLabel else { return XCTFail("no key") }
        XCTAssertNotEqual(current, orphan)
        XCTAssertEqual(try WalletKeyEnvelope.decode(try XCTUnwrap(envelope)).keyLabel, current)
        let bundle = try await keyStore().load(reason: "test")
        XCTAssertEqual(bundle.privateKeyHex, WalletCreatorTests.testPrivateKeyHex)
    }

    /// No metadata and a transient failure during the rebuild: a retry,
    /// never the import that would replace the keys, and the key stays.
    func testATransientRebuildFailureIsARetryNotAReplace() async throws {
        try await phraseWallet()
        try walletStore.delete()
        let keyBefore = wrapper.keyLabel
        let stub = StubKeyWrapper(tag: tag)
        let store = keyStore(wrapper: stub)
        let gate = LaunchGate(
            walletStore: walletStore,
            walletKeyStore: store,
            keyKeychain: keyKeychain,
            pinKeychain: pinKeychain,
            preferences: preferences,
            biometrics: StubBiometrics(availability: .unavailable),
            pinCost: .testing
        )

        for failure: KeyWrapperError in [.keyCreationFailed(errSecAuthFailed), .operationFailed("token"), .keyCreationFailed(errSecIO)] {
            stub.fail(with: failure)
            var thrown: Error?
            do { try await creator(store).rebuildMetadata(reason: "test") } catch { thrown = error }
            let outcome = await gate.rebuildOutcome(error: thrown)
            XCTAssertEqual(outcome, .retryOnRequest, "\(failure)")
            let route = await gate.restoreRoute()
            XCTAssertEqual(route, .restore(.rebuildMetadata), "\(failure)")
        }

        XCTAssertEqual(wrapper.keyLabel, keyBefore, "the working key is untouched")
        stub.fail(with: nil)
        let rebuilt = try await creator(store).rebuildMetadata(reason: "test")
        XCTAssertEqual(rebuilt.mainnetAddress, WalletCreatorTests.testMainnetAddress)
    }

    /// The phrase reveal is the decrypt the wallet phase does today: one that
    /// proves the keys unusable is reported so the root reroutes; a
    /// dismissed prompt is not.
    func testARevealThatProvesTheKeysUnusableIsReported() async throws {
        var reports = 0
        let reader = StubWalletKeyReader(result: .failure(WalletKeyStoreError.keyInvalidated))
        let model = BackupViewModel(
            walletKeyStore: reader,
            walletStore: walletStore,
            auth: StubAuthGate(),
            isOnboarding: false,
            hasPin: { true },
            onKeysUnusable: { reports += 1 }
        )

        await model.reveal()
        XCTAssertEqual(reports, 1)

        for transient: WalletKeyStoreError in [
            .authenticationCancelled, .authenticationFailed, .keychain(errSecAuthFailed),
            .keychain(errSecMissingEntitlement), .wrapping("token"),
        ] {
            reader.set(result: .failure(transient))
            await model.reveal()
        }
        XCTAssertEqual(reports, 1, "a transient failure does not reroute")
    }

    // MARK: - (c) A replacement killed part way

    /// The process dies after each step of the replacement, for an envelope
    /// with a label and for a legacy one: every point reads as invalidated,
    /// and the restore then completes.
    func testAReplacementKilledAtAnyStepLeavesARestorableState() async throws {
        for legacy in [false, true] {
            for step in [WalletKeyStore.ReplacementStep.retired, .oldKeyDeleted, .wrapped] {
                try? keyKeychain.deleteAll()
                try? wrapper.deleteKey()
                try? walletStore.delete()
                let record = try await phraseWallet()
                if legacy { try downgradeEnvelopeToVersion1() }
                try invalidateKeys()
                let store = keyStore()
                await store.simulateKill(after: step)

                do {
                    try await creator(store).restoreMnemonic(words: WalletCreatorTests.testPhrase, replacing: record)
                    XCTFail("killed after \(step)")
                } catch {}

                let freshLaunch = keyStore()
                let health = await freshLaunch.keyHealth
                XCTAssertEqual(health, .invalidated, "killed after \(step), legacy \(legacy)")
                XCTAssertNotNil(envelope, "an envelope is always there")
                let route = await makeGate().restoreRoute()
                XCTAssertEqual(route, .restore(.keysInvalidated(record)), "killed after \(step), legacy \(legacy)")

                try await creator(freshLaunch).restoreMnemonic(words: WalletCreatorTests.testPhrase, replacing: record)
                let bundle = try await freshLaunch.load(reason: "test")
                XCTAssertEqual(bundle.privateKeyHex, WalletCreatorTests.testPrivateKeyHex)
            }
        }
    }

    /// The envelope write fails and so does the rollback's key delete: the
    /// retired envelope still reads as invalidated.
    func testAFailedWriteWhoseRollbackAlsoFailsStillReadsInvalidated() async throws {
        try await phraseWallet()
        try invalidateKeys()
        let failing = FailingKeyValueStore(service: keyService)
        let stub = StubKeyWrapper(tag: tag)
        let store = keyStore(keychain: failing, wrapper: stub)
        stub.afterWrap = {
            failing.failWrites(true)
            stub.fail(with: .deleteFailed(errSecIO))
        }

        do {
            try await store.replaceUnusableKeys(with: WalletKeyBundle(privateKeyHex: WalletCreatorTests.testPrivateKeyHex))
            XCTFail("the write was refused")
        } catch {}

        XCTAssertTrue(wrapper.hasKey, "the fresh key could not be removed")
        let health = await keyStore().keyHealth
        XCTAssertEqual(health, .invalidated)
    }

    // MARK: - Metadata writes (review items 4 and 5)

    /// An undecodable `wallet.json` that cannot be set aside is never
    /// written over.
    func testAnUndecodableRecordThatCannotBeSetAsideIsNotOverwritten() async throws {
        try await phraseWallet()
        try Data("not json".utf8).write(to: walletFile)
        let aside = directory.appendingPathComponent("wallet.unreadable.json")
        try FileManager.default.createDirectory(at: aside, withIntermediateDirectories: true)
        try Data("x".utf8).write(to: aside.appendingPathComponent("pinned"))
        try FileManager.default.setAttributes([.posixPermissions: 0o555], ofItemAtPath: aside.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: aside.path) }

        do {
            try await creator().rebuildMetadata(reason: "test")
            XCTFail("the set-aside failed, so nothing may be written")
        } catch let error as WalletCreationError {
            XCTAssertEqual(error, .metadataStorageFailed)
        }

        XCTAssertEqual(try Data(contentsOf: walletFile), Data("not json".utf8), "the undecodable file is untouched")
    }

    /// A record written while the rebuild's prompt was up is not
    /// overwritten.
    func testARecordWrittenDuringTheRebuildPromptIsNotOverwritten() async throws {
        try await phraseWallet()
        try walletStore.delete()
        let appeared = WalletRecord(
            id: "appeared", name: "Meanwhile", type: WalletCreator.typeMnemonic,
            mainnetAddress: "ckb1", testnetAddress: "ckt1", createdAt: 0
        )
        let stub = StubKeyWrapper(tag: tag)
        let file = walletFile
        let encoded = try JSONEncoder().encode(appeared)
        stub.beforeUnwrap = { try? encoded.write(to: file) }

        do {
            try await creator(keyStore(wrapper: stub)).rebuildMetadata(reason: "test")
            XCTFail("a record is there now")
        } catch let error as WalletCreationError {
            XCTAssertEqual(error, .walletAlreadyExists)
        }

        XCTAssertEqual(walletStore.load(), appeared)
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

        // The old envelope is still there, retired: same wrapped key and
        // ciphertext, a label that matches no key.
        let old = try WalletKeyEnvelope.decode(before)
        let now = try WalletKeyEnvelope.decode(try XCTUnwrap(envelope))
        XCTAssertEqual(now.wrappedDataKey, old.wrappedDataKey)
        XCTAssertEqual(now.ciphertext, old.ciphertext)
        XCTAssertEqual(now.keyLabel, WalletKeyEnvelope.retiredLabel(.structural), "the key was absent: structural")
        let after = await store.keyHealth
        XCTAssertEqual(after, .invalidated)
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
