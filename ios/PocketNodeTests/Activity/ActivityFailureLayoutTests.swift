import PocketNodeCore
import SwiftUI
import UIKit
import XCTest

@testable import PocketNode

/// Captures the complete historical-state qualification at narrow and accessible sizes.
/// Images are visual QA evidence, not a golden-image or text-source assertion.
@MainActor
final class ActivityFailureLayoutTests: XCTestCase {
    func testFailureReasonsWrapAtNarrowWidth() throws {
        try render(size: .large, name: "activity-failure-narrow")
    }

    func testFailureReasonsWrapAtAccessibilitySize() throws {
        try render(size: .accessibility3, name: "activity-failure-accessibility")
    }

    private func render(size: DynamicTypeSize, name: String) throws {
        let rejected = ActivityFixtures.item(record: ActivityFixtures.record(status: "FAILED"),
            broadcast: ActivityFixtures.broadcast(state: "FAILED"))
        let dropped = ActivityFixtures.item(record: ActivityFixtures.record(status: "FAILED"),
            broadcast: ActivityFixtures.broadcast(state: "FAILED", nullCount: 3))
        let view = VStack(spacing: 16) {
            ActivityRow(item: rejected, theme: .light, elapsed: nil)
            ActivityRow(item: dropped, theme: .light, elapsed: nil)
        }
        .frame(width: 320)
        .environment(\.dynamicTypeSize, size)
        let host = UIHostingController(rootView: view)
        host.safeAreaRegions = []
        let fitted = host.sizeThatFits(in: CGSize(width: 320, height: 10_000))
        XCTAssertGreaterThan(fitted.height, 0)
        XCTAssertLessThan(fitted.height, 10_000)
        host.view.frame = CGRect(x: 0, y: 0, width: 320, height: ceil(fitted.height))
        host.view.backgroundColor = .systemBackground
        let window = UIWindow(frame: host.view.frame)
        window.rootViewController = host
        window.makeKeyAndVisible()
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        RunLoop.current.run(until: Date().addingTimeInterval(0.35))
        let image = UIGraphicsImageRenderer(size: host.view.bounds.size).image { _ in
            host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true)
        }
        let png = try XCTUnwrap(image.pngData())
        let attachment = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        let url = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("\(name).png")
        try png.write(to: url)
        print("SCREENSHOT \(name): \(url.path)")
        window.isHidden = true
    }
}
