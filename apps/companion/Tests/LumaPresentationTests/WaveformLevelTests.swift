import XCTest
import LumaPresentation

final class WaveformLevelTests: XCTestCase {
    func testSilenceAndNoiseFloorAreZero() {
        XCTAssertEqual(WaveformLevel.gain(0), 0)
        XCTAssertEqual(WaveformLevel.gain(0.0005), 0)
        XCTAssertEqual(WaveformLevel.gain(0.0003), 0, "Room noise from the fixed capture stays flat")
        XCTAssertEqual(WaveformLevel.gain(-1), 0)
        XCTAssertEqual(WaveformLevel.gain(.nan), 0)
    }
    func testSpeechRangeIsVisibleAndLogScaled() {
        let soft = WaveformLevel.gain(0.001), normal = WaveformLevel.gain(0.01), loud = WaveformLevel.gain(0.1)
        XCTAssertGreaterThan(soft, 0.15, "Soft speech must move the bars clearly")
        XCTAssertLessThan(soft, 0.45)
        XCTAssertGreaterThan(normal, soft); XCTAssertGreaterThan(normal, 0.45); XCTAssertLessThan(normal, 0.85)
        XCTAssertEqual(loud, 1); XCTAssertEqual(WaveformLevel.gain(0.3), 1)
        var previous = 0.0
        for step in stride(from: 0.0005, through: 0.1, by: 0.0005) {
            let value = WaveformLevel.gain(Float(step))
            XCTAssertGreaterThanOrEqual(value, previous); previous = value
        }
    }
    func testAttackIsFasterThanRelease() {
        let up = WaveformLevel.approach(from: 0, to: 1, elapsed: 0.05)
        let down = WaveformLevel.approach(from: 1, to: 0, elapsed: 0.05)
        XCTAssertGreaterThan(up, 0.6, "A peak registers within one frame burst")
        XCTAssertGreaterThan(down, 0.7, "Silence decays smoothly rather than snapping")
        XCTAssertEqual(WaveformLevel.approach(from: 0.4, to: 0.9, elapsed: 0), 0.4)
        XCTAssertLessThan(WaveformLevel.approach(from: 1, to: 0, elapsed: 1), 0.02, "Settles to the baseline within a second")
    }
    func testHeightsSettleToAThinBaselineWhenSilent() {
        for animated in [true, false] {
            let heights = WaveformLevel.heights(gain: 0, time: 1.23, animated: animated)
            XCTAssertEqual(heights.count, WaveformLevel.bars)
            XCTAssertTrue((24...32).contains(heights.count))
            XCTAssertTrue(heights.allSatisfy { $0 == WaveformLevel.baseline })
        }
    }
    func testHeightsAreNeverFlatWhileThereIsSound() {
        for level in [0.0007, 0.001, 0.005, 0.05] as [Float] {
            for animated in [true, false] {
                let heights = WaveformLevel.heights(gain: WaveformLevel.gain(level), time: 0.7, animated: animated)
                XCTAssertGreaterThan(heights.max()!, WaveformLevel.baseline + 0.03, "level \(level)")
                XCTAssertGreaterThan(Set(heights.map { ($0 * 1000).rounded() }).count, 3, "level \(level) animated=\(animated)")
                XCTAssertTrue(heights.allSatisfy { $0 >= WaveformLevel.baseline && $0 <= 1 })
            }
        }
        let soft = WaveformLevel.heights(gain: WaveformLevel.gain(0.001), time: 0, animated: false).max()!
        let loud = WaveformLevel.heights(gain: 1, time: 0, animated: false).max()!
        XCTAssertGreaterThan(loud, soft); XCTAssertGreaterThan(loud, 0.9)
    }
}
