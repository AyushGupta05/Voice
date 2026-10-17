import AppKit
import SwiftUI

/// Penny's light palette: every companion surface (bar, panel, result views, cursor label and chips) takes its colours here.
///
/// Text tokens meet WCAG AA (at least 4.5:1) on `surface` (white) and on `surfaceMuted`; the measured ratios are noted on
/// each token. The overlay tokens are drawn over microscopy images rather than on Penny's surfaces, so they stay bright.
enum PennyPalette {
    /// The appearance the companion's windows and panels are forced to, regardless of the system setting.
    static var appearance: NSAppearance? { NSAppearance(named: .aqua) }

    // MARK: Surfaces
    /// Bar and panel background.
    static let surface = Color.white
    /// Cards, inputs and quick actions on the panel (#F4F5F7).
    static let surfaceMuted = Color(hex: 0xF4F5F7)
    /// Code blocks inside cards (#ECEEF1).
    static let surfaceSunken = Color(hex: 0xECEEF1)
    /// Hairline borders, dividers and card outlines.
    static let border = Color.black.opacity(0.08)
    /// Neutral fill behind idle round controls.
    static let controlFill = Color.black.opacity(0.05)
    /// Soft lift under floating chips and capsules.
    static let shadow = Color.black.opacity(0.16)

    // MARK: Text
    /// #1D2125: 16.2:1 on white, 14.9:1 on `surfaceMuted`.
    static let textPrimary = Color(hex: 0x1D2125)
    /// #5E646C: 5.97:1 on white, 5.48:1 on `surfaceMuted`.
    static let textSecondary = Color(hex: 0x5E646C)

    // MARK: Accent and status
    /// Deep teal #0B7D66: 5.07:1 on white, 4.65:1 on `surfaceMuted`; white text on it is 5.07:1.
    /// (The brighter #0E8F74 is only 4.04:1 on white, below AA for text.)
    static let accent = Color(hex: 0x0B7D66)
    /// Text and glyphs on an `accent` fill.
    static let onAccent = Color.white
    /// Icon tiles and the active microphone ring.
    static let accentSoft = accent.opacity(0.10)
    /// Large tinted hint cards.
    static let accentWash = accent.opacity(0.06)
    /// #C2410C: 5.18:1 on white; attention icons, offline dots and stale-observation notes.
    static let warning = Color(hex: 0xC2410C)

    // MARK: Class swatches (small dots next to class names)
    static let swatchPositive = Color(hex: 0xDC4A3C)
    static let swatchNegative = Color(hex: 0x2F6FDB)
    static let swatchTumour = Color(hex: 0xE07B12)
    static let swatchStroma = Color(hex: 0xD4A20B)

    // MARK: Overlay (over the image canvas)
    /// Bright mint for the cursor arrow, its trail and the object outlines: it reads on dark fluorescence and, with
    /// `overlayOutline`, on pale brightfield images.
    static let overlayAccent = Color(red: 0.36, green: 0.92, blue: 0.78)
    /// Dark rim around the bright cursor arrow.
    static let overlayOutline = Color(hex: 0x06352B).opacity(0.9)
}

private extension Color {
    init(hex: UInt32) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255, opacity: 1)
    }
}
