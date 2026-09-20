import PocketNodeCore
import XCTest

@testable import PocketNode

/// The wallet shell's first screen: which address it shows, how it shortens
/// it, and when the backup banner belongs on screen.
@MainActor
final class HomeViewModelTests: XCTestCase {
    private let suiteName = "com.rjnr.pocketnode.tests.home.prefs"

    private var directory: URL!
    private var walletStore: WalletStore!
    private var defaults: UserDefaults!
    private var preferences: UserDefaultsPreferences!

    // `async` on purpose: see `PinServiceTests` — the non-async override runs
    // task-isolated and cannot touch this `@MainActor` test case's properties.
    override func setUp() async throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("com.rjnr.pocketnode.tests.homeVM-\(UUID().uuidString)")
        walletStore = WalletStore(directory: directory)
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
        defaults = UserDefaults(suiteName: suiteName)
        preferences = UserDefaultsPreferences(defaults: defaults)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: directory)
        defaults.removePersistentDomain(forName: suiteName)
        directory = nil
        walletStore = nil
        defaults = nil
        preferences = nil
    }

    private static let mainnetAddress = "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqhp5jft"
    private static let testnetAddress = "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqenlarn"

    private func makeRecord(
        type: String = WalletCreator.typeMnemonic,
        backedUp: Bool = false
    ) -> WalletRecord {
        WalletRecord(
            id: "w1",
            name: "Main Wallet",
            type: type,
            derivationPath: "m/44'/309'/0'/0/0",
            mainnetAddress: Self.mainnetAddress,
            testnetAddress: Self.testnetAddress,
            mnemonicBackedUp: backedUp,
            createdAt: 1_700_000_000_000
        )
    }

    private func makeModel() -> HomeViewModel {
        HomeViewModel(walletStore: walletStore, preferences: preferences)
    }

    // MARK: - Address

    func testTheAddressFollowsTheSelectedNetwork() throws {
        try walletStore.save(makeRecord())

        preferences.setSelectedNetwork(network: .mainnet)
        XCTAssertEqual(makeModel().address, Self.mainnetAddress)

        preferences.setSelectedNetwork(network: .testnet)
        XCTAssertEqual(makeModel().address, Self.testnetAddress)
    }

    func testTheWalletNameComesFromTheRecord() throws {
        try walletStore.save(makeRecord())

        XCTAssertEqual(makeModel().walletName, "Main Wallet")
    }

    func testWithNoWalletEverythingIsEmptyAndNothingIsNagged() {
        let model = makeModel()

        XCTAssertEqual(model.walletName, "")
        XCTAssertEqual(model.address, "")
        XCTAssertFalse(model.needsBackup)
    }

    // MARK: - Shortening

    func testALongAddressKeepsItsFirstTwelveAndLastEightCharacters() throws {
        try walletStore.save(makeRecord())
        preferences.setSelectedNetwork(network: .mainnet)

        let short = makeModel().shortAddress

        XCTAssertEqual(short, "ckb1qzda0cr0...fqhp5jft")
        XCTAssertTrue(Self.mainnetAddress.hasPrefix("ckb1qzda0cr0"))
        XCTAssertTrue(Self.mainnetAddress.hasSuffix("fqhp5jft"))
    }

    func testAnAddressNoLongerThanTheTwoEndsIsLeftWhole() {
        // 20 characters, exactly head + tail: abbreviating would hide
        // characters without shortening the line.
        let twenty = "12345678901234567890"
        XCTAssertEqual(twenty.count, HomeViewModel.headLength + HomeViewModel.tailLength)
        XCTAssertEqual(HomeViewModel.shortened(twenty), twenty)

        XCTAssertEqual(HomeViewModel.shortened("ckb1short"), "ckb1short")
        XCTAssertEqual(HomeViewModel.shortened(""), "")
    }

    func testOneCharacterPastTheEndsIsAbbreviated() {
        XCTAssertEqual(HomeViewModel.shortened("123456789012X34567890"), "123456789012...34567890")
    }

    // MARK: - Backup banner

    func testAnUnbackedUpMnemonicWalletIsNagged() throws {
        try walletStore.save(makeRecord(backedUp: false))

        XCTAssertTrue(makeModel().needsBackup)
    }

    func testABackedUpWalletIsNotNagged() throws {
        try walletStore.save(makeRecord(backedUp: true))

        XCTAssertFalse(makeModel().needsBackup)
    }

    func testARawKeyWalletIsNeverNagged() throws {
        try walletStore.save(makeRecord(type: WalletCreator.typeRawKey, backedUp: false))

        XCTAssertFalse(makeModel().needsBackup, "a raw-key wallet has no phrase to back up")
    }

    func testRefreshPicksUpABackupCompletedElsewhere() throws {
        try walletStore.save(makeRecord(backedUp: false))
        let model = makeModel()
        XCTAssertTrue(model.needsBackup)

        // What `BackupViewModel.markBackedUpAndComplete` writes while the Home
        // screen is off screen behind the pushed backup flow.
        try walletStore.save(makeRecord(backedUp: true))
        model.refresh()

        XCTAssertFalse(model.needsBackup)
    }
}
