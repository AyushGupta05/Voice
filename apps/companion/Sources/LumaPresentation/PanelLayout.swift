import Foundation
import CoreGraphics

public enum PanelLayout {
    public static func frame(current: CGRect, visible: CGRect, expanded: Bool) -> CGRect {
        let height = min(expanded ? 656.0 : 56.0, visible.height)
        let width = min(420.0, visible.width)
        return CGRect(x: min(max(current.minX, visible.minX), visible.maxX - width),
                      y: max(visible.minY, min(current.maxY, visible.maxY) - height),
                      width: width, height: height)
    }
}
