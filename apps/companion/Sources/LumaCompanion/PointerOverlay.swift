import AppKit
import SwiftUI
import os

/// One outlined target in global screen points, top-left origin (JavaFX/CoreGraphics convention).
struct HighlightTarget: Identifiable, Equatable {
    let id: Int
    let rect: CGRect
    let ellipse: Bool
}

/// Clicky-style companion cursor: a click-through full-screen overlay that flies a glowing cursor
/// along an arc to what the agent is talking about and outlines the matched objects or controls.
@MainActor
final class PointerOverlay: ObservableObject {
    @Published fileprivate var cursor = CGPoint(x: -100, y: -100)
    @Published fileprivate var previous = CGPoint(x: -100, y: -100)
    @Published fileprivate var flight = 0
    @Published fileprivate var label = ""
    @Published fileprivate var targets: [HighlightTarget] = []
    @Published fileprivate var visible = false
    @Published fileprivate var generation = 0

    private var panel: NSPanel?
    private var screenBounds = CGRect.zero
    private var hideTask: Task<Void, Never>?
    private var followTimer: Timer?
    private var following = false

    /// Fly to a single point (e.g. after a focus) with a short label.
    func show(x: Double, y: Double, label: String) {
        present(point: CGPoint(x: x, y: y), label: label, targets: [], hold: 3)
    }

