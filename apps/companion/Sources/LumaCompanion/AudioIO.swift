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
        // AVAudioConverter silently outputs zeros when asked to downmix the multi-channel microphone
        // (9 channels under voice processing, 3 plain) to mono. Take the first channel ourselves and
        // only let the converter change the sample rate.
        guard let monoSource = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: source.sampleRate, channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: monoSource, to: target) else { throw CompanionError.message("Could not configure microphone audio.") }
        var taps = 0
        var gain: Float = 8
        input.installTap(onBus: 0, bufferSize: 2048, format: source) { [target] buffer, _ in
            let capacity = AVAudioFrameCount(Double(buffer.frameLength) * 24000 / source.sampleRate + 64)
            guard let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity),
                  let mono = AVAudioPCMBuffer(pcmFormat: monoSource, frameCapacity: buffer.frameLength),
                  let firstChannel = buffer.floatChannelData?[0], let monoChannel = mono.floatChannelData?[0] else { return }
            mono.frameLength = buffer.frameLength
            monoChannel.update(from: firstChannel, count: Int(buffer.frameLength))
            taps += 1
            if taps % 100 == 1 {
                var peak: Float = 0
                for index in 0..<Int(buffer.frameLength) { peak = max(peak, abs(firstChannel[index])) }
                audioLog.info("Raw microphone buffer \(taps): \(source.channelCount) ch @ \(source.sampleRate) Hz, \(buffer.frameLength) frames, channel-0 peak \(peak)")
            }
            var consumed = false
            var error: NSError?
            converter.convert(to: output, error: &error) { _, status in
                if consumed { status.pointee = .noDataNow; return nil }
                consumed = true; status.pointee = .haveData; return mono
            }
            guard error == nil, let samples = output.floatChannelData?[0] else { return }
            // The voice-processed microphone is very quiet (speech peaks ~0.003–0.05). Bring speech towards -20 dBFS
            // for the transcriber with a slow automatic gain, without pumping silence up to noise.
            let frames = Int(output.frameLength)
            var energy: Float = 0
            for index in 0..<frames { energy += samples[index] * samples[index] }
            let rms = (energy / Float(max(frames, 1))).squareRoot()
            if rms > 0.0015 {
                let wanted = min(40, max(1, 0.07 / rms))
                gain += (wanted - gain) * (wanted < gain ? 0.5 : 0.15)
            }
            for index in 0..<frames { samples[index] = max(-1, min(1, samples[index] * gain)) }
            guard let chunk = try? PCM16.encode(UnsafeBufferPointer(start: samples, count: frames)) else { return }
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
