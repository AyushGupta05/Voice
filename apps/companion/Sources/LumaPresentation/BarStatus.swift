/// The visual phase of the floating bar. Exactly one phase is shown at a time.
public enum BarPhase: Equatable {
    /// Connection or review problems that need the researcher (review card, gateway, QuPath, events).
    case attention
    /// Nothing is happening; invite the researcher to talk.
    case idle
    /// Voice has been requested but the microphone is not yet capturing.
    case starting
    /// The microphone is live; show the waveform and the partial transcript.
    case listening
    /// Luma is doing something in QuPath; show the acknowledgement phrase with progress.
    case executing
    /// Luma is talking back and nothing is running.
    case speaking
    /// A task just finished; show its final sentence briefly.
    case result
}

/// Short, factual status for the floating bar, derived purely from model state.
///
/// Precedence: review card > connection problems > executing > result > starting > speaking > listening > idle.
/// Executing wins over speaking because the acknowledgement phrase is spoken while the action runs.
public struct BarStatus: Equatable {
    public let phase: BarPhase
    public let title: String
    public let symbol: String
    public let needsAttention: Bool
    public static let idleInvitation = "Hey Luma · tap to talk"
    public static let workingTitle = "Working in QuPath"

    public init(connected: Bool, bridgeConnected: Bool, eventsReady: Bool, working: Bool,
                voiceActive: Bool, pendingApprovals: Int, runningActions: Int = 0,
                voiceStarting: Bool = false, voiceSpeaking: Bool = false,
                executingText: String = "", resultText: String = "", liveTranscript: String = "",
                voiceReady: Bool = true, voiceReason: String = "") {
        let ack = executingText.trimmingCharacters(in: .whitespacesAndNewlines)
        let result = resultText.trimmingCharacters(in: .whitespacesAndNewlines)
        let transcript = liveTranscript.trimmingCharacters(in: .whitespacesAndNewlines)
        if pendingApprovals > 0 { self.init(.attention, "Review action", "hand.raised", true) }
        else if !connected { self.init(.attention, "Connecting", "link", true) }
        else if !bridgeConnected { self.init(.attention, "Open QuPath", "viewfinder", true) }
        else if !eventsReady { self.init(.attention, "Reconnecting", "arrow.triangle.2.circlepath", true) }
        else if working || runningActions > 0 { self.init(.executing, ack.isEmpty ? Self.workingTitle : ack, "sparkle", false) }
        else if !result.isEmpty { self.init(.result, result, "checkmark", false) }
        else if voiceStarting { self.init(.starting, "Starting voice", "waveform", false) }
        else if voiceActive && voiceSpeaking { self.init(.speaking, "Speaking", "waveform", false) }
        else if voiceActive { self.init(.listening, transcript.isEmpty ? "Listening" : transcript, "waveform", false) }
        else if voiceSpeaking { self.init(.speaking, "Speaking", "waveform", false) }
        else if voiceReady { self.init(.idle, Self.idleInvitation, "mic", false) }
        else { self.init(.idle, voiceReason.isEmpty ? "Voice setup needed" : voiceReason, "mic.slash", false) }
    }
    private init(_ phase: BarPhase, _ title: String, _ symbol: String, _ attention: Bool) {
        self.phase = phase; self.title = title; self.symbol = symbol; needsAttention = attention
    }
}
