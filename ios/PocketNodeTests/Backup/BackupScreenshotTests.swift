import SwiftUI
import UIKit
import XCTest

@testable import PocketNode

/// Renders the backup display and verify steps and saves a PNG of each, the
/// same "it laid out, here is the evidence" contract as `AuthScreenshotTests`.
///
/// The phrase here is a throwaway 12 valid-but-meaningless BIP-39 words
/// generated for this test only — never a real wallet's mnemonic.
@MainActor
final class BackupScreenshotTests: XCTestCase {
    private var directory: URL!
    private var walletStore: WalletStore!

    // `async` on purpose: see `PinServiceTests` — the non-async override runs
    // task-isolated and cannot touch this `@MainActor` test case's properties.
    override func setUp() async throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("com.rjnr.pocketnode.tests.backup.screenshots-\(UUID().uuidString)")
        walletStore = WalletStore(directory: directory)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: directory)
        directory = nil
        walletStore = nil
    }

    private func makeRevealedViewModel() async -> BackupViewModel {
        let throwawayPhrase = "abandon ability able about above absent absorb abstract absurd abuse access accident"
        let reader = StubWalletKeyReader(
            result: .success(WalletKeyBundle(privateKeyHex: "00", mnemonic: throwawayPhrase))
        )
        let vm = BackupViewModel(
            walletKeyStore: reader,
            walletStore: walletStore,
            auth: StubAuthGate(),
            isOnboarding: true,
            hasPin: { false },
            rng: SeededRandomNumberGenerator(seed: 1)
        )
        await vm.reveal()
        return vm
    }

    func testDisplayStepRenders() async throws {
        let vm = await makeRevealedViewModel()
        XCTAssertEqual(vm.step, .display)

        // `PrivacyShield` reads `scenePhase` from the environment; the test
        // host has no real active scene, so without this the shield would
        // cover the whole screen and the screenshot would show nothing.
        let image = render(BackupView(viewModel: vm, onFinished: {}).environment(\.scenePhase, .active))

        save(image, named: "516-backup-display")
    }

    func testVerifyStepRenders() async throws {
        let vm = await makeRevealedViewModel()
        vm.advanceToVerify()
        XCTAssertEqual(vm.step, .verify)

        let image = render(BackupView(viewModel: vm, onFinished: {}).environment(\.scenePhase, .active))

        save(image, named: "516-backup-verify")
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
        RunLoop.current.run(until: Date().addingTimeInterval(0.35))

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
