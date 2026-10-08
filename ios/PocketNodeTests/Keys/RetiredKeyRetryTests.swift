import XCTest
import Security

@testable import PocketNode

/// A decrypt refusal that may not repeat (#557 review round 3): it must
/// never let an unrelated phrase replace working keys, and a retry that
/// works must bring the wallet back. Each test asserts the safe behaviour.
@MainActor
final class RetiredKeyRetryTests: XCTestCase {
    private let keyService = "com.rjnr.pocketnode.tests.retiredretry.keys"
    private let tag = "com.rjnr.pocketnode.tests.retiredretry.wrapper"
    private var keyKeychain: KeychainStore!
    private var wrapper: SecureEnclaveKeyWrapper!
    private var directory: URL!
    private var walletStore: WalletStore!

    private static let otherPhrase = [
        "legal", "winner", "thank", "year", "wave", "sausage",
        "worth", "useful", "legal", "winner", "thank", "yellow",
    ]

    override func setUp() async throws {
        keyKeychain = KeychainStore(service: keyService)
        wrapper = SecureEnclaveKeyWrapper(tag: tag)
        try? keyKeychain.deleteAll()
        try? wrapper.deleteKey()
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("retiredretry-\(UUID().uuidString)")
        walletStore = WalletStore(directory: directory)
    }

    override func tearDown() async throws {
        try? keyKeychain.deleteAll()
        try? wrapper.deleteKey()
        try? FileManager.default.removeItem(at: directory)
    }

    /// A decryptionFailed (errSecParam or errSecDecode) that does not repeat,
    /// with no metadata, must not let any phrase replace the wallet.
    func testARefusedDecryptNeverLetsAnUnrelatedPhraseReplaceWorkingKeys() async throws {
        for status in [errSecParam, errSecDecode] {
            try? keyKeychain.deleteAll(); try? wrapper.deleteKey(); try? walletStore.delete()
            _ = try await WalletCreator(keyStore: WalletKeyStore(keychain: keyKeychain, wrapper: wrapper), walletStore: walletStore)
                .importMnemonic(words: WalletCreatorTests.testPhrase, name: "Savings")
            try walletStore.delete()
            let stub = StubKeyWrapper(tag: tag)
            let store = WalletKeyStore(keychain: keyKeychain, wrapper: stub)
            stub.fail(with: .decryptionFailed(status))
            _ = try? await store.load(reason: "probe")
            stub.fail(with: nil)
            let healthAfter = await store.keyHealth
            let creator = WalletCreator(keyStore: store, walletStore: walletStore)
            var replaced = false
            do {
                try await creator.replaceUnusableKeys(words: Self.otherPhrase, name: "Other")
                replaced = true
            } catch {}
            XCTAssertFalse(replaced, "status \(status): one transient decryptionFailed (health after = \(healthAfter)) let an unrelated phrase replace working keys")
        }
    }

    /// After such a refusal with no metadata, the gate routes to the rebuild
    /// (which retries the decrypt), and a decrypt that works re-binds.
    func testAMistakenRetireIsRetriedByTheRebuild() async throws {
        _ = try await WalletCreator(keyStore: WalletKeyStore(keychain: keyKeychain, wrapper: wrapper), walletStore: walletStore)
            .importMnemonic(words: WalletCreatorTests.testPhrase, name: "Savings")
        try walletStore.delete()
        let stub = StubKeyWrapper(tag: tag)
        let store = WalletKeyStore(keychain: keyKeychain, wrapper: stub)
        stub.fail(with: .decryptionFailed(errSecParam))
        _ = try? await store.load(reason: "probe")
        stub.fail(with: nil)
        let health = await store.keyHealth
        let decision = LaunchGate.recoveryDecision(metadata: .missing, health: health)
        let creator = WalletCreator(keyStore: store, walletStore: walletStore)
        var rebuilt = false
        do { _ = try await creator.rebuildMetadata(reason: "probe"); rebuilt = true } catch {}
        XCTAssertTrue(rebuilt, "decision=\(decision): rebuildMetadata refused although the key still decrypts")
    }

