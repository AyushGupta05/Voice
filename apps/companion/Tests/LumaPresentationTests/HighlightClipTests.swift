import XCTest
import LumaPresentation

final class HighlightClipTests: XCTestCase {
    private let fiji: Int32 = 400, other: Int32 = 500, me: Int32 = 1
    private let canvas = CGRect(x: 100, y: 100, width: 400, height: 300)
    private var imageWindow: ScreenWindow { ScreenWindow(number: 7, pid: fiji, layer: 0, bounds: CGRect(x: 90, y: 70, width: 420, height: 340), owner: "Fiji") }

    func testUncoveredCanvasIsFullyVisible() {
        let clip = HighlightClipper.clip(viewer: canvas, windows: [imageWindow], appPIDs: [fiji], ownPID: me)
        XCTAssertEqual(clip.holder?.number, 7)
        XCTAssertEqual(clip.visibleFraction, 1, accuracy: 1e-9)
    }

    func testAnotherAppInFrontCutsTheOutlinesOut() {
        let browser = ScreenWindow(number: 3, pid: other, layer: 0, bounds: CGRect(x: 300, y: 0, width: 600, height: 800), owner: "Google Chrome")
        let clip = HighlightClipper.clip(viewer: canvas, windows: [browser, imageWindow], appPIDs: [fiji], ownPID: me)
        // Only the left half (x 100–300) of the canvas is visible.
        XCTAssertEqual(clip.visibleFraction, 0.5, accuracy: 1e-9)
        XCTAssertTrue(clip.visible.allSatisfy { $0.maxX <= 300 })
    }

    func testWindowsBehindTheImageAndTheCompanionNeverOcclude() {
        let behind = ScreenWindow(number: 9, pid: other, layer: 0, bounds: CGRect(x: 0, y: 0, width: 2000, height: 2000))
        let overlay = ScreenWindow(number: 2, pid: me, layer: 25, bounds: CGRect(x: 0, y: 0, width: 2000, height: 2000))
        let clip = HighlightClipper.clip(viewer: canvas, windows: [overlay, imageWindow, behind], appPIDs: [fiji], ownPID: me)
        XCTAssertEqual(clip.visibleFraction, 1, accuracy: 1e-9)
    }

    func testFijisOwnDialogInFrontAlsoOccludes() {
        let roiManager = ScreenWindow(number: 8, pid: fiji, layer: 0, bounds: CGRect(x: 100, y: 100, width: 100, height: 100), owner: "Fiji")
        let clip = HighlightClipper.clip(viewer: canvas, windows: [roiManager, imageWindow], appPIDs: [fiji], ownPID: me, holderNumber: 7)
        XCTAssertEqual(clip.visibleFraction, 1 - 10_000.0 / 120_000.0, accuracy: 1e-9)
    }

    func testMinimisedOrClosedImageHasNoHolderAndNoOutlines() {
        let clip = HighlightClipper.clip(viewer: canvas, windows: [], appPIDs: [fiji], ownPID: me, holderNumber: 7)
        XCTAssertNil(clip.holder); XCTAssertTrue(clip.visible.isEmpty)
    }

    func testFollowsTheSameWindowAfterItMoves() {
        let moved = ScreenWindow(number: 7, pid: fiji, layer: 0, bounds: CGRect(x: 600, y: 70, width: 420, height: 340))
        let clip = HighlightClipper.clip(viewer: canvas.offsetBy(dx: 510, dy: 0), windows: [moved], appPIDs: [fiji], ownPID: me, holderNumber: 7)
        XCTAssertEqual(clip.holder?.bounds.minX, 600)
        XCTAssertEqual(clip.visibleFraction, 1, accuracy: 1e-9)
    }

    func testSubtractionKeepsAreaExact() {
        let parts = HighlightClipper.subtract(CGRect(x: 50, y: 50, width: 20, height: 20), from: CGRect(x: 0, y: 0, width: 100, height: 100))
        XCTAssertEqual(parts.reduce(0) { $0 + $1.width * $1.height }, 10_000 - 400, accuracy: 1e-9)
        for (i, a) in parts.enumerated() { for b in parts[(i + 1)...] { XCTAssertTrue(a.intersection(b).isNull || a.intersection(b).width * a.intersection(b).height == 0) } }
    }

    func testCursorMoodFollowsTheBar() {
        XCTAssertEqual(CursorMood(BarStatus(connected: true, bridgeConnected: true, eventsReady: true, working: false, voiceActive: true)), .listening)
        XCTAssertEqual(CursorMood(BarStatus(connected: true, bridgeConnected: true, eventsReady: true, working: true, voiceActive: true, executingText: "Finding the nuclei.")), .thinking)
        XCTAssertEqual(CursorMood(BarStatus(connected: true, bridgeConnected: true, eventsReady: true, working: false, voiceActive: true, voiceSpeaking: true, resultText: "Found 6 nuclei.")), .speaking)
    }
}
