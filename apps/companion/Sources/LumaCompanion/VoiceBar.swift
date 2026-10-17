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
        .modifier(PennySurfaceChrome(cornerRadius: model.expanded ? 24 : 28))
    }
}

/// Penny's light floating surface: white fill, PennyPalette.border border, light appearance whatever the system setting.
/// The window shadow comes from the panel itself (`hasShadow`).
struct PennySurfaceChrome: ViewModifier {
    var cornerRadius: CGFloat
    func body(content: Content) -> some View {
        // One shape for fill, clip and border; the border sits inside the edge so no sliver shows outside the fill.
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .circular)
        content
            .background(PennyPalette.surface, in: shape)
            .clipShape(shape)
            .overlay(shape.strokeBorder(PennyPalette.border, lineWidth: 1))
            .foregroundStyle(PennyPalette.textPrimary)
            .environment(\.colorScheme, .light)
    }
}

/// Binds the bar to the model: derives `BarStatus`, forwards the phase and microphone level to the companion cursor,
/// and routes the controls back to the model. Rendering lives in `VoiceBarContent`.
private struct VoiceBar: View {
    @ObservedObject var model: CompanionModel
    private var working: Bool { model.textBusy || model.busy || model.context["analysisRunning"] as? Bool == true }
    private var status: BarStatus {
        BarStatus(connected: model.connected, bridgeConnected: model.bridgeConnected,
                  eventsReady: model.eventsReady, working: working,
                  voiceActive: model.voiceActive, runningActions: model.runningActionIDs.count,
                  voiceStarting: model.voiceStarting, voiceSpeaking: model.voiceSpeaking,
                  executingText: model.executingText, resultText: model.resultText, liveTranscript: model.liveTranscript,
                  voiceReady: model.voiceReady, voiceReason: model.voiceReason, appName: model.appName,
                  resultIsError: model.resultIsError)
    }

    var body: some View {
        let status = status
        VoiceBarContent(status: status, level: model.microphoneLevel, voiceOn: model.voiceActive || model.voiceStarting,
                        liveTranscript: model.liveTranscript, voiceReason: model.voiceReason,
                        canStopTask: model.canStopTask, expanded: model.expanded,
                        toggleVoice: { model.toggleVoice() }, toggleDetails: { model.toggleDetails() }, stopTask: { model.stopTextTask() })
        // The companion cursor shows the same phase: listening ring, thinking orbit, speaking ripple.
        .onChange(of: status, initial: true) { _, now in model.pointer.setMood(CursorMood(now)) }
        .onChange(of: model.microphoneLevel) { _, level in model.pointer.setLevel(level) }
    }
}