    /// A version 1 envelope is upgraded with the label of the key that
    /// opened it, and stays usable.
    func testAVersion1UpgradeBindsTheLabelOfTheKeyThatOpenedIt() async throws {
        _ = try await WalletCreator(keyStore: WalletKeyStore(keychain: keyKeychain, wrapper: wrapper), walletStore: walletStore)
            .importMnemonic(words: WalletCreatorTests.testPhrase, name: "Savings")
        let stored = try XCTUnwrap(try keyKeychain.get(account: WalletKeyAccount.envelope))
        let d = try WalletKeyEnvelope.decode(stored)
        try keyKeychain.set(WalletKeyEnvelope.encode(wrappedDataKey: d.wrappedDataKey, ciphertext: d.ciphertext), account: WalletKeyAccount.envelope)
        let store = WalletKeyStore(keychain: keyKeychain, wrapper: wrapper)
        _ = try await store.load(reason: "probe")
        let after = try WalletKeyEnvelope.decode(try XCTUnwrap(try keyKeychain.get(account: WalletKeyAccount.envelope)))
        guard case .label(let label) = wrapper.keyLabel else { return XCTFail("no label") }
        XCTAssertEqual(after.keyLabel, label)
        let health = await store.keyHealth
        XCTAssertEqual(health, .usable)
        let bundle = try await store.load(reason: "probe")
        XCTAssertEqual(bundle.privateKeyHex, WalletCreatorTests.testPrivateKeyHex)
    }

    // MARK: - Helpers

    private var envelope: Data? {
        try? keyKeychain.get(account: WalletKeyAccount.envelope)
    }

    @discardableResult
    private func phraseWallet() async throws -> WalletRecord {
        try await WalletCreator(keyStore: WalletKeyStore(keychain: keyKeychain, wrapper: wrapper), walletStore: walletStore)
            .importMnemonic(words: WalletCreatorTests.testPhrase, name: "Savings").record
    }

    private func gate(_ store: WalletKeyStore) -> LaunchGate {
        let pins = KeychainStore(service: "\(keyService).pin")
        try? pins.deleteAll()
        let suite = "\(keyService).prefs"
        UserDefaults.standard.removePersistentDomain(forName: suite)
        return LaunchGate(
            walletStore: walletStore,
            walletKeyStore: store,
            keyKeychain: keyKeychain,
            pinKeychain: pins,
            preferences: UserDefaultsPreferences(defaults: UserDefaults(suiteName: suite)!),
            biometrics: StubBiometrics(availability: .unavailable),
            pinCost: .testing
        )
    }

    // MARK: - (a) Retry once inside unwrap

    func testTheRetryAbsorbsOneRefusalAndReportsARepeatedOne() throws {
        var calls = 0
        let once = try SecureEnclaveKeyWrapper.retryingDecryptionFailureOnce {
            calls += 1
            if calls == 1 { throw KeyWrapperError.decryptionFailed(errSecParam) }
            return Data([1])
        }
        XCTAssertEqual(once, Data([1]))
        XCTAssertEqual(calls, 2)

        calls = 0
        XCTAssertThrowsError(try SecureEnclaveKeyWrapper.retryingDecryptionFailureOnce {
            calls += 1
            throw KeyWrapperError.decryptionFailed(errSecDecode)
        }) { XCTAssertEqual($0 as? KeyWrapperError, .decryptionFailed(errSecDecode)) }
        XCTAssertEqual(calls, 2)

        calls = 0
        XCTAssertThrowsError(try SecureEnclaveKeyWrapper.retryingDecryptionFailureOnce {
            calls += 1
            throw KeyWrapperError.authenticationCancelled
        })
        XCTAssertEqual(calls, 1, "nothing but a refusal is retried")
    }

