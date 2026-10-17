import Foundation

public enum PCMError: Error { case invalidFrameCount, invalidByteCount }
public struct PCMChunk {
    public let data: Data
    public let rms: Float
}

/// Signed little-endian PCM16 at the transport boundary. Resampling is a separate step.
public enum PCM16 {
    public static let sampleRate = 24_000
    public static let maximumInputFrames = 24_000
    public static let maximumOutputFrames = 48_000

    public static func encode(_ samples: UnsafeBufferPointer<Float>) throws -> PCMChunk {
        guard !samples.isEmpty, samples.count <= maximumInputFrames else { throw PCMError.invalidFrameCount }
        var data = Data(count: samples.count * 2)
        var energy: Double = 0
        data.withUnsafeMutableBytes { (bytes: UnsafeMutableRawBufferPointer) in
            for (index, raw) in samples.enumerated() {
                // Invalid device samples become silence instead of trapping during Float -> Int16 conversion.
                let sample = raw.isFinite ? max(-1, min(1, raw)) : 0
                energy += Double(sample) * Double(sample)
                let integer = Int16(max(-32768, min(32767, Int(sample * 32768))))
                bytes.storeBytes(of: integer.littleEndian, toByteOffset: index * 2, as: Int16.self)
            }
        }
        return PCMChunk(data: data, rms: Float(sqrt(energy / Double(samples.count))))
    }
    public static func encode(_ samples: [Float]) throws -> PCMChunk {
        try samples.withUnsafeBufferPointer { try encode($0) }
    }
    public static func decode(_ data: Data) throws -> [Float] {
        guard !data.isEmpty, data.count % 2 == 0, data.count <= maximumOutputFrames * 2 else { throw PCMError.invalidByteCount }
        return data.withUnsafeBytes { bytes in
            (0..<data.count / 2).map { index in
                Float(Int16(littleEndian: bytes.loadUnaligned(fromByteOffset: index * 2, as: Int16.self))) / 32768
            }
        }
    }
}
