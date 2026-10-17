import SwiftUI
import AppKit

private let mint = Color(red: 0.64, green: 0.92, blue: 0.79)
private let secondaryText = Color.white.opacity(0.5)

/// Rich rendering for retained tool results in the session list. Display only; values come straight from QuPath.
struct ToolResultView: View {
    let payload: [String: Any]
    var body: some View {
        if let workflow = payload["workflow"] as? [String: Any] { WorkflowResultView(workflow: workflow) }
        if let classes = payload["classes"] as? [[String: Any]] { ClassSummaryView(rows: classes, total: payload["total"] as? Int) }
    }
}

/// Generated or saved Groovy in a monospaced scrollable block with a Copy button and the saved path.
struct WorkflowResultView: View {
    let workflow: [String: Any]
    @State private var copied = false
    private var script: String? { (workflow["script"] as? String).flatMap { $0.isEmpty ? nil : $0 } }
    private var path: String? { workflow["path"] as? String }
    private var stepNames: [String] {
        if let names = workflow["stepNames"] as? [String] { return names }
        return (workflow["steps"] as? [[String: Any]])?.compactMap { $0["name"] as? String } ?? []
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let path {
                HStack(spacing: 6) {
                    Image(systemName: "doc.text").font(.system(size: 10)).foregroundStyle(mint)
                    Text(path).font(.system(size: 10, design: .monospaced)).foregroundStyle(secondaryText).lineLimit(1).truncationMode(.middle).textSelection(.enabled)
                    Spacer(minLength: 0)
                    Button("Reveal") { NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)]) }.buttonStyle(.plain).font(.system(size: 10, weight: .medium)).foregroundStyle(mint).help("Show the saved script in Finder")
                }
            }
            if let script {
                ZStack(alignment: .topTrailing) {
                    ScrollView([.vertical, .horizontal]) {
                        Text(script).font(.system(size: 10, design: .monospaced)).textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .topLeading).padding(10).padding(.trailing, 50)
                    }.frame(maxHeight: 180).background(.black.opacity(0.3), in: RoundedRectangle(cornerRadius: 8))
                    Button {
                        NSPasteboard.general.clearContents(); NSPasteboard.general.setString(script, forType: .string)
                        copied = true
                        Task { try? await Task.sleep(for: .seconds(1.5)); copied = false }
                    } label: {
                        Label(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc").font(.system(size: 10, weight: .medium))
                            .padding(.horizontal, 8).padding(.vertical, 4).background(.white.opacity(0.1), in: Capsule())
                    }.buttonStyle(.plain).padding(6).help("Copy the Groovy script")
                }
            } else if !stepNames.isEmpty {
                ForEach(Array(stepNames.enumerated()), id: \.offset) { index, name in
                    Text("\(index + 1). \(name)").font(.system(size: 10, design: .monospaced)).foregroundStyle(secondaryText)
                }
            }
            if let hint = workflow["howToRun"] as? String ?? workflow["note"] as? String {
                Text(hint).font(.system(size: 10)).foregroundStyle(secondaryText).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// Per-class count and percent table. Percent comes from QuPath when present; otherwise from the row counts.
struct ClassSummaryView: View {
    let rows: [[String: Any]]
    let total: Int?
    private var denominator: Int { total ?? rows.reduce(0) { $0 + count($1) } }
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 4) {
                GridRow {
                    Text("Class").gridColumnAlignment(.leading)
                    Text("Count").gridColumnAlignment(.trailing)
                    Text("%").gridColumnAlignment(.trailing)
                }.font(.system(size: 9, weight: .semibold)).foregroundStyle(secondaryText)
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    GridRow {
                        HStack(spacing: 6) {
                            Circle().fill(swatch(name(row))).frame(width: 6, height: 6)
                            Text(name(row)).lineLimit(1).truncationMode(.tail)
                        }
                        Text("\(count(row))").monospacedDigit()
                        Text(percentText(row)).monospacedDigit().foregroundStyle(secondaryText)
                    }.font(.system(size: 11))
                }
            }
            if denominator > 0 {
                Text("\(denominator) objects").font(.system(size: 10)).foregroundStyle(secondaryText)
            }
        }.padding(10).background(.white.opacity(0.03), in: RoundedRectangle(cornerRadius: 8))
    }
    private func name(_ row: [String: Any]) -> String {
        row["classification"] as? String ?? row["class"] as? String ?? row["name"] as? String ?? row["label"] as? String ?? "Unclassified"
    }
    private func count(_ row: [String: Any]) -> Int {
        if let value = row["count"] as? Int { return value }
        if let value = row["count"] as? Double { return Int(value) }
        return row["total"] as? Int ?? 0
    }
    private func percentText(_ row: [String: Any]) -> String {
        let value: Double
        if let percent = row["percent"] as? Double { value = percent }
        else if let percent = row["percent"] as? Int { value = Double(percent) }
        else if let fraction = row["fraction"] as? Double { value = fraction * 100 }
        else if denominator > 0 { value = Double(count(row)) * 100 / Double(denominator) }
        else { return "—" }
        return value.formatted(.number.precision(.fractionLength(value >= 10 ? 0 : 1))) + "%"
    }
    private func swatch(_ name: String) -> Color {
        let lower = name.lowercased()
        if lower.contains("positive") { return Color(red: 0.95, green: 0.45, blue: 0.4) }
        if lower.contains("negative") { return Color(red: 0.4, green: 0.6, blue: 0.95) }
        if lower.contains("tumor") || lower.contains("tumour") { return Color.orange }
        if lower.contains("stroma") { return Color.yellow }
        if lower.contains("unclassified") { return secondaryText }
        return mint
    }
}
