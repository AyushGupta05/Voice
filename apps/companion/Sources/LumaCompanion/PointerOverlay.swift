import AppKit
import SwiftUI
import LumaPresentation
import os

/// One outlined target in global screen points, top-left origin (JavaFX/CoreGraphics convention).
struct HighlightTarget: Identifiable, Equatable {
    let id: Int
    let rect: CGRect
    let ellipse: Bool
    var name: String = ""
}

/// Clicky-style companion cursor: a click-through full-screen overlay that flies a glowing cursor
/// along an arc to what the agent is talking about and outlines the matched objects or controls.
///
/// Outlines belong to the image, not to the screen: when the bridge reports the canvas (`viewer`), they are clipped to the
/// part of the canvas that is really visible (windows in front cut them out), follow the application window when it is
/// dragged, and disappear while it is minimised or on another Space. The cursor shows what Penny is doing (listening,
/// thinking, speaking) and the outline under the researcher's mouse lights up with its name.
@MainActor
final class PointerOverlay: ObservableObject {
    @Published fileprivate var cursor = CGPoint(x: -100, y: -100)
    @Published fileprivate var previous = CGPoint(x: -100, y: -100)
    @Published fileprivate var flight = 0
    @Published fileprivate var label = ""
    @Published fileprivate var targets: [HighlightTarget] = []
    @Published fileprivate var visible = false
    @Published fileprivate var generation = 0
    /// Visible canvas pieces in panel-local points; nil when the payload has no canvas (desktop highlights are not clipped).
    @Published fileprivate var clipRects: [CGRect]?
    /// The outline under the researcher's mouse.
    @Published fileprivate var hovered: Int?
    @Published fileprivate var mood: CursorMood = .idle
    @Published fileprivate var level: CGFloat = 0
    /// The target point is hidden behind another window: the cursor waits there, dimmed.
    @Published fileprivate var pointHidden = false
    /// The application's canvas is not on this screen at all (another Space, minimised, hidden): nothing is drawn.
    @Published fileprivate var canvasAway = false

    /// The application whose windows hold the canvas (from the gateway's /health `app.bundleId`).
    var appBundleID: String?

    private var panel: NSPanel?
    private var screenBounds = CGRect.zero
    private var hideTask: Task<Void, Never>?
    private var followTimer: Timer?
    private var following = false

    // Canvas tracking for the current highlight, in global top-left points.
    private var viewer: CGRect?
    private var holderNumber: Int?
    private var holderOrigin: CGPoint?
    private var baseTargets: [HighlightTarget] = []
    private var basePoint = CGPoint.zero
    private var clipTimer: Timer?
    private var raisedForGeneration = -1
    private var currentOffset = CGVector(dx: 0, dy: 0)

    /// Fly to a single point (e.g. after a focus) with a short label.
    func show(x: Double, y: Double, label: String) {
        present(point: CGPoint(x: x, y: y), label: label, targets: [], viewer: nil, hold: 3)
    }

