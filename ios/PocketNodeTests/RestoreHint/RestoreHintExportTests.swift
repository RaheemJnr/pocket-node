import Foundation
import PocketNodeCore
import SwiftUI
import UIKit
import XCTest

@testable import PocketNode

/// Settings > "Export restore hint" (#559): what goes in the file, how the
/// export is gated, and that a sealed file outlives the screen.
@MainActor
final class RestoreHintExportTests: XCTestCase {
    private let suiteName = "com.rjnr.pocketnode.tests.restoreHintExport.prefs"
    private var defaults: UserDefaults!
    private var preferences: UserDefaultsPreferences!

    private static let record = WalletRecord(
        id: "w1",
        name: "Main Wallet",
        type: WalletCreator.typeMnemonic,
        derivationPath: "m/44'/309'/0'/0/0",
        mainnetAddress: WalletCreatorTests.testMainnetAddress,
        testnetAddress: WalletCreatorTests.testTestnetAddress,
        mnemonicBackedUp: true,
        createdAt: 1_700_000_000_000
    )

    override func setUp() async throws {
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
        defaults = UserDefaults(suiteName: suiteName)
        preferences = UserDefaultsPreferences(defaults: defaults)
    }

    override func tearDown() async throws {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        preferences = nil
    }

    // MARK: - Coverage

    func testCoverageIsTheModeDerivedStartWhenRegistrationRewroteTheLightStart() {
        // Registration rewrites the light start to the resume block; a CUSTOM
        // 5,000,000 wallet still covered from 5,000,000.
        let coverage = RestoreHintPayloadBuilder.coverageStart(
            lightStart: 10_000_000,
            mode: .custom,
            customHeight: 5_000_000,
            tipHeight: 22_700_000,
            network: .testnet
        )
        XCTAssertEqual(coverage, 5_000_000)
    }

    func testCoverageIsTheLightStartWhenItIsLower() {
        let coverage = RestoreHintPayloadBuilder.coverageStart(
            lightStart: 4_000_000,
            mode: .custom,
            customHeight: 5_000_000,
            tipHeight: 22_700_000,
            network: .testnet
        )
        XCTAssertEqual(coverage, 4_000_000)
    }

    func testFullHistoryCoversFromZero() {
        let coverage = RestoreHintPayloadBuilder.coverageStart(
            lightStart: 22_000_000,
            mode: .fullHistory,
            customHeight: nil,
            tipHeight: 22_700_000,
            network: .testnet
        )
        XCTAssertEqual(coverage, 0)
    }

    func testRecentCoversTheRecentWindow() {
        let coverage = RestoreHintPayloadBuilder.coverageStart(
            lightStart: 22_690_000,
            mode: .recent,
            customHeight: nil,
            tipHeight: 22_700_000,
            network: .testnet
        )
        XCTAssertEqual(coverage, 22_500_000)
    }

    func testAWalletWithNoModeFallsBackToZero() {
        let coverage = RestoreHintPayloadBuilder.coverageStart(
            lightStart: 22_000_000,
            mode: nil,
            customHeight: nil,
            tipHeight: 22_700_000,
            network: .testnet
        )
        XCTAssertEqual(coverage, 0)
    }

    // MARK: - Payload

    func testThePayloadIsCoarsenedAndHoldsNoAddressesOrAmounts() throws {
        let payload = RestoreHintPayloadBuilder.build(
            kind: RestoreHintKind.shared.MNEMONIC,
            network: .testnet,
            lightStart: 10_000_000,
            mode: .custom,
            customHeight: 5_123_456,
            tipHeight: 22_700_123,
            tipHash: "0x" + String(repeating: "ab", count: 32),
            blockNumbers: ["0x0", "0x5e3b2a", "0x" + String(9_876_543, radix: 16)],
            nowMs: 1_790_000_000_000
        )

        XCTAssertEqual(payload.accounts.count, 1)
        let account = try XCTUnwrap(payload.accounts.first)
        XCTAssertEqual(account.index, 0)
        XCTAssertEqual(account.coverageStart, 5_120_000)
        // 0x5e3b2a = 6,175,530, rounded down to 6,170,000. Block 0 is ignored.
        XCTAssertEqual(account.firstActivity?.int64Value, 6_170_000)
        XCTAssertEqual(account.syncMode, "CUSTOM")
        XCTAssertEqual(payload.tipHeight, 22_700_123)
        XCTAssertEqual(payload.network, "TESTNET")
        XCTAssertTrue(payload.discovery.found.isEmpty)

        let json = String(decoding: RestoreHintCodec.shared.encodePayload(payload: payload).data, as: UTF8.self)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        XCTAssertEqual(
            Set(object.keys),
            ["v", "network", "createdAtMs", "tipHeight", "tipHash", "kind", "accounts", "discovery"]
        )
        let accounts = try XCTUnwrap(object["accounts"] as? [[String: Any]])
        XCTAssertEqual(Set(accounts[0].keys), ["index", "coverageStart", "firstActivity", "syncMode"])
        XCTAssertFalse(json.contains("ckt1"))
        XCTAssertFalse(json.contains("ckb1"))
        XCTAssertFalse(json.lowercased().contains("capacity"))
        XCTAssertFalse(json.lowercased().contains("address"))
    }

