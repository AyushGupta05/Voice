/// Short, factual status for the floating bar. A pending review always stays visible.
public struct BarStatus: Equatable {
    public let title: String
    public let symbol: String
    public let needsAttention: Bool
    public init(connected: Bool, bridgeConnected: Bool, eventsReady: Bool, working: Bool,
                voiceActive: Bool, pendingApprovals: Int, runningActions: Int = 0, voiceStarting: Bool = false, voiceSpeaking: Bool = false) {
        if pendingApprovals > 0 { self.init("Review action", "hand.raised", true) }
        else if !connected { self.init("Connecting", "link", true) }
        else if !bridgeConnected { self.init("Open QuPath", "viewfinder", true) }
        else if !eventsReady { self.init("Reconnecting", "arrow.triangle.2.circlepath", true) }
        else if working || runningActions > 0 { self.init("Working in QuPath", "sparkle", false) }
        else if voiceStarting { self.init("Starting voice", "waveform", false) }
        else if voiceActive && voiceSpeaking { self.init("Speaking", "waveform", false) }
        else if voiceActive { self.init("Listening", "waveform", false) }
        else { self.init("QuPath ready", "checkmark", false) }
    }
    private init(_ title: String, _ symbol: String, _ attention: Bool) {
        self.title = title; self.symbol = symbol; needsAttention = attention
    }
}
