import XCTest

/// M3 acceptance: the activity list, offline.
///
/// The seeded wallet has no history and the node has no peers, so every filter
/// is empty. That is the point: the three empty messages are different on
/// purpose, and a filter switch that silently kept the previous tab's message
/// would be invisible to a view-model test that only checks the filter value.
@MainActor
final class ActivityUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testHomeReachesActivityAndEachFilterHasItsOwnEmptyMessage() throws {
        let app = M3UITest.launch()

        let activity = app.buttons["home.activity"]
        XCTAssertTrue(activity.waitForExistence(timeout: 30), "Home never came up")
        activity.tap()

        XCTAssertTrue(
            app.buttons["activity.filter.all"].firstMatch.waitForExistence(timeout: 15),
            "Home did not reach Activity"
        )
        XCTAssertTrue(app.buttons["activity.filter.received"].firstMatch.exists)
        XCTAssertTrue(app.buttons["activity.filter.sent"].firstMatch.exists)

        // The walk is retried a few times on a cold start before the screen
        // settles, so the empty state is waited for rather than read at once.
        XCTAssertTrue(
            app.staticTexts["activity.empty"].firstMatch.waitForExistence(timeout: 30),
            "the list never settled into an empty state offline"
        )
        XCTAssertEqual(M3UITest.label("activity.empty", in: app), "No transactions yet")

        app.buttons["activity.filter.received"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["activity.empty"].firstMatch.waitForExistence(timeout: 15))
        XCTAssertEqual(M3UITest.label("activity.empty", in: app), "No received transactions")

        app.buttons["activity.filter.sent"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["activity.empty"].firstMatch.waitForExistence(timeout: 15))
        XCTAssertEqual(M3UITest.label("activity.empty", in: app), "No sent transactions")

        app.buttons["activity.filter.all"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["activity.empty"].firstMatch.waitForExistence(timeout: 15))
        XCTAssertEqual(M3UITest.label("activity.empty", in: app), "No transactions yet")
    }

    /// The refresh gesture is safe with a node that has no peers.
    ///
    /// `.refreshable` lives on the `List`, which an empty wallet does not draw,
    /// so on this device the swipe lands on the empty state and asks for
    /// nothing. It is still worth performing: what would break here is the
    /// screen surviving the walk that `onAppear` already started, and a crash
    /// or a hang would take the app down with it.
    func testPullingToRefreshOfflineLeavesTheScreenStanding() throws {
        let app = M3UITest.launch(route: "activity")

        XCTAssertTrue(
            app.buttons["activity.filter.all"].firstMatch.waitForExistence(timeout: 30),
            "Activity never came up"
        )

        app.swipeDown()
        app.swipeDown()

        XCTAssertEqual(app.state, .runningForeground, "the app went away during a refresh")
        XCTAssertTrue(
            app.buttons["activity.filter.all"].firstMatch.waitForExistence(timeout: 15),
            "the filters went away during a refresh"
        )
    }
}