    func testTheFileNameNamesTheNetwork() {
        XCTAssertEqual(RestoreHintPayloadBuilder.fileName(network: .testnet), "pocket-node-restore-hint-testnet.json")
        XCTAssertEqual(RestoreHintPayloadBuilder.fileName(network: .mainnet), "pocket-node-restore-hint-mainnet.json")
    }

    // MARK: - The export flow

    private func makeModel(
        keyReader: StubWalletKeyReader = StubWalletKeyReader(
            result: .success(WalletKeyBundle(
                privateKeyHex: WalletCreatorTests.testPrivateKeyHex,
                mnemonic: RestoreHintFixtures.abandonWords.joined(separator: " ")
            ))
        ),
        auth: StubAuthGate = StubAuthGate(),
        sources: StubRestoreHintSources = StubRestoreHintSources(lightStart: 22_690_000),
        record: WalletRecord? = RestoreHintExportTests.record,
        store: StubWalletRecordStore = StubWalletRecordStore(),
        savedMessageLifetime: Duration = .seconds(4)
    ) -> RestoreHintExportViewModel {
        store.recordToLoad = record
        return RestoreHintExportViewModel(
            walletKeyStore: keyReader,
            walletStore: store,
            auth: auth,
            preferences: preferences,
            sources: sources,
            now: { 1_790_000_000_000 },
            savedMessageLifetime: savedMessageLifetime
        )
    }

    func testEverythingButTheSecretIsReadBeforeTheAuthPrompt() async {
        let sources = StubRestoreHintSources(lightStart: 22_690_000)
        let auth = StubAuthGate()
        let readsAtPrompt = ReadsRecorder()
        auth.whilePrompting = { readsAtPrompt.values = [sources.lightStartReads, sources.blockNumberReads] }
        let keyReader = StubWalletKeyReader(
            result: .success(WalletKeyBundle(privateKeyHex: "00", mnemonic: RestoreHintFixtures.abandonWords.joined(separator: " ")))
        )
        let model = makeModel(keyReader: keyReader, auth: auth, sources: sources)

        await model.export()

        XCTAssertEqual(readsAtPrompt.values, [1, 1], "the chain-side inputs were read before the prompt")
        XCTAssertEqual(sources.lightStartReads, 1)
        XCTAssertEqual(sources.blockNumberReads, 1)
        XCTAssertNotNil(model.pendingDocument)
    }

    func testAFileSealedForAnotherWalletIsDroppedWhenSettingsAppears() async {
        let store = StubWalletRecordStore()
        let model = makeModel(store: store)
        await model.export()
        XCTAssertNotNil(model.pendingDocument)

        model.screenAppeared()
        XCTAssertNotNil(model.pendingDocument, "same wallet: the file waits")

        store.recordToLoad = WalletRecord(
            id: "w2",
            name: "Other",
            type: WalletCreator.typeMnemonic,
            derivationPath: "m/44'/309'/0'/0/0",
            mainnetAddress: WalletCreatorTests.testMainnetAddress,
            testnetAddress: WalletCreatorTests.testTestnetAddress,
            mnemonicBackedUp: true,
            createdAt: 0
        )
        model.screenAppeared()

        XCTAssertNil(model.pendingDocument)
        XCTAssertFalse(model.isPresentingSaver)
    }

    func testTheSavedLineIsTransient() async throws {
        let model = makeModel(savedMessageLifetime: .milliseconds(50))
        await model.export()

        model.saveSucceeded()
        XCTAssertEqual(model.message, "Restore hint saved")
        try await Task.sleep(for: .milliseconds(400))

        XCTAssertNil(model.message)
    }

    func testTheSavedLineIsClearedOnTheNextVisitButNotSavedStays() async {
        let model = makeModel()
        await model.export()
        model.saveSucceeded()

        model.screenAppeared()
        XCTAssertNil(model.message)

        await model.export()
        model.saveDidNotComplete()
        model.screenAppeared()
        XCTAssertEqual(model.message, "The restore hint was not saved. Try again.")
    }

