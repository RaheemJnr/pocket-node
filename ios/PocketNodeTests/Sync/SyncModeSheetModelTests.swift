import PocketNodeCore
import XCTest

@testable import PocketNode

/// The sheet's one rule: a custom start needs a real block number, and the
/// other three modes need nothing.
@MainActor
final class SyncModeSheetModelTests: XCTestCase {

    func testTheThreeFixedModesCanBeConfirmedImmediately() {
        for mode in [SyncMode.theNewWallet, .recent, .fullHistory] {
            let model = SyncModeSheetModel(selected: mode)

            XCTAssertFalse(model.requiresHeight, "\(mode.name) asks for no height")
            XCTAssertTrue(model.canConfirm, "\(mode.name) is confirmable as chosen")
            XCTAssertNil(model.heightToApply, "\(mode.name) applies no height")
        }
    }

    func testCustomCannotBeConfirmedWithAnEmptyField() {
        let model = SyncModeSheetModel(selected: .custom)

        XCTAssertTrue(model.requiresHeight)
        XCTAssertFalse(model.canConfirm)
    }

    func testCustomCannotBeConfirmedWithSomethingThatIsNotANumber() {
        let model = SyncModeSheetModel(selected: .custom)
        model.customHeight = "twelve million"

        XCTAssertNil(model.parsedHeight)
        XCTAssertFalse(model.canConfirm)
    }

    func testCustomCannotBeConfirmedAtZeroOrBelow() {
        let model = SyncModeSheetModel(selected: .custom)

        model.customHeight = "0"
        XCTAssertFalse(model.canConfirm, "block 0 is the full-history mode, not a custom start")

        model.customHeight = "-1"
        XCTAssertFalse(model.canConfirm)
    }

    func testCustomIsConfirmableWithAPositiveHeight() {
        let model = SyncModeSheetModel(selected: .custom)
        model.customHeight = " 12000000 "

        XCTAssertEqual(model.parsedHeight, 12_000_000, "surrounding spaces are trimmed")
        XCTAssertTrue(model.canConfirm)
        XCTAssertEqual(model.heightToApply, 12_000_000)
    }

    func testSwitchingAwayFromCustomDropsTheHeight() {
        let model = SyncModeSheetModel(selected: .custom, customHeight: 12_000_000)
        XCTAssertEqual(model.heightToApply, 12_000_000)

        model.selected = .recent

        XCTAssertNil(model.heightToApply, "a non-custom mode must not carry a height into registration")
        XCTAssertTrue(model.canConfirm)
    }

    func testNothingCanBeConfirmedWhileASubmissionIsInFlight() {
        let model = SyncModeSheetModel(selected: .recent)
        XCTAssertTrue(model.canConfirm)

        model.beginSubmitting()
        XCTAssertFalse(model.canConfirm, "the button must not fire a second registration")

        model.endSubmitting()
        XCTAssertTrue(model.canConfirm)
    }

    func testAValidHeightStaysValidWhileTheRegistrationIsInFlight() {
        // The field's error line is gated on `hasValidHeight`, not `canConfirm`:
        // otherwise submitting would flash "enter a block number above zero"
        // under a perfectly good number.
        let model = SyncModeSheetModel(selected: .custom, customHeight: 12_000_000)
        model.beginSubmitting()

        XCTAssertFalse(model.canConfirm)
        XCTAssertTrue(model.hasValidHeight)
    }

    func testTheModesThatTakeNoHeightAlwaysHaveAValidOne() {
        for mode in [SyncMode.theNewWallet, .recent, .fullHistory] {
            XCTAssertTrue(SyncModeSheetModel(selected: mode).hasValidHeight, mode.name)
        }
    }

    func testThePreviouslyChosenModeIsPreselected() {
        XCTAssertEqual(SyncModeSheetModel(selected: .fullHistory).selected, .fullHistory)
        // Nothing stored: the sheet opens on the mode Android marks recommended.
        XCTAssertEqual(SyncModeSheetModel().selected, .recent)
    }

    // MARK: - Copy

    func testOnlyRecentCarriesTheRecommendedBadge() {
        XCTAssertTrue(SyncCopy.isRecommended(.recent))
        for mode in [SyncMode.theNewWallet, .custom, .fullHistory] {
            XCTAssertFalse(SyncCopy.isRecommended(mode), mode.name)
        }
    }

    func testEveryModeHasItsOwnTitleAndBody() {
        let titles = SyncCopy.order.map { SyncCopy.title(for: $0) }
        let bodies = SyncCopy.order.map { SyncCopy.body(for: $0) }
        let identifiers = SyncCopy.order.map { SyncCopy.identifier(for: $0) }

        XCTAssertEqual(Set(titles).count, 4)
        XCTAssertEqual(Set(bodies).count, 4)
        XCTAssertEqual(identifiers, ["newWallet", "recent", "custom", "fullHistory"])
    }

    func testTheCopyCarriesNoEmDashes() {
        // The Android strings use them; the repository's copy rule does not.
        let everything = ([SyncCopy.headerTitle, SyncCopy.headerBody]
            + SyncCopy.order.map { SyncCopy.title(for: $0) }
            + SyncCopy.order.map { SyncCopy.body(for: $0) }).joined()

        XCTAssertFalse(everything.contains("\u{2014}"))
    }
}