    /// One refusal that does not repeat: the load works and nothing is
    /// retired. Two in a row: the envelope is suspended.
    func testOneRefusalLeavesTheKeysAloneAndTwoSuspendThem() async throws {
        try await phraseWallet()
        let stub = StubKeyWrapper(tag: tag)
        let store = WalletKeyStore(keychain: keyKeychain, wrapper: stub)
        let before = try XCTUnwrap(envelope)

        stub.failNextDecrypts([.decryptionFailed(errSecParam)])
        let bundle = try await store.load(reason: "test")
        XCTAssertEqual(bundle.privateKeyHex, WalletCreatorTests.testPrivateKeyHex)
        XCTAssertEqual(stub.decryptAttempts, 2)
        XCTAssertEqual(envelope, before, "nothing retired")
        var health = await store.keyHealth
        XCTAssertEqual(health, .usable)

        stub.failNextDecrypts([.decryptionFailed(errSecParam), .decryptionFailed(errSecParam)])
        do {
            _ = try await store.load(reason: "test")
            XCTFail("two refusals in a row")
        } catch let error as WalletKeyStoreError {
            XCTAssertEqual(error, .keyRefused)
        }
        health = await store.keyHealth
        XCTAssertEqual(health, .suspended)
        XCTAssertEqual(try WalletKeyEnvelope.decode(try XCTUnwrap(envelope)).keyLabel, WalletKeyEnvelope.retiredLabel(.keyRefused))
    }

    // MARK: - (b) and (c) Suspended keys: retry yes, replace with no record no

    /// With a record: the restore offers "Try unlocking again"; a retry that
    /// works binds the envelope back and the flow finishes into the wallet.
    func testTryUnlockingAgainBringsASuspendedWalletBack() async throws {
        let record = try await phraseWallet()
        let stub = StubKeyWrapper(tag: tag)
        let store = WalletKeyStore(keychain: keyKeychain, wrapper: stub)
        stub.failNextDecrypts([.decryptionFailed(errSecParam), .decryptionFailed(errSecParam)])
        _ = try? await store.load(reason: "test")
        let route = await gate(store).restoreRoute()
        XCTAssertEqual(route, .restore(.keysSuspended(record)))

        let creator = WalletCreator(keyStore: store, walletStore: walletStore)
        let model = OnboardingViewModel(
            creator: creator,
            restoring: record,
            keysInvalidated: true,
            hasPin: { true },
            retryUnlock: { try await creator.retryUnlock(reason: "test", matching: record) }
        )
        XCTAssertTrue(model.canRetryUnlock)
        XCTAssertEqual(OnboardingViewModel.retryUnlockTitle, "Try unlocking again")

        // A retry that fails again keeps the screen.
        stub.failNextDecrypts([.decryptionFailed(errSecParam), .decryptionFailed(errSecParam)])
        await model.retryUnlock()
        XCTAssertEqual(model.step, .importWallet)
        XCTAssertNotNil(model.errorMessage)

        await model.retryUnlock()
        XCTAssertNil(model.errorMessage)
        XCTAssertEqual(model.step, .done, "into the wallet")
        let health = await store.keyHealth
        XCTAssertEqual(health, .usable, "bound back")
        let after = await gate(store).restoreRoute()
        XCTAssertEqual(after, LaunchGate.RestoreRoute.none)
    }

    /// A restore for invalidated (not suspended) keys offers no retry.
    func testNoRetryIsOfferedWithoutARetryAction() async throws {
        let record = try await phraseWallet()
        let model = OnboardingViewModel(
            creator: WalletCreator(keyStore: WalletKeyStore(keychain: keyKeychain, wrapper: wrapper), walletStore: walletStore),
            restoring: record,
            keysInvalidated: true,
            hasPin: { true }
        )
        XCTAssertFalse(model.canRetryUnlock)
    }

