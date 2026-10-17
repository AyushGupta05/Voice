import Foundation

/// The two gateway profiles expose different context fields and view operations.
public enum CompanionApplication: String {
    case qupath, fiji

    public init(id: String?) { self = Self(rawValue: id ?? "") ?? .qupath }
    public var knowledgeTool: String { "\(rawValue)_knowledge" }
    public var quickActions: [CompanionQuickAction] {
        switch self {
        case .fiji:
            return [
                CompanionQuickAction(title: "Fit image", icon: "scope", tool: "fiji_zoom", arguments: ["fit": true]),
                CompanionQuickAction(title: "Highlights", icon: "circle.dotted", tool: "fiji_overlay", arguments: ["show": true]),
                CompanionQuickAction(title: "Checkpoint", icon: "clock.arrow.circlepath", tool: "fiji_checkpoint", arguments: ["label": "Researcher checkpoint"])
            ]
        case .qupath:
            return [
                CompanionQuickAction(title: "Focus here", icon: "scope", tool: "qupath_focus", arguments: [:]),
                CompanionQuickAction(title: "Highlights", icon: "circle.dotted", tool: "qupath_overlays", arguments: ["detections": true, "fill": true]),
                CompanionQuickAction(title: "Checkpoint", icon: "clock.arrow.circlepath", tool: "qupath_checkpoint", arguments: ["label": "Researcher checkpoint"])
            ]
        }
    }

    public func imageName(context: [String: Any]) -> String {
        let image = context["image"] as? [String: Any]
        return image?[self == .fiji ? "title" : "name"] as? String ?? "No image open"
    }

    public func selectionLabel(context: [String: Any]) -> String {
        if self == .fiji {
            var parts: [String] = []
            if let roi = context["roi"] as? [String: Any], !roi.isEmpty {
                let name = (roi["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                let type = roi["type"] as? String ?? "Region"
                parts.append(name.isEmpty ? "\(type.capitalized) selected" : name)
            }
            // The manager is global to Fiji. Only its image-scoped count describes this image.
            if let count = (context["counts"] as? [String: Any])?["roiManagerForImage"] as? Int, count > 0,
               context["image"] is [String: Any] {
                parts.append("\(count) object\(count == 1 ? "" : "s") in this image")
            }
            return parts.isEmpty ? "Select a region" : parts.joined(separator: " · ")
        }
        let selected = context["selection"] as? [[String: Any]] ?? []
        let count = context["selectionCount"] as? Int ?? selected.count
        if count == 0 { return "Select a region" }
        if count == 1, let first = selected.first {
            return first["name"] as? String ?? "1 \(first["kind"] as? String ?? "object") selected"
        }
        return "\(count) objects selected"
    }
}

public struct CompanionQuickAction: Identifiable {
    public var id: String { tool }
    public let title: String
    public let icon: String
    public let tool: String
    public let arguments: [String: Any]
}

/// An action returning a payload is not automatically a successful action (macros can return `ok: false`).
public enum ToolFeedback {
    public static func failure(_ value: [String: Any]) -> String? {
        if let error = value["error"] as? String, !error.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return error }
        if value["ok"] as? Bool == false { return value["message"] as? String ?? "The operation did not complete. Inspect the application before retrying." }
        if let status = value["status"] as? String, ["failed", "denied", "expired"].contains(status) {
            return value["message"] as? String ?? "The operation \(status)."
        }
        if let nested = value["result"] as? [String: Any] { return failure(nested) }
        return nil
    }
}
