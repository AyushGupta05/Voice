import XCTest
import LumaPresentation

final class ApplicationPresentationTests: XCTestCase {
    func testFijiContextUsesTitleAndOnlyObjectsBelongingToTheActiveImage() {
        let context: [String: Any] = [
            "image": ["id": -3, "title": "Cells.tif"],
            "roi": ["type": "rectangle", "name": "Corner"],
            "counts": ["roiManager": 200, "roiManagerForImage": 17]
        ]
        XCTAssertEqual(CompanionApplication.fiji.imageName(context: context), "Cells.tif")
        XCTAssertEqual(CompanionApplication.fiji.selectionLabel(context: context), "Corner · 17 objects in this image")
        XCTAssertEqual(CompanionApplication.fiji.selectionLabel(context: [
            "image": ["title": "Other.tif"], "counts": ["roiManager": 200, "roiManagerForImage": 0]
        ]), "Select a region", "A global ROI Manager count cannot describe another image")
    }

    func testFijiAbsentImagesAndUnnamedSelectionsAreTruthful() {
        XCTAssertEqual(CompanionApplication.fiji.imageName(context: ["image": NSNull()]), "No image open")
        XCTAssertEqual(CompanionApplication.fiji.selectionLabel(context: ["image": NSNull(), "counts": ["roiManagerForImage": 7]]), "Select a region")
        XCTAssertEqual(CompanionApplication.fiji.selectionLabel(context: ["roi": ["type": "oval", "name": "  "]]), "Oval selected")
        XCTAssertEqual(CompanionApplication.fiji.selectionLabel(context: ["roi": [:]]), "Select a region")
    }

    func testQuPathContextKeepsTheFullSelectionCount() {
        let context: [String: Any] = ["image": ["name": "Slide.svs"], "selectionCount": 240,
                                      "selection": [["kind": "cell", "name": "Preview"]]]
        XCTAssertEqual(CompanionApplication.qupath.imageName(context: context), "Slide.svs")
        XCTAssertEqual(CompanionApplication.qupath.selectionLabel(context: context), "240 objects selected")
        XCTAssertEqual(CompanionApplication.qupath.selectionLabel(context: ["selection": [["name": "Region 1"]]]), "Region 1")
    }

    func testQuickActionsAndKnowledgeMatchTheActiveGatewayProfile() {
        let fiji = CompanionApplication(id: "fiji")
        XCTAssertEqual(fiji.knowledgeTool, "fiji_knowledge")
        XCTAssertEqual(fiji.quickActions.map(\.tool), ["fiji_zoom", "fiji_overlay", "fiji_checkpoint"])
        XCTAssertEqual(fiji.quickActions[0].arguments["fit"] as? Bool, true)
        XCTAssertEqual(fiji.quickActions[1].arguments["show"] as? Bool, true)
        XCTAssertNil(fiji.quickActions[1].arguments["fromManager"], "Showing an overlay should not rebuild it from a global manager")
        let qupath = CompanionApplication(id: "qupath")
        XCTAssertEqual(qupath.knowledgeTool, "qupath_knowledge")
        XCTAssertEqual(qupath.quickActions.map(\.tool), ["qupath_focus", "qupath_overlays", "qupath_checkpoint"])
        XCTAssertEqual(CompanionApplication(id: nil), .qupath)
    }

    func testMacroFailureInsideCompletedActionRemainsAFailure() {
        XCTAssertEqual(ToolFeedback.failure(["status": "completed", "result": ["ok": false, "error": "Macro failed at line 4."]]), "Macro failed at line 4.")
        XCTAssertNotNil(ToolFeedback.failure(["ok": false]))
        XCTAssertNotNil(ToolFeedback.failure(["status": "expired"]))
        XCTAssertNil(ToolFeedback.failure(["status": "completed", "error": NSNull(), "result": ["ok": true, "count": 0]]))
        XCTAssertNil(ToolFeedback.failure(["error": "", "count": 17]))
    }
}
