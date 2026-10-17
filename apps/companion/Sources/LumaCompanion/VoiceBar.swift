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

/// Dynamic-Island style bar: Idle → Listening (waveform + partial transcript) → Executing (ack phrase + shimmer) → Result,
/// with a Speaking look while Luma talks. Phase selection lives in `BarStatus`; this view only renders it.
private struct VoiceBar: View {
    @ObservedObject var model: CompanionModel
    @State private var showingVoiceSetup = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private let mint = Color(red: 0.64, green: 0.92, blue: 0.79)
    private var working: Bool { model.textBusy || model.busy || !model.decidingActionIDs.isEmpty || model.context["analysisRunning"] as? Bool == true }
    private var status: BarStatus {
        BarStatus(connected: model.connected, bridgeConnected: model.bridgeConnected,
                  eventsReady: model.eventsReady, working: working,
                  voiceActive: model.voiceActive, pendingApprovals: model.approvals.count, runningActions: model.runningActionIDs.count,
                  voiceStarting: model.voiceStarting, voiceSpeaking: model.voiceSpeaking,
                  executingText: model.executingText, resultText: model.resultText, liveTranscript: model.liveTranscript,
                  voiceReady: model.voiceReady, voiceReason: model.voiceReason)
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
            trailing(status)
        }
        .padding(.horizontal, 14).frame(width: 420, height: 56)
        .background(Color(red: 0.075, green: 0.085, blue: 0.09))
        .animation(reduceMotion ? .easeInOut(duration: 0.15) : .spring(response: 0.38, dampingFraction: 0.82), value: status.phase)
        .animation(.easeInOut(duration: 0.2), value: status.title)
        .popover(isPresented: $showingVoiceSetup) {
            VStack(alignment: .leading, spacing: 12) {
                Text("Voice setup").font(.headline)
                Text(model.voiceReason).font(.callout).fixedSize(horizontal: false, vertical: true)
                Button("Type a request") { showingVoiceSetup = false; model.expanded = true }
            }.padding(20).frame(width: 300)
        }
    }

    /// Mic glyph when idle, stop control while voice is on, spinner while starting.
    private func micControl(_ status: BarStatus) -> some View {
        Button { tapMic() } label: {
            ZStack {
                Circle().fill(voiceOn ? mint.opacity(0.16) : .white.opacity(0.06)).frame(width: 34, height: 34)
                if status.phase == .starting {
                    ProgressView().controlSize(.small).tint(mint)
                } else if voiceOn {
                    Image(systemName: "stop.fill").font(.system(size: 13, weight: .bold)).foregroundStyle(mint)
                } else {
                    Image(systemName: model.voiceReady ? "mic.fill" : "mic.slash").font(.system(size: 15, weight: .medium)).foregroundStyle(.white.opacity(0.75))
                }
            }
        }.buttonStyle(.plain)
            .accessibilityLabel(voiceOn ? "Stop voice" : model.voiceReady ? "Start voice" : "Voice setup")
            .help(voiceOn ? "Stop microphone" : model.voiceReady ? "Talk to Luma" : model.voiceReason)
    }
    private func tapMic() {
        if voiceOn || model.voiceReady { model.toggleVoice() } else { showingVoiceSetup = true }
    }

    @ViewBuilder private func centre(_ status: BarStatus) -> some View {
        switch status.phase {
        case .idle:
            Button { tapMic() } label: {
                HStack(spacing: 7) {
                    Image(systemName: status.symbol).font(.system(size: 10)).foregroundStyle(model.voiceReady ? mint : .secondary)
                    Text(status.title).font(.system(size: 12, weight: .semibold)).lineLimit(1).truncationMode(.tail)
                }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityLabel(model.voiceReady ? "Start voice" : "Voice setup").accessibilityValue(status.title)
        case .starting:
            label(status.title, icon: nil, secondary: "Connecting speech")
        case .listening:
            HStack(spacing: 10) {
                Waveform(level: model.microphoneLevel, color: mint, animated: !reduceMotion)
                    .frame(width: 44, height: 22)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(status.title)
                        .font(.system(size: 12, weight: model.liveTranscript.isEmpty ? .semibold : .regular))
                        .foregroundStyle(model.liveTranscript.isEmpty ? Color.primary : Color.white.opacity(0.9))
                        .lineLimit(1).truncationMode(.head)
                    if model.liveTranscript.isEmpty {
                        Text("Say what you need").font(.system(size: 10)).foregroundStyle(.secondary)
                    }
                }
            }.contentShape(Rectangle()).onTapGesture { model.toggleDetails() }
                .accessibilityElement(children: .combine).accessibilityLabel("Listening").accessibilityValue(model.liveTranscript)
        case .executing:
            HStack(spacing: 10) {
                ProgressView().controlSize(.small).tint(mint).accessibilityHidden(true)
                ShimmerText(text: status.title, animated: !reduceMotion)
            }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle()).onTapGesture { model.toggleDetails() }
                .accessibilityElement(children: .combine).accessibilityLabel("Working").accessibilityValue(status.title)
        case .speaking:
            HStack(spacing: 10) {
                SpeakingDots(color: mint, animated: !reduceMotion).frame(width: 30, height: 12).accessibilityHidden(true)
                Text(status.title).font(.system(size: 12, weight: .semibold))
            }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle()).onTapGesture { model.toggleDetails() }
                .accessibilityElement(children: .combine).accessibilityLabel("Speaking")
        case .result:
            HStack(spacing: 8) {
                Image(systemName: "checkmark.circle.fill").font(.system(size: 13)).foregroundStyle(mint)
                Text(status.title).font(.system(size: 12, weight: .medium)).lineLimit(2).truncationMode(.tail).fixedSize(horizontal: false, vertical: true)
            }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle()).onTapGesture { model.toggleDetails() }
                .accessibilityElement(children: .combine).accessibilityLabel("Result").accessibilityValue(status.title)
        case .attention:
            Button { model.toggleDetails() } label: {
                label(status.title, icon: status.symbol, secondary: voiceOn ? "Voice on" : "Open details")
            }.buttonStyle(.plain).accessibilityLabel("Open Luma conversation").accessibilityValue(status.title)
        }
    }
    private func label(_ title: String, icon: String?, secondary: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                if let icon { Image(systemName: icon).font(.system(size: 10)).foregroundStyle(.orange) }
                Text(title).font(.system(size: 12, weight: .semibold)).lineLimit(1)
            }
            Text(secondary).font(.system(size: 10)).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
    }

    @ViewBuilder private func trailing(_ status: BarStatus) -> some View {
        if model.textBusy {
            Button { model.stopTextTask() } label: { Image(systemName: "stop.fill").font(.system(size: 12)).frame(width: 24, height: 30) }
                .buttonStyle(.plain).accessibilityLabel("Stop task").help("Stop unstarted work; completed actions remain")
        }
        Button { model.toggleDetails() } label: { Image(systemName: model.expanded ? "chevron.up" : "chevron.down").font(.system(size: 11, weight: .semibold)).frame(width: 24, height: 30) }
            .buttonStyle(.plain).accessibilityLabel(model.expanded ? "Collapse Luma" : "Expand Luma")
    }
}