/// Dynamic-Island style bar with exactly three looks: Idle ("Hey Penny", mic) → Listening (waveform + partial transcript)
/// → Executing (acknowledgement phrase + spinner, then the result sentence with a check; dots while Penny talks).
/// Phase selection lives in `BarStatus`; this view only renders it from plain values, so previews need no live model.
struct VoiceBarContent: View {
    let status: BarStatus
    var level: Float = 0
    var voiceOn = false
    var liveTranscript = ""
    var voiceReason = ""
    var canStopTask = false
    var expanded = false
    var toggleVoice: () -> Void = {}
    var toggleDetails: () -> Void = {}
    var stopTask: () -> Void = {}
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private var transition: AnyTransition { reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .bottom)).combined(with: .scale(scale: 0.96)) }

    var body: some View {
        HStack(spacing: 12) {
            micControl
            centre
                .frame(maxWidth: .infinity, alignment: .leading)
                .id(status.phase)
                .transition(transition)
            trailing
        }
        .padding(.horizontal, 14).frame(width: 420, height: 56)
        .background(PennyPalette.surface)
        .animation(reduceMotion ? .easeInOut(duration: 0.15) : .spring(response: 0.38, dampingFraction: 0.82), value: status.phase)
        .animation(.easeInOut(duration: 0.2), value: status.title)
        .animation(.easeInOut(duration: 0.2), value: status.showsResult)
    }

    /// Mic glyph when idle, stop control while voice is on, spinner while the speech connection is starting.
    private var micControl: some View {
        Button { toggleVoice() } label: {
            ZStack {
                Circle().fill(voiceOn ? PennyPalette.accentSoft : PennyPalette.controlFill).frame(width: 34, height: 34)
                if status.phase == .listening && !status.ready {
                    ProgressView().controlSize(.small).tint(PennyPalette.accent)
                } else if voiceOn {
                    Image(systemName: "stop.fill").font(.system(size: 13, weight: .bold)).foregroundStyle(PennyPalette.accent)
                } else {
                    Image(systemName: status.canStartVoice ? "mic.fill" : "mic.slash")
                        .font(.system(size: 15, weight: .medium)).foregroundStyle(status.canStartVoice ? PennyPalette.textPrimary : PennyPalette.textSecondary)
                }
            }
        }.buttonStyle(.plain)
            .disabled(!voiceOn && !status.canStartVoice)
            .accessibilityLabel(voiceOn ? "Stop voice" : "Start voice")
            .help(voiceOn ? "Stop microphone" : status.canStartVoice ? "Talk to Penny" : voiceReason)
    }

    @ViewBuilder private var centre: some View {
        switch status.phase {
        case .idle:
            // The whole pill starts voice; when voice cannot start the reason sits under the invitation.
            Button { toggleVoice() } label: {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 7) {
                        Image(systemName: "mic.fill").font(.system(size: 10)).foregroundStyle(status.ready ? PennyPalette.accent : PennyPalette.textSecondary)
                        Text(status.title).font(.system(size: 12, weight: .semibold)).foregroundStyle(PennyPalette.textPrimary).lineLimit(1)
                    }
                    if !status.note.isEmpty {
                        Text(status.note).font(.system(size: 10)).foregroundStyle(PennyPalette.textSecondary).lineLimit(1).truncationMode(.tail)
                    }
                }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
            }.buttonStyle(.plain).disabled(!status.ready)
                .accessibilityLabel("Start voice").accessibilityValue(status.note.isEmpty ? status.title : status.note)
        case .listening:
            // Same spot stops voice while listening.
            Button { toggleVoice() } label: {
                HStack(spacing: 10) {
                    Waveform(level: status.ready ? level : 0, color: PennyPalette.accent, animated: !reduceMotion)
                        .frame(width: 92, height: 22)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(status.title)
                            .font(.system(size: 12, weight: liveTranscript.isEmpty ? .semibold : .regular))
                            .foregroundStyle(PennyPalette.textPrimary)
                            .lineLimit(1).truncationMode(.head)
                        if !status.note.isEmpty {
                            Text(status.note).font(.system(size: 10)).foregroundStyle(PennyPalette.textSecondary).lineLimit(1)
                        }
                    }
                }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
            }.buttonStyle(.plain)
                .accessibilityLabel("Stop voice").accessibilityValue(liveTranscript.isEmpty ? "Listening" : liveTranscript)
        case .executing:
            HStack(spacing: 10) {
                if status.showsResult {
                    Image(systemName: status.showsError ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
                        .font(.system(size: 13)).foregroundStyle(status.showsError ? PennyPalette.warning : PennyPalette.accent)
                        .transition(.scale.combined(with: .opacity)).accessibilityHidden(true)
                } else {
                    ProgressView().controlSize(.small).tint(PennyPalette.accent).accessibilityHidden(true)
                }
                if status.showsResult {
                    Text(status.title).font(.system(size: 12, weight: .medium)).foregroundStyle(PennyPalette.textPrimary)
                        .lineLimit(2).truncationMode(.tail)
                        .fixedSize(horizontal: false, vertical: true)
                } else {
                    ShimmerText(text: status.title, animated: !reduceMotion)
                }
                if status.speaking {
                    SpeakingDots(color: PennyPalette.accent, animated: !reduceMotion).frame(width: 22, height: 10).accessibilityHidden(true)
                }
            }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle()).onTapGesture { toggleDetails() }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(status.showsError ? "Needs attention" : status.showsResult ? "Done" : status.speaking ? "Speaking" : "Working")
                .accessibilityValue(status.title)
        }
    }

    @ViewBuilder private var trailing: some View {
        if canStopTask {
            Button { stopTask() } label: { Image(systemName: "stop.fill").font(.system(size: 12)).frame(width: 24, height: 30) }
                .buttonStyle(.plain).foregroundStyle(PennyPalette.textSecondary)
                .accessibilityLabel("Stop task").help("Stop unstarted work; completed actions remain")
        }
        Button { toggleDetails() } label: { Image(systemName: expanded ? "chevron.up" : "chevron.down").font(.system(size: 11, weight: .semibold)).frame(width: 24, height: 30) }
            .buttonStyle(.plain).foregroundStyle(PennyPalette.textSecondary)
            .accessibilityLabel(expanded ? "Collapse Penny" : "Expand Penny")
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
    @State private var anchor: (gain: Double, time: TimeInterval)
    @State private var target: Double
    /// Starts at the current level (as `onAppear` would), so the very first frame already shows it.
    init(level: Float, color: Color, animated: Bool) {
        self.level = level; self.color = color; self.animated = animated
        let gain = WaveformLevel.gain(level)
        _anchor = State(initialValue: (gain, Date().timeIntervalSinceReferenceDate))
        _target = State(initialValue: gain)
    }
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
            .foregroundStyle(PennyPalette.textPrimary.opacity(0.82))
            .overlay {
                if animated {
                    GeometryReader { proxy in
                        LinearGradient(colors: [.clear, PennyPalette.accent.opacity(0.9), .clear], startPoint: .leading, endPoint: .trailing)
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
