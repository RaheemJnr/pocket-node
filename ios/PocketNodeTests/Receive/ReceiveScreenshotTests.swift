import PocketNodeCore
import SwiftUI
import UIKit
import XCTest

@testable import PocketNode

/// Renders the Receive screen and saves a PNG, the same "it laid out, here is
/// the evidence" contract as `AuthScreenshotTests` / `BackupScreenshotTests`.
@MainActor
final class ReceiveScreenshotTests: XCTestCase {
    private let suiteName = "com.rjnr.pocketnode.tests.receive.screenshots.prefs"

    private var directory: URL!
    private var walletStore: WalletStore!
    private var defaults: UserDefaults!

    // `async` on purpose: see `PinServiceTests` — the non-async override runs
    // task-isolated and cannot touch this `@MainActor` test case's properties.
    override func setUp() async throws {
        try await super.setUp()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("com.rjnr.pocketnode.tests.receive.screenshots-\(UUID().uuidString)")
        walletStore = WalletStore(directory: directory)
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: directory)
        defaults.removePersistentDomain(forName: suiteName)
        directory = nil
        walletStore = nil
        defaults = nil
        try await super.tearDown()
    }

    func testReceiveViewRenders() throws {
        try walletStore.save(
            WalletRecord(
                id: "w1",
                name: "Main Wallet",
                type: "mnemonic",
                derivationPath: "m/44'/309'/0'/0/0",
                mainnetAddress: "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqjmpk4",
                testnetAddress: "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqjmpk4",
                mnemonicBackedUp: true,
                createdAt: 1_700_000_000_000
            )
        )
        let preferences = UserDefaultsPreferences(defaults: defaults)
        preferences.setSelectedNetwork(network: .testnet)
        let vm = ReceiveViewModel(walletStore: walletStore, preferences: preferences, onBackUp: {})

        let image = render(ReceiveView(viewModel: vm))

        save(image, named: "516-receive")
    }

    // MARK: - Rendering (mirrors AuthScreenshotTests)

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
