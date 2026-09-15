import PocketNodeCore
import XCTest

@testable import PocketNode

@MainActor
final class ReceiveViewModelTests: XCTestCase {
    private let suiteName = "com.rjnr.pocketnode.tests.receive.prefs"

    private var directory: URL!
    private var walletStore: WalletStore!
    private var defaults: UserDefaults!
    private var preferences: UserDefaultsPreferences!

    // `async` on purpose: see `PinServiceTests` — the non-async override runs
    // task-isolated and cannot touch this `@MainActor` test case's properties.
    override func setUp() async throws {
        try await super.setUp()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("com.rjnr.pocketnode.tests.receiveVM-\(UUID().uuidString)")
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
        try await super.tearDown()
    }

    private func makeRecord(type: String = "mnemonic", backedUp: Bool = false) -> WalletRecord {
        WalletRecord(
            id: "w1",
            name: "Main Wallet",
            type: type,
            derivationPath: type == "mnemonic" ? "m/44'/309'/0'/0/0" : nil,
            mainnetAddress: "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqjmpk4",
            testnetAddress: "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqjmpk4",
            mnemonicBackedUp: backedUp,
            createdAt: 1_700_000_000_000
        )
    }

    // MARK: - Address follows the selected network

    func testAddressIsTheTestnetAddressWhenTestnetIsSelected() throws {
        try walletStore.save(makeRecord())
        preferences.setSelectedNetwork(network: .testnet)

        let vm = ReceiveViewModel(walletStore: walletStore, preferences: preferences, onBackUp: {})

        XCTAssertEqual(vm.address, makeRecord().testnetAddress)
    }

    func testAddressIsTheMainnetAddressWhenMainnetIsSelected() throws {
        try walletStore.save(makeRecord())
        preferences.setSelectedNetwork(network: .mainnet)

        let vm = ReceiveViewModel(walletStore: walletStore, preferences: preferences, onBackUp: {})

        XCTAssertEqual(vm.address, makeRecord().mainnetAddress)
    }

    func testRefreshPicksUpANetworkSwitch() throws {
        try walletStore.save(makeRecord())
        preferences.setSelectedNetwork(network: .mainnet)
        let vm = ReceiveViewModel(walletStore: walletStore, preferences: preferences, onBackUp: {})
        XCTAssertEqual(vm.address, makeRecord().mainnetAddress)

        preferences.setSelectedNetwork(network: .testnet)
        vm.refresh()

        XCTAssertEqual(vm.address, makeRecord().testnetAddress)
    }

    func testAddressIsEmptyWithNoWalletStored() {
        let vm = ReceiveViewModel(walletStore: walletStore, preferences: preferences, onBackUp: {})

        XCTAssertEqual(vm.address, "")
    }

    // MARK: - Backup prompt

    func testShowsBackupPromptForAnUnbackedUpMnemonicWallet() throws {
        try walletStore.save(makeRecord(type: "mnemonic", backedUp: false))

        let vm = ReceiveViewModel(walletStore: walletStore, preferences: preferences, onBackUp: {})

        XCTAssertTrue(vm.showBackupPrompt)
    }

    func testHidesBackupPromptForABackedUpMnemonicWallet() throws {
        try walletStore.save(makeRecord(type: "mnemonic", backedUp: true))

        let vm = ReceiveViewModel(walletStore: walletStore, preferences: preferences, onBackUp: {})

        XCTAssertFalse(vm.showBackupPrompt)
    }

    func testHidesBackupPromptForARawKeyWalletEvenIfNotBackedUp() throws {
        try walletStore.save(makeRecord(type: "raw_key", backedUp: false))

        let vm = ReceiveViewModel(walletStore: walletStore, preferences: preferences, onBackUp: {})

        XCTAssertFalse(vm.showBackupPrompt)
    }

    func testDismissBackupPromptHidesItWithoutCallingOnBackUp() throws {
        try walletStore.save(makeRecord(type: "mnemonic", backedUp: false))
        var backUpCalled = false
        let vm = ReceiveViewModel(walletStore: walletStore, preferences: preferences, onBackUp: { backUpCalled = true })
        XCTAssertTrue(vm.showBackupPrompt)

        vm.dismissBackupPrompt()

        XCTAssertFalse(vm.showBackupPrompt)
        XCTAssertFalse(backUpCalled)
    }

    func testBackUpNowHidesThePromptAndCallsOnBackUp() throws {
        try walletStore.save(makeRecord(type: "mnemonic", backedUp: false))
        var backUpCalled = false
        let vm = ReceiveViewModel(walletStore: walletStore, preferences: preferences, onBackUp: { backUpCalled = true })

        vm.backUpNow()

        XCTAssertFalse(vm.showBackupPrompt)
        XCTAssertTrue(backUpCalled)
    }
}