    /// Fly to and outline a qupath_highlight result.
    func highlight(_ value: [String: Any]) {
        let raw = value["targets"] as? [[String: Any]] ?? []
        var targets: [HighlightTarget] = []
        for (index, item) in raw.prefix(120).enumerated() {
            guard let x = item["x"] as? Double, let y = item["y"] as? Double,
                  let w = item["width"] as? Double, let h = item["height"] as? Double, w > 0, h > 0 else { continue }
            targets.append(HighlightTarget(id: index, rect: CGRect(x: x, y: y, width: w, height: h), ellipse: item["shape"] as? String == "ellipse"))
        }
        let pointer = value["pointer"] as? [String: Any]
        var point = CGPoint(x: pointer?["screenX"] as? Double ?? .nan, y: pointer?["screenY"] as? Double ?? .nan)
        if !point.x.isFinite || !point.y.isFinite {
            guard let first = targets.first else { return }
            point = CGPoint(x: first.rect.midX, y: first.rect.midY)
        }
        var text = (value["label"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty, let total = value["total"] as? Int, total > 1 { text = "\(total) here" }
        present(point: point, label: text.isEmpty ? "Right here" : text, targets: targets, hold: 6)
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

    private func trackMouse() {
        guard following, hideTask == nil || targets.isEmpty else { return }
        let mouse = NSEvent.mouseLocation
        guard let screen = NSScreen.screens.first(where: { $0.frame.contains(mouse) }) ?? NSScreen.main else { return }
        let global = CGPoint(x: mouse.x - screen.frame.minX + globalBounds(of: screen).minX,
                             y: globalBounds(of: screen).minY + (screen.frame.maxY - mouse.y))
        ensurePanel(for: global)
        let local = CGPoint(x: global.x - screenBounds.minX + 22, y: global.y - screenBounds.minY + 18)
        previous = local; cursor = local; label = ""; visible = true
        panel?.orderFrontRegardless()
    }

    private func present(point: CGPoint, label: String, targets: [HighlightTarget], hold: Double) {
        hideTask?.cancel(); hideTask = nil
        Logger(subsystem: "science.luma.companion", category: "cursor").info("Cursor flies to (\(Int(point.x)), \(Int(point.y))) '\(label)' with \(targets.count) outlines")
        let movedScreen = ensurePanel(for: point)
        let local = CGPoint(x: point.x - screenBounds.minX, y: point.y - screenBounds.minY)
        self.targets = targets.map { HighlightTarget(id: $0.id, rect: $0.rect.offsetBy(dx: -screenBounds.minX, dy: -screenBounds.minY), ellipse: $0.ellipse) }
        self.label = label
        // Start a fresh flight from wherever the cursor was (or from just above the target the first time).
        previous = (visible && !movedScreen) ? cursor : CGPoint(x: local.x - 160, y: max(0, local.y - 220))
        cursor = local
        flight += 1; generation += 1
        visible = true
        panel?.orderFrontRegardless()
        scheduleHide(after: hold)
    }

    private func scheduleHide(after seconds: Double) {
        hideTask?.cancel()
        let current = generation
        hideTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard let self, !Task.isCancelled, self.generation == current else { return }
            self.hideTask = nil
            if self.following { self.targets = []; self.label = ""; return }
            withAnimation(.easeOut(duration: 0.35)) { self.visible = false; self.targets = [] }
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
            p.level = .statusBar; p.ignoresMouseEvents = true
            p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
            p.contentView = NSHostingView(rootView: OverlayView(overlay: self))
            panel = p
        }
        if changed || panel?.frame != screen.frame { panel?.setFrame(screen.frame, display: true) }
        return changed
    }
}

/// Moves along a quadratic arc between two points as `progress` animates 0 → 1.
private struct ArcFlight: GeometryEffect {
    var progress: CGFloat
    let from: CGPoint
    let to: CGPoint
    var animatableData: CGFloat { get { progress } set { progress = newValue } }
    func effectValue(size: CGSize) -> ProjectionTransform {
        let distance = hypot(to.x - from.x, to.y - from.y)
        let control = CGPoint(x: (from.x + to.x) / 2, y: min(from.y, to.y) - min(140, distance * 0.35))
        let t = progress, u = 1 - t
        let x = u * u * from.x + 2 * u * t * control.x + t * t * to.x
        let y = u * u * from.y + 2 * u * t * control.y + t * t * to.y
        return ProjectionTransform(CGAffineTransform(translationX: x, y: y))
    }
}

private struct OverlayView: View {
    @ObservedObject var overlay: PointerOverlay
    @State private var progress: CGFloat = 1
    private let accent = Color(red: 0.36, green: 0.92, blue: 0.78)
    var body: some View {
        ZStack(alignment: .topLeading) {
            ForEach(overlay.targets) { target in
                HighlightShape(target: target, accent: accent, delay: Double(min(target.id, 30)) * 0.025, generation: overlay.generation)
            }
            CursorBuddy(label: overlay.label, accent: accent)
                .modifier(ArcFlight(progress: progress, from: overlay.previous, to: overlay.cursor))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .opacity(overlay.visible ? 1 : 0)
        .allowsHitTesting(false)
        .onChange(of: overlay.flight) { _, _ in
            progress = 0
            let reduce = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
            withAnimation(reduce ? nil : .timingCurve(0.3, 0.0, 0.2, 1.0, duration: 0.65)) { progress = 1 }
        }
    }
}

private struct CursorBuddy: View {
    let label: String
    let accent: Color
    @State private var breathe = false
    var body: some View {
        HStack(alignment: .top, spacing: 4) {
            BuddyArrow()
                .fill(accent)
                .overlay(BuddyArrow().stroke(Color.black.opacity(0.55), lineWidth: 1.2))
                .frame(width: 20, height: 26)
                .shadow(color: accent.opacity(breathe ? 0.9 : 0.45), radius: breathe ? 14 : 8)
                .scaleEffect(breathe ? 1.06 : 1)
            if !label.isEmpty {
                Text(label)
                    .font(.system(size: 12, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(Color.black.opacity(0.82), in: Capsule())
                    .overlay(Capsule().stroke(accent.opacity(0.7), lineWidth: 1))
                    .offset(y: 16)
                    .transition(.scale(scale: 0.6, anchor: .topLeading).combined(with: .opacity))
            }
        }
        .fixedSize()
        .animation(.spring(response: 0.3, dampingFraction: 0.75), value: label)
        .onAppear { withAnimation(.easeInOut(duration: 1.1).repeatForever(autoreverses: true)) { breathe = true } }
        .accessibilityLabel(label.isEmpty ? "Penny cursor" : "Penny is pointing at \(label)")
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

private struct HighlightShape: View {
    let target: HighlightTarget
    let accent: Color
    let delay: Double
    let generation: Int
    @State private var shown = false
    @State private var pulse = false
    var body: some View {
        let inset = target.rect.insetBy(dx: -4, dy: -4)
        Group {
            if target.ellipse {
                Ellipse().stroke(accent, lineWidth: 2.2).background(Ellipse().fill(accent.opacity(0.12)))
            } else {
                RoundedRectangle(cornerRadius: 6).stroke(accent, lineWidth: 2.2).background(RoundedRectangle(cornerRadius: 6).fill(accent.opacity(0.10)))
            }
        }
        .shadow(color: accent.opacity(pulse ? 0.9 : 0.35), radius: pulse ? 10 : 4)
        .frame(width: inset.width, height: inset.height)
        .scaleEffect(shown ? 1 : 1.35)
        .opacity(shown ? 1 : 0)
        .position(x: inset.midX, y: inset.midY)
        .onAppear { animateIn() }
        .onChange(of: generation) { _, _ in shown = false; animateIn() }
    }
    private func animateIn() {
        withAnimation(.spring(response: 0.35, dampingFraction: 0.7).delay(0.45 + delay)) { shown = true }
        withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true).delay(0.8 + delay)) { pulse = true }
    }
}
