import SwiftUI
import Charts

struct MeasurementReport: Decodable {
    let imageName: String
    let contextKey: String
    let total: Int
    let scope: MeasurementScope
    let measurements: [String: MeasurementDistribution]
}
struct MeasurementScope: Decodable {
    let scope: String?
    let kind: String?
    let classification: String?
    let ids: [String]?
    var label: String {
        let location = scope == "region" ? "Selected region" : scope == "selection" ? "Selected objects" : "Whole image"
        let objectType = kind == "cell" ? "cells" : kind == "annotation" ? "annotations" : kind == "detection" ? "detections" : "objects"
        let classFilter = classification.flatMap { $0.isEmpty ? nil : " · class: \($0)" } ?? ""
        let idFilter = ids.flatMap { $0.isEmpty ? nil : " · \($0.count) requested IDs" } ?? ""
        return "\(location) · \(objectType)\(classFilter)\(idFilter)"
    }
}
struct MeasurementDistribution: Decodable {
    let count: Int
    let missingOrNonFinite: Int
    let min: Double?
    let max: Double?
    let mean: Double?
    let sampleStandardDeviation: Double?
    let histogram: [MeasurementBin]
}
struct MeasurementBin: Decodable {
    let lower: Double
    let upper: Double
    let count: Int
}

/// A retained observation, labeled with its source rather than presented as changing live data.
struct MeasurementReportView: View {
    let report: MeasurementReport
    let currentContextKey: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "chart.bar.xaxis").foregroundStyle(PennyPalette.accent)
                Text("Measured results").font(.system(size: 12, weight: .semibold))
                Spacer()
                Text("\(report.total) objects").font(.system(size: 10)).foregroundStyle(PennyPalette.textSecondary)
            }
            Text(report.imageName).font(.system(size: 10)).foregroundStyle(PennyPalette.textSecondary).lineLimit(1).truncationMode(.middle)
            Text(report.scope.label).font(.system(size: 10)).foregroundStyle(PennyPalette.textSecondary).fixedSize(horizontal: false, vertical: true)
            if currentContextKey != report.contextKey {
                Text("Earlier observation · refresh the analysis for current objects.")
                    .font(.system(size: 10)).foregroundStyle(PennyPalette.warning).fixedSize(horizontal: false, vertical: true)
            }
            ForEach(Array(report.measurements.keys.sorted().prefix(2)), id: \.self) { name in
                if let distribution = report.measurements[name] {
                    distributionView(name, distribution)
                }
            }
            if report.measurements.count > 2 {
                Text("Plots show 2 of \(report.measurements.count) measurements. Ask for a specific measurement to plot it.")
                    .font(.system(size: 10)).foregroundStyle(PennyPalette.textSecondary).fixedSize(horizontal: false, vertical: true)
            }
        }.padding(12).background(PennyPalette.surfaceMuted, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(PennyPalette.border))
    }
    private func distributionView(_ name: String, _ value: MeasurementDistribution) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(name).font(.system(size: 11, weight: .medium)).textSelection(.enabled)
            Text("Mean \(format(value.mean)) · n = \(value.count) · missing/invalid \(value.missingOrNonFinite)")
                .font(.system(size: 10)).foregroundStyle(PennyPalette.textSecondary).fixedSize(horizontal: false, vertical: true)
            if !value.histogram.isEmpty {
                Chart(Array(value.histogram.enumerated()), id: \.offset) { entry in
                    BarMark(x: .value("Bin", String(entry.offset)), y: .value("Objects", entry.element.count), width: .ratio(0.85))
                        .foregroundStyle(PennyPalette.accent.gradient)
                        .accessibilityLabel("\(format(entry.element.lower)) to \(format(entry.element.upper))")
                        .accessibilityValue("\(entry.element.count) objects")
                }.chartXAxis(.hidden).frame(height: 95)
                HStack {
                    Text(format(value.min)); Spacer(); Text(format(value.max))
                }.font(.system(size: 9)).foregroundStyle(PennyPalette.textSecondary)
            }
        }
    }
    private func format(_ number: Double?) -> String {
        guard let number, number.isFinite else { return "—" }
        return number.formatted(.number.precision(.significantDigits(1...4)))
    }
}