    /// Suspended keys with no metadata: the gate routes to the rebuild, never
    /// to the import that would replace them, and the store refuses that
    /// replace too. The key stays.
    func testSuspendedKeysWithNoMetadataNeverReachTheImport() async throws {
        try await phraseWallet()
        try walletStore.delete()
        let stub = StubKeyWrapper(tag: tag)
        let store = WalletKeyStore(keychain: keyKeychain, wrapper: stub)
        let keyBefore = wrapper.keyLabel
        stub.fail(with: .decryptionFailed(errSecDecode))
        _ = try? await store.load(reason: "test")
        stub.fail(with: nil)

        let route = await gate(store).restoreRoute()
        XCTAssertEqual(route, .restore(.rebuildMetadata))
        do {
            try await store.replaceUnusableKeys(with: WalletKeyBundle(privateKeyHex: WalletCreatorTests.testPrivateKeyHex))
            XCTFail("suspended keys are not structurally invalid")
        } catch let error as WalletKeyStoreError {
            XCTAssertEqual(error, .notReplaceable)
        }
        XCTAssertEqual(wrapper.keyLabel, keyBefore)

        // A rebuild that fails again stays a retry.
        stub.fail(with: .decryptionFailed(errSecDecode))
        var thrown: Error?
        do { try await WalletCreator(keyStore: store, walletStore: walletStore).rebuildMetadata(reason: "test") } catch { thrown = error }
        let outcome = await gate(store).rebuildOutcome(error: thrown)
        XCTAssertEqual(outcome, .retryOnRequest)
    }

    /// Structural evidence still allows the import with no record: key
    /// absent, a real label mismatch, an envelope that does not parse, and
    /// a ciphertext that fails after a good unwrap.
    func testStructuralInvalidationStillAllowsTheImport() async throws {
        enum Case: CaseIterable { case keyAbsent, labelMismatch, unparseable, corrupt }
        for kind in Case.allCases {
            try? keyKeychain.deleteAll()
            try? wrapper.deleteKey()
            try? walletStore.delete()
            try await phraseWallet()
            try walletStore.delete()
            let store = WalletKeyStore(keychain: keyKeychain, wrapper: wrapper)
            switch kind {
            case .keyAbsent:
                try wrapper.deleteKey()
            case .labelMismatch:
                try wrapper.deleteKey()
                _ = try wrapper.wrap(Data(repeating: 3, count: 32))
            case .unparseable:
                try keyKeychain.set(Data([0x02, 0x00]), account: WalletKeyAccount.envelope)
            case .corrupt:
                var bytes = try XCTUnwrap(envelope)
                bytes[bytes.count - 1] ^= 0xFF
                try keyKeychain.set(bytes, account: WalletKeyAccount.envelope)
                _ = try? await store.load(reason: "test")
            }
            let health = await store.keyHealth
            XCTAssertEqual(health, .invalidated, "\(kind)")
            let route = await gate(store).restoreRoute()
            XCTAssertEqual(route, .restore(.replaceKeys), "\(kind)")
            try await WalletCreator(keyStore: store, walletStore: walletStore)
                .replaceUnusableKeys(words: Self.otherPhrase, name: "Fresh")
            let bundle = try await store.load(reason: "test")
            XCTAssertEqual(bundle.mnemonic, Self.otherPhrase.joined(separator: " "), "\(kind)")
        }
    }

    // MARK: - Finding 2: compare and retire

    /// An envelope that changed after the failed open is not retired.
    func testOnlyTheEnvelopeThatFailedIsRetired() async throws {
        try await phraseWallet()
        let store = WalletKeyStore(keychain: keyKeychain, wrapper: wrapper)
        let opened = try XCTUnwrap(envelope)
        // Another write lands in between (a restore, say).
        try await phraseWalletReplacingEnvelope()
        let current = try XCTUnwrap(envelope)
        XCTAssertNotEqual(current, opened)

        await store.retireUnusableBundle(ifStill: opened)
        XCTAssertEqual(envelope, current, "the newer envelope is untouched")
        var health = await store.keyHealth
        XCTAssertEqual(health, .usable)

        await store.retireUnusableBundle(ifStill: current)
        health = await store.keyHealth
        XCTAssertEqual(health, .invalidated, "the one that failed is retired, as structural")
    }

    /// Writes a new envelope for the same wallet under the same key, the way
    /// a concurrent store would.
    private func phraseWalletReplacingEnvelope() async throws {
        let store = WalletKeyStore(keychain: keyKeychain, wrapper: wrapper)
        try await store.store(WalletKeyBundle(privateKeyHex: WalletCreatorTests.testPrivateKeyHex, mnemonic: nil))
    }

    // MARK: - Final review (#557)

    /// Two refusals in a row: the envelope is suspended.
    private func suspend(_ store: WalletKeyStore, _ stub: StubKeyWrapper) async {
        stub.failNextDecrypts([.decryptionFailed(errSecParam), .decryptionFailed(errSecParam)])
        _ = try? await store.load(reason: "test")
    }

