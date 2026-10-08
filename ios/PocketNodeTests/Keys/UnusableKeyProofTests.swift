import XCTest
import Security

@testable import PocketNode

/// Which failures count as proof that the keys are unusable (#557 review
/// round 2). Each test asserts the safe behaviour.
@MainActor
final class UnusableKeyProofTests: XCTestCase {
    private let keyService = "com.rjnr.pocketnode.tests.unusableproof.keys"
    private let tag = "com.rjnr.pocketnode.tests.unusableproof.wrapper"
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
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("unusableproof-\(UUID().uuidString)")
        walletStore = WalletStore(directory: directory)
    }

    override func tearDown() async throws {
        try? keyKeychain.deleteAll()
        try? wrapper.deleteKey()
        try? FileManager.default.removeItem(at: directory)
    }

    private func healthAfterTransient(_ failure: KeyWrapperError) async throws -> KeyHealth {
        try? keyKeychain.deleteAll()
        try? wrapper.deleteKey()
        try? walletStore.delete()
        _ = try await WalletCreator(keyStore: WalletKeyStore(keychain: keyKeychain, wrapper: wrapper), walletStore: walletStore)
            .importMnemonic(words: WalletCreatorTests.testPhrase, name: "Savings")
        let stub = StubKeyWrapper(tag: tag)
        let store = WalletKeyStore(keychain: keyKeychain, wrapper: stub)
        stub.fail(with: failure)
        _ = try? await store.load(reason: "probe")
        return await store.keyHealth
    }

    /// errSecAuthFailed from the key lookup is an authentication failure,
    /// not proof the keys are unusable.
    func testAuthFailedFromTheKeyLookupIsNotProof() async throws {
        let health = try await healthAfterTransient(.keyCreationFailed(errSecAuthFailed))
        XCTAssertNotEqual(health, .invalidated, "errSecAuthFailed marked healthy keys invalidated")
    }

    /// Other statuses a lookup can return transiently are not proof either.
    func testOtherTransientLookupStatusesAreNotProof() async throws {
        for status in [errSecMissingEntitlement, errSecNotAvailable, errSecInternalComponent, errSecIO] {
            let health = try await healthAfterTransient(.keyCreationFailed(status))
            XCTAssertNotEqual(health, .invalidated, "status \(status) marked healthy keys invalidated")
        }
    }

    /// Nor is an error from another domain (CryptoTokenKit, say).
    func testTokenDomainErrorsAreNotProof() async throws {
        let health = try await healthAfterTransient(.operationFailed("CryptoTokenKit communication error"))
        XCTAssertNotEqual(health, .invalidated, "a token-domain error marked healthy keys invalidated")
    }

    /// A transient failure followed by a decrypt that works reads usable.
    func testASuccessfulDecryptAfterATransientFailureReadsUsable() async throws {
        _ = try await WalletCreator(keyStore: WalletKeyStore(keychain: keyKeychain, wrapper: wrapper), walletStore: walletStore)
            .importMnemonic(words: WalletCreatorTests.testPhrase, name: "Savings")
        let stub = StubKeyWrapper(tag: tag)
        let store = WalletKeyStore(keychain: keyKeychain, wrapper: stub)
        stub.fail(with: .operationFailed("transient"))
        _ = try? await store.load(reason: "probe")
        stub.fail(with: nil)
        let bundle = try await store.load(reason: "probe")
        XCTAssertEqual(bundle.privateKeyHex, WalletCreatorTests.testPrivateKeyHex, "keys decrypt fine")
        let health = await store.keyHealth
        XCTAssertEqual(health, .usable, "a successful decrypt still reads invalidated")
    }

    /// With no metadata, a transient failure must never let any phrase
    /// replace a wallet whose keys still decrypt, nor delete its key.
    func testATransientFailureNeverLetsAnImportReplaceWorkingKeys() async throws {
        _ = try await WalletCreator(keyStore: WalletKeyStore(keychain: keyKeychain, wrapper: wrapper), walletStore: walletStore)
            .importMnemonic(words: WalletCreatorTests.testPhrase, name: "Savings")
        try walletStore.delete()
        let stub = StubKeyWrapper(tag: tag)
        let store = WalletKeyStore(keychain: keyKeychain, wrapper: stub)
        let keyBefore = wrapper.keyLabel
        stub.fail(with: .keyCreationFailed(errSecAuthFailed))
        _ = try? await store.load(reason: "probe")
        stub.fail(with: nil)
        let creator = WalletCreator(keyStore: store, walletStore: walletStore)
        do {
            try await creator.replaceUnusableKeys(words: Self.otherPhrase, name: "Other")
        } catch {}
        let bundle = try await store.load(reason: "probe")
        XCTAssertEqual(bundle.privateKeyHex, WalletCreatorTests.testPrivateKeyHex, "working wallet was replaced by another phrase")
        XCTAssertEqual(wrapper.keyLabel, keyBefore, "the working Secure Enclave key was not deleted")
    }

    /// A version 2 envelope sliced at a nonzero start index parses, and
    /// retiring it keeps the data under the retired label.
    func testAVersion2SliceParsesAndRetires() throws {
        let wrapped = Data(repeating: 7, count: 97)
        let ct = Data(repeating: 9, count: 60)
        let v2 = WalletKeyEnvelope.encode(wrappedDataKey: wrapped, ciphertext: ct, keyLabel: Data(repeating: 1, count: 20))
        let padded = Data([0xAA, 0xBB, 0xCC]) + v2
        let slice = padded[3...]
        let decoded = try WalletKeyEnvelope.decode(slice)
        XCTAssertEqual(decoded.wrappedDataKey, wrapped)
        XCTAssertEqual(decoded.keyLabel, Data(repeating: 1, count: 20))
        let retired = try XCTUnwrap(WalletKeyEnvelope.retired(v2))
        XCTAssertEqual(try WalletKeyEnvelope.decode(retired).keyLabel, WalletKeyEnvelope.retiredKeyLabel)
    }

    /// Malformed version 2 inputs throw and never crash.
    func testMalformedVersion2EnvelopesNeverCrash() {
        var inputs: [Data] = [Data([0x02]), Data([0x02, 0x00]), Data([0x02, 0xFF]), Data([0x02, 0x41]) + Data(repeating: 0, count: 65),
                              Data([0x02, 0x01, 0x00]), Data([0x02, 0x01, 0x00, 0, 0, 0, 1])]
        let v2 = WalletKeyEnvelope.encode(wrappedDataKey: Data(repeating: 7, count: 97), ciphertext: Data(repeating: 9, count: 60), keyLabel: Data(repeating: 1, count: 20))
        for n in 0..<v2.count { inputs.append(v2.prefix(n)) }
        for i in 0..<v2.count { var m = v2; m[i] = 0xFF; inputs.append(m) }
        for input in inputs { _ = try? WalletKeyEnvelope.decode(input) }
        for n in 0..<(2 + 20 + 4 + 97 + 28) {
            XCTAssertThrowsError(try WalletKeyEnvelope.decode(v2.prefix(n)), "prefix \(n) parsed")
        }
    }
}
