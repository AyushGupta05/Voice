import AppKit
import ApplicationServices
import Carbon.HIToolbox
import Foundation

/// Desktop-level control through macOS Accessibility (AXUIElement) with synthetic CGEvent input as the fallback.
///
/// This is the layer behind the `qupath_desktop_*` tools: it sees every window on screen, including third-party
/// plugin windows, OS-owned open/save panels and alerts that the in-app JavaFX bridge cannot observe. Everything
/// AppKit-related stays on the main actor. Coordinates are global screen points with a top-left origin, the same
/// frame `PointerOverlay` draws in, so a reply's `highlight` can be shown as-is.
@MainActor
final class DesktopControl {
    static let quPathBundleID = "science.luma.qupath"
    /// Apple's out-of-process open/save panel host; its windows belong to whichever app is showing a file dialog.
    static let panelServiceBundleID = "com.apple.appkit.xpc.openAndSavePanelService"
    /// Elements may only be acted on this long after they were inspected.
    static let inspectionValidity: TimeInterval = 30
    /// Pause between flying the cursor to a target and acting on it, so the researcher can follow along.
    static let showDelay: TimeInterval = 0.25
    static let defaultMaxElements = 400
    static let hardMaxElements = 2000

    private struct Seen { let element: AXUIElement; let role: String; let title: String; let frame: CGRect; let pid: pid_t; let at: Date }
    private var seen: [String: Seen] = [:]
    private var promptedForTrust = false
    /// Called with a `highlight` payload before any act/click so the companion cursor flies there first.
    var show: (([String: Any]) -> Void)?

    struct Failure: LocalizedError { let message: String; var errorDescription: String? { message } }

    // MARK: Entry point used by the companion socket glue

    func perform(op: String, args: [String: Any]) async throws -> [String: Any] {
        switch op {
        case "inspect": return inspect(args)
        case "act": return try await act(args)
        case "click": return try await click(args)
        case "type": return try await type(args)
        case "key": return try await key(args)
        default: throw Failure(message: "Unknown desktop operation \"\(op)\". Expected inspect, act, click, type or key.")
        }
    }

    // MARK: Permission

    /// Asks macOS once for the Accessibility permission; later calls only report the current state.
    func ensureTrusted() -> Bool {
        if AXIsProcessTrusted() { return true }
        if !promptedForTrust {
            promptedForTrust = true
            let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
            return AXIsProcessTrustedWithOptions(options)
        }
        return false
    }

    static let permissionMessage = "Luma is not trusted for Accessibility. Open System Settings → Privacy & Security → Accessibility, enable Luma, then try again. In-app qupath_ui/qupath_ui_action still work without it."

    private func untrusted() -> [String: Any] { ["trusted": false, "error": Self.permissionMessage] }

    // MARK: Inspect