    private func suspendedModel(
        record: WalletRecord,
        creator: WalletCreator,
        matching: WalletRecord? = nil
    ) -> OnboardingViewModel {
        let target = matching ?? record
        return OnboardingViewModel(
            creator: creator,
            restoring: record,
            hasPin: { true },
            retryUnlock: { try await creator.retryUnlock(reason: "test", matching: target) }
        )
    }

    /// 1. A key lookup that misses once does not mark a working key's
    /// envelope: nothing is retired, health stays usable, and with no
    /// metadata the import that would replace the keys stays out of reach.
    func testAMissedKeyLookupDoesNotRetireTheEnvelope() async throws {
        try await phraseWallet()
        try walletStore.delete()
        let before = try XCTUnwrap(envelope)
        let keyBefore = wrapper.keyLabel
        let stub = StubKeyWrapper(tag: tag)
        let store = WalletKeyStore(keychain: keyKeychain, wrapper: stub)
        stub.fail(with: .keyNotFound)

        do {
            _ = try await store.load(reason: "test")
            XCTFail("the lookup missed")
        } catch let error as WalletKeyStoreError {
            XCTAssertEqual(error, .keyInvalidated)
        }
        stub.fail(with: nil)

        XCTAssertEqual(envelope, before, "nothing retired")
        let health = await store.keyHealth
        XCTAssertEqual(health, .usable, "the live check finds the key")
        let route = await gate(store).restoreRoute()
        XCTAssertEqual(route, .restore(.rebuildMetadata))
        do {
            try await WalletCreator(keyStore: store, walletStore: walletStore)
                .replaceUnusableKeys(words: Self.otherPhrase, name: "Other")
            XCTFail("working keys are never replaced")
        } catch {}
        XCTAssertEqual(wrapper.keyLabel, keyBefore, "the working key is not deleted")
    }

    /// 2. A bundle with no valid key inside a version 1 envelope: the load
    /// re-binds the envelope, and the retire must still find it (it compares
    /// against what is stored after the re-bind).
    func testABadBundleIsRetiredEvenAfterTheLoadRebindsTheEnvelope() async throws {
        let store = WalletKeyStore(keychain: keyKeychain, wrapper: wrapper)
        try await store.store(WalletKeyBundle(privateKeyHex: "not a key"))
        let decoded = try WalletKeyEnvelope.decode(try XCTUnwrap(envelope))
        try keyKeychain.set(
            WalletKeyEnvelope.encode(wrappedDataKey: decoded.wrappedDataKey, ciphertext: decoded.ciphertext),
            account: WalletKeyAccount.envelope
        )

        do {
            try await WalletCreator(keyStore: store, walletStore: walletStore).rebuildMetadata(reason: "test")
            XCTFail("the bundle holds no key")
        } catch let error as WalletCreationError {
            XCTAssertEqual(error, .keyReadFailed(.corrupt))
        }

        let health = await store.keyHealth
        XCTAssertEqual(health, .invalidated, "retired as structural, so the rebuild does not loop")
        XCTAssertEqual(try WalletKeyEnvelope.decode(try XCTUnwrap(envelope)).keyLabel, WalletKeyEnvelope.retiredLabel(.structural))
    }

    /// 3. A retry reports success only when the keys then read usable and
    /// derive the record's own addresses.
    func testTryUnlockingAgainNeedsUsableKeysForThisWallet() async throws {
        let record = try await phraseWallet()
        let stub = StubKeyWrapper(tag: tag)
        let store = WalletKeyStore(keychain: keyKeychain, wrapper: stub)
        let creator = WalletCreator(keyStore: store, walletStore: walletStore)
        await suspend(store, stub)

        // The decrypt works but the label cannot be read, so the envelope is
        // not bound back and the keys do not read usable.
        stub.overridePresence(.unknown)
        let unreadable = suspendedModel(record: record, creator: creator)
        await unreadable.retryUnlock()
        XCTAssertEqual(unreadable.step, .importWallet, "not finished")
        XCTAssertNotNil(unreadable.errorMessage)
        stub.overridePresence(nil)

        // The keys open, but they are another wallet's.
        let other = WalletRecord(
            id: record.id, name: record.name, type: record.type, derivationPath: record.derivationPath,
            mainnetAddress: "ckb1other", testnetAddress: "ckt1other", createdAt: record.createdAt
        )
        let mismatched = suspendedModel(record: other, creator: creator)
        await mismatched.retryUnlock()
        XCTAssertEqual(mismatched.step, .importWallet, "not finished for another wallet's keys")
        XCTAssertEqual(
            mismatched.errorMessage,
            "That does not match this wallet. Enter the recovery phrase or private key for the address shown."
        )
    }

