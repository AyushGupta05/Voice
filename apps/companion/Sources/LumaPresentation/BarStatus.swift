/// The visual phase of the floating bar. Exactly three phases exist; everything else is a sub-look of one of them.
public enum BarPhase: Equatable {
    /// Nothing is happening; invite the researcher to talk ("Hey Luma").
    case idle
    /// Voice is on (or starting); show the waveform and the live partial transcript.
    case listening
    /// Luma is doing something, has just finished (result with a check mark) or is talking back (dots).
    case executing
}

/// Short, factual status for the floating bar, derived purely from model state.
///
/// Precedence: executing (work or running actions) > result > speaking > listening/starting > idle.
/// Connection and voice-funding problems never get their own phase: they show as the idle pill's note.
public struct BarStatus: Equatable {
    public let phase: BarPhase
    /// Main line: invitation, partial transcript, acknowledgement phrase or result sentence.
    public let title: String
    /// Idle: why voice cannot start right now (empty when ready). Listening: "Say what you need" style hint.
    public let note: String
    /// Idle: voice can start on tap. Listening: the microphone is capturing (false while starting).
    public let ready: Bool
    /// Executing: the title is a finished result (check mark) rather than work in progress (spinner).
    public let showsResult: Bool
    /// Executing: Luma is talking; show subtle dots next to the title.
    public let speaking: Bool
    public static let idleInvitation = "Hey Luma"
    public static let workingTitle = "Working in QuPath"
    public static let speakingTitle = "Speaking"

    public init(connected: Bool, bridgeConnected: Bool, eventsReady: Bool, working: Bool,
                voiceActive: Bool, runningActions: Int = 0,
                voiceStarting: Bool = false, voiceSpeaking: Bool = false,
                executingText: String = "", resultText: String = "", liveTranscript: String = "",
                voiceReady: Bool = true, voiceReason: String = "", appName: String = "QuPath") {
        let ack = executingText.trimmingCharacters(in: .whitespacesAndNewlines)
        let result = resultText.trimmingCharacters(in: .whitespacesAndNewlines)
        let transcript = liveTranscript.trimmingCharacters(in: .whitespacesAndNewlines)
        if working || runningActions > 0 {
            self.init(.executing, ack.isEmpty ? "Working in \(appName)" : ack, speaking: voiceSpeaking)
        } else if !result.isEmpty {
            self.init(.executing, result, showsResult: true, speaking: voiceSpeaking)
        } else if voiceSpeaking {
            self.init(.executing, ack.isEmpty ? Self.speakingTitle : ack, speaking: true)
        } else if voiceActive || voiceStarting {
            let capturing = voiceActive && !voiceStarting
            self.init(.listening, transcript.isEmpty ? "Listening" : transcript,
                      note: transcript.isEmpty ? (capturing ? "Say what you need" : "Connecting speech") : "", ready: capturing)
        } else {
            let note = !connected ? "Connecting to Luma" : !eventsReady ? "Reconnecting"
                : !voiceReady ? (voiceReason.isEmpty ? "Voice is unavailable" : voiceReason)
                : !bridgeConnected ? "Open \(appName)" : ""
            self.init(.idle, Self.idleInvitation, note: note, ready: connected && eventsReady && voiceReady)
        }
    }
    private init(_ phase: BarPhase, _ title: String, note: String = "", ready: Bool = true,
                 showsResult: Bool = false, speaking: Bool = false) {
        self.phase = phase; self.title = title; self.note = note; self.ready = ready
        self.showsResult = showsResult; self.speaking = speaking
    }
}
