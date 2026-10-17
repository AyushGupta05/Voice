import AVFoundation
import Foundation

/// PCM16 mono at 24 kHz, matching the documented OpenAI realtime format.
/// Production capture is only started after a funded session is accepted.
final class AudioIO {
    private let capture = AVAudioEngine()
    private let playback = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 24000, channels: 1, interleaved: false)!
    private var tapping = false
    init() {
        playback.attach(player)
        playback.connect(player, to: playback.mainMixerNode, format: target)
    }
    func start(onChunk: @escaping (Data, Float) -> Void) async throws {
        guard await AVCaptureDevice.requestAccess(for: .audio) else { throw CompanionError.message("Microphone access is needed for voice.") }
        let input = capture.inputNode
        let source = input.outputFormat(forBus: 0)
        guard let converter = AVAudioConverter(from: source, to: target) else { throw CompanionError.message("Could not configure microphone audio.") }
        stopCapture()
        input.installTap(onBus: 0, bufferSize: 2048, format: source) { [target] buffer, _ in
            let capacity = AVAudioFrameCount(Double(buffer.frameLength) * 24000 / source.sampleRate + 64)
            guard let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return }
            var consumed = false
            var error: NSError?
            converter.convert(to: output, error: &error) { _, status in
                if consumed { status.pointee = .noDataNow; return nil }
                consumed = true; status.pointee = .haveData; return buffer
            }
            guard error == nil, let samples = output.floatChannelData?[0] else { return }
            var pcm = Data(capacity: Int(output.frameLength) * 2)
            var energy: Float = 0
            for index in 0..<Int(output.frameLength) {
                let sample = max(-1, min(1, samples[index]))
                energy += sample * sample
                var value = Int16(sample * 32767).littleEndian
                withUnsafeBytes(of: &value) { pcm.append(contentsOf: $0) }
            }
            onChunk(pcm, sqrt(energy / Float(max(1, output.frameLength))))
        }
        tapping = true
        capture.prepare(); try capture.start()
    }
    func play(pcm: Data) {
        guard pcm.count >= 2, pcm.count % 2 == 0, let buffer = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: AVAudioFrameCount(pcm.count / 2)), let channel = buffer.floatChannelData?[0] else { return }
        buffer.frameLength = buffer.frameCapacity
        pcm.withUnsafeBytes { bytes in
            for index in 0..<pcm.count / 2 {
                let sample = bytes.loadUnaligned(fromByteOffset: index * 2, as: Int16.self)
                channel[index] = Float(Int16(littleEndian: sample)) / 32768
            }
        }
        if !playback.isRunning { try? playback.start() }
        player.scheduleBuffer(buffer)
        if !player.isPlaying { player.play() }
    }
    func interruptPlayback() { player.stop() }
    private func stopCapture() { capture.stop(); if tapping { capture.inputNode.removeTap(onBus: 0); tapping = false } }
    func stop() { stopCapture(); player.stop(); playback.stop() }
}
