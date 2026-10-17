import XCTest
import CoreGraphics
@testable import LumaCompanion

final class DesktopProtocolTests: XCTestCase {
    private let ansi = DesktopProtocol.ansiKeys

    func testShortcutParsingMapsModifiersAndKeys() {
        let goTo = DesktopProtocol.parseShortcut("cmd+shift+g", layout: ansi)
        XCTAssertEqual(goTo?.keyCode, 5)
        XCTAssertEqual(goTo?.flags, [.maskCommand, .maskShift])
        XCTAssertEqual(DesktopProtocol.parseShortcut("Return", layout: ansi)?.keyCode, 36)
        XCTAssertEqual(DesktopProtocol.parseShortcut("ctrl+alt+Down", layout: ansi)?.flags, [.maskControl, .maskAlternate])
        XCTAssertEqual(DesktopProtocol.parseShortcut("escape", layout: ansi)?.keyCode, 53)
        XCTAssertEqual(DesktopProtocol.parseShortcut("command+A", layout: ansi)?.flags, [.maskCommand, .maskShift])
    }

    func testShortcutParsingRejectsNonsense() {
        XCTAssertNil(DesktopProtocol.parseShortcut("cmd+shift", layout: ansi), "a shortcut needs one key")
        XCTAssertNil(DesktopProtocol.parseShortcut("cmd+g+h", layout: ansi), "two keys are ambiguous")
        XCTAssertNil(DesktopProtocol.parseShortcut("bogus", layout: ansi))
        XCTAssertNil(DesktopProtocol.parseShortcut("", layout: ansi))
    }

    func testHighlightPayloadMatchesPointerOverlayContract() {
        let payload = DesktopProtocol.highlight(label: "Open", frame: CGRect(x: 100, y: 200, width: 80, height: 24))
        XCTAssertEqual(payload["label"] as? String, "Open")
        let targets = payload["targets"] as? [[String: Any]]
        XCTAssertEqual(targets?.count, 1)
        XCTAssertEqual(targets?.first?["shape"] as? String, "rect")
        XCTAssertEqual(targets?.first?["width"] as? CGFloat, 80)
        let pointer = payload["pointer"] as? [String: CGFloat]
        XCTAssertEqual(pointer?["screenX"], 140); XCTAssertEqual(pointer?["screenY"], 212)
        XCTAssertEqual((DesktopProtocol.highlight(label: String(repeating: "x", count: 200), frame: .zero)["label"] as? String)?.count, 80)
    }

    func testDefaultLabelsAreShortAndActionSpecific() {
        XCTAssertEqual(DesktopProtocol.defaultLabel(action: "press", role: "AXButton", title: "Open"), "Open")
        XCTAssertEqual(DesktopProtocol.defaultLabel(action: "setValue", role: "AXTextField", title: ""), "Fill TextField")
        XCTAssertEqual(DesktopProtocol.defaultLabel(action: "select", role: "AXRow", title: "slide.svs"), "Select slide.svs")
    }

    func testNumberCoercionAndConstants() {
        XCTAssertEqual(DesktopProtocol.number(12), 12)
        XCTAssertEqual(DesktopProtocol.number("3.5"), 3.5)
        XCTAssertNil(DesktopProtocol.number(Double.nan)); XCTAssertNil(DesktopProtocol.number("x")); XCTAssertNil(DesktopProtocol.number(nil))
        XCTAssertEqual(DesktopProtocol.operations, ["inspect", "act", "click", "type", "key"])
        XCTAssertEqual(DesktopProtocol.actions, ["press", "setValue", "focus", "select", "toggle", "type"])
        XCTAssertEqual(DesktopControl.inspectionValidity, 30)
        XCTAssertEqual(DesktopControl.quPathBundleID, "science.luma.qupath")
    }

    @MainActor func testActRefusesIdsThatWereNeverInspectedOrUnknownOps() async {
        let control = DesktopControl()
        do { _ = try await control.perform(op: "dance", args: [:]); XCTFail("unknown op must throw") }
        catch { XCTAssertTrue(error.localizedDescription.contains("Unknown desktop operation")) }
        do { _ = try await control.perform(op: "act", args: ["id": "1/w0/3", "action": "press"]) }
        catch {
            // Either the permission is missing (reported as a thrown/returned error) or the id is unknown; never a blind click.
            XCTAssertTrue(error.localizedDescription.contains("not seen") || error.localizedDescription.contains("Accessibility"))
        }
        do { _ = try await control.perform(op: "act", args: ["id": "1/w0/3", "action": "explode"]); }
        catch { XCTAssertTrue(error.localizedDescription.contains("action must be one of") || error.localizedDescription.contains("Accessibility")) }
    }
}