    /// 4. Dismissing the prompt during a retry says nothing and is not a
    /// failed attempt.
    func testADismissedRetryShowsNoMessage() async throws {
        let record = try await phraseWallet()
        let stub = StubKeyWrapper(tag: tag)
        let store = WalletKeyStore(keychain: keyKeychain, wrapper: stub)
        await suspend(store, stub)
        let model = suspendedModel(record: record, creator: WalletCreator(keyStore: store, walletStore: walletStore))

        stub.fail(with: .authenticationCancelled)
        await model.retryUnlock()

        XCTAssertNil(model.errorMessage)
        XCTAssertEqual(model.failedRetryUnlocks, 0)
        XCTAssertNil(OnboardingViewModel.message(for: WalletCreationError.keyReadFailed(.authenticationCancelled)))
    }

    /// 5. Suspended keys get their own explanation: they may still unlock.
    func testSuspendedKeysHaveTheirOwnExplanation() async throws {
        let record = try await phraseWallet()
        let creator = WalletCreator(keyStore: WalletKeyStore(keychain: keyKeychain, wrapper: wrapper), walletStore: walletStore)

        XCTAssertEqual(
            suspendedModel(record: record, creator: creator).restoreExplanation,
            "Your wallet keys could not be unlocked. Try unlocking again, or enter the recovery phrase to restore it."
        )
        XCTAssertEqual(
            OnboardingViewModel(creator: creator, restoring: record, keysInvalidated: true, hasPin: { true }).restoreExplanation,
            OnboardingViewModel.invalidatedPhraseRestoreMessage
        )
        XCTAssertEqual(
            OnboardingViewModel(creator: creator, restoring: record, hasPin: { true }).restoreExplanation,
            OnboardingViewModel.missingPhraseRestoreMessage
        )
        let rawRecord = WalletRecord(
            id: "k", name: "Key", type: WalletCreator.typeRawKey,
            mainnetAddress: "ckb1", testnetAddress: "ckt1", createdAt: 0
        )
        XCTAssertEqual(
            suspendedModel(record: rawRecord, creator: creator).restoreExplanation,
            OnboardingViewModel.suspendedKeyRestoreMessage
        )
    }

    /// 7. After three failed retries in a session, the way out is said.
    func testTheReinstallHintFollowsThreeFailedRetries() async throws {
        let record = try await phraseWallet()
        let stub = StubKeyWrapper(tag: tag)
        let store = WalletKeyStore(keychain: keyKeychain, wrapper: stub)
        await suspend(store, stub)
        let model = suspendedModel(record: record, creator: WalletCreator(keyStore: store, walletStore: walletStore))

        stub.fail(with: .authenticationCancelled)
        await model.retryUnlock()
        stub.fail(with: .decryptionFailed(errSecParam))
        await model.retryUnlock()
        await model.retryUnlock()
        XCTAssertFalse(model.showsReinstallHint, "two failures, and a dismissal that does not count")
        await model.retryUnlock()
        XCTAssertTrue(model.showsReinstallHint)
        XCTAssertEqual(
            OnboardingViewModel.reinstallHint,
            "If this keeps failing, delete Pocket Node, install it again and restore with your recovery phrase."
        )
        XCTAssertFalse(OnboardingViewModel.showsReinstallHint(failedAttempts: 2))
        XCTAssertTrue(OnboardingViewModel.showsReinstallHint(failedAttempts: 3))
    }
}
