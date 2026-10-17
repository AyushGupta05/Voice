import XCTest
import SwiftUI
import LumaPresentation
@testable import LumaCompanion

/// Renders Penny's surfaces offscreen (PENNY_PREVIEW_DIR set) so their look can be reviewed without screen capture:
/// the cursor, label and outlines over a microscopy image, every voice bar look, and the expanded panel.
/// The image comes from PENNY_PREVIEW_BACKGROUND, else `background.png` in the preview directory.
@MainActor
final class CursorPreviewTests: XCTestCase {
    private func previewDirectory() throws -> String {
        guard let directory = ProcessInfo.processInfo.environment["PENNY_PREVIEW_DIR"] else { throw XCTSkip("Set PENNY_PREVIEW_DIR to render previews.") }
        return directory
    }

    private func background(_ directory: String) -> NSImage? {
        NSImage(contentsOfFile: ProcessInfo.processInfo.environment["PENNY_PREVIEW_BACKGROUND"] ?? directory + "/background.png")
    }

    func testRenderPreviews() throws {
        let directory = try previewDirectory()
        let accent = PennyPalette.overlayAccent
        let background = background(directory)
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
            try write(render(view), to: "\(directory)/cursor-\(name).png")
        }
    }

    /// Every look of the floating bar, from sample `BarStatus` values (no model, no gateway).
    func testRenderVoiceBarPreviews() throws {
        let directory = try previewDirectory()
        let states: [(String, VoiceBarContent)] = [
            ("Idle", VoiceBarContent(status: BarStatus(connected: true, bridgeConnected: true, eventsReady: true, working: false, voiceActive: false, appName: "Fiji"))),
            ("Idle, not ready", VoiceBarContent(status: BarStatus(connected: true, bridgeConnected: false, eventsReady: true, working: false, voiceActive: false, voiceReady: false, voiceReason: "Voice needs verified AWS credits", appName: "Fiji"), voiceReason: "Voice needs verified AWS credits")),
            ("Connecting speech", VoiceBarContent(status: BarStatus(connected: true, bridgeConnected: true, eventsReady: true, working: false, voiceActive: false, voiceStarting: true, appName: "Fiji"), voiceOn: true)),
            ("Listening", VoiceBarContent(status: BarStatus(connected: true, bridgeConnected: true, eventsReady: true, working: false, voiceActive: true, appName: "Fiji"), level: 0.004, voiceOn: true)),
            ("Transcript", VoiceBarContent(status: BarStatus(connected: true, bridgeConnected: true, eventsReady: true, working: false, voiceActive: true, liveTranscript: "count the nuclei and show me the sizes", appName: "Fiji"), level: 0.02, voiceOn: true, liveTranscript: "count the nuclei and show me the sizes")),
            ("Working", VoiceBarContent(status: BarStatus(connected: true, bridgeConnected: true, eventsReady: true, working: true, voiceActive: true, executingText: "Counting nuclei", appName: "Fiji"), voiceOn: true, canStopTask: true)),
            ("Result", VoiceBarContent(status: BarStatus(connected: true, bridgeConnected: true, eventsReady: true, working: false, voiceActive: true, resultText: "Found 42 nuclei; median area 69 µm².", appName: "Fiji"), voiceOn: true)),
            ("Speaking", VoiceBarContent(status: BarStatus(connected: true, bridgeConnected: true, eventsReady: true, working: false, voiceActive: true, voiceSpeaking: true, resultText: "Found 42 nuclei; median area 69 µm².", appName: "Fiji"), voiceOn: true, canStopTask: true)),
            ("Error", VoiceBarContent(status: BarStatus(connected: true, bridgeConnected: true, eventsReady: true, working: false, voiceActive: false, resultText: "Fiji has no image open. Open one and ask again.", appName: "Fiji", resultIsError: true), expanded: true)),
        ]
        let sheet = VStack(alignment: .leading, spacing: 14) {
            ForEach(Array(states.enumerated()), id: \.offset) { _, state in
                VStack(alignment: .leading, spacing: 6) {
                    Text(state.0).font(.system(size: 11, weight: .medium)).foregroundStyle(Color(white: 0.35))
                    // No SwiftUI shadow: in the app the panel's window shadow sits under the bar.
                    state.1.frame(width: 420).modifier(PennySurfaceChrome(cornerRadius: 28))
                }
            }
        }
        .padding(28)
        .frame(width: 476, height: 900, alignment: .topLeading)
        .background(Color(white: 0.86))
        // Snapshot rather than ImageRenderer: the spinners are AppKit progress indicators.
        try write(snapshot(sheet, size: CGSize(width: 476, height: 900)), to: "\(directory)/bar-states.png")

        // The bar over the image (as it floats above Fiji).
        let over = ZStack(alignment: .top) {
            if let image = background(directory) { Image(nsImage: image).resizable().frame(width: 512, height: 512) } else { Color.black.frame(width: 512, height: 512) }
            states[6].1.frame(width: 420).modifier(PennySurfaceChrome(cornerRadius: 28))
                .shadow(color: .black.opacity(0.25), radius: 14, y: 5)
                .padding(.top, 24)
        }.frame(width: 512, height: 512)
        try write(snapshot(over, size: CGSize(width: 512, height: 512)), to: "\(directory)/bar-over-image.png")
    }

    /// The retained result views (measurement plot, class table, saved workflow) on the panel surface.
    func testRenderResultPreviews() throws {
        let directory = try previewDirectory()
        let report = try JSONDecoder().decode(MeasurementReport.self, from: Data(Self.report.utf8))
        let view = VStack(alignment: .leading, spacing: 16) {
            MeasurementReportView(report: report, currentContextKey: "k0")
            ClassSummaryView(rows: [["classification": "Positive", "count": 12], ["classification": "Negative", "count": 27],
                                    ["classification": "Tumor", "count": 5], ["classification": "Stroma", "count": 9], ["classification": "Unclassified", "count": 3]], total: nil)
            WorkflowResultView(workflow: ["path": "/Users/researcher/Penny/workflows/count-nuclei.ijm",
                                          "script": "run(\"8-bit\");\nsetAutoThreshold(\"Otsu dark\");\nrun(\"Convert to Mask\");\nrun(\"Watershed\");",
                                          "howToRun": "Plugins › Macros › Run… and pick the saved file."])
        }
        .padding(22).frame(width: 420, alignment: .topLeading)
        .background(PennyPalette.surface)
        .foregroundStyle(PennyPalette.textPrimary)
        .environment(\.colorScheme, .light)
        .font(.system(size: 13))
        try write(snapshot(view, size: CGSize(width: 420, height: 720)), to: "\(directory)/results.png")
    }

    /// The expanded panel from an un-started model (nothing connects; the fields are set directly).
    func testRenderPanelPreviews() throws {
        let directory = try previewDirectory()
        for (name, filled) in [("panel-empty", false), ("panel-session", true)] {
            let model = CompanionModel()
            model.connected = true; model.bridgeConnected = true; model.eventsReady = true
            model.appName = "Fiji"; model.application = .fiji
            model.imageName = "hela-cells.tif"; model.selectionLabel = filled ? "42 objects · whole image" : "Select a region"
            model.context = ["image": ["title": "hela-cells.tif"], "contextKey": "k1"]
            model.voiceReady = false; model.voiceReason = "Voice needs verified AWS credits"
            model.textReady = true
            model.expanded = true; model.detailsHeight = 600
            if filled {
                model.resultText = "Found 42 nuclei; median area 69 µm²."
                model.measurementReport = try JSONDecoder().decode(MeasurementReport.self, from: Data(Self.report.utf8))
                model.activities = [
                    Activity(title: "Counted nuclei", detail: "42 objects on hela-cells.tif (Otsu, watershed).", icon: "circle.dotted",
                             payload: ["classes": [["classification": "Positive", "count": 12], ["classification": "Negative", "count": 27], ["classification": "Unclassified", "count": 3]], "total": 42]),
                    Activity(title: "Saved workflow", detail: "Reusable macro for the next image.", icon: "doc.text",
                             payload: ["workflow": ["path": "/Users/researcher/Penny/workflows/count-nuclei.ijm",
                                                    "script": "run(\"8-bit\");\nsetAutoThreshold(\"Otsu dark\");\nrun(\"Convert to Mask\");\nrun(\"Watershed\");\nrun(\"Analyze Particles...\", \"size=20-Infinity show=Outlines display summarize\");"]]),
                    Activity(title: "Could not export", detail: "The results folder is not writable.", isError: true),
                ]
            }
            let view = ZStack {
                Color(white: 0.86)
                CompanionSurface(model: model).shadow(color: .black.opacity(0.18), radius: 14, y: 5)
            }.frame(width: 476, height: 712)
            try write(snapshot(view, size: CGSize(width: 476, height: 712)), to: "\(directory)/\(name).png")
        }
    }

    private static let report = """
    {"imageName":"hela-cells.tif","contextKey":"k1","total":42,"scope":{"scope":"image","kind":"detection"},
     "measurements":{"Area":{"count":42,"missingOrNonFinite":0,"min":18.2,"max":196.4,"mean":72.5,"sampleStandardDeviation":31.0,
       "histogram":[{"lower":18,"upper":40,"count":5},{"lower":40,"upper":62,"count":12},{"lower":62,"upper":84,"count":14},{"lower":84,"upper":106,"count":6},{"lower":106,"upper":128,"count":3},{"lower":128,"upper":196,"count":2}]}}}
    """

    private func render<V: View>(_ view: V) throws -> Data {
        let renderer = ImageRenderer(content: view); renderer.scale = 2
        guard let image = renderer.nsImage, let tiff = image.tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiff),
              let png = bitmap.representation(using: .png, properties: [:]) else { throw XCTSkip("ImageRenderer produced no image") }
        return png
    }

    /// AppKit-backed controls (text fields, segmented pickers, spinners) only draw through a real hosting view.
    private func snapshot<V: View>(_ view: V, size: CGSize) throws -> Data {
        let host = NSHostingView(rootView: view)
        host.frame = CGRect(origin: .zero, size: size)
        let window = NSWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false)
        window.appearance = PennyPalette.appearance
        window.contentView = host
        host.layoutSubtreeIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.5))
        guard let rep = host.bitmapImageRepForCachingDisplay(in: host.bounds) else { throw XCTSkip("no bitmap for snapshot") }
        host.cacheDisplay(in: host.bounds, to: rep)
        guard let png = rep.representation(using: .png, properties: [:]) else { throw XCTSkip("PNG encoding failed") }
        return png
    }

    private func write(_ data: Data, to path: String) throws {
        try data.write(to: URL(fileURLWithPath: path))
    }
}
