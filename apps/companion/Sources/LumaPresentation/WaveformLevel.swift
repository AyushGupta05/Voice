import Foundation

/// Pure mapping from the microphone level to waveform bar heights.
///
/// Speech from the fixed single-channel capture arrives as small linear RMS levels: silence around 0.0005 and
/// speech peaks of roughly 0.001–0.05 (measured live on 17 Oct 2026), so the gain is log-scaled between a
/// silence floor and a loud ceiling. The displayed gain approaches the target with a
/// fast attack and slower release so peaks register at once and silence settles to a thin baseline.
public enum WaveformLevel {
    public static let bars = 28
    /// Levels at or below this are silence (noise floor of the capture path).
    public static let floor: Float = 0.0005
    /// Levels at or above this fill the bars.
    public static let ceiling: Float = 0.1
    /// Fraction of the available height a bar keeps while silent.
    public static let baseline = 0.12
    /// Time constants (seconds) for the exponential approach towards a louder / quieter target.
    public static let attack = 0.05
    public static let release = 0.22

    /// Log-scaled 0…1 gain. Exactly 0 at or below the floor, exactly 1 at or above the ceiling.
    public static func gain(_ level: Float) -> Double {
        guard level.isFinite, level > floor else { return 0 }
        guard level < ceiling else { return 1 }
        let ratio = log10(Double(level) / Double(floor)) / log10(Double(ceiling) / Double(floor))
        // Lift the quiet end so soft speech is clearly visible without pinning normal speech at the top.
        return min(1, max(0, pow(ratio, 0.8)))
    }

    /// Exponential approach from `start` towards `target` after `elapsed` seconds.
    public static func approach(from start: Double, to target: Double, elapsed: Double) -> Double {
        guard elapsed > 0 else { return start }
        let tau = target > start ? attack : release
        return target + (start - target) * exp(-elapsed / tau)
    }

    /// Per-bar heights as fractions (0…1) of the available height for a displayed gain.
    /// Always at least the baseline; strictly above it in the middle whenever `gain > 0`.
    /// `animated: false` (reduce motion) still scales with the level but does not drift over time.
    public static func heights(gain: Double, time: Double, animated: Bool, bars count: Int = bars) -> [Double] {
        let clamped = min(1, max(0, gain))
        let centre = Double(count - 1) / 2
        return (0..<count).map { index in
            let distance = centre > 0 ? abs(Double(index) - centre) / centre : 0
            let envelope = 1 - 0.65 * distance * distance
            let wobble = animated ? 0.5 + 0.5 * sin(time * (5.5 + Double(index % 5) * 0.9) + Double(index) * 1.1) : 0.75
            let amplitude = clamped * envelope * (0.55 + 0.45 * wobble)
            return min(1, baseline + (1 - baseline) * amplitude)
        }
    }
}