/// Live waveform: bar heights follow a smoothed microphone level with a gentle per-bar phase so it breathes at 60 fps.
/// With reduce-motion the bars still scale with the level but do not drift.
struct Waveform: View {
    var level: Float
    var color: Color
    var animated: Bool
    var bars = 9
    @State private var smoothed: Double = 0
    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 60.0, paused: !animated)) { timeline in
            let time = timeline.date.timeIntervalSinceReferenceDate
            Canvas { context, size in
                let width = size.width / CGFloat(bars)
                let barWidth = max(2, width * 0.55)
                for index in 0..<bars {
                    let centre = Double(bars - 1) / 2
                    let distance = abs(Double(index) - centre) / max(1, centre)
                    let envelope = 1 - 0.55 * distance
                    let wobble = animated ? 0.5 + 0.5 * sin(time * (5.5 + Double(index) * 0.7) + Double(index) * 1.3) : 0.6
                    let amplitude = 0.08 + smoothed * envelope * (0.55 + 0.45 * wobble)
                    let height = max(3, size.height * min(1, amplitude))
                    let rect = CGRect(x: CGFloat(index) * width + (width - barWidth) / 2, y: (size.height - height) / 2, width: barWidth, height: height)
                    context.fill(Path(roundedRect: rect, cornerRadius: barWidth / 2), with: .color(color))
                }
            }
        }
        .onChange(of: level) { _, new in
            // Fast attack, slower release: speech peaks register at once and decay smoothly.
            let gain = min(1, Double(new) * 6)
            smoothed = gain > smoothed ? smoothed + (gain - smoothed) * 0.6 : smoothed + (gain - smoothed) * 0.25
        }
        .onAppear { smoothed = min(1, Double(level) * 6) }
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

/// Three dots that pulse in sequence while Luma speaks.
struct SpeakingDots: View {
    var color: Color
    var animated: Bool
    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: !animated)) { timeline in
            let time = timeline.date.timeIntervalSinceReferenceDate
            HStack(spacing: 4) {
                ForEach(0..<3, id: \.self) { index in
                    let pulse = animated ? 0.5 + 0.5 * sin(time * 6 - Double(index) * 0.9) : 0.7
                    Circle().fill(color.opacity(0.45 + 0.55 * pulse)).frame(width: 6, height: 6).scaleEffect(0.8 + 0.4 * pulse)
                }
            }
        }
    }
}
