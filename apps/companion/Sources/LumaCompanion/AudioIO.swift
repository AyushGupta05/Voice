import AVFoundation
import Foundation
import os

private let audioLog = Logger(subsystem: "science.luma.companion", category: "audio")
import LumaAudioCore

/// PCM16 mono at 24 kHz at the local speech transport boundary.
/// Production capture is only started after a funded session is accepted.
@MainActor
final class AudioIO {
    private var engine = AVAudioEngine()
    private var player = AVAudioPlayerNode()
    private let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 24000, channels: 1, interleaved: false)!
    private var tapping = false
    /// False when the device rejected voice processing; the caller then mutes the microphone during playback.
    private(set) var echoCancelled = true
    private lazy var captureLifecycle = CaptureLifecycle(stopDevice: { [weak self] in self?.stopCapture() })
    init() { engine.attach(player) }
    func start(onChunk: @escaping (Data, Float) -> Void) async throws {
        try await captureLifecycle.start(permission: { await AVCaptureDevice.requestAccess(for: .audio) }, begin: {
            try self.startCapture(onChunk: onChunk)
        })
    }
    private func startCapture(onChunk: @escaping (Data, Float) -> Void) throws {
        do { try configure(voiceProcessing: true, onChunk: onChunk); echoCancelled = true; audioLog.info("Capture started with voice processing") }
        catch {
            // Some devices refuse the voice-processing aggregate (-10875). Use a fresh plain engine instead.
            stopCapture(); engine = AVAudioEngine(); player = AVAudioPlayerNode(); engine.attach(player)
            try configure(voiceProcessing: false, onChunk: onChunk); echoCancelled = false; audioLog.error("Voice processing refused (\(error.localizedDescription)); capturing without echo cancellation")
        }
    }
    private func configure(voiceProcessing: Bool, onChunk: @escaping (Data, Float) -> Void) throws {
        stopCapture()
        // The output side must exist before voice processing is enabled, otherwise AUVoiceProcessor
        // initialization fails with "client-side input and output formats do not match" (-10875).
        _ = engine.outputNode; _ = engine.mainMixerNode
        let input = engine.inputNode
        if input.isVoiceProcessingEnabled != voiceProcessing { try input.setVoiceProcessingEnabled(voiceProcessing) }
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
        guard engine.isRunning else { audioLog.error("Dropped \(samples.count) reply samples: audio engine not running"); return }
        player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { _ in completion?() }
        if !player.isPlaying { player.play() }
        audioLog.info("Scheduled \(samples.count) reply samples (echoCancelled=\(self.echoCancelled), volume=\(self.player.volume))")
    }
    func interruptPlayback() { player.stop() }
    private func stopCapture() { player.stop(); engine.stop(); if tapping { engine.inputNode.removeTap(onBus: 0); tapping = false } }
    func stop() { captureLifecycle.stop(); player.stop(); engine.stop() }
}