    func inspect(_ args: [String: Any]) -> [String: Any] {
        guard ensureTrusted() else { return untrusted() }
        let requested = (args["apps"] as? [String]) ?? (args["appBundleIds"] as? [String]) ?? []
        let titleFilter = ((args["windowTitleContains"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let maxElements = min(Self.hardMaxElements, max(1, (args["maxElements"] as? Int) ?? Self.defaultMaxElements))
        let apps = resolveApps(requested)
        let now = Date()
        seen = seen.filter { now.timeIntervalSince($0.value.at) < Self.inspectionValidity }
        var windows: [[String: Any]] = []
        var total = 0, truncated = false
        for app in apps {
            let application = AXUIElementCreateApplication(app.processIdentifier)
            let axWindows = (attribute(application, kAXWindowsAttribute) as? [AXUIElement]) ?? []
            for (index, window) in axWindows.enumerated() {
                let title = string(attribute(window, kAXTitleAttribute))
                let role = string(attribute(window, kAXRoleAttribute))
                let subrole = string(attribute(window, kAXSubroleAttribute))
                guard let frame = frame(of: window), frame.width > 0, frame.height > 0 else { continue }
                if !titleFilter.isEmpty && !title.lowercased().contains(titleFilter) { continue }
                let base = "\(app.processIdentifier)/w\(index)"
                var elements: [[String: Any]] = []
                var ids = 0
                walk(window, id: base, depth: 0, pid: app.processIdentifier, into: &elements, count: &ids, budget: maxElements - total, truncated: &truncated)
                total += elements.count
                var entry: [String: Any] = [
                    "id": base, "app": app.bundleIdentifier ?? "pid:\(app.processIdentifier)", "pid": Int(app.processIdentifier),
                    "title": title, "role": role, "frame": json(frame), "elements": elements,
                    "focused": (attribute(window, kAXFocusedAttribute) as? Bool) ?? false,
                    "main": (attribute(window, kAXMainAttribute) as? Bool) ?? false,
                ]
                if !subrole.isEmpty { entry["subrole"] = subrole }
                if (attribute(window, kAXModalAttribute) as? Bool) == true { entry["modal"] = true }
                if role == "AXSheet" || subrole == "AXDialog" || subrole == "AXSystemDialog" || subrole == "AXFloatingWindow" { entry["dialog"] = true }
                windows.append(entry)
                if total >= maxElements { truncated = true; break }
            }
            if total >= maxElements { break }
        }
        let frontmost = NSWorkspace.shared.frontmostApplication
        return [
            "trusted": true,
            "apps": apps.map { ["bundleId": $0.bundleIdentifier ?? "", "pid": Int($0.processIdentifier), "name": $0.localizedName ?? "", "active": $0.isActive] },
            "frontmost": frontmost?.bundleIdentifier ?? "",
            "screens": NSScreen.screens.map { json(Self.globalBounds(of: $0)) },
            "windows": windows, "elements": total, "truncated": truncated,
            "validForSeconds": Int(Self.inspectionValidity),
            "coordinateSystem": "global screen points, top-left origin",
            "note": "JavaFX (QuPath) windows often report enabled:false for controls that are live; trust qupath_ui for JavaFX enabled state. Ids are valid for 30 s.",
        ]
    }

    private func resolveApps(_ requested: [String]) -> [NSRunningApplication] {
        var result: [NSRunningApplication] = []
        func add(_ app: NSRunningApplication?) { guard let app, !result.contains(where: { $0.processIdentifier == app.processIdentifier }) else { return }; result.append(app) }
        let names = requested.isEmpty ? [Self.quPathBundleID] : requested
        for name in names {
            if name == "frontmost" { add(NSWorkspace.shared.frontmostApplication); continue }
            for app in NSRunningApplication.runningApplications(withBundleIdentifier: name) { add(app) }
        }
        // File dialogs are drawn by Apple's panel service, one process per host app named "… (Host)"; include only ours.
        let hosts = result.compactMap { $0.localizedName }.filter { !$0.isEmpty }
        for app in NSRunningApplication.runningApplications(withBundleIdentifier: Self.panelServiceBundleID) {
            let name = app.localizedName ?? ""
            if hosts.contains(where: { name.contains("(\($0))") }) { add(app) }
        }
        return result
    }

    private static let actionableRoles: Set<String> = [
        "AXButton", "AXCheckBox", "AXRadioButton", "AXPopUpButton", "AXMenuButton", "AXMenuItem", "AXMenuBarItem", "AXTextField", "AXTextArea",
        "AXSecureTextField", "AXComboBox", "AXSlider", "AXIncrementor", "AXDisclosureTriangle", "AXLink", "AXRow", "AXCell", "AXColorWell",
        "AXTabGroup", "AXToolbar", "AXStaticText", "AXImage", "AXValueIndicator", "AXScrollBar", "AXOutline", "AXTable", "AXList", "AXWebArea", "AXDateField", "AXTimeField", "AXStepper",
    ]
    private static let containerOnlyRoles: Set<String> = ["AXGroup", "AXScrollArea", "AXSplitGroup", "AXLayoutArea", "AXLayoutItem", "AXUnknown", "AXWindow", "AXSheet", "AXDrawer", "AXGrowArea", "AXSplitter", "AXTabGroup", "AXToolbar", "AXOutline", "AXTable", "AXList", "AXMenu", "AXMenuBar"]
    private static let leafRoles: Set<String> = ["AXButton", "AXCheckBox", "AXRadioButton", "AXMenuItem", "AXStaticText", "AXTextField", "AXSecureTextField", "AXSlider", "AXIncrementor", "AXDisclosureTriangle", "AXLink", "AXImage", "AXColorWell", "AXValueIndicator", "AXScrollBar", "AXStepper"]
    private static let maxDepth = 28

    private func walk(_ element: AXUIElement, id: String, depth: Int, pid: pid_t, into elements: inout [[String: Any]], count: inout Int, budget: Int, truncated: inout Bool) {
        if elements.count >= budget { truncated = true; return }
        guard depth < Self.maxDepth else { return }
        let children = (attribute(element, kAXChildrenAttribute) as? [AXUIElement]) ?? []
        for (index, child) in children.enumerated() {
            if elements.count >= budget { truncated = true; return }
            let childID = depth == 0 ? "\(id)/\(index)" : "\(id).\(index)"
            let role = string(attribute(child, kAXRoleAttribute))
            let frame = frame(of: child)
            let visible = frame.map { rect in rect.width > 0 && rect.height > 0 && NSScreen.screens.contains(where: { Self.globalBounds(of: $0).intersects(rect) }) } ?? false
            if let frame, visible, Self.actionableRoles.contains(role) || !Self.containerOnlyRoles.contains(role) {
                let actions = actionNames(child)
                let title = string(attribute(child, kAXTitleAttribute))
                let label = string(attribute(child, kAXDescriptionAttribute))
                let value = string(attribute(child, kAXValueAttribute))
                let keep = Self.actionableRoles.contains(role) || !actions.isEmpty
                if keep && !(role == "AXStaticText" && title.isEmpty && value.isEmpty && label.isEmpty) && !(role == "AXImage" && title.isEmpty && label.isEmpty) {
                    var entry: [String: Any] = ["id": childID, "role": role, "frame": json(frame), "enabled": (attribute(child, kAXEnabledAttribute) as? Bool) ?? true]
                    if !title.isEmpty { entry["title"] = clip(title) }
                    if !label.isEmpty { entry["label"] = clip(label) }
                    if !value.isEmpty { entry["value"] = clip(value) }
                    let subrole = string(attribute(child, kAXSubroleAttribute)); if !subrole.isEmpty { entry["subrole"] = subrole }
                    let placeholder = string(attribute(child, kAXPlaceholderValueAttribute)); if !placeholder.isEmpty { entry["placeholder"] = clip(placeholder) }
                    let help = string(attribute(child, kAXHelpAttribute)); if !help.isEmpty { entry["help"] = clip(help) }
                    if (attribute(child, kAXFocusedAttribute) as? Bool) == true { entry["focused"] = true }
                    if (attribute(child, kAXSelectedAttribute) as? Bool) == true { entry["selected"] = true }
                    if !actions.isEmpty { entry["actions"] = actions.map { $0.replacingOccurrences(of: "AX", with: "") } }
                    if role == "AXPopUpButton" || role == "AXComboBox", let options = attribute(child, kAXChildrenAttribute) as? [AXUIElement], !options.isEmpty {
                        entry["childCount"] = options.count
                    }
                    elements.append(entry)
                    seen[childID] = Seen(element: child, role: role, title: title.isEmpty ? label : title, frame: frame, pid: pid, at: Date())
                    count += 1
                }
            }
            // Leaf controls only contain their own label text; recurse into everything else (menus that are not open are skipped by AX itself).
            if Self.leafRoles.contains(role) { continue }
            walk(child, id: childID, depth: depth + 1, pid: pid, into: &elements, count: &count, budget: budget, truncated: &truncated)
        }
    }

    // MARK: Act on an inspected element

    func act(_ args: [String: Any]) async throws -> [String: Any] {
        guard ensureTrusted() else { return untrusted() }
        guard let id = args["id"] as? String, !id.isEmpty else { throw Failure(message: "act needs the id of an element from qupath_desktop_inspect.") }
        guard let action = args["action"] as? String, DesktopProtocol.actions.contains(action) else { throw Failure(message: "action must be one of \(DesktopProtocol.actions.joined(separator: ", ")).") }
        guard let target = seen[id] else { throw Failure(message: "Element \(id) was not seen in the last \(Int(Self.inspectionValidity)) s. Run qupath_desktop_inspect again and use a fresh id.") }
        guard Date().timeIntervalSince(target.at) < Self.inspectionValidity else { seen[id] = nil; throw Failure(message: "Inspection of \(id) is older than \(Int(Self.inspectionValidity)) s. Inspect again before acting.") }
        let role = string(attribute(target.element, kAXRoleAttribute))
        guard !role.isEmpty else { seen[id] = nil; throw Failure(message: "Element \(id) no longer exists on screen. Inspect again.") }
        guard role == target.role else { seen[id] = nil; throw Failure(message: "Element \(id) changed from \(target.role) to \(role) since inspection. Inspect again.") }
        let frame = frame(of: target.element) ?? target.frame
        // JavaFX reports AXEnabled=false for many live controls (verified against qupath_ui), so the flag is advisory only:
        // the toolkit itself ignores presses on a truly disabled control, and the reply says what was reported.
        let enabled = (attribute(target.element, kAXEnabledAttribute) as? Bool) ?? true
        let value = (args["value"] as? String) ?? ""
        let label = (args["label"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? DesktopProtocol.defaultLabel(action: action, role: role, title: target.title)
        let highlight = DesktopProtocol.highlight(label: label, frame: frame)
        raise(target.element, pid: target.pid)
        await reveal(highlight)
        var method = "ax"
        var note = ""
        let actions = actionNames(target.element)
        switch action {
        case "press", "toggle":
            if actions.contains(kAXPressAction), AXUIElementPerformAction(target.element, kAXPressAction as CFString) == .success {}
            else if actions.contains(kAXConfirmAction), AXUIElementPerformAction(target.element, kAXConfirmAction as CFString) == .success {}
            else { method = "synthetic"; SyntheticInput.click(at: CGPoint(x: frame.midX, y: frame.midY)) }
        case "focus":
            if AXUIElementSetAttributeValue(target.element, kAXFocusedAttribute as CFString, kCFBooleanTrue) != .success { method = "synthetic"; SyntheticInput.click(at: CGPoint(x: frame.midX, y: frame.midY)) }
        case "select":
            if AXUIElementSetAttributeValue(target.element, kAXSelectedAttribute as CFString, kCFBooleanTrue) == .success {}
            else if actions.contains(kAXPressAction), AXUIElementPerformAction(target.element, kAXPressAction as CFString) == .success {}
            else if actions.contains(kAXPickAction), AXUIElementPerformAction(target.element, kAXPickAction as CFString) == .success {}
            else { method = "synthetic"; SyntheticInput.click(at: CGPoint(x: frame.midX, y: frame.midY)) }
            if role == "AXPopUpButton" || role == "AXComboBox" { note = "The menu may now be open: inspect again and select the wanted AXMenuItem." }
        case "setValue":
            if AXUIElementSetAttributeValue(target.element, kAXValueAttribute as CFString, value as CFString) != .success {
                method = "synthetic"
                focusByClick(target.element, frame: frame)
                SyntheticInput.press(DesktopProtocol.parseShortcut("cmd+a")!)
                SyntheticInput.type(value)
            }
        case "type":
            method = "synthetic"
            focusByClick(target.element, frame: frame)
            SyntheticInput.type(value)
        default: break
        }
        try? await Task.sleep(for: .milliseconds(120))
        var result: [String: Any] = ["id": id, "action": action, "role": role, "title": target.title, "method": method, "highlight": highlight, "frame": json(frame)]
        let after = string(attribute(target.element, kAXValueAttribute)); if !after.isEmpty { result["value"] = clip(after) }
        if !note.isEmpty { result["note"] = note }
        if !enabled { result["warning"] = "Accessibility reported this control as disabled (JavaFX often misreports this); if nothing changed, the control really was disabled." }
        result["next"] = "Inspect again to confirm the effect; dispatch is not completion."
        return result
    }

    private func focusByClick(_ element: AXUIElement, frame: CGRect) {
        if AXUIElementSetAttributeValue(element, kAXFocusedAttribute as CFString, kCFBooleanTrue) != .success || (attribute(element, kAXFocusedAttribute) as? Bool) != true {
            SyntheticInput.click(at: CGPoint(x: frame.midX, y: frame.midY))
        }
    }

    /// Brings the owning window and application forward so synthetic input lands where the cursor is pointing.
    private func raise(_ element: AXUIElement, pid: pid_t) {
        if let window = attribute(element, kAXWindowAttribute) { AXUIElementPerformAction(window as! AXUIElement, kAXRaiseAction as CFString) }
        activate(pid: pid)
    }

    private func activate(pid: pid_t) {
        guard let app = NSRunningApplication(processIdentifier: pid), !app.isActive else { return }
        app.activate()
    }

    private func activate(bundleID: String?) -> String {
        let name = bundleID ?? Self.quPathBundleID
        if name == "none" { return "" }
        let app = name == "frontmost" ? NSWorkspace.shared.frontmostApplication : NSRunningApplication.runningApplications(withBundleIdentifier: name).first
        guard let app else { return "" }
        activate(pid: app.processIdentifier)
        return app.bundleIdentifier ?? name
    }

    private func reveal(_ highlight: [String: Any]) async {
        show?(highlight)
        try? await Task.sleep(for: .seconds(Self.showDelay))
    }

    // MARK: Primitives

    func click(_ args: [String: Any]) async throws -> [String: Any] {
        guard ensureTrusted() else { return untrusted() }
        guard let x = DesktopProtocol.number(args["x"]), let y = DesktopProtocol.number(args["y"]) else { throw Failure(message: "click needs numeric x and y in global screen points.") }
        let point = CGPoint(x: x, y: y)
        guard NSScreen.screens.contains(where: { Self.globalBounds(of: $0).contains(point) }) else { throw Failure(message: "(\(Int(x)), \(Int(y))) is outside every screen.") }
        let count = min(3, max(1, (args["count"] as? Int) ?? 1))
        let button = (args["button"] as? String) == "right" ? CGMouseButton.right : .left
        let modifiers = DesktopProtocol.modifierFlags((args["modifiers"] as? [String]) ?? [])
        let label = (args["label"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? (count > 1 ? "Double-click" : "Click here")
        let activated = activate(bundleID: args["app"] as? String)
        let highlight = DesktopProtocol.highlight(label: label, frame: CGRect(x: x - 12, y: y - 12, width: 24, height: 24))
        await reveal(highlight)
        SyntheticInput.click(at: point, button: button, count: count, modifiers: modifiers)
        try? await Task.sleep(for: .milliseconds(120))
        return ["x": x, "y": y, "count": count, "button": button == .right ? "right" : "left", "activated": activated, "method": "synthetic", "highlight": highlight, "next": "Inspect again to see what the click did."]
    }

    func type(_ args: [String: Any]) async throws -> [String: Any] {
        guard ensureTrusted() else { return untrusted() }
        guard let text = args["text"] as? String, !text.isEmpty else { throw Failure(message: "type needs a non-empty text.") }
        guard text.count <= 4096 else { throw Failure(message: "type accepts at most 4096 characters per call.") }
        let activated = activate(bundleID: args["app"] as? String)
        let focused = focusedElementInfo()
        var result: [String: Any] = ["typed": text.count, "activated": activated, "method": "synthetic"]
        if let focused {
            let highlight = DesktopProtocol.highlight(label: (args["label"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Typing here", frame: focused.frame)
            result["highlight"] = highlight; result["focused"] = ["role": focused.role, "title": focused.title]
            await reveal(highlight)
        } else {
            result["warning"] = "No focused text element was found; the keystrokes go to whatever has keyboard focus."
            try? await Task.sleep(for: .seconds(Self.showDelay))
        }
        SyntheticInput.type(text)
        if (args["submit"] as? Bool) == true { try? await Task.sleep(for: .milliseconds(80)); SyntheticInput.press(DesktopProtocol.parseShortcut("return")!); result["submitted"] = true }
        try? await Task.sleep(for: .milliseconds(120))
        if let focused = focusedElementInfo(), !focused.value.isEmpty { result["value"] = clip(focused.value) }
        result["next"] = "Inspect again to verify the field contents or the dialog state."
        return result
    }

    func key(_ args: [String: Any]) async throws -> [String: Any] {
        guard ensureTrusted() else { return untrusted() }
        var shortcuts = (args["keys"] as? [String]) ?? []
        if let single = args["shortcut"] as? String { shortcuts.insert(single, at: 0) }
        shortcuts = shortcuts.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        guard !shortcuts.isEmpty, shortcuts.count <= 10 else { throw Failure(message: "key needs 1-10 shortcuts such as \"cmd+shift+g\" or \"return\".") }
        var parsed: [DesktopProtocol.Shortcut] = []
        for shortcut in shortcuts {
            guard let combo = DesktopProtocol.parseShortcut(shortcut) else { throw Failure(message: "Unrecognised shortcut \"\(shortcut)\". Use modifiers cmd/shift/alt/ctrl plus one key such as g, return, escape, tab, down or f5.") }
            parsed.append(combo)
        }
        let activated = activate(bundleID: args["app"] as? String)
        var result: [String: Any] = ["keys": shortcuts, "activated": activated, "method": "synthetic"]
        if let focused = focusedElementInfo() {
            let highlight = DesktopProtocol.highlight(label: (args["label"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? shortcuts.joined(separator: ", "), frame: focused.frame)
            result["highlight"] = highlight
            await reveal(highlight)
        } else { try? await Task.sleep(for: .seconds(Self.showDelay)) }
        for (index, combo) in parsed.enumerated() {
            if index > 0 { try? await Task.sleep(for: .milliseconds(90)) }
            SyntheticInput.press(combo)
        }
        try? await Task.sleep(for: .milliseconds(150))
        result["next"] = "Inspect again: a shortcut may have opened a sheet or moved focus."
        return result
    }

    private struct Focused { let role: String; let title: String; let value: String; let frame: CGRect }
    private func focusedElementInfo() -> Focused? {
        let system = AXUIElementCreateSystemWide()
        guard let raw = attribute(system, kAXFocusedUIElementAttribute) else { return nil }
        let element = raw as! AXUIElement
        guard let frame = frame(of: element), frame.width > 0 else { return nil }
        let title = string(attribute(element, kAXTitleAttribute)), label = string(attribute(element, kAXDescriptionAttribute))
        return Focused(role: string(attribute(element, kAXRoleAttribute)), title: title.isEmpty ? label : title, value: string(attribute(element, kAXValueAttribute)), frame: frame)
    }

    // MARK: AX helpers

    private func attribute(_ element: AXUIElement, _ name: String) -> AnyObject? {
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(element, name as CFString, &value) == .success else { return nil }
        return value
    }

    private func actionNames(_ element: AXUIElement) -> [String] {
        var names: CFArray?
        guard AXUIElementCopyActionNames(element, &names) == .success, let list = names as? [String] else { return [] }
        return list
    }

    private func frame(of element: AXUIElement) -> CGRect? {
        guard let positionValue = attribute(element, kAXPositionAttribute), let sizeValue = attribute(element, kAXSizeAttribute) else { return nil }
        var point = CGPoint.zero, size = CGSize.zero
        guard AXValueGetValue(positionValue as! AXValue, .cgPoint, &point), AXValueGetValue(sizeValue as! AXValue, .cgSize, &size) else { return nil }
        guard point.x.isFinite, point.y.isFinite, size.width.isFinite, size.height.isFinite else { return nil }
        return CGRect(origin: point, size: size)
    }

    private func string(_ value: AnyObject?) -> String {
        guard let value else { return "" }
        if let text = value as? String { return text }
        if let number = value as? NSNumber { return number.stringValue }
        if let url = value as? URL { return url.path }
        if CFGetTypeID(value) == AXValueGetTypeID() {
            let axValue = value as! AXValue
            var range = CFRange(); if AXValueGetValue(axValue, .cfRange, &range) { return "\(range.location)+\(range.length)" }
            return ""
        }
        if let list = value as? [AnyObject] { return list.prefix(8).map { string($0) }.filter { !$0.isEmpty }.joined(separator: ", ") }
        if CFGetTypeID(value) == AXUIElementGetTypeID() { return "" }
        return String(describing: value)
    }

    private func clip(_ text: String) -> String {
        let flat = text.replacingOccurrences(of: "\n", with: " ")
        return flat.count > 240 ? String(flat.prefix(240)) + "…" : flat
    }

    private func json(_ rect: CGRect) -> [String: Double] { ["x": rect.minX.rounded(), "y": rect.minY.rounded(), "width": rect.width.rounded(), "height": rect.height.rounded()] }

    nonisolated static func globalBounds(of screen: NSScreen) -> CGRect {
        let id = screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? CGDirectDisplayID ?? CGMainDisplayID()
        return CGDisplayBounds(id)
    }
}

/// Pure, testable pieces of the desktop protocol: action names, shortcut parsing and highlight payload shape.
enum DesktopProtocol {
    static let operations = ["inspect", "act", "click", "type", "key"]
    static let actions = ["press", "setValue", "focus", "select", "toggle", "type"]

    struct Shortcut: Equatable { let keyCode: CGKeyCode; let flags: CGEventFlags; let name: String }

    static func number(_ value: Any?) -> Double? {
        if let number = value as? NSNumber { return number.doubleValue.isFinite ? number.doubleValue : nil }
        if let text = value as? String { return Double(text) }
        return nil
    }

    static func defaultLabel(action: String, role: String, title: String) -> String {
        let what = title.isEmpty ? role.replacingOccurrences(of: "AX", with: "") : String(title.prefix(32))
        switch action {
        case "press", "toggle": return what
        case "focus": return "Focus \(what)"
        case "select": return "Select \(what)"
        case "setValue", "type": return "Fill \(what)"
        default: return what
        }
    }

    /// The payload `PointerOverlay.highlight` renders: one rectangle plus the pointer landing at its centre.
    static func highlight(label: String, frame: CGRect) -> [String: Any] {
        [
            "label": String(label.prefix(80)),
            "targets": [["x": frame.minX, "y": frame.minY, "width": max(frame.width, 1), "height": max(frame.height, 1), "shape": "rect"]],
            "pointer": ["screenX": frame.midX, "screenY": frame.midY],
        ]
    }

    static func modifierFlags(_ names: [String]) -> CGEventFlags {
        var flags = CGEventFlags()
        for name in names { if let flag = modifier(named: name) { flags.insert(flag) } }
        return flags
    }

    private static func modifier(named raw: String) -> CGEventFlags? {
        switch raw.lowercased() {
        case "cmd", "command", "meta", "super": return .maskCommand
        case "shift": return .maskShift
        case "alt", "option", "opt": return .maskAlternate
        case "ctrl", "control": return .maskControl
        case "fn", "function": return .maskSecondaryFn
        default: return nil
        }
    }

    /// Named keys with layout-independent virtual key codes (Carbon kVK_* values).
    static let namedKeys: [String: CGKeyCode] = [
        "return": CGKeyCode(kVK_Return), "enter": CGKeyCode(kVK_Return), "tab": CGKeyCode(kVK_Tab), "space": CGKeyCode(kVK_Space),
        "delete": CGKeyCode(kVK_Delete), "backspace": CGKeyCode(kVK_Delete), "forwarddelete": CGKeyCode(kVK_ForwardDelete),
        "escape": CGKeyCode(kVK_Escape), "esc": CGKeyCode(kVK_Escape), "left": CGKeyCode(kVK_LeftArrow), "right": CGKeyCode(kVK_RightArrow),
        "up": CGKeyCode(kVK_UpArrow), "down": CGKeyCode(kVK_DownArrow), "home": CGKeyCode(kVK_Home), "end": CGKeyCode(kVK_End),
        "pageup": CGKeyCode(kVK_PageUp), "pagedown": CGKeyCode(kVK_PageDown), "f1": CGKeyCode(kVK_F1), "f2": CGKeyCode(kVK_F2), "f3": CGKeyCode(kVK_F3),
        "f4": CGKeyCode(kVK_F4), "f5": CGKeyCode(kVK_F5), "f6": CGKeyCode(kVK_F6), "f7": CGKeyCode(kVK_F7), "f8": CGKeyCode(kVK_F8), "f9": CGKeyCode(kVK_F9),
        "f10": CGKeyCode(kVK_F10), "f11": CGKeyCode(kVK_F11), "f12": CGKeyCode(kVK_F12),
    ]
    /// ANSI US layout fallback for printable characters when the current layout cannot be read.
    static let ansiKeys: [Character: CGKeyCode] = [
        "a": 0, "s": 1, "d": 2, "f": 3, "h": 4, "g": 5, "z": 6, "x": 7, "c": 8, "v": 9, "b": 11, "q": 12, "w": 13, "e": 14, "r": 15, "y": 16, "t": 17,
        "1": 18, "2": 19, "3": 20, "4": 21, "6": 22, "5": 23, "=": 24, "9": 25, "7": 26, "-": 27, "8": 28, "0": 29, "]": 30, "o": 31, "u": 32, "[": 33,
        "i": 34, "p": 35, "l": 37, "j": 38, "'": 39, "k": 40, ";": 41, "\\": 42, ",": 43, "/": 44, "n": 45, "m": 46, ".": 47, "`": 50,
    ]

    /// Parses "cmd+shift+g", "Return", "ctrl+alt+Down" into a key code and modifier flags. Returns nil when no single key is named.
    static func parseShortcut(_ text: String, layout: [Character: CGKeyCode] = currentLayoutKeys()) -> Shortcut? {
        let parts = text.split(whereSeparator: { $0 == "+" || $0 == "-" && text.count > 1 }).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        var flags = CGEventFlags(); var key: CGKeyCode?; var name = ""
        let tokens = parts.isEmpty && !text.isEmpty ? [text] : parts
        for token in tokens {
            if let flag = modifier(named: token) { flags.insert(flag); continue }
            guard key == nil else { return nil }
            let lower = token.lowercased()
            if let code = namedKeys[lower] { key = code; name = lower; continue }
            if lower.count == 1, let character = lower.first, let code = layout[character] ?? ansiKeys[character] {
                if token.first!.isUppercase { flags.insert(.maskShift) }
                key = code; name = lower; continue
            }
            if token == "+" , let code = layout["="] { key = code; flags.insert(.maskShift); name = "+"; continue }
            return nil
        }
        guard let key else { return nil }
        return Shortcut(keyCode: key, flags: flags, name: name)
    }

    /// Reads the user's current keyboard layout once so letters map to the physical key that produces them.
    static func currentLayoutKeys() -> [Character: CGKeyCode] {
        if let cached = layoutCache { return cached }
        var map: [Character: CGKeyCode] = [:]
        if let source = TISCopyCurrentKeyboardLayoutInputSource()?.takeRetainedValue(),
           let raw = TISGetInputSourceProperty(source, kTISPropertyUnicodeKeyLayoutData) {
            let data = Unmanaged<CFData>.fromOpaque(raw).takeUnretainedValue() as Data
            data.withUnsafeBytes { (bytes: UnsafeRawBufferPointer) in
                guard let base = bytes.baseAddress?.assumingMemoryBound(to: UCKeyboardLayout.self) else { return }
                for code in 0..<CGKeyCode(128) {
                    var deadKeys: UInt32 = 0; var length = 0; var chars = [UniChar](repeating: 0, count: 4)
                    let status = UCKeyTranslate(base, UInt16(code), UInt16(kUCKeyActionDisplay), 0, UInt32(LMGetKbdType()), UInt32(kUCKeyTranslateNoDeadKeysMask), &deadKeys, 4, &length, &chars)
                    guard status == noErr, length == 1, let scalar = Unicode.Scalar(chars[0]) else { continue }
                    let character = Character(scalar)
                    if map[character] == nil, !character.isWhitespace { map[character] = code }
                }
            }
        }
        layoutCache = map
        return map
    }
    nonisolated(unsafe) private static var layoutCache: [Character: CGKeyCode]?
}

/// Synthetic input via CGEvent for anything Accessibility actions cannot reach. Requires the Accessibility permission.
enum SyntheticInput {
    private static let source = CGEventSource(stateID: .combinedSessionState)
    private static func pause(_ milliseconds: UInt32) { usleep(milliseconds * 1000) }

    static func click(at point: CGPoint, button: CGMouseButton = .left, count: Int = 1, modifiers: CGEventFlags = []) {
        let down: CGEventType = button == .right ? .rightMouseDown : .leftMouseDown
        let up: CGEventType = button == .right ? .rightMouseUp : .leftMouseUp
        CGEvent(mouseEventSource: source, mouseType: .mouseMoved, mouseCursorPosition: point, mouseButton: button)?.post(tap: .cghidEventTap)
        pause(40)
        for state in 1...max(1, count) {
            for type in [down, up] {
                guard let event = CGEvent(mouseEventSource: source, mouseType: type, mouseCursorPosition: point, mouseButton: button) else { continue }
                event.setIntegerValueField(.mouseEventClickState, value: Int64(state))
                event.flags = modifiers
                event.post(tap: .cghidEventTap)
                pause(type == down ? 30 : 20)
            }
            if state < count { pause(60) }
        }
    }

    static func press(_ shortcut: DesktopProtocol.Shortcut) {
        guard let down = CGEvent(keyboardEventSource: source, virtualKey: shortcut.keyCode, keyDown: true),
              let up = CGEvent(keyboardEventSource: source, virtualKey: shortcut.keyCode, keyDown: false) else { return }
        down.flags = shortcut.flags; up.flags = shortcut.flags
        down.post(tap: .cghidEventTap); pause(25); up.post(tap: .cghidEventTap); pause(25)
    }

    /// Types text one character per key event so Java/JavaFX receives each as a normal keystroke; newlines press Return.
    static func type(_ text: String) {
        for character in text {
            if character == "\n" || character == "\r" { press(DesktopProtocol.parseShortcut("return")!); continue }
            var units = Array(String(character).utf16)
            guard let down = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: true),
                  let up = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: false) else { continue }
            down.keyboardSetUnicodeString(stringLength: units.count, unicodeString: &units)
            up.keyboardSetUnicodeString(stringLength: units.count, unicodeString: &units)
            down.post(tap: .cghidEventTap); pause(8); up.post(tap: .cghidEventTap); pause(8)
        }
    }
}
