import XCTest
@testable import LumaAudioCore

final class CaptureLifecycleTests: XCTestCase {
    @MainActor
    func testStopDuringPermissionPreventsLateCapture() async {
        var permission: CheckedContinuation<Bool, Never>?
        var started = 0
        var stopped = 0
        let lifecycle = CaptureLifecycle { stopped += 1 }
        let task = Task { try await lifecycle.start(permission: {
            await withCheckedContinuation { permission = $0 }
        }, begin: { started += 1 }) }
        while permission == nil { await Task.yield() }
        lifecycle.stop()
        permission?.resume(returning: true)
        do { try await task.value; XCTFail("Stopped capture must not start") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertEqual(started, 0)
        XCTAssertEqual(stopped, 2)
    }
    @MainActor
    func testNewRequestOwnsCaptureWhenOlderPermissionArrives() async throws {
        var permission: CheckedContinuation<Bool, Never>?
        var running = false
        var starts: [String] = []
        let lifecycle = CaptureLifecycle { running = false }
        let older = Task { try await lifecycle.start(permission: {
            await withCheckedContinuation { permission = $0 }
        }, begin: { starts.append("old"); running = true }) }
        while permission == nil { await Task.yield() }
        try await lifecycle.start(permission: { true }, begin: { starts.append("new"); running = true })
        permission?.resume(returning: true)
        do { try await older.value; XCTFail("Superseded permission must not reclaim capture") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertEqual(starts, ["new"])
        XCTAssertTrue(running, "Old cleanup must not stop the newer device owner")
        lifecycle.stop()
        XCTAssertFalse(running)
    }
    @MainActor
    func testCancelledPermissionWaitNeverStartsDevice() async {
        var permission: CheckedContinuation<Bool, Never>?
        var starts = 0
        var stops = 0
        let lifecycle = CaptureLifecycle { stops += 1 }
        let task = Task { try await lifecycle.start(permission: {
            await withCheckedContinuation { permission = $0 }
        }, begin: { starts += 1 }) }
        while permission == nil { await Task.yield() }
        task.cancel()
        permission?.resume(returning: true)
        do { try await task.value; XCTFail("Cancelled capture must not start") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertEqual(starts, 0)
        XCTAssertEqual(stops, 2)
    }
    @MainActor
    func testDeniedPermissionAndFailedDeviceDoNotLeaveCaptureRunning() async throws {
        enum DeviceFailure: Error { case unavailable }
        var running = false
        var starts = 0
        let lifecycle = CaptureLifecycle { running = false }
        do {
            try await lifecycle.start(permission: { false }, begin: { starts += 1; running = true })
            XCTFail("Denied permission must fail")
        } catch { XCTAssertTrue(error is CaptureLifecycleError) }
        XCTAssertEqual(starts, 0)
        do {
            try await lifecycle.start(permission: { true }, begin: { starts += 1; running = true; throw DeviceFailure.unavailable })
            XCTFail("Device failure must propagate")
        } catch { XCTAssertTrue(error is DeviceFailure) }
        XCTAssertFalse(running)
        try await lifecycle.start(permission: { true }, begin: { starts += 1; running = true })
        XCTAssertTrue(running)
        XCTAssertEqual(starts, 2)
        lifecycle.stop()
        XCTAssertFalse(running)
    }
}
