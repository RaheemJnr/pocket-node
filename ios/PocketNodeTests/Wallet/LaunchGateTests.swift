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

    // MARK: - The provisional wallet behind the lock

    func testTheProvisionalWalletDecision() {
        XCTAssertEqual(LaunchGate.provisionalWalletCheck(metadataExists: false, envelope: .absent), .startOver)
        XCTAssertEqual(LaunchGate.provisionalWalletCheck(metadataExists: false, envelope: .present), .stay)
        XCTAssertEqual(LaunchGate.provisionalWalletCheck(metadataExists: false, envelope: .unknown), .stay)
        XCTAssertEqual(LaunchGate.provisionalWalletCheck(metadataExists: true, envelope: .absent), .stay)
    }

    /// The launch could not look the envelope up and went behind the lock.
    /// After the unlock the lookup answers that there is none: the device
    /// starts over as a cold launch would, clearing the orphaned PIN and
    /// landing on Welcome, instead of an empty wallet shell.
    func testAnEnvelopeThatTurnsOutAbsentAfterTheUnlockStartsOver() async throws {
        try await storeOldPin()
        let flaky = UnreadableKeyValueStore(service: keyService)
        let gate = makeGate(keyStore: flaky)
        let atLaunch = await gate.launchDestination
        XCTAssertEqual(atLaunch, .wallet)
        let unlocked = await gate.auth.unlock(pin: "111111")
        XCTAssertTrue(unlocked)

        flaky.failReads(nil)
        let reroute = await gate.reroute()

        XCTAssertEqual(reroute, .onboarding(.welcome))
        XCTAssertEqual(gate.pinService.pinPresence, .absent, "the orphaned PIN is cleared")
        XCTAssertEqual(gate.auth.state, .noPin)
        let allowed = await gate.prepareNewWallet()
        XCTAssertTrue(allowed, "and a new wallet may be started")
    }

    /// Still unreadable after the unlock: stay, keep the PIN, ask again.
    func testAnEnvelopeThatStaysUnknownStaysAndKeepsThePin() async throws {
        try await storeOldPin()
        let gate = makeGate(keyStore: UnreadableKeyValueStore(service: keyService))
        _ = await gate.launchDestination
        _ = await gate.auth.unlock(pin: "111111")

        let reroute = await gate.reroute()

        XCTAssertNil(reroute)
        XCTAssertEqual(gate.pinService.pinPresence, .present)
        XCTAssertEqual(gate.auth.state, .unlocked)
    }

    /// Keys with no metadata are a wallet, so the device does not start
    /// over and the PIN stays. They no longer open an empty wallet shell
    /// either: the recovery route rebuilds the metadata from the keys first.
    func testAnEnvelopeThatTurnsOutPresentStaysAndIsRebuiltNotAnEmptyShell() async throws {
        try await importWallet()
        try walletStore.delete()
        try await storeOldPin()
        let gate = makeGate()
        _ = await gate.auth.unlock(pin: "111111")

        let route = await gate.restoreRoute()
        let reroute = await gate.reroute()

        XCTAssertEqual(route, .restore(.rebuildMetadata), "not the empty shell")
        XCTAssertNil(reroute, "no start-over: a wallet is there")
        XCTAssertEqual(gate.pinService.pinPresence, .present)
    }

    /// Metadata that is on disk but cannot be read is not an absence, even
    /// with the envelope confirmed absent: the PIN in front of it stays.
    func testUnreadableMetadataIsNotAnAbsenceAndKeepsThePin() async throws {
        try Data("{}".utf8).write(to: walletFile)
        try FileManager.default.setAttributes([.posixPermissions: 0o000], ofItemAtPath: walletFile.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: walletFile.path) }
        try await storeOldPin()
        let gate = makeGate()
        XCTAssertEqual(gate.pinService.pinPresence, .present, "the launch cleanup left it too")
        _ = await gate.auth.unlock(pin: "111111")

        let reroute = await gate.reroute()

        XCTAssertNil(reroute)
        XCTAssertEqual(gate.pinService.pinPresence, .present)
        XCTAssertTrue(walletStore.hasWallet)
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

    // MARK: - Starting sync

    /// Every combination of the four inputs: only a readable record, with
    /// usable keys, no security setup pending and no recovery held, may
    /// start sync. Suspended, invalidated and unknown keys never do.
    func testMaySyncOnlyForAWalletThatIsReallyThere() {
        let record = WalletRecord(
            id: "w", name: "n", type: WalletCreator.typeMnemonic,
            mainnetAddress: "ckb1", testnetAddress: "ckt1", createdAt: 0
        )
        let healths: [KeyHealth] = [.usable, .absent, .invalidated, .suspended, .unknown]
        var allowed = 0
        for candidate in [record, nil] as [WalletRecord?] {
            for health in healths {
                for needsSecuritySetup in [false, true] {
                    for pendingRestore in [false, true] {
                        let result = LaunchGate.maySync(
                            record: candidate,
                            health: health,
                            needsSecuritySetup: needsSecuritySetup,
                            pendingRestore: pendingRestore
                        )
                        let expected = candidate != nil && health == .usable && !needsSecuritySetup && !pendingRestore
                        XCTAssertEqual(
                            result, expected,
                            "record \(candidate != nil) health \(health) setup \(needsSecuritySetup) restore \(pendingRestore)"
                        )
                        if result { allowed += 1 }
                    }
                }
            }
        }
        XCTAssertEqual(allowed, 1, "exactly one of the 40 combinations")
    }

    /// Sync starts behind the lock screen: it reads no keys, and the PIN only
    /// has to exist, not be answered.
    func testAWalletWithKeysAndAPinMaySyncWhileLocked() async throws {
        try await importWallet()
        try await storeOldPin()
        let gate = makeGate()
        XCTAssertTrue(gate.auth.isGated)

        let allowed = await gate.maySync(pendingRestore: false)

        XCTAssertTrue(allowed)
    }

    /// A held restore keeps sync off even for a wallet whose keys are there;
    /// once `RootView` clears it, the same wallet may sync.
    func testAHeldRestoreKeepsSyncOff() async throws {
        try await importWallet()
        try await storeOldPin()
        let gate = makeGate()

        let whileHeld = await gate.maySync(pendingRestore: true)
        let afterwards = await gate.maySync(pendingRestore: false)

        XCTAssertFalse(whileHeld)
        XCTAssertTrue(afterwards)
    }

    /// An onboarding cut short before the PIN: not until the PIN is set.
    func testAWalletWithNoPinMayNotSyncUntilThePinIsSet() async throws {
        try await importWallet()
        let gate = makeGate()
        XCTAssertTrue(gate.needsSecuritySetup)

        let before = await gate.maySync(pendingRestore: false)
        try await gate.auth.setPin("222222")
        let after = await gate.maySync(pendingRestore: false)

        XCTAssertFalse(before)
        XCTAssertTrue(after)
    }

    /// Metadata whose keys did not come across with a device backup.
    func testAKeylessWalletMayNotSync() async throws {
        try walletStore.save(keylessRecord)
        try await storeOldPin()

        let allowed = await makeGate().maySync(pendingRestore: false)

        XCTAssertFalse(allowed)
    }

    /// A record whose envelope is still there but whose Secure Enclave key is
    /// gone (a Face ID or passcode change): the keys read as invalidated, so
    /// no sync until the recovery `RootView` routes to has run.
    func testAWalletWithInvalidatedKeysMayNotSync() async throws {
        try await importWallet()
        try await storeOldPin()
        let gate = makeGate()
        let before = await gate.maySync(pendingRestore: false)
        XCTAssertTrue(before)

        try wrapper.deleteKey()
        let health = await WalletKeyStore(keychain: keyKeychain, wrapper: wrapper).keyHealth
        let after = await gate.maySync(pendingRestore: false)

        XCTAssertEqual(health, .invalidated)
        XCTAssertFalse(after)
    }

    /// Keys with no metadata: there is no record to register.
    func testKeysWithNoRecordMayNotSync() async throws {
        try await importWallet()
        try walletStore.delete()
        try await storeOldPin()

        let allowed = await makeGate().maySync(pendingRestore: false)

        XCTAssertFalse(allowed)
    }

    /// A launch before the first device unlock cannot look the envelope up.
    /// No sync then; once the lookup answers, the same wallet may sync, which
    /// is what the next reroute asks.
    func testAnUnreadableEnvelopeWaitsAndThenSyncs() async throws {
        try await importWallet()
        try await storeOldPin()
        let flaky = UnreadableKeyValueStore(service: keyService)
        let gate = makeGate(keyStore: flaky)

        let whileUnreadable = await gate.maySync(pendingRestore: false)
        flaky.failReads(nil)
        let afterwards = await gate.maySync(pendingRestore: false)

        XCTAssertFalse(whileUnreadable)
        XCTAssertTrue(afterwards)
    }

    /// A `wallet.json` that data protection keeps unreadable at launch: no
    /// sync, then sync once it can be read.
    func testAnUnreadableRecordWaitsAndThenSyncs() async throws {
        try await importWallet()
        try await storeOldPin()
        let gate = makeGate()
        try FileManager.default.setAttributes([.posixPermissions: 0o000], ofItemAtPath: walletFile.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: walletFile.path) }

        let whileUnreadable = await gate.maySync(pendingRestore: false)
        try FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: walletFile.path)
        let afterwards = await gate.maySync(pendingRestore: false)

        XCTAssertFalse(whileUnreadable)
        XCTAssertTrue(afterwards)
    }

    /// The UI tests' seeded wallet has no keys and no PIN on purpose and
    /// still needs sync; with no record there is nothing to sync.
    func testTheSkipHookSyncsAnyReadableRecord() async throws {
        let empty = await makeGate(skipsOnboarding: true).maySync(pendingRestore: false)
        XCTAssertFalse(empty)

        try walletStore.save(keylessRecord)
        let seeded = await makeGate(skipsOnboarding: true).maySync(pendingRestore: false)
        XCTAssertTrue(seeded)
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
        XCTAssertEqual(atLaunch, .hold(.keysMissing(keylessRecord)), "the restore screen names the wallet, so it waits")
        XCTAssertTrue(gate.auth.isGated, "the lock screen is what shows")

        let unlocked = await gate.auth.unlock(pin: "111111")
        XCTAssertTrue(unlocked)
        let afterUnlock = await gate.restoreRoute(pending: .keysMissing(keylessRecord))

        XCTAssertEqual(afterUnlock, .restore(.keysMissing(keylessRecord)))
    }

    func testMetadataWithoutKeysAndNoPinRestoresAtOnce() async throws {
        try walletStore.save(keylessRecord)

        let route = await makeGate().restoreRoute()

        XCTAssertEqual(route, .restore(.keysMissing(keylessRecord)))
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
        XCTAssertEqual(atLaunch, .hold(.keysMissing(keylessRecord)))
        _ = await gate.auth.unlock(pin: "111111")

        flaky.failReads(errSecInteractionNotAllowed)
        let transient = await gate.restoreRoute(pending: .keysMissing(keylessRecord))
        XCTAssertEqual(transient, .hold(.keysMissing(keylessRecord)), "only an envelope confirmed present ends a pending restore")

        flaky.failReads(nil)
        let recovered = await gate.restoreRoute(pending: .keysMissing(keylessRecord))
        XCTAssertEqual(recovered, .restore(.keysMissing(keylessRecord)))
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

        let route = await makeGate().restoreRoute(pending: .keysMissing(record))

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
