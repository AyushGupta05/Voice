import SwiftUI
import LumaPresentation

struct CompanionSurface: View {
    @ObservedObject var model: CompanionModel
    var body: some View {
        VStack(spacing: 0) {
            VoiceBar(model: model)
            if model.expanded { CompanionView(model: model, height: model.detailsHeight) }
        }
        .frame(width: 420)
        .background(.ultraThinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: model.expanded ? 24 : 28))
        .overlay(RoundedRectangle(cornerRadius: model.expanded ? 24 : 28).stroke(.white.opacity(0.12)))
        .environment(\.colorScheme, .dark)
    }
}

/// Dynamic-Island style bar with exactly three looks: Idle ("Hey Penny", mic) → Listening (waveform + partial transcript)
/// → Executing (acknowledgement phrase + spinner, then the result sentence with a check; dots while Penny talks).
/// Phase selection lives in `BarStatus`; this view only renders it.
private struct VoiceBar: View {
    @ObservedObject var model: CompanionModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private let mint = Color(red: 0.64, green: 0.92, blue: 0.79)
    private var working: Bool { model.textBusy || model.busy || model.context["analysisRunning"] as? Bool == true }
    private var status: BarStatus {
        BarStatus(connected: model.connected, bridgeConnected: model.bridgeConnected,
                  eventsReady: model.eventsReady, working: working,
                  voiceActive: model.voiceActive, runningActions: model.runningActionIDs.count,
                  voiceStarting: model.voiceStarting, voiceSpeaking: model.voiceSpeaking,
                  executingText: model.executingText, resultText: model.resultText, liveTranscript: model.liveTranscript,
                  voiceReady: model.voiceReady, voiceReason: model.voiceReason, appName: model.appName)
    }
    private var voiceOn: Bool { model.voiceActive || model.voiceStarting }
    private var transition: AnyTransition { reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .bottom)).combined(with: .scale(scale: 0.96)) }

    var body: some View {
        let status = status
        HStack(spacing: 12) {
            micControl(status)
            centre(status)
                .frame(maxWidth: .infinity, alignment: .leading)
                .id(status.phase)
                .transition(transition)
            trailing
        }
        .padding(.horizontal, 14).frame(width: 420, height: 56)
        .background(Color(red: 0.075, green: 0.085, blue: 0.09))
        .animation(reduceMotion ? .easeInOut(duration: 0.15) : .spring(response: 0.38, dampingFraction: 0.82), value: status.phase)
        .animation(.easeInOut(duration: 0.2), value: status.title)
        .animation(.easeInOut(duration: 0.2), value: status.showsResult)
    }

    /// Mic glyph when idle, stop control while voice is on, spinner while the speech connection is starting.
    private func micControl(_ status: BarStatus) -> some View {
        Button { model.toggleVoice() } label: {
            ZStack {
                Circle().fill(voiceOn ? mint.opacity(0.16) : .white.opacity(0.06)).frame(width: 34, height: 34)
                if status.phase == .listening && !status.ready {
                    ProgressView().controlSize(.small).tint(mint)
                } else if voiceOn {
                    Image(systemName: "stop.fill").font(.system(size: 13, weight: .bold)).foregroundStyle(mint)
                } else {
                    Image(systemName: status.phase == .idle && !status.ready ? "mic.slash" : "mic.fill")
                        .font(.system(size: 15, weight: .medium)).foregroundStyle(.white.opacity(0.75))
                }
            }
        }.buttonStyle(.plain)
            .disabled(!voiceOn && !(status.phase == .idle && status.ready))
            .accessibilityLabel(voiceOn ? "Stop voice" : "Start voice")
            .help(voiceOn ? "Stop microphone" : status.phase == .idle && !status.ready ? status.note : "Talk to Penny")
    }

    @ViewBuilder private func centre(_ status: BarStatus) -> some View {
        switch status.phase {
        case .idle:
            // The whole pill starts voice; when voice cannot start the reason sits under the invitation.
            Button { model.toggleVoice() } label: {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 7) {
                        Image(systemName: "mic.fill").font(.system(size: 10)).foregroundStyle(status.ready ? mint : .secondary)
                        Text(status.title).font(.system(size: 12, weight: .semibold)).lineLimit(1)
                    }
                    if !status.note.isEmpty {
                        Text(status.note).font(.system(size: 10)).foregroundStyle(.secondary).lineLimit(1).truncationMode(.tail)
                    }
                }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
            }.buttonStyle(.plain).disabled(!status.ready)
                .accessibilityLabel("Start voice").accessibilityValue(status.note.isEmpty ? status.title : status.note)
        case .listening:
            // Same spot stops voice while listening.
            Button { model.toggleVoice() } label: {
                HStack(spacing: 10) {
                    Waveform(level: status.ready ? model.microphoneLevel : 0, color: mint, animated: !reduceMotion)
                        .frame(width: 92, height: 22)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(status.title)
                            .font(.system(size: 12, weight: model.liveTranscript.isEmpty ? .semibold : .regular))
                            .foregroundStyle(model.liveTranscript.isEmpty ? Color.primary : Color.white.opacity(0.9))
                            .lineLimit(1).truncationMode(.head)
                        if !status.note.isEmpty {
                            Text(status.note).font(.system(size: 10)).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }
                }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
            }.buttonStyle(.plain)
                .accessibilityLabel("Stop voice").accessibilityValue(model.liveTranscript.isEmpty ? "Listening" : model.liveTranscript)
        case .executing:
            HStack(spacing: 10) {
                if status.showsResult {
                    Image(systemName: "checkmark.circle.fill").font(.system(size: 13)).foregroundStyle(mint)
                        .transition(.scale.combined(with: .opacity)).accessibilityHidden(true)
                } else {
                    ProgressView().controlSize(.small).tint(mint).accessibilityHidden(true)
                }
                if status.showsResult {
                    Text(status.title).font(.system(size: 12, weight: .medium)).lineLimit(2).truncationMode(.tail)
                        .fixedSize(horizontal: false, vertical: true)
                } else {
                    ShimmerText(text: status.title, animated: !reduceMotion)
                }
                if status.speaking {
                    SpeakingDots(color: mint, animated: !reduceMotion).frame(width: 22, height: 10).accessibilityHidden(true)
                }
            }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle()).onTapGesture { model.toggleDetails() }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(status.showsResult ? "Done" : status.speaking ? "Speaking" : "Working")
                .accessibilityValue(status.title)
        }
    }

    @ViewBuilder private var trailing: some View {
        if model.textBusy {
            Button { model.stopTextTask() } label: { Image(systemName: "stop.fill").font(.system(size: 12)).frame(width: 24, height: 30) }
                .buttonStyle(.plain).accessibilityLabel("Stop task").help("Stop unstarted work; completed actions remain")
        }
        Button { model.toggleDetails() } label: { Image(systemName: model.expanded ? "chevron.up" : "chevron.down").font(.system(size: 11, weight: .semibold)).frame(width: 24, height: 30) }
            .buttonStyle(.plain).accessibilityLabel(model.expanded ? "Collapse Penny" : "Expand Penny")
    }
}

