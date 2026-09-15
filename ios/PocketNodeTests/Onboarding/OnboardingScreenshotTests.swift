import SwiftUI
import UIKit
import XCTest

@testable import PocketNode

/// Renders each onboarding screen and saves a PNG of it.
///
/// Same arrangement as `AuthScreenshotTests`: not a golden-image comparison,
/// there is no committed reference and nothing fails on a pixel change. What it
/// asserts is that each view lays out at all, and the images it leaves behind
/// are the visual evidence for the issue. Paths are printed so they can be
/// copied out of the simulator.
@MainActor
final class OnboardingScreenshotTests: XCTestCase {
    private let service = "com.rjnr.pocketnode.tests.onboarding.screenshots"

    private var keychain: KeychainStore!
    private var wrapper: SecureEnclaveKeyWrapper!
    private var directory: URL!
    private var model: OnboardingViewModel!

    override func setUp() async throws {
        try await super.setUp()
        keychain = KeychainStore(service: service)
        wrapper = SecureEnclaveKeyWrapper(tag: service)
        try? keychain.deleteAll()
        try? wrapper.deleteKey()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(service)-\(UUID().uuidString)")
        model = OnboardingViewModel(
            creator: WalletCreator(
                keyStore: WalletKeyStore(keychain: keychain, wrapper: wrapper),
                walletStore: WalletStore(directory: directory)
            )
        )
    }

    override func tearDown() async throws {
        try? keychain.deleteAll()
        try? wrapper.deleteKey()
        try? FileManager.default.removeItem(at: directory)
        model = nil
        wrapper = nil
        keychain = nil
        directory = nil
        try await super.tearDown()
    }

    func testWelcomeRenders() {
        save(render(flow()), named: "515-onboarding-welcome")
    }

    func testCreateRenders() {
        model.beginCreate()

        save(render(flow()), named: "515-onboarding-create")
    }

    func testImportPhraseRenders() {
        model.beginImport()

        save(render(flow()), named: "515-onboarding-import-phrase")
    }

    func testImportPrivateKeyRenders() {
        model.beginImport()

        // The private-key half is behind a segmented control, so it is rendered
        // on its own rather than by driving the picker.
        let view = NavigationStack { ImportWalletView(model: model, initialMode: .privateKey) }
        save(render(view), named: "515-onboarding-import-key")
    }

    func testBackupPlaceholderRenders() async {
        await model.createWallet(wordCount: 12, name: "Main")
        XCTAssertEqual(model.step, .backup)

        save(render(flow()), named: "515-onboarding-backup")
    }

    // MARK: - Rendering

    private func flow() -> some View {
        OnboardingView(
            model: model,
            auth: AuthService(
                pin: PinService(keychain: KeychainStore(service: "\(service).pin"), cost: .testing),
                biometrics: StubBiometrics(availability: .faceID),
                preferences: UserDefaultsPreferences(defaults: UserDefaults(suiteName: "\(service).prefs")!)
            ),
            onFinished: {}
        )
    }

    /// Hosts the view at iPhone 16 Pro point size and draws it. See
    /// `AuthScreenshotTests.render` for why this goes through a real window.
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
