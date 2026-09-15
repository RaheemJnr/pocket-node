import XCTest

@testable import PocketNode

/// Exercises the generic-password layer on its own, against a throwaway service.
final class KeychainStoreTests: XCTestCase {
    private let store = KeychainStore(service: "com.rjnr.pocketnode.tests.keychain")

    override func setUp() {
        super.setUp()
        try? store.deleteAll()
    }

    override func tearDown() {
        try? store.deleteAll()
        super.tearDown()
    }

    func testSetThenGetReturnsTheSameBytes() throws {
        try store.set(Data([0x01, 0x02, 0x03]), account: "a")

        XCTAssertEqual(try store.get(account: "a"), Data([0x01, 0x02, 0x03]))
    }

    func testGetReturnsNilForAMissingAccount() throws {
        XCTAssertNil(try store.get(account: "missing"))
    }

    func testSetOverwritesAnExistingItem() throws {
        try store.set(Data([0x01]), account: "a")
        try store.set(Data([0x02]), account: "a")

        XCTAssertEqual(try store.get(account: "a"), Data([0x02]))
    }

    func testContainsTracksPresence() throws {
        XCTAssertFalse(try store.contains(account: "a"))

        try store.set(Data([0x01]), account: "a")
        XCTAssertTrue(try store.contains(account: "a"))

        try store.delete(account: "a")
        XCTAssertFalse(try store.contains(account: "a"))
    }

    /// Deleting something that is not there is not an error: the reinstall wipe
    /// and `WalletKeyStore.delete()` both rely on that.
    func testDeletingAMissingAccountSucceeds() throws {
        XCTAssertNoThrow(try store.delete(account: "missing"))
    }

    func testDeleteAllRemovesEveryAccountForTheService() throws {
        try store.set(Data([0x01]), account: "a")
        try store.set(Data([0x02]), account: "b")

        try store.deleteAll()

        XCTAssertNil(try store.get(account: "a"))
        XCTAssertNil(try store.get(account: "b"))
    }

    /// Services are separate namespaces, which is what keeps these tests off the
    /// real wallet's items.
    func testDeleteAllLeavesOtherServicesAlone() throws {
        let other = KeychainStore(service: "com.rjnr.pocketnode.tests.keychain.other")
        defer { try? other.deleteAll() }
        try store.set(Data([0x01]), account: "a")
        try other.set(Data([0x02]), account: "a")

        try store.deleteAll()

        XCTAssertEqual(try other.get(account: "a"), Data([0x02]))
    }
}
