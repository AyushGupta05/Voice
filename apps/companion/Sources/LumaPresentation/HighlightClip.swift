import CoreGraphics

/// One on-screen window as the window server lists it (front to back), in global top-left points.
public struct ScreenWindow: Equatable, Sendable {
    public let number: Int
    public let pid: Int32
    public let layer: Int
    public let bounds: CGRect
    public let alpha: Double
    public let owner: String
    public init(number: Int, pid: Int32, layer: Int, bounds: CGRect, alpha: Double = 1, owner: String = "") {
        self.number = number; self.pid = pid; self.layer = layer; self.bounds = bounds; self.alpha = alpha; self.owner = owner
    }
}

/// Where highlight outlines may be drawn: the part of the application's canvas that is actually visible.
public struct HighlightClip: Equatable, Sendable {
    /// The application window that holds the canvas (nil: not on screen — minimised, hidden, another Space or closed).
    public let holder: ScreenWindow?
    /// Disjoint rectangles of the canvas not covered by any window in front of the holder.
    public let visible: [CGRect]
    /// Visible share of the canvas, 0…1.
    public let visibleFraction: Double
}

/// Outlines belong to the image, not to the screen: they are clipped to the canvas and cut out wherever another window
/// (another app, or one of the application's own dialogs) is in front of it.
public enum HighlightClipper {
    /// - Parameters:
    ///   - viewer: the canvas in global top-left points, as the bridge reports it.
    ///   - windows: on-screen windows, front to back (CGWindowListCopyWindowInfo order).
    ///   - appPIDs: the application's processes; the holder is its frontmost window containing the canvas centre.
    ///   - ownPID: the companion itself (its overlay panels never occlude).
    ///   - holderNumber: once found, the same window is followed even after it moves.
    public static func clip(viewer: CGRect, windows: [ScreenWindow], appPIDs: Set<Int32>, ownPID: Int32, holderNumber: Int? = nil) -> HighlightClip {
        guard viewer.width > 0, viewer.height > 0 else { return HighlightClip(holder: nil, visible: [], visibleFraction: 0) }
        let centre = CGPoint(x: viewer.midX, y: viewer.midY)
        let index: Int? = {
            if let number = holderNumber, let found = windows.firstIndex(where: { $0.number == number }) { return found }
            return windows.firstIndex(where: { appPIDs.contains($0.pid) && $0.layer == 0 && $0.alpha > 0 && $0.bounds.contains(centre) })
        }()
        guard let holderIndex = index else { return HighlightClip(holder: nil, visible: [], visibleFraction: 0) }
        let holder = windows[holderIndex]
        var visible = [viewer.intersection(holder.bounds)].filter { !$0.isNull && $0.width > 0 && $0.height > 0 }
        for window in windows[..<holderIndex] where occludes(window, ownPID: ownPID) {
            visible = visible.flatMap { subtract(window.bounds, from: $0) }
            if visible.isEmpty { break }
        }
        let area = visible.reduce(0.0) { $0 + Double($1.width * $1.height) }
        return HighlightClip(holder: holder, visible: visible, visibleFraction: min(1, area / Double(viewer.width * viewer.height)))
    }

    /// Windows that really cover content: not the companion, not invisible, not the window server's own full-screen layers.
    static func occludes(_ window: ScreenWindow, ownPID: Int32) -> Bool {
        if window.pid == ownPID || window.alpha <= 0.01 || window.layer < 0 { return false }
        if window.owner == "Window Server" { return false }
        // The Dock's Mission Control / desktop layers span whole screens and are transparent where it matters.
        if window.owner == "Dock" && window.layer >= 20 && window.bounds.width * window.bounds.height > 600_000 { return false }
        return true
    }

    /// `rect` minus `hole`, as up to four disjoint rectangles.
    public static func subtract(_ hole: CGRect, from rect: CGRect) -> [CGRect] {
        let cut = rect.intersection(hole)
        guard !cut.isNull, cut.width > 0, cut.height > 0 else { return [rect] }
        var parts: [CGRect] = []
        if cut.minY > rect.minY { parts.append(CGRect(x: rect.minX, y: rect.minY, width: rect.width, height: cut.minY - rect.minY)) }
        if cut.maxY < rect.maxY { parts.append(CGRect(x: rect.minX, y: cut.maxY, width: rect.width, height: rect.maxY - cut.maxY)) }
        if cut.minX > rect.minX { parts.append(CGRect(x: rect.minX, y: cut.minY, width: cut.minX - rect.minX, height: cut.height)) }
        if cut.maxX < rect.maxX { parts.append(CGRect(x: cut.maxX, y: cut.minY, width: rect.maxX - cut.maxX, height: cut.height)) }
        return parts
    }
}

/// What the companion cursor is doing, so it can show it: listening, thinking, speaking or resting.
public enum CursorMood: Equatable, Sendable {
    case idle, listening, thinking, speaking
    public init(_ status: BarStatus) {
        switch status.phase {
        case .idle: self = .idle
        case .listening: self = .listening
        case .executing: self = status.speaking ? .speaking : (status.showsResult ? .idle : .thinking)
        }
    }
}
