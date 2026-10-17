import AVFoundation
import Foundation
import LumaAudioCore

/// PCM16 mono at 24 kHz, matching the documented OpenAI voice format.
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
            guard let chunk = try? PCM16.encode(UnsafeBufferPointer(start: samples, count: Int(output.frameLength))) else { return }
            onChunk(chunk.data, chunk.rms)
        }
        tapping = true
        do { capture.prepare(); try capture.start() }
        catch { stopCapture(); throw error }
    }
    func play(pcm: Data) {
        guard let samples = try? PCM16.decode(pcm), let buffer = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: AVAudioFrameCount(samples.count)), let channel = buffer.floatChannelData?[0] else { return }
        buffer.frameLength = buffer.frameCapacity
        samples.withUnsafeBufferPointer { channel.update(from: $0.baseAddress!, count: $0.count) }
        if !playback.isRunning { try? playback.start() }
        player.scheduleBuffer(buffer)
        if !player.isPlaying { player.play() }
    }
    func interruptPlayback() { player.stop() }
    private func stopCapture() { capture.stop(); if tapping { capture.inputNode.removeTap(onBus: 0); tapping = false } }
    func stop() { stopCapture(); player.stop(); playback.stop() }
}