    func testAnExportAuthenticatesAndHoldsAFileTheSamePhraseVerifies() async throws {
        preferences.setSyncMode(mode: .custom, network: .testnet, walletId: "w1")
        preferences.setCustomBlockHeight(height: KotlinLong(longLong: 22_585_000), network: .testnet, walletId: "w1")
        let auth = StubAuthGate()
        let model = makeModel(auth: auth)

        await model.export()

        XCTAssertEqual(auth.requestCount, 1)
        XCTAssertEqual(auth.lastReason, "Export restore hint")
        let document = try XCTUnwrap(model.pendingDocument)
        XCTAssertTrue(model.isPresentingSaver)
        XCTAssertEqual(document.fileName, "pocket-node-restore-hint-testnet.json")
        XCTAssertNil(model.message)

        // min(22,690,000, CUSTOM 22,585,000) = 22,585,000, rounded to 22,580,000.
        let result = RestoreHintVerifier.verify(
            fileText: document.text,
            secret: RestoreHintFixtures.abandonSecret(),
            network: .testnet
        )
        XCTAssertEqual(result, .ready(RestoreHintStart(startBlock: 22_579_000, sourceCoverageStart: 22_580_000)))
    }

    func testARawKeyWalletIsSealedWithItsPrivateKey() async throws {
        let rawRecord = WalletRecord(
            id: "w1",
            name: "Key",
            type: WalletCreator.typeRawKey,
            derivationPath: "",
            mainnetAddress: WalletCreatorTests.testMainnetAddress,
            testnetAddress: WalletCreatorTests.testTestnetAddress,
            mnemonicBackedUp: false,
            createdAt: 0
        )
        let model = makeModel(
            keyReader: StubWalletKeyReader(result: .success(WalletKeyBundle(privateKeyHex: WalletCreatorTests.testPrivateKeyHex))),
            record: rawRecord
        )

        await model.export()

        let document = try XCTUnwrap(model.pendingDocument)
        let key = RestoreHintKeyBytes(try XCTUnwrap(WalletCreator.decodePrivateKey(WalletCreatorTests.testPrivateKeyHex)))
        let result = await RestoreHintVerifier.verify(fileText: document.text, material: .privateKey(key), network: .testnet)
        guard case .ready = result else { return XCTFail("expected the raw key to verify its own file, got \(result)") }
    }

    func testARefusedPromptExportsNothing() async {
        let auth = StubAuthGate()
        auth.granted = false
        let keyReader = StubWalletKeyReader()
        let model = makeModel(keyReader: keyReader, auth: auth)

        await model.export()

        XCTAssertNil(model.pendingDocument)
        XCTAssertEqual(keyReader.loadCount, 0)
    }

    func testALockDuringTheKeyReadDiscardsTheExport() async {
        let auth = StubAuthGate()
        let keyReader = StubWalletKeyReader(
            result: .success(WalletKeyBundle(privateKeyHex: "00", mnemonic: RestoreHintFixtures.abandonWords.joined(separator: " ")))
        )
        keyReader.whileLoading = { await MainActor.run { auth.lockGeneration += 1 } }
        let model = makeModel(keyReader: keyReader, auth: auth)

        await model.export()

        XCTAssertNil(model.pendingDocument)
        XCTAssertNil(model.message)
    }

    func testAKeyReadFailureShowsTheGenericMessage() async {
        let model = makeModel(keyReader: StubWalletKeyReader(result: .failure(StubWalletKeyReaderError.unreadable)))

        await model.export()

        XCTAssertNil(model.pendingDocument)
        XCTAssertEqual(model.message, "Could not export the restore hint. Try again.")
    }

    func testNoTipYetSaysSoBeforeAskingForAuthentication() async {
        let auth = StubAuthGate()
        let model = makeModel(auth: auth, sources: StubRestoreHintSources(tip: nil))

        await model.export()

        XCTAssertNil(model.pendingDocument)
        XCTAssertEqual(auth.requestCount, 0)
        XCTAssertEqual(model.message, RestoreHintCopy.exportNoTip)
    }

    func testASaveThatDoesNotCompleteSaysTheHintWasNotSaved() async {
        let model = makeModel()
        await model.export()
        XCTAssertNotNil(model.pendingDocument)

        model.saveDidNotComplete()

        XCTAssertNil(model.pendingDocument)
        XCTAssertFalse(model.isPresentingSaver)
        XCTAssertEqual(model.message, "The restore hint was not saved. Try again.")
    }

