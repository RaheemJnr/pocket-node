import XCTest

@testable import PocketNode

/// The launch decisions `AppContainer` delegates to ``LaunchGate``, over
/// throwaway Keychain services, wrapping-key tag, metadata directory and
/// defaults suite, through the real functions `RootView` calls.
@MainActor
final class LaunchGateTests: XCTestCase {
    private let keyService = "com.rjnr.pocketnode.tests.gate.keys"
    private let pinService = "com.rjnr.pocketnode.tests.gate.pin"
    private let tag = "com.rjnr.pocketnode.tests.gate.wrapper"
    private let suiteName = "com.rjnr.pocketnode.tests.gate.prefs"

    private var keyKeychain: KeychainStore!
    private var pinKeychain: KeychainStore!
    private var wrapper: SecureEnclaveKeyWrapper!
    private var directory: URL!
    private var walletStore: WalletStore!
    private var defaults: UserDefaults!

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

    private func makeGate(
        keyStore: (any KeyValueStoring)? = nil,
        pinStore: (any KeyValueStoring)? = nil,
        skipsOnboarding: Bool = false
    ) -> LaunchGate {
        LaunchGate(
            walletStore: walletStore,
            walletKeyStore: WalletKeyStore(keychain: keyStore ?? keyKeychain, wrapper: wrapper),
            keyKeychain: keyStore ?? keyKeychain,
            pinKeychain: pinStore ?? pinKeychain,
            preferences: preferences,
            biometrics: StubBiometrics(availability: .unavailable),
            pinCost: .testing,
            skipsOnboarding: skipsOnboarding
        )
    }

    private func storeOldPin() async throws {
        try await PinService(keychain: pinKeychain, cost: .testing).setPin("111111")
    }

    private func importWallet() async throws {
        let creator = WalletCreator(keyStore: WalletKeyStore(keychain: keyKeychain, wrapper: wrapper), walletStore: walletStore)
        try await creator.importPrivateKey(hex: WalletCreatorTests.testPrivateKeyHex, name: "Key")
    }

    private var walletFile: URL { directory.appendingPathComponent("wallet.json") }

    // MARK: - Orphaned PIN at launch

    func testLaunchClearsAPinLeftWithNoWallet() async throws {
        try await storeOldPin()
        preferences.isBiometricEnabled = true

        let gate = makeGate()

        XCTAssertEqual(gate.pinService.pinPresence, .absent)
        XCTAssertEqual(gate.auth.state, .noPin, "the first frame already has no PIN to answer")
        XCTAssertFalse(preferences.isBiometricEnabled)
    }

    func testLaunchLeavesAPinInFrontOfAWallet() async throws {
        try await importWallet()
        try await storeOldPin()

        let gate = makeGate()

        XCTAssertEqual(gate.pinService.pinPresence, .present)
        XCTAssertEqual(gate.auth.state, .locked)
    }

    // MARK: - Starting a new wallet

    /// The PIN delete fails at launch and again when onboarding starts a
    /// wallet. Creating the wallet anyway would leave the PIN step refusing
    /// every PIN and the next launch asking for one the user never chose, so
    /// nothing is created and the user is told what to do.
    func testAPinThatCannotBeClearedStopsTheWalletBeforeItIsCreated() async throws {
        try await storeOldPin()
        let refusing = FailingKeyValueStore(service: pinService)
        refusing.failDeletes(true)
        let gate = makeGate(pinStore: refusing)
        XCTAssertEqual(gate.auth.state, .locked, "the launch cleanup failed")
        let model = OnboardingViewModel(
            creator: WalletCreator(keyStore: WalletKeyStore(keychain: keyKeychain, wrapper: wrapper), walletStore: walletStore),
            prepareNewWallet: { await gate.prepareNewWallet() }
        )
        model.beginImport()

        await model.importPrivateKey(hex: WalletCreatorTests.testPrivateKeyHex, name: "Key")

        XCTAssertEqual(model.step, .importWallet)
        XCTAssertEqual(model.errorMessage, OnboardingViewModel.staleAppDataMessage)
        XCTAssertFalse(walletStore.hasWallet, "no wallet was created")
        XCTAssertFalse(try keyKeychain.contains(account: WalletKeyAccount.envelope))

        // The cleanup can succeed on a later try, and then the flow goes on.
        refusing.failDeletes(false)
        await model.importPrivateKey(hex: WalletCreatorTests.testPrivateKeyHex, name: "Key")
        XCTAssertNil(model.errorMessage)
        XCTAssertEqual(model.step, .pinSetup)
        XCTAssertEqual(gate.auth.state, .noPin)
        try await gate.auth.setPin("222222")
        XCTAssertEqual(gate.auth.state, .unlocked)
    }

    func testPrepareNewWalletClearsAnOrphanedPinItFindsAndAllowsTheStart() async throws {
        let gate = makeGate()
        // A PIN that appeared after launch, still with no wallet.
        try await storeOldPin()
        await gate.auth.refresh()
        XCTAssertEqual(gate.auth.state, .locked)

        let allowed = await gate.prepareNewWallet()

        XCTAssertTrue(allowed)
        XCTAssertEqual(gate.pinService.pinPresence, .absent)
        XCTAssertEqual(gate.auth.state, .noPin)
    }

