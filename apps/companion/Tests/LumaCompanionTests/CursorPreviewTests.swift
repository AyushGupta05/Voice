import XCTest
import SwiftUI
import LumaPresentation
@testable import LumaCompanion

/// Renders the cursor and outlines offscreen (PENNY_PREVIEW_DIR set) so their look can be reviewed without screen capture.
@MainActor
final class CursorPreviewTests: XCTestCase {
    func testRenderPreviews() throws {
        guard let directory = ProcessInfo.processInfo.environment["PENNY_PREVIEW_DIR"] else { throw XCTSkip("Set PENNY_PREVIEW_DIR to render previews.") }
        let accent = Color(red: 0.36, green: 0.92, blue: 0.78)
        let background = NSImage(contentsOfFile: directory + "/background.png")
        let targets = [CGRect(x: 40, y: 50, width: 52, height: 46), CGRect(x: 270, y: 60, width: 46, height: 44), CGRect(x: 250, y: 300, width: 70, height: 60), CGRect(x: 120, y: 410, width: 44, height: 40)]
        for (name, mood, hovered) in [("listening", CursorMood.listening, -1), ("thinking", CursorMood.thinking, -1), ("speaking", CursorMood.speaking, 2), ("idle", CursorMood.idle, 1)] {
            let view = ZStack(alignment: .topLeading) {
                if let background { Image(nsImage: background).resizable().frame(width: 512, height: 512) } else { Color.black.frame(width: 512, height: 512) }
                ForEach(Array(targets.enumerated()), id: \.offset) { index, rect in
                    HighlightShape(target: HighlightTarget(id: index, rect: rect, ellipse: true, name: index == 2 ? "Cell 3" : ""), accent: accent, delay: 0, generation: 0, hovered: index == hovered, shown: true)
                }
                CursorBuddy(label: mood == .idle ? "6 nuclei" : "", accent: accent, mood: mood, level: 0.6, dimmed: false)
                    .offset(x: 300, y: 330)
            }
            .frame(width: 512, height: 512, alignment: .topLeading)
            let renderer = ImageRenderer(content: view); renderer.scale = 2
            guard let image = renderer.nsImage, let tiff = image.tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiff),
                  let png = bitmap.representation(using: .png, properties: [:]) else { return XCTFail("render \(name)") }
            try png.write(to: URL(fileURLWithPath: "\(directory)/cursor-\(name).png"))
        }
    }
}