    /// Fly to and outline a highlight result.
    func highlight(_ value: [String: Any]) {
        let raw = value["targets"] as? [[String: Any]] ?? []
        var targets: [HighlightTarget] = []
        for (index, item) in raw.prefix(120).enumerated() {
            guard let x = number(item["x"]), let y = number(item["y"]),
                  let w = number(item["width"]), let h = number(item["height"]), w > 0, h > 0 else { continue }
            targets.append(HighlightTarget(id: index, rect: CGRect(x: x, y: y, width: w, height: h), ellipse: item["shape"] as? String == "ellipse",
                                           name: (item["name"] as? String) ?? (item["label"] as? String) ?? ""))
        }
        let pointer = value["pointer"] as? [String: Any]
        var point = CGPoint(x: number(pointer?["screenX"]) ?? .nan, y: number(pointer?["screenY"]) ?? .nan)
        if !point.x.isFinite || !point.y.isFinite {
            guard let first = targets.first else { return }
            point = CGPoint(x: first.rect.midX, y: first.rect.midY)
        }
        var text = (value["label"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty, let total = value["total"] as? Int, total > 1 { text = "\(total) here" }
        var canvas: CGRect?
        if let v = value["viewer"] as? [String: Any], let x = number(v["x"]), let y = number(v["y"]), let w = number(v["width"]), let h = number(v["height"]), w > 0, h > 0 {
            canvas = CGRect(x: x, y: y, width: w, height: h)
        }
        present(point: point, label: text.isEmpty ? "Right here" : text, targets: targets, viewer: canvas, hold: 6)
    }

    /// What Penny is doing; the cursor shows it (ring for listening, orbit for thinking, ripple for speaking).
    func setMood(_ mood: CursorMood) {
        guard mood != self.mood else { return }
        self.mood = mood
        if mood != .idle, !visible, following { trackMouse() }
    }

    /// Microphone level 0…1 while listening; the listening ring breathes with the voice.
    func setLevel(_ value: Float) {
        let next = CGFloat(max(0, min(1, value * 6)))
        if abs(next - level) > 0.02 { level = level * 0.6 + next * 0.4 }
    }

    /// While listening, the cursor rides beside the system pointer, like Clicky.
    func follow(_ enabled: Bool) {
        guard enabled != following else { return }
        following = enabled
        followTimer?.invalidate(); followTimer = nil
        guard enabled else { if targets.isEmpty { scheduleHide(after: 0.4) }; return }
        followTimer = Timer.scheduledTimer(withTimeInterval: 1 / 30, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.trackMouse() }
        }
    }

    private func number(_ value: Any?) -> Double? {
        if let v = value as? Double { return v }
        if let v = value as? Int { return Double(v) }
        if let v = value as? NSNumber { return v.doubleValue }
        return nil
    }

    private func mouseGlobal() -> CGPoint? {
        let mouse = NSEvent.mouseLocation
        guard let screen = NSScreen.screens.first(where: { $0.frame.contains(mouse) }) ?? NSScreen.main else { return nil }
        let bounds = globalBounds(of: screen)
        return CGPoint(x: mouse.x - screen.frame.minX + bounds.minX, y: bounds.minY + (screen.frame.maxY - mouse.y))
    }

    private func trackMouse() {
        // A point-only focus has no outlined targets; it still owns its hold time before mouse following resumes.
        guard following, hideTask == nil, let global = mouseGlobal() else { return }
        ensurePanel(for: global)
        let local = CGPoint(x: global.x - screenBounds.minX + 22, y: global.y - screenBounds.minY + 18)
        previous = local; cursor = local; label = ""; visible = true; pointHidden = false
        if viewer == nil { canvasAway = false }
        panel?.orderFrontRegardless()
    }

    private func present(point: CGPoint, label: String, targets: [HighlightTarget], viewer: CGRect?, hold: Double) {
        guard point.x.isFinite, point.y.isFinite else { return }
        hideTask?.cancel(); hideTask = nil
        Logger(subsystem: "science.luma.companion", category: "cursor").info("Cursor flies to (\(Int(point.x)), \(Int(point.y))) '\(label)' with \(targets.count) outlines")
        let movedScreen = ensurePanel(for: point)
        self.viewer = viewer; holderNumber = nil; holderOrigin = nil; currentOffset = CGVector(dx: 0, dy: 0)
        baseTargets = targets; basePoint = point; hovered = nil
        let local = CGPoint(x: point.x - screenBounds.minX, y: point.y - screenBounds.minY)
        self.targets = targets.map { localTarget($0, dx: 0, dy: 0) }
        self.label = label
        // Start a fresh flight from wherever the cursor was (or from just above the target the first time).
        previous = (visible && !movedScreen) ? cursor : CGPoint(x: local.x - 160, y: max(0, local.y - 220))
        cursor = local
        flight += 1; generation += 1
        visible = true
        clipRects = nil; canvasAway = false
        updateClip(first: true)
        panel?.orderFrontRegardless()
        startTracking()
        scheduleHide(after: hold)
    }

    private func localTarget(_ target: HighlightTarget, dx: CGFloat, dy: CGFloat) -> HighlightTarget {
        HighlightTarget(id: target.id, rect: target.rect.offsetBy(dx: dx - screenBounds.minX, dy: dy - screenBounds.minY), ellipse: target.ellipse, name: target.name)
    }

    /// 15 Hz while outlines are up: re-clip against the windows in front, follow the window if it moves, light up the hovered outline.
    private func startTracking() {
        clipTimer?.invalidate()
        clipTimer = Timer.scheduledTimer(withTimeInterval: 1 / 15, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    private func tick() {
        guard visible, !targets.isEmpty || viewer != nil else { clipTimer?.invalidate(); clipTimer = nil; return }
        updateClip(first: false)
        if let mouse = mouseGlobal() {
            let local = CGPoint(x: mouse.x - screenBounds.minX, y: mouse.y - screenBounds.minY)
            let inside = clipRects.map { rects in rects.contains { $0.contains(local) } } ?? true
            let hit = inside ? targets.first(where: { $0.rect.insetBy(dx: -3, dy: -3).contains(local) })?.id : nil
            if hit != hovered { withAnimation(.spring(response: 0.25, dampingFraction: 0.7)) { hovered = hit } }
        }
    }

    private func updateClip(first: Bool) {
        guard let viewer else { clipRects = nil; pointHidden = false; return }
        let windows = Self.screenWindows()
        let pids = Set(appPIDs())
        let clip = HighlightClipper.clip(viewer: viewer.offsetBy(dx: currentOffset.dx, dy: currentOffset.dy), windows: windows,
                                         appPIDs: pids, ownPID: ProcessInfo.processInfo.processIdentifier, holderNumber: holderNumber)
        if first {
            Logger(subsystem: "science.luma.companion", category: "cursor").info("Canvas \(clip.holder == nil ? "not on this screen" : "in window \(clip.holder!.number)", privacy: .public), \(Int(clip.visibleFraction * 100))% visible, \(pids.count) app processes")
        }
        guard let holder = clip.holder else {
            // Not on screen (minimised, hidden, another Space): nothing may be drawn over whatever is there instead.
            clipRects = []; pointHidden = true
            if !canvasAway { withAnimation(.easeOut(duration: 0.15)) { canvasAway = true } }
            // Penny is showing an answer: bring the application (and its Space) forward once so the answer appears.
            if raisedForGeneration != generation, let app = pids.compactMap({ NSRunningApplication(processIdentifier: $0) }).first(where: { $0.activationPolicy == .regular }) {
                raisedForGeneration = generation
                // Hold the outlines a little longer: switching Spaces takes a moment.
                scheduleHide(after: 7)
                app.activate()
            }
            return
        }
        if canvasAway { withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { canvasAway = false }; previous = CGPoint(x: cursor.x - 160, y: max(0, cursor.y - 220)); flight += 1 }
        if holderNumber == nil { holderNumber = holder.number; holderOrigin = holder.bounds.origin }
        // Follow the window: the canvas moved by exactly as much as its window.
        if let origin = holderOrigin {
            let dx = holder.bounds.minX - origin.x, dy = holder.bounds.minY - origin.y
            if dx != currentOffset.dx || dy != currentOffset.dy {
                currentOffset = CGVector(dx: dx, dy: dy)
                targets = baseTargets.map { localTarget($0, dx: dx, dy: dy) }
                if !following { previous = cursor; cursor = CGPoint(x: basePoint.x + dx - screenBounds.minX, y: basePoint.y + dy - screenBounds.minY) }
                return updateClip(first: false)
            }
        }
        let rects = clip.visible.map { $0.offsetBy(dx: -screenBounds.minX, dy: -screenBounds.minY) }
        if rects != clipRects { withAnimation(.easeOut(duration: 0.12)) { clipRects = rects } }
        let target = CGPoint(x: basePoint.x + currentOffset.dx - screenBounds.minX, y: basePoint.y + currentOffset.dy - screenBounds.minY)
        let hidden = !rects.contains { $0.contains(target) }
        if hidden != pointHidden { withAnimation(.easeInOut(duration: 0.2)) { pointHidden = hidden } }
        // Mostly covered when Penny starts to show something: bring the application forward once, so the answer is visible.
        if first, clip.visibleFraction < 0.35, raisedForGeneration != generation, let app = NSRunningApplication(processIdentifier: holder.pid) {
            raisedForGeneration = generation
            app.activate()
        }
    }

    private func appPIDs() -> [Int32] {
        guard let id = appBundleID, !id.isEmpty else { return [] }
        return NSRunningApplication.runningApplications(withBundleIdentifier: id).map(\.processIdentifier)
    }

    /// On-screen windows, front to back, in global top-left points.
    static func screenWindows() -> [ScreenWindow] {
        guard let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] else { return [] }
        return list.compactMap { info in
            guard let number = info[kCGWindowNumber as String] as? Int, let pid = info[kCGWindowOwnerPID as String] as? Int32,
                  let boundsDict = info[kCGWindowBounds as String] as? [String: Any],
                  let bounds = CGRect(dictionaryRepresentation: boundsDict as CFDictionary) else { return nil }
            return ScreenWindow(number: number, pid: pid, layer: info[kCGWindowLayer as String] as? Int ?? 0, bounds: bounds,
                                alpha: info[kCGWindowAlpha as String] as? Double ?? 1, owner: info[kCGWindowOwnerName as String] as? String ?? "")
        }
    }

    private func scheduleHide(after seconds: Double) {
        hideTask?.cancel()
        let current = generation
        hideTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard let self, !Task.isCancelled, self.generation == current else { return }
            self.hideTask = nil
            if self.following { self.targets = []; self.label = ""; self.viewer = nil; self.clipRects = nil; return }
            withAnimation(.easeOut(duration: 0.35)) { self.visible = false; self.targets = [] }
            self.clipTimer?.invalidate(); self.clipTimer = nil; self.viewer = nil
            try? await Task.sleep(for: .seconds(0.4))
            if !self.visible { self.panel?.orderOut(nil) }
        }
    }

    private func globalBounds(of screen: NSScreen) -> CGRect {
        let id = screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? CGDirectDisplayID ?? CGMainDisplayID()
        return CGDisplayBounds(id)
    }

    /// Covers the screen containing `point` (global top-left coordinates). Returns true if the screen changed.
    @discardableResult
    private func ensurePanel(for point: CGPoint) -> Bool {
        let screen = NSScreen.screens.first(where: { globalBounds(of: $0).contains(point) }) ?? NSScreen.main ?? NSScreen.screens[0]
        let bounds = globalBounds(of: screen)
        let changed = bounds != screenBounds
        screenBounds = bounds
        if panel == nil {
            let p = NSPanel(contentRect: screen.frame, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
            p.isOpaque = false; p.backgroundColor = .clear; p.hasShadow = false
            p.appearance = PennyPalette.appearance
            p.level = .statusBar; p.ignoresMouseEvents = true
            p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
            p.contentView = NSHostingView(rootView: OverlayView(overlay: self))
            panel = p
        }
        if changed || panel?.frame != screen.frame { panel?.setFrame(screen.frame, display: true) }
        return changed
    }
}

/// Quadratic arc shared by the flight and its trail.
private func arcPoint(_ t: CGFloat, from: CGPoint, to: CGPoint) -> CGPoint {
    let distance = hypot(to.x - from.x, to.y - from.y)
    let control = CGPoint(x: (from.x + to.x) / 2, y: min(from.y, to.y) - min(140, distance * 0.35))
    let u = 1 - t
    return CGPoint(x: u * u * from.x + 2 * u * t * control.x + t * t * to.x, y: u * u * from.y + 2 * u * t * control.y + t * t * to.y)
}

/// Moves along a quadratic arc between two points as `progress` animates 0 → 1.
private struct ArcFlight: GeometryEffect {
    var progress: CGFloat
    let from: CGPoint
    let to: CGPoint
    var animatableData: CGFloat { get { progress } set { progress = newValue } }
    func effectValue(size: CGSize) -> ProjectionTransform {
        let p = arcPoint(progress, from: from, to: to)
        return ProjectionTransform(CGAffineTransform(translationX: p.x, y: p.y))
    }
}

/// A comet tail behind the flying cursor: the last stretch of the arc, fading out.
private struct ArcTrail: Shape {
    var progress: CGFloat
    let from: CGPoint
    let to: CGPoint
    var animatableData: CGFloat { get { progress } set { progress = newValue } }
    func path(in rect: CGRect) -> Path {
        var path = Path()
        let start = max(0, progress - 0.3)
        guard progress > 0.01, progress < 0.999, hypot(to.x - from.x, to.y - from.y) > 40 else { return path }
        path.move(to: arcPoint(start, from: from, to: to))
        for step in 1...16 { path.addLine(to: arcPoint(start + (progress - start) * CGFloat(step) / 16, from: from, to: to)) }
        return path
    }
}

private struct OverlayView: View {
    @ObservedObject var overlay: PointerOverlay
    @State private var progress: CGFloat = 1
    private let accent = PennyPalette.overlayAccent
    var body: some View {
        ZStack(alignment: .topLeading) {
            highlights
                .mask { ClipMask(rects: overlay.clipRects) }
            ArcTrail(progress: progress, from: overlay.previous, to: overlay.cursor)
                .stroke(LinearGradient(colors: [accent.opacity(0), accent.opacity(0.75)], startPoint: .leading, endPoint: .trailing),
                        style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .blur(radius: 0.6)
            CursorBuddy(label: overlay.label, accent: accent, mood: overlay.mood, level: overlay.level, dimmed: overlay.pointHidden)
                .modifier(ArcFlight(progress: progress, from: overlay.previous, to: overlay.cursor))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .opacity(overlay.visible && !overlay.canvasAway ? 1 : 0)
        .allowsHitTesting(false)
        .onChange(of: overlay.flight) { _, _ in
            progress = 0
            let reduce = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
            withAnimation(reduce ? nil : .timingCurve(0.3, 0.0, 0.2, 1.0, duration: 0.65)) { progress = 1 }
        }
    }
    private var highlights: some View {
        ZStack(alignment: .topLeading) {
            ForEach(overlay.targets) { target in
                HighlightShape(target: target, accent: accent, delay: Double(min(target.id, 30)) * 0.025, generation: overlay.generation,
                               hovered: overlay.hovered == target.id)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

/// Only the visible canvas pieces let outlines through; nil means no canvas was reported, so nothing is clipped.
private struct ClipMask: View {
    let rects: [CGRect]?
    var body: some View {
        if let rects {
            ZStack(alignment: .topLeading) {
                ForEach(Array(rects.enumerated()), id: \.offset) { _, rect in
                    Rectangle().frame(width: rect.width, height: rect.height).position(x: rect.midX, y: rect.midY)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        } else {
            Rectangle()
        }
    }
}

struct CursorBuddy: View {
    let label: String
    let accent: Color
    let mood: CursorMood
    let level: CGFloat
    let dimmed: Bool
    @State private var breathe = false
    @State private var orbit = false
    @State private var ripple = false
    var body: some View {
        HStack(alignment: .top, spacing: 4) {
            ZStack(alignment: .topLeading) {
                moodLayer
                BuddyArrow()
                    .fill(accent)
                    .overlay(BuddyArrow().stroke(PennyPalette.overlayOutline, lineWidth: 1.3))
                    .frame(width: 20, height: 26)
                    .shadow(color: accent.opacity(breathe ? 0.9 : 0.45), radius: breathe ? 14 : 8)
                    .scaleEffect(breathe ? 1.06 : 1, anchor: .topLeading)
                    .rotationEffect(.degrees(mood == .thinking ? (orbit ? 6 : -6) : 0), anchor: .topLeading)
            }
            .frame(width: 20, height: 26, alignment: .topLeading)
            if !label.isEmpty {
                Text(label)
                    .font(.system(size: 12, weight: .semibold, design: .rounded))
                    .foregroundStyle(PennyPalette.textPrimary)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(PennyPalette.surface, in: Capsule())
                    .overlay(Capsule().stroke(PennyPalette.border, lineWidth: 1))
                    .shadow(color: PennyPalette.shadow, radius: 6, y: 2)
                    .offset(y: 16)
                    .transition(.scale(scale: 0.6, anchor: .topLeading).combined(with: .opacity))
            }
        }
        .fixedSize()
        .opacity(dimmed ? 0.45 : 1)
        .animation(.spring(response: 0.3, dampingFraction: 0.75), value: label)
        .animation(.easeInOut(duration: 0.25), value: mood)
        .onAppear {
            withAnimation(.easeInOut(duration: 1.1).repeatForever(autoreverses: true)) { breathe = true }
            withAnimation(.linear(duration: 1.2).repeatForever(autoreverses: false)) { orbit = true }
            withAnimation(.easeOut(duration: 1.4).repeatForever(autoreverses: false)) { ripple = true }
        }
        .accessibilityLabel(label.isEmpty ? "Penny cursor" : "Penny is pointing at \(label)")
    }

    /// Listening: a ring at the tip that swells with the researcher's voice. Thinking: three dots orbiting the tip.
    /// Speaking: soft ripples spreading from the tip.
    @ViewBuilder private var moodLayer: some View {
        switch mood {
        case .listening:
            Circle().stroke(accent.opacity(0.85), lineWidth: 2)
                .frame(width: 22 + level * 26, height: 22 + level * 26)
                .position(x: 0, y: 0)
                .animation(.easeOut(duration: 0.08), value: level)
        case .thinking:
            ForEach(0..<3, id: \.self) { index in
                Circle().fill(accent).frame(width: 5, height: 5)
                    .offset(x: 15)
                    .rotationEffect(.degrees((orbit ? 360 : 0) + Double(index) * 120))
                    .position(x: 2, y: 2)
            }
        case .speaking:
            ForEach(0..<2, id: \.self) { index in
                Circle().stroke(accent.opacity(ripple ? 0 : 0.7), lineWidth: 1.5)
                    .frame(width: ripple ? 46 : 10, height: ripple ? 46 : 10)
                    .position(x: 0, y: 0)
                    .animation(.easeOut(duration: 1.4).repeatForever(autoreverses: false).delay(Double(index) * 0.7), value: ripple)
            }
        case .idle:
            EmptyView()
        }
    }
}

/// Classic pointer silhouette, tip at the view's top-left so the tip lands exactly on the target.
private struct BuddyArrow: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: r.minX, y: r.minY))
        p.addLine(to: CGPoint(x: r.minX, y: r.maxY * 0.82))
        p.addLine(to: CGPoint(x: r.maxX * 0.30, y: r.maxY * 0.63))
        p.addLine(to: CGPoint(x: r.maxX * 0.52, y: r.maxY))
        p.addLine(to: CGPoint(x: r.maxX * 0.70, y: r.maxY * 0.92))
        p.addLine(to: CGPoint(x: r.maxX * 0.48, y: r.maxY * 0.57))
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY * 0.57))
        p.closeSubpath()
        return p
    }
}

struct HighlightShape: View {
    let target: HighlightTarget
    let accent: Color
    let delay: Double
    let generation: Int
    let hovered: Bool
    @State private var shown: Bool
    @State private var pulse = false
    /// A spotlight, not a second overlay: after a few seconds the outline fades and Fiji's own overlay remains (hover brings it back).
    @State private var faded = false
    init(target: HighlightTarget, accent: Color, delay: Double, generation: Int, hovered: Bool, shown: Bool = false) {
        self.target = target; self.accent = accent; self.delay = delay; self.generation = generation; self.hovered = hovered; _shown = State(initialValue: shown)
    }
    var body: some View {
        let inset = target.rect.insetBy(dx: -4, dy: -4)
        ZStack(alignment: .topLeading) {
            Group {
                if target.ellipse {
                    Ellipse().stroke(accent, lineWidth: hovered ? 3.4 : 2.2).background(Ellipse().fill(accent.opacity(hovered ? 0.26 : 0.12)))
                } else {
                    RoundedRectangle(cornerRadius: 6).stroke(accent, lineWidth: hovered ? 3.4 : 2.2).background(RoundedRectangle(cornerRadius: 6).fill(accent.opacity(hovered ? 0.24 : 0.10)))
                }
            }
            .shadow(color: accent.opacity(hovered ? 1 : (pulse ? 0.9 : 0.35)), radius: hovered ? 14 : (pulse ? 10 : 4))
            .frame(width: inset.width, height: inset.height)
            if hovered {
                Text(target.name.isEmpty ? "#\(target.id + 1)" : target.name)
                    .font(.system(size: 11, weight: .semibold, design: .rounded))
                    .foregroundStyle(PennyPalette.textPrimary)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(PennyPalette.surface, in: Capsule())
                    .overlay(Capsule().stroke(PennyPalette.border, lineWidth: 1))
                    .shadow(color: PennyPalette.shadow, radius: 4, y: 1)
                    .fixedSize()
                    .offset(y: -18)
                    .transition(.scale(scale: 0.7, anchor: .bottomLeading).combined(with: .opacity))
            }
        }
        .frame(width: inset.width, height: inset.height, alignment: .topLeading)
        .scaleEffect(shown ? (hovered ? 1.08 : 1) : 1.35)
        .opacity(shown ? (faded && !hovered ? 0 : 1) : 0)
        .animation(.easeInOut(duration: 0.2), value: hovered)
        .position(x: inset.midX, y: inset.midY)
        .onAppear { animateIn() }
        .onChange(of: generation) { _, _ in shown = false; faded = false; animateIn() }
    }
    private func animateIn() {
        withAnimation(.spring(response: 0.35, dampingFraction: 0.7).delay(0.45 + delay)) { shown = true }
        withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true).delay(0.8 + delay)) { pulse = true }
        withAnimation(.easeOut(duration: 1.1).delay(3.2 + delay)) { faded = true }
    }
}