    // MARK: - Launch destination and the security-setup gate

    func testAWalletWithNoPinResumesOnboardingAndNeedsSecuritySetup() async throws {
        try await importWallet()
        let gate = makeGate()

        let destination = await gate.launchDestination
        XCTAssertEqual(destination, .onboarding(.pinSetup))
        XCTAssertTrue(gate.needsSecuritySetup)
    }

    func testAWalletWithAPinOpensBehindTheLock() async throws {
        try await importWallet()
        try await storeOldPin()
        let gate = makeGate()

        let destination = await gate.launchDestination
        XCTAssertEqual(destination, .wallet)
        XCTAssertFalse(gate.needsSecuritySetup)
        XCTAssertTrue(gate.auth.isGated)
    }

    func testNoWalletStartsAtWelcome() async {
        let destination = await makeGate().launchDestination
        XCTAssertEqual(destination, .onboarding(.welcome))
    }

    /// No metadata, a key envelope the Keychain cannot look up, and a PIN.
    /// The envelope may well be there, so this is not a walletless device:
    /// welcome would let the user start a wallet that `prepareNewWallet`
    /// refuses for as long as the PIN stands. It goes behind the lock.
    func testAnUnreadableEnvelopeBehindAPinOpensBehindTheLockNotWelcome() async throws {
        try await storeOldPin()
        let unreadable = UnreadableKeyValueStore(service: keyService)
        let gate = makeGate(keyStore: unreadable)
        XCTAssertEqual(gate.pinService.pinPresence, .present, "an unknown envelope does not orphan the PIN")

        let destination = await gate.launchDestination

        XCTAssertEqual(destination, .wallet)
        XCTAssertTrue(gate.auth.isGated)
        XCTAssertFalse(gate.needsSecuritySetup)
        let reroute = await gate.reroute()
        XCTAssertNil(reroute, "the lock screen is the answer")
    }

    /// The same unreadable envelope with no PIN has nothing to lock behind,
    /// and nothing for a new wallet to collide with, so it still starts at
    /// welcome.
    func testAnUnreadableEnvelopeWithNoPinStartsAtWelcome() async {
        let gate = makeGate(keyStore: UnreadableKeyValueStore(service: keyService))

        let destination = await gate.launchDestination

        XCTAssertEqual(destination, .onboarding(.welcome))
    }

    func testTheSkipHookOpensTheShell() async throws {
        try walletStore.save(
            WalletRecord(
                id: "w", name: "n", type: WalletCreator.typeMnemonic,
                mainnetAddress: "ckb1", testnetAddress: "ckt1", createdAt: 0
            )
        )
        let gate = makeGate(skipsOnboarding: true)

        let destination = await gate.launchDestination
        XCTAssertEqual(destination, .wallet)
        XCTAssertFalse(gate.needsSecuritySetup)
    }

    /// The session says no PIN while the PIN store has since become
    /// unreadable with a PIN in it. The destination and the security-setup
    /// gate must come from the same read, or the shell routes to `.wallet`
    /// while the security gate draws a blank screen nothing moves on from.
    func testTheDestinationAndTheSecurityGateCannotDisagree() async throws {
        try await importWallet()
        let flaky = UnreadableKeyValueStore(service: pinService, status: nil)
        let gate = makeGate(pinStore: flaky)
        XCTAssertEqual(gate.auth.state, .noPin)
        try await storeOldPin()
        flaky.failReads(errSecInteractionNotAllowed)
        await gate.pinService.refresh()
        XCTAssertEqual(gate.pinService.pinPresence, .unknown)

        let destination = await gate.launchDestination

        XCTAssertEqual(destination, .wallet)
        XCTAssertFalse(gate.needsSecuritySetup, "the shell would be a blank screen")
        XCTAssertTrue(gate.auth.isGated, "an unreadable PIN store locks")
        let reroute = await gate.reroute()
        XCTAssertNil(reroute, "nothing to reroute to: the lock screen is the answer")
        XCTAssertFalse(gate.needsSecuritySetup)
    }

    /// The reroute `RootView` runs when the session changes: a PIN store that
    /// was unreadable at launch and turns out to hold no PIN sends the wallet
    /// back to the PIN step.
    func testRerouteSendsAPinlessWalletBackToOnboarding() async throws {
        try await importWallet()
        let flaky = UnreadableKeyValueStore(service: pinService)
        let gate = makeGate(pinStore: flaky)
        XCTAssertEqual(gate.auth.state, .locked, "unreadable at launch")
        let atLaunch = await gate.reroute()
        XCTAssertNil(atLaunch)

        flaky.failReads(nil)
        let reroute = await gate.reroute()

        XCTAssertEqual(reroute, .onboarding(.pinSetup))
    }

    // MARK: - Restoring missing keys

    private var keylessRecord: WalletRecord {
        WalletRecord(
            id: "restored", name: "From backup", type: WalletCreator.typeMnemonic,
            mainnetAddress: "ckb1", testnetAddress: "ckt1", createdAt: 0
        )
    }

