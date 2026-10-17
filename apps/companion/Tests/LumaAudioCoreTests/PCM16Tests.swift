import XCTest
@testable import LumaAudioCore

final class PCM16Tests: XCTestCase {
    func testKnownSignedLittleEndianSamples() throws {
        let chunk = try PCM16.encode([-1, -0.5, 0, 0.5, 1])
        XCTAssertEqual(Array(chunk.data), [0,128,0,192,0,0,0,64,255,127])
        XCTAssertEqual(try PCM16.decode(chunk.data), [-1,-0.5,0,0.5,Float(32767)/32768])
    }
    func testClippingAndInvalidSamplesAreFinite() throws {
        let chunk = try PCM16.encode([Float.nan, .infinity, -.infinity, -2, 2])
        XCTAssertEqual(try PCM16.decode(chunk.data), [0,0,0,-1,Float(32767)/32768])
        XCTAssertTrue(chunk.rms.isFinite)
        XCTAssertEqual(chunk.rms, sqrt(0.4), accuracy: 0.00001)
    }
    func testMalformedAndOversizedChunksAreRejected() throws {
        XCTAssertThrowsError(try PCM16.encode([]))
        XCTAssertThrowsError(try PCM16.encode(Array(repeating: 0, count: PCM16.maximumInputFrames + 1)))
        for data in [Data(), Data([0]), Data(repeating: 0, count: PCM16.maximumOutputFrames * 2 + 2)] {
            XCTAssertThrowsError(try PCM16.decode(data))
        }
        XCTAssertEqual(try PCM16.decode(Data(repeating: 0, count: PCM16.maximumOutputFrames * 2)).count, PCM16.maximumOutputFrames)
    }
    func testSyntheticSignalRoundTripAndMeter() throws {
        let samples = (0..<24_000).map { Float(sin(2 * .pi * 1000 * Double($0) / 24_000)) * 0.5 }
        let chunk = try PCM16.encode(samples)
        XCTAssertEqual(chunk.data.count, 48_000)
        XCTAssertEqual(chunk.rms, Float(0.5 / sqrt(2)), accuracy: 0.00001)
        let decoded = try PCM16.decode(chunk.data)
        XCTAssertEqual(decoded.count, samples.count)
        XCTAssertLessThan(zip(decoded, samples).map { abs($0 - $1) }.max()!, 1 / 32768)
    }
}
