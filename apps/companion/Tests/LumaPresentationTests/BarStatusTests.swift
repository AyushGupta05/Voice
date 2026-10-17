import XCTest
import LumaPresentation

final class BarStatusTests: XCTestCase {
    private func status(connected: Bool = true, bridge: Bool = true, events: Bool = true,
                        working: Bool = false, voice: Bool = false, approvals: Int = 0, running: Int = 0,
                        starting: Bool = false, speaking: Bool = false,
                        ack: String = "", result: String = "", transcript: String = "",
                        voiceReady: Bool = true, reason: String = "") -> BarStatus {
        BarStatus(connected: connected, bridgeConnected: bridge, eventsReady: events,
                  working: working, voiceActive: voice, pendingApprovals: approvals, runningActions: running,
                  voiceStarting: starting, voiceSpeaking: speaking,
                  executingText: ack, resultText: result, liveTranscript: transcript,
                  voiceReady: voiceReady, voiceReason: reason)
    }
    func testPendingReviewSurvivesDisconnectionAndWorking() {
        let value = status(connected: false, working: true, voice: true, approvals: 1)
        XCTAssertEqual(value.title, "Review action"); XCTAssertTrue(value.needsAttention)
        XCTAssertEqual(value.phase, .attention)
    }
    func testIdleInvitesTalkingOnlyWhenVoiceIsReady() {
        XCTAssertEqual(status().phase, .idle)
        XCTAssertEqual(status().title, BarStatus.idleInvitation)
        XCTAssertEqual(status(voiceReady: false, reason: "Voice needs AWS credits.").title, "Voice needs AWS credits.")
        XCTAssertEqual(status(voiceReady: false).title, "Voice setup needed")
        XCTAssertFalse(status(voiceReady: false).needsAttention)
    }
    func testListeningRequiresActiveVoiceAndHealthyConnections() {
        XCTAssertEqual(status(voice: true).phase, .listening)
        XCTAssertEqual(status(voice: true).title, "Listening")
        XCTAssertEqual(status(connected: false, voice: true).title, "Connecting")
        XCTAssertEqual(status(bridge: false, voice: true).title, "Open QuPath")
        XCTAssertEqual(status(events: false, voice: true).title, "Reconnecting")
    }
    func testListeningShowsThePartialTranscript() {
        let value = status(voice: true, transcript: "detect cells in the ")
        XCTAssertEqual(value.phase, .listening); XCTAssertEqual(value.title, "detect cells in the")
        XCTAssertEqual(status(transcript: "stale").title, BarStatus.idleInvitation, "A transcript never implies capture")
    }
    func testExecutingShowsTheAcknowledgementAndBeatsSpeakingAndListening() {
        let value = status(working: true, voice: true, speaking: true, ack: "Detecting cells.", transcript: "detect cells")
        XCTAssertEqual(value.phase, .executing); XCTAssertEqual(value.title, "Detecting cells.")
        XCTAssertFalse(value.needsAttention)
        XCTAssertEqual(status(working: true).title, BarStatus.workingTitle)
        XCTAssertEqual(status(ack: "Detecting cells.").phase, .idle, "An acknowledgement without a working task is stale")
    }
    func testApprovedMCPWorkIsNotReportedAsReady() {
        XCTAssertEqual(status(running: 1).phase, .executing)
        XCTAssertEqual(status(running: 1).title, BarStatus.workingTitle)
    }
    func testResultIsShownAfterWorkAndBeforeReturningToListeningOrIdle() {
        let value = status(voice: true, result: "Detected 294 cells.")
        XCTAssertEqual(value.phase, .result); XCTAssertEqual(value.title, "Detected 294 cells.")
        XCTAssertEqual(status(result: "Done.").phase, .result)
        XCTAssertEqual(status(working: true, result: "Done.").phase, .executing, "New work replaces a lingering result")
        XCTAssertEqual(status(approvals: 1, result: "Done.").phase, .attention)
    }
    func testSpeakingAndStartingAreExplicitWithoutPretendingCapture() {
        XCTAssertEqual(status(starting: true).phase, .starting)
        XCTAssertEqual(status(starting: true).title, "Starting voice")
        XCTAssertEqual(status(voice: true, speaking: true).phase, .speaking)
        XCTAssertEqual(status(speaking: true).phase, .speaking, "Playback of a text reply is still speech")
        XCTAssertEqual(status(voice: true, speaking: true, transcript: "ignored").title, "Speaking")
    }
}