    /// What `RootView` walks through for a backup restored onto a new phone
    /// with its PIN: metadata with no keys, held behind the lock, and the
    /// restore screen once the PIN is answered.
    func testMetadataWithoutKeysBehindAPinWaitsForTheUnlockThenRestores() async throws {
        try walletStore.save(keylessRecord)
        try await storeOldPin()
        let gate = makeGate()

        let atLaunch = await gate.restoreRoute()
        XCTAssertEqual(atLaunch, .hold(keylessRecord), "the restore screen names the wallet, so it waits")
        XCTAssertTrue(gate.auth.isGated, "the lock screen is what shows")

        let unlocked = await gate.auth.unlock(pin: "111111")
        XCTAssertTrue(unlocked)
        let afterUnlock = await gate.restoreRoute(pending: keylessRecord)

        XCTAssertEqual(afterUnlock, .restore(keylessRecord))
    }

    func testMetadataWithoutKeysAndNoPinRestoresAtOnce() async throws {
        try walletStore.save(keylessRecord)

        let route = await makeGate().restoreRoute()

        XCTAssertEqual(route, .restore(keylessRecord))
    }

    /// A Keychain lookup that fails right after the unlock says nothing about
    /// the keys. The restore being held must stay held, not be dropped for
    /// the wallet shell, and it goes ahead once the lookup answers.
    func testAnUnreadableEnvelopeAfterTheUnlockKeepsTheRestorePending() async throws {
        try walletStore.save(keylessRecord)
        try await storeOldPin()
        let flaky = UnreadableKeyValueStore(service: keyService, status: nil)
        let gate = makeGate(keyStore: flaky)
        let atLaunch = await gate.restoreRoute()
        XCTAssertEqual(atLaunch, .hold(keylessRecord))
        _ = await gate.auth.unlock(pin: "111111")

        flaky.failReads(errSecInteractionNotAllowed)
        let transient = await gate.restoreRoute(pending: keylessRecord)
        XCTAssertEqual(transient, .hold(keylessRecord), "only an envelope confirmed present ends a pending restore")

        flaky.failReads(nil)
        let recovered = await gate.restoreRoute(pending: keylessRecord)
        XCTAssertEqual(recovered, .restore(keylessRecord))
    }

    /// With no restore pending, an unreadable envelope is not a missing one:
    /// launch goes on to the PIN routing, as before.
    func testAnUnreadableEnvelopeWithNothingPendingIsNoRestore() async throws {
        try walletStore.save(keylessRecord)
        let route = await makeGate(keyStore: UnreadableKeyValueStore(service: keyService)).restoreRoute()

        XCTAssertEqual(route, LaunchGate.RestoreRoute.none)
    }

    func testAWalletWithItsKeysEndsAPendingRestore() async throws {
        try await importWallet()
        let record = try XCTUnwrap(walletStore.load())

        let route = await makeGate().restoreRoute(pending: record)

        XCTAssertEqual(route, LaunchGate.RestoreRoute.none)
    }

    func testTheSkipHookNeverRestores() async throws {
        try walletStore.save(keylessRecord)

        let route = await makeGate(skipsOnboarding: true).restoreRoute()

        XCTAssertEqual(route, LaunchGate.RestoreRoute.none)
    }

    // MARK: - Corrupt metadata with no keys

    func testAnUndecodableRecordWithNoKeysIsSetAsideAndTheDeviceStartsFresh() async throws {
        try Data("not json".utf8).write(to: walletFile)
        XCTAssertTrue(walletStore.hasWallet)

        let gate = makeGate()

        XCTAssertFalse(walletStore.hasWallet, "the corrupt record is out of the way")
        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.appendingPathComponent("wallet.unreadable.json").path))
        let destination = await gate.launchDestination
        XCTAssertEqual(destination, .onboarding(.welcome))
    }

    func testAnUndecodableRecordWithKeysIsLeftInPlace() async throws {
        try await importWallet()
        try Data("not json".utf8).write(to: walletFile)

        let gate = makeGate()

        XCTAssertTrue(walletStore.hasWallet)
        let destination = await gate.launchDestination
        XCTAssertEqual(destination, .onboarding(.backup), "keys are there: resume, do not start over")
    }

    func testAnUndecodableRecordWithAnUnreadableKeychainIsLeftInPlace() throws {
        try Data("not json".utf8).write(to: walletFile)

        _ = makeGate(keyStore: UnreadableKeyValueStore(service: keyService))

        XCTAssertTrue(walletStore.hasWallet, "nothing is known about the keys, so nothing is moved")
    }

    // MARK: - Copy

    func testAPinThatIsAlreadySetGetsItsOwnMessage() {
        XCTAssertEqual(
            PinSetupView.message(for: AuthServiceError.pinAlreadySet),
            PinSetupView.pinAlreadySetMessage
        )
        XCTAssertEqual(
            PinSetupView.message(for: PinServiceError.storeUnavailable(errSecIO)),
            "Could not save your PIN. Try again."
        )
    }
}
