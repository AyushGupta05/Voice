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

private struct VoiceBar: View {
    @ObservedObject var model: CompanionModel
    @State private var showingVoiceSetup = false
    private let mint = Color(red: 0.64, green: 0.92, blue: 0.79)
    private var working: Bool { model.textBusy || model.busy || !model.decidingActionIDs.isEmpty || model.context["analysisRunning"] as? Bool == true }
    private var status: BarStatus {
        BarStatus(connected: model.connected, bridgeConnected: model.bridgeConnected,
                  eventsReady: model.eventsReady, working: working,
                  voiceActive: model.voiceActive, pendingApprovals: model.approvals.count, runningActions: model.runningActionIDs.count, voiceStarting: model.voiceStarting, voiceSpeaking: model.voiceSpeaking)
    }
    var body: some View {
        HStack(spacing: 12) {
            Button {
                if model.voiceActive || model.voiceStarting || model.voiceReady { model.toggleVoice() }
                else { showingVoiceSetup = true }
            } label: {
                Image(systemName: model.voiceActive ? "waveform" : "mic.slash")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(model.voiceActive ? mint : .white.opacity(0.6))
                    .frame(width: 34, height: 34)
                    .background(.white.opacity(0.06), in: Circle())
            }.buttonStyle(.plain)
                .accessibilityLabel(model.voiceActive || model.voiceStarting ? "Stop voice" : model.voiceReady ? "Start voice" : "Voice setup")
                .help(model.voiceActive || model.voiceStarting ? "Stop microphone" : model.voiceReason)
            Button { model.toggleDetails() } label: {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        if working || !model.runningActionIDs.isEmpty { ProgressView().controlSize(.mini) }
                        else { Image(systemName: status.symbol).font(.system(size: 10)).foregroundStyle(status.needsAttention ? .orange : mint) }
                        Text(status.title).font(.system(size: 12, weight: .semibold))
                    }
                    Text(model.voiceActive ? "Voice on" : model.voiceStarting ? "Connecting speech" : "Voice off · Type to ask")
                        .font(.system(size: 10)).foregroundStyle(.secondary)
                }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityLabel("Open Luma conversation")
                .accessibilityValue(status.title + (model.voiceActive ? ", Voice on" : ", Voice off"))
            if model.textBusy {
                Button { model.stopTextTask() } label: { Image(systemName: "stop.fill").font(.system(size: 12)) }
                    .buttonStyle(.plain).accessibilityLabel("Stop task")
            }
            Button { model.toggleDetails() } label: { Image(systemName: model.expanded ? "chevron.up" : "chevron.down").font(.system(size: 11, weight: .semibold)).frame(width: 24, height: 30) }
                .buttonStyle(.plain).accessibilityLabel(model.expanded ? "Collapse Luma" : "Expand Luma")
        }
        .padding(.horizontal, 14).frame(width: 420, height: 56)
        .background(Color(red: 0.075, green: 0.085, blue: 0.09))
        .popover(isPresented: $showingVoiceSetup) {
            VStack(alignment: .leading, spacing: 12) {
                Text("Voice setup").font(.headline)
                Text(model.voiceReason).font(.callout).fixedSize(horizontal: false, vertical: true)
                Button("Type a request") { showingVoiceSetup = false; model.expanded = true }
            }.padding(20).frame(width: 300)
        }
    }
}
