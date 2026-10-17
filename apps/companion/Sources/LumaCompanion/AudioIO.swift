import AVFoundation
import Foundation
import LumaAudioCore

/// PCM16 mono at 24 kHz at the local speech transport boundary.
/// Production capture is only started after a funded session is accepted.
@MainActor
final class AudioIO {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 24000, channels: 1, interleaved: false)!
    private var tapping = false
    private lazy var captureLifecycle = CaptureLifecycle(stopDevice: { [weak self] in self?.stopCapture() })
    init() {
        engine.attach(player)
    }
    func start(onChunk: @escaping (Data, Float) -> Void) async throws {
        try await captureLifecycle.start(permission: { await AVCaptureDevice.requestAccess(for: .audio) }, begin: {
            try self.startCapture(onChunk: onChunk)
        })
    }
    private func startCapture(onChunk: @escaping (Data, Float) -> Void) throws {
        stopCapture()
        let input = engine.inputNode
        // Capture and assistant playback share the voice-processing engine's echo reference.
        try input.setVoiceProcessingEnabled(true)
        engine.connect(player, to: engine.mainMixerNode, format: target)
        let source = input.outputFormat(forBus: 0)
        guard source.sampleRate > 0, source.channelCount > 0 else { throw CompanionError.message("No microphone input is available.") }
        guard let converter = AVAudioConverter(from: source, to: target) else { throw CompanionError.message("Could not configure microphone audio.") }
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
        do { engine.prepare(); try engine.start() }
        catch { stopCapture(); throw error }
    }
    func play(pcm: Data, completion: (() -> Void)? = nil) {
        guard let samples = try? PCM16.decode(pcm), let buffer = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: AVAudioFrameCount(samples.count)), let channel = buffer.floatChannelData?[0] else { return }
        buffer.frameLength = buffer.frameCapacity
        samples.withUnsafeBufferPointer { channel.update(from: $0.baseAddress!, count: $0.count) }
        guard engine.isRunning else { return }
        player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { _ in completion?() }
        if !player.isPlaying { player.play() }
    }
    func interruptPlayback() { player.stop() }
    private func stopCapture() { player.stop(); engine.stop(); if tapping { engine.inputNode.removeTap(onBus: 0); tapping = false } }
    func stop() { captureLifecycle.stop(); player.stop(); engine.stop() }
}
