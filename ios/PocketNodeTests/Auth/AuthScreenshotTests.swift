import SwiftUI
import UIKit
import XCTest

@testable import PocketNode

/// Renders the two auth screens and saves a PNG of each.
///
/// Not a golden-image comparison: there is no committed reference and nothing
/// fails on a pixel change. What it asserts is that each view lays out at all
/// (a SwiftUI view that traps or renders empty is a real failure this catches),
/// and the images it leaves behind are the visual evidence for the issue.
///
/// Each PNG is attached to the test result and written to the simulator's
/// temporary directory; the paths are printed so they can be copied out.
@MainActor
final class AuthScreenshotTests: XCTestCase {
    private let keychainService = "com.rjnr.pocketnode.tests.auth.screenshots"
    private let suiteName = "com.rjnr.pocketnode.tests.auth.screenshots.prefs"

    private var keychain: KeychainStore!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        keychain = KeychainStore(service: keychainService)
        try? keychain.deleteAll()
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        try? keychain.deleteAll()
        defaults.removePersistentDomain(forName: suiteName)
        keychain = nil
        defaults = nil
        super.tearDown()
    }

    private func makeAuth(biometrics: StubBiometrics) -> AuthService {
        AuthService(
            pin: PinService(keychain: keychain, cost: .testing, clock: TestClock().source),
            biometrics: biometrics,
            preferences: UserDefaultsPreferences(defaults: defaults)
        )
    }

    func testLockViewRenders() async throws {
        let biometrics = StubBiometrics(availability: .faceID, result: .failure(.cancelled))
        let auth = makeAuth(biometrics: biometrics)
        try await auth.setPin("123456")
        auth.isBiometricEnabled = true
        auth.handleScenePhase(.background)
        XCTAssertEqual(auth.state, .locked)

        let image = render(LockView(auth: auth))

        save(image, named: "513-lock")
    }

    func testPinSetupViewRenders() async throws {
        let auth = makeAuth(biometrics: StubBiometrics(availability: .faceID))

        let image = render(PinSetupView(auth: auth))

        save(image, named: "513-pin-setup")
    }

    // MARK: - Rendering

    /// Hosts the view at iPhone 16 Pro point size and draws it.
    ///
    /// `UIGraphicsImageRenderer` over the hosted view's layer rather than
    /// `ImageRenderer`, because the latter rasterises the SwiftUI tree in
    /// isolation and drops the parts that need a real window, which is most of
    /// what these screens are made of.
    private func render(_ view: some View, size: CGSize = CGSize(width: 393, height: 852)) -> UIImage {
        let host = UIHostingController(rootView: view)
        host.view.frame = CGRect(origin: .zero, size: size)
        host.view.backgroundColor = .systemBackground

        let window = UIWindow(frame: host.view.frame)
        window.rootViewController = host
        window.makeKeyAndVisible()
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        // One turn of the run loop, so `.task` and the first animation frame
        // have landed before the snapshot.
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
