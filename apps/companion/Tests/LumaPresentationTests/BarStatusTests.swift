import XCTest
import LumaPresentation

final class BarStatusTests: XCTestCase {
    private func status(connected: Bool = true, bridge: Bool = true, events: Bool = true,
                        working: Bool = false, voice: Bool = false, approvals: Int = 0) -> BarStatus {
        BarStatus(connected: connected, bridgeConnected: bridge, eventsReady: events,
                  working: working, voiceActive: voice, pendingApprovals: approvals)
    }
    func testPendingReviewSurvivesDisconnectionAndWorking() {
        let value = status(connected: false, working: true, voice: true, approvals: 1)
        XCTAssertEqual(value.title, "Review action"); XCTAssertTrue(value.needsAttention)
    }
    func testListeningRequiresActiveVoiceAndHealthyConnections() {
        XCTAssertEqual(status().title, "QuPath ready")
        XCTAssertEqual(status(voice: true).title, "Listening")
        XCTAssertEqual(status(connected: false, voice: true).title, "Connecting")
        XCTAssertEqual(status(bridge: false, voice: true).title, "Open QuPath")
        XCTAssertEqual(status(events: false, voice: true).title, "Reconnecting")
    }
    func testTaskStatusDoesNotClaimCompletion() {
        XCTAssertEqual(status(working: true, voice: true).title, "Working in QuPath")
        XCTAssertFalse(status(working: true).needsAttention)
    }
    func testApprovedMCPWorkIsNotReportedAsReady() {
        let value = BarStatus(connected: true, bridgeConnected: true, eventsReady: true,
                              working: false, voiceActive: false, pendingApprovals: 0, runningActions: 1)
        XCTAssertEqual(value.title, "Working in QuPath")
    }
}
