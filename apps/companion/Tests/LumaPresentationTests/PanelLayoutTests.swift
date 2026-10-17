import XCTest
import CoreGraphics
import LumaPresentation

final class PanelLayoutTests: XCTestCase {
    func testExpandedReviewFitsShorterDisplayWithNegativeOrigin() {
        let visible = CGRect(x: -1280, y: -50, width: 1280, height: 540)
        let frame = PanelLayout.frame(current: CGRect(x: 600, y: 500, width: 420, height: 656), visible: visible, expanded: true)
        XCTAssertTrue(visible.contains(frame)); XCTAssertEqual(frame.height, 540)
        XCTAssertEqual(frame.maxY, visible.maxY)
    }
    func testCollapsePreservesTopAndFitsDisplay() {
        let visible = CGRect(x: 0, y: 25, width: 1512, height: 900)
        let frame = PanelLayout.frame(current: CGRect(x: 500, y: 200, width: 420, height: 656), visible: visible, expanded: false)
        XCTAssertEqual(frame, CGRect(x: 500, y: 800, width: 420, height: 56))
    }
}