/// Live waveform: 28 bars whose heights follow a log-scaled microphone level with fast attack and slow release
/// (`WaveformLevel`), redrawn each frame so speech visibly animates and silence settles to a thin baseline.
/// With reduce-motion the bars still scale with the level but do not drift or breathe.
struct Waveform: View {
    var level: Float
    var color: Color
    var animated: Bool
    /// Gain the bars were showing when the level last changed, and when; the displayed gain approaches
    /// the target from there so decay continues smoothly between level updates.
    @State private var anchor: (gain: Double, time: TimeInterval) = (0, Date().timeIntervalSinceReferenceDate)
    @State private var target: Double = 0
    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 60.0, paused: !animated && target == anchor.gain)) { timeline in
            let now = timeline.date.timeIntervalSinceReferenceDate
            let gain = WaveformLevel.approach(from: anchor.gain, to: target, elapsed: now - anchor.time)
            Canvas { context, size in
                let heights = WaveformLevel.heights(gain: gain, time: now, animated: animated)
                let slot = size.width / CGFloat(heights.count)
                let barWidth = max(1.5, slot * 0.6)
                for (index, fraction) in heights.enumerated() {
                    let height = max(2, size.height * CGFloat(fraction))
                    let rect = CGRect(x: CGFloat(index) * slot + (slot - barWidth) / 2, y: (size.height - height) / 2, width: barWidth, height: height)
                    context.fill(Path(roundedRect: rect, cornerRadius: barWidth / 2), with: .color(color))
                }
            }
        }
        .onChange(of: level) { _, new in retarget(new) }
        .onAppear { retarget(level, immediate: true) }
    }
    private func retarget(_ level: Float, immediate: Bool = false) {
        let now = Date().timeIntervalSinceReferenceDate
        let shown = immediate ? WaveformLevel.gain(level) : WaveformLevel.approach(from: anchor.gain, to: target, elapsed: now - anchor.time)
        target = WaveformLevel.gain(level)
        // Snap once converged so the timeline can pause under reduce-motion instead of redrawing a static picture.
        anchor = (abs(shown - target) < 0.005 ? target : shown, now)
    }
}

/// The acknowledgement phrase with a soft highlight sweeping across it while the action runs.
struct ShimmerText: View {
    var text: String
    var animated: Bool
    @State private var phase: CGFloat = -1
    var body: some View {
        Text(text).font(.system(size: 12, weight: .semibold)).lineLimit(1).truncationMode(.tail)
            .foregroundStyle(.white.opacity(0.85))
            .overlay {
                if animated {
                    GeometryReader { proxy in
                        LinearGradient(colors: [.clear, .white.opacity(0.9), .clear], startPoint: .leading, endPoint: .trailing)
                            .frame(width: proxy.size.width * 0.6)
                            .offset(x: phase * proxy.size.width)
                    }
                    .mask(Text(text).font(.system(size: 12, weight: .semibold)).lineLimit(1).truncationMode(.tail))
                    .allowsHitTesting(false)
                }
            }
            .onAppear {
                guard animated else { return }
                phase = -0.6
                withAnimation(.linear(duration: 1.4).repeatForever(autoreverses: false)) { phase = 1 }
            }
    }
}

/// Three small dots that pulse in sequence while Penny speaks.
struct SpeakingDots: View {
    var color: Color
    var animated: Bool
    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: !animated)) { timeline in
            let time = timeline.date.timeIntervalSinceReferenceDate
            HStack(spacing: 3) {
                ForEach(0..<3, id: \.self) { index in
                    let pulse = animated ? 0.5 + 0.5 * sin(time * 6 - Double(index) * 0.9) : 0.7
                    Circle().fill(color.opacity(0.4 + 0.6 * pulse)).frame(width: 5, height: 5).scaleEffect(0.8 + 0.4 * pulse)
                }
            }
        }
    }
}
