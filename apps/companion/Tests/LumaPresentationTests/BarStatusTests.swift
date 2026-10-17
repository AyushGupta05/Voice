import XCTest
import LumaPresentation

final class BarStatusTests: XCTestCase {
    private func status(connected: Bool = true, bridge: Bool = true, events: Bool = true,
                        working: Bool = false, voice: Bool = false, running: Int = 0,
                        starting: Bool = false, speaking: Bool = false,
                        ack: String = "", result: String = "", transcript: String = "",
                        voiceReady: Bool = true, reason: String = "", resultIsError: Bool = false) -> BarStatus {
        BarStatus(connected: connected, bridgeConnected: bridge, eventsReady: events,
                  working: working, voiceActive: voice, runningActions: running,
                  voiceStarting: starting, voiceSpeaking: speaking,
                  executingText: ack, resultText: result, liveTranscript: transcript,
                  voiceReady: voiceReady, voiceReason: reason, resultIsError: resultIsError)
    }
    func testOnlyThreePhasesExist() {
        let phases: [BarPhase] = [.idle, .listening, .executing]
        XCTAssertEqual(Set(phases.map { "\($0)" }).count, 3)
    }
    func testIdleAlwaysSaysHeyLumaAndExplainsWhyVoiceCannotStart() {
        XCTAssertEqual(status().phase, .idle)
        XCTAssertEqual(status().title, BarStatus.idleInvitation)
        XCTAssertEqual(status().note, ""); XCTAssertTrue(status().ready)
        XCTAssertEqual(status(connected: false).title, BarStatus.idleInvitation)
        XCTAssertEqual(status(connected: false).note, "Connecting to Penny"); XCTAssertFalse(status(connected: false).ready)
        XCTAssertEqual(status(events: false).note, "Reconnecting"); XCTAssertFalse(status(events: false).ready)
        XCTAssertEqual(status(bridge: false).note, "Open QuPath")
        XCTAssertTrue(status(bridge: false).ready, "Voice can start before QuPath is open")
        XCTAssertEqual(status(voiceReady: false, reason: "Voice needs AWS credits.").note, "Voice needs AWS credits.")
        XCTAssertFalse(status(voiceReady: false).ready)
        XCTAssertFalse(status(voiceReady: false).note.lowercased().contains("setup"))
        XCTAssertEqual(status(transcript: "stale").title, BarStatus.idleInvitation, "A transcript never implies capture")
        XCTAssertEqual(status(ack: "Detecting cells.").phase, .idle, "An acknowledgement without a working task is stale")
    }
    func testListeningShowsThePartialTranscriptAndStartingIsNotCapturing() {
        XCTAssertEqual(status(voice: true).phase, .listening)
        XCTAssertEqual(status(voice: true).title, "Listening"); XCTAssertTrue(status(voice: true).ready)
        let partial = status(voice: true, transcript: "detect cells in the ")
        XCTAssertEqual(partial.title, "detect cells in the"); XCTAssertEqual(partial.note, "")
        XCTAssertEqual(status(starting: true).phase, .listening)
        XCTAssertFalse(status(starting: true).ready); XCTAssertEqual(status(starting: true).note, "Connecting speech")
        XCTAssertEqual(status(connected: false, voice: true).phase, .listening, "A live microphone stays visible while the gateway poll fails")
    }
    func testExecutingShowsTheAcknowledgementAndBeatsAnOldResult() {
        let value = status(working: true, voice: true, speaking: true, ack: "Detecting cells.", result: "Old.")
        XCTAssertEqual(value.phase, .executing); XCTAssertEqual(value.title, "Detecting cells.")
        XCTAssertFalse(value.showsResult); XCTAssertTrue(value.speaking)
        XCTAssertEqual(status(working: true).title, BarStatus.workingTitle)
        XCTAssertFalse(status(working: true).speaking)
        XCTAssertEqual(status(running: 1).phase, .executing)
        XCTAssertEqual(status(running: 1).title, BarStatus.workingTitle)
    }
    func testResultIsASubLookOfExecuting() {
        let value = status(voice: true, result: "Detected 294 cells.")
        XCTAssertEqual(value.phase, .executing); XCTAssertTrue(value.showsResult)
        XCTAssertEqual(value.title, "Detected 294 cells."); XCTAssertFalse(value.speaking)
        XCTAssertTrue(status(speaking: true, result: "Done.").speaking, "The result is read aloud while shown")
        XCTAssertEqual(status(working: true, result: "Done.").showsResult, false, "New work replaces a lingering result")
    }
    func testSpeakingIsASubLookOfExecuting() {
        XCTAssertEqual(status(voice: true, speaking: true).phase, .executing)
        XCTAssertEqual(status(voice: true, speaking: true).title, BarStatus.speakingTitle)
        XCTAssertTrue(status(speaking: true).speaking, "Playback of a text reply is still speech")
        XCTAssertFalse(status(speaking: true).showsResult)
        XCTAssertEqual(status(speaking: true, ack: "Zooming in.").title, "Zooming in.")
    }
    func testResearcherSpeechReplacesAResultOrOngoingWorkImmediately() {
        let value = status(working: true, voice: true, speaking: true, ack: "Detecting cells.",
                           result: "Old result.", transcript: "stop, use the other channel")
        XCTAssertEqual(value.phase, .listening)
        XCTAssertEqual(value.title, "stop, use the other channel")
        XCTAssertTrue(value.ready)
        XCTAssertFalse(value.showsResult)
        XCTAssertEqual(status(working: true, transcript: "stale transcript").phase, .executing,
                       "An inactive microphone never presents stale speech as a new request")
    }
    func testVoiceCanStartDuringResultsAndWorkButNeedsAnActualConnection() {
        XCTAssertTrue(status(result: "Found 67 objects.").canStartVoice)
        XCTAssertTrue(status(working: true).canStartVoice)
        XCTAssertFalse(status(working: true, voiceReady: false).canStartVoice)
        XCTAssertFalse(status(events: false, result: "Old result.").canStartVoice)
        XCTAssertFalse(status(connected: false, voice: true).canStartVoice)
    }
    func testFailedOutcomeGetsAttentionInsteadOfASuccessCheck() {
        let value = status(result: "No checkpoint for this image.", resultIsError: true)
        XCTAssertTrue(value.showsResult)
        XCTAssertTrue(value.showsError)
        XCTAssertFalse(status(working: true, result: "Old failure.", resultIsError: true).showsError)
        XCTAssertFalse(status(result: "Found 67 objects.").showsError)
    }
}
