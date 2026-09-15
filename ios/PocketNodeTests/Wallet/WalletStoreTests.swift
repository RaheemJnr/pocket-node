import XCTest

@testable import PocketNode

/// Round trips for the M2 single-wallet JSON store. Runs against a throwaway
/// directory under the process's temporary directory, so nothing here can
/// touch a real wallet file on the maintainer's simulator.
final class WalletStoreTests: XCTestCase {
    private var directory: URL!
    private var store: WalletStore!

    override func setUp() {
        super.setUp()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("com.rjnr.pocketnode.tests.walletStore-\(UUID().uuidString)")
        store = WalletStore(directory: directory)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: directory)
        directory = nil
        store = nil
        super.tearDown()
    }

    private func makeRecord(id: String = "w1") -> WalletRecord {
        WalletRecord(
            id: id,
            name: "Main Wallet",
            type: "mnemonic",
            derivationPath: "m/44'/309'/0'/0/0",
            mainnetAddress: "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqjmpk4",
            testnetAddress: "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqjmpk4",
            mnemonicBackedUp: false,
            createdAt: 1_700_000_000_000
        )
    }

    func testHasWalletIsFalseBeforeAnySave() {
        XCTAssertFalse(store.hasWallet)
        XCTAssertNil(store.load())
    }

    func testSaveThenLoadRoundTrips() throws {
        let record = makeRecord()

        try store.save(record)

        XCTAssertTrue(store.hasWallet)
        XCTAssertEqual(store.load(), record)
    }

    func testSaveOverwritesPreviousRecord() throws {
        try store.save(makeRecord(id: "w1"))
        let updated = makeRecord(id: "w1").withBackedUp(true)

        try store.save(updated)

        XCTAssertEqual(store.load(), updated)
    }

    func testDeleteRemovesTheRecord() throws {
        try store.save(makeRecord())
        XCTAssertTrue(store.hasWallet)

        try store.delete()

        XCTAssertFalse(store.hasWallet)
        XCTAssertNil(store.load())
    }

    func testDeleteWithoutAPriorSaveDoesNotThrow() {
        XCTAssertNoThrow(try store.delete())
    }

    func testRawKeyWalletHasNoDerivationPath() throws {
        let record = WalletRecord(
            id: "w2",
            name: "Imported Key",
            type: "raw_key",
            mainnetAddress: "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqjmpk4",
            testnetAddress: "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqjmpk4",
            createdAt: 1_700_000_000_000
        )

        try store.save(record)

        let loaded = try XCTUnwrap(store.load())
        XCTAssertNil(loaded.derivationPath)
        XCTAssertEqual(loaded.type, "raw_key")
    }

    /// Pins the on-disk JSON field names to Android's `WalletEntity`, since a
    /// later multi-wallet store is expected to import this file directly.
    func testJSONFieldNamesMatchAndroidWalletEntity() throws {
        try store.save(makeRecord())

        let data = try Data(contentsOf: directory.appendingPathComponent("wallet.json"))
        let json = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any]
        )

        let expectedKeys: Set<String> = [
            "id", "name", "type", "derivationPath",
            "mainnetAddress", "testnetAddress", "mnemonicBackedUp", "createdAt"
        ]
        XCTAssertEqual(Set(json.keys), expectedKeys)
    }
}

private extension WalletRecord {
    func withBackedUp(_ backedUp: Bool) -> WalletRecord {
        WalletRecord(
            id: id,
            name: name,
            type: type,
            derivationPath: derivationPath,
            mainnetAddress: mainnetAddress,
            testnetAddress: testnetAddress,
            mnemonicBackedUp: backedUp,
            createdAt: createdAt
        )
    }
}
