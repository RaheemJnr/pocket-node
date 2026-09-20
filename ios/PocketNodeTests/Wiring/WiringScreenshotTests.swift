import PocketNodeCore
import SwiftUI
import UIKit
import XCTest

@testable import PocketNode

/// Renders the screens this wiring pass added or changed and saves a PNG of
/// each.
///
/// Same arrangement as `OnboardingScreenshotTests` and `ReceiveScreenshotTests`:
/// not a golden-image comparison, nothing fails on a pixel change. What it
/// asserts is that each screen lays out at all with real view models behind
/// it, and the images are the visual evidence. Paths are printed so they can
/// be copied out of the simulator.
@MainActor
final class WiringScreenshotTests: XCTestCase {
    private let suiteName = "com.rjnr.pocketnode.tests.wiring.prefs"

    private var directory: URL!
    private var walletStore: WalletStore!
    private var defaults: UserDefaults!
    private var preferences: UserDefaultsPreferences!

    // `async` on purpose: see `PinServiceTests` — the non-async override runs
    // task-isolated and cannot touch this `@MainActor` test case's properties.
    override func setUp() async throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("com.rjnr.pocketnode.tests.wiring-\(UUID().uuidString)")
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

    private func saveWallet(backedUp: Bool) throws {
        try walletStore.save(
            WalletRecord(
                id: "w1",
                name: "Main Wallet",
                type: WalletCreator.typeMnemonic,
                derivationPath: "m/44'/309'/0'/0/0",
                mainnetAddress: "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqhp5jft",
                testnetAddress: "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqenlarn",
                mnemonicBackedUp: backedUp,
                createdAt: 1_700_000_000_000
            )
        )
    }

    func testHomeWithTheBackupBannerRenders() throws {
        try saveWallet(backedUp: false)
        let model = HomeViewModel(walletStore: walletStore, preferences: preferences)
        XCTAssertTrue(model.needsBackup)

        let view = NavigationStack {
            HomeView(model: model, theme: .light, onReceive: {}, onBackUp: {})
                .navigationTitle("Pocket Node")
        }

        save(render(view), named: "wiring-home-banner")
    }

    func testHomeWithoutTheBannerRenders() throws {
        try saveWallet(backedUp: true)
        let model = HomeViewModel(walletStore: walletStore, preferences: preferences)
        XCTAssertFalse(model.needsBackup)

        let view = NavigationStack {
            HomeView(model: model, theme: .light, onReceive: {}, onBackUp: {})
                .navigationTitle("Pocket Node")
        }

        save(render(view), named: "wiring-home-backed-up")
    }

    func testReceiveRendersFromTheShell() throws {
        try saveWallet(backedUp: true)
        let model = ReceiveViewModel(walletStore: walletStore, preferences: preferences, hasPin: { true }, onBackUp: {})

        let view = NavigationStack {
            ReceiveView(viewModel: model)
                .navigationTitle("Receive")
                .navigationBarTitleDisplayMode(.inline)
        }

        save(render(view), named: "wiring-receive")
    }

    func testSettingsRenders() {
        let auth = AuthService(
            pin: PinService(keychain: KeychainStore(service: "\(suiteName).pin"), cost: .testing),
            biometrics: StubBiometrics(availability: .faceID),
            preferences: preferences
        )

        let view = NavigationStack {
            SettingsView(auth: auth, onBackUp: {})
        }

        save(render(view), named: "wiring-settings")
    }

    // MARK: - Rendering (mirrors OnboardingScreenshotTests.render)

    private func render(_ view: some View, size: CGSize = CGSize(width: 393, height: 852)) -> UIImage {
        let host = UIHostingController(rootView: view)
        host.view.frame = CGRect(origin: .zero, size: size)
        host.view.backgroundColor = .systemBackground

        let window = UIWindow(frame: host.view.frame)
        window.rootViewController = host
        window.makeKeyAndVisible()
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        RunLoop.current.run(until: Date().addingTimeInterval(0.5))

        return UIGraphicsImageRenderer(size: size).image { _ in
            host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true)
        }
    }

    private func save(_ image: UIImage, named name: String) {
        guard let png = image.pngData() else {
            XCTFail("\(name) produced no image data")
            return
        }
        XCTAssertGreaterThan(png.count, 5_000, "\(name) rendered as a blank or near-blank page")

        let attachment = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)

        let url = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("\(name).png")
        do {
            try png.write(to: url)
            print("SCREENSHOT \(name): \(url.path)")
        } catch {
            XCTFail("could not write \(name): \(error)")
        }
    }
}