    func testASavedFileSaysSo() async {
        let model = makeModel()
        await model.export()

        model.saveSucceeded()

        XCTAssertNil(model.pendingDocument)
        XCTAssertEqual(model.message, "Restore hint saved")
    }

    // MARK: - Survives a rebuild

    func testThePendingExportSurvivesTheSettingsScreenBeingRebuilt() async throws {
        let model = makeModel()
        await model.export()
        let document = try XCTUnwrap(model.pendingDocument)
        let auth = AuthService(
            pin: PinService(keychain: KeychainStore(service: "\(suiteName).pin"), cost: .testing),
            biometrics: StubBiometrics(availability: .faceID),
            preferences: preferences
        )

        // First screen: shown, then torn down (what a lock does to the stack).
        var host: UIHostingController<AnyView>? = host(for: SettingsView(auth: auth, restoreHint: model, onBackUp: {}))
        host = nil
        XCTAssertNil(host)
        try await Task.sleep(for: .milliseconds(200))

        XCTAssertEqual(model.pendingDocument, document)
        XCTAssertTrue(model.isPresentingSaver)

        // A rebuilt screen gets the same file back.
        let rebuilt = self.host(for: SettingsView(auth: auth, restoreHint: model, onBackUp: {}))
        XCTAssertNotNil(rebuilt)
        XCTAssertEqual(model.pendingDocument, document)
    }

    /// Drives the real document picker: present it from a hosted Settings
    /// screen, cancel it the way the system does, and check the file is
    /// reported as not saved and a second export works.
    func testCancellingTheRealSavePickerSaysNotSavedAndAllowsAnotherExport() async throws {
        let model = makeModel()
        await model.export()
        XCTAssertNotNil(model.pendingDocument)
        let auth = AuthService(
            pin: PinService(keychain: KeychainStore(service: "\(suiteName).pin"), cost: .testing),
            biometrics: StubBiometrics(availability: .faceID),
            preferences: preferences
        )

        let host = UIHostingController(rootView: NavigationStack {
            SettingsView(auth: auth, restoreHint: model, onBackUp: {})
        })
        // Attached to the app's scene: a window with no scene cannot present
        // the picker.
        let scene = try XCTUnwrap(
            UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        )
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer {
            window.rootViewController = nil
            window.isHidden = true
        }

        let picker = try await presentedPicker(from: host)
        let delegate = try XCTUnwrap(picker.delegate, "the exporter's picker has a delegate")
        delegate.documentPickerWasCancelled?(picker)
        picker.dismiss(animated: false)
        for _ in 0..<40 where model.pendingDocument != nil {
            try await Task.sleep(for: .milliseconds(50))
        }

        XCTAssertNil(model.pendingDocument)
        XCTAssertFalse(model.isPresentingSaver)
        XCTAssertEqual(model.message, "The restore hint was not saved. Try again.")

        await model.export()
        XCTAssertNotNil(model.pendingDocument, "a cancelled save does not block the next export")
    }

    /// Waits for the `UIDocumentPickerViewController` the exporter presents,
    /// searching every controller reachable from the host's window (SwiftUI
    /// presents from whichever child controller hosts the modifier).
    private func presentedPicker(from host: UIViewController) async throws -> UIDocumentPickerViewController {
        for _ in 0..<100 {
            let roots = [host.view.window?.rootViewController, host].compactMap { $0 }
            for root in roots {
                if let picker = Self.findPicker(in: root) { return picker }
            }
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTFail("the document picker was never presented")
        throw CancellationError()
    }

    private static func findPicker(in controller: UIViewController) -> UIDocumentPickerViewController? {
        if let picker = controller as? UIDocumentPickerViewController { return picker }
        for child in controller.children {
            if let picker = findPicker(in: child) { return picker }
        }
        if let presented = controller.presentedViewController {
            return findPicker(in: presented)
        }
        return nil
    }

    private func host(for view: some View) -> UIHostingController<AnyView> {
        let host = UIHostingController(rootView: AnyView(NavigationStack { view }))
        host.view.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
        let window = UIWindow(frame: host.view.frame)
        window.rootViewController = host
        window.makeKeyAndVisible()
        host.view.layoutIfNeeded()
        RunLoop.current.run(until: Date().addingTimeInterval(0.3))
        window.rootViewController = nil
        return host
    }
}

/// Holds what a stub's callback saw, instead of a captured `var`.
@MainActor
final class ReadsRecorder {
    var values: [Int] = []
}
