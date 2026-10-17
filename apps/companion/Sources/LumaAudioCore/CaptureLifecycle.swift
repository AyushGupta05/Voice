import Foundation

public enum CaptureLifecycleError: LocalizedError {
    case microphoneDenied
    public var errorDescription: String? { "Microphone access is needed for voice." }
}

/// Owns capture intent across asynchronous permission requests. No device or provider access.
/// The host still verifies funding and researcher intent before calling start.
@MainActor
public final class CaptureLifecycle {
    private var request: UUID?
    private let stopDevice: () -> Void

    public init(stopDevice: @escaping () -> Void) { self.stopDevice = stopDevice }

    public func start(permission: () async -> Bool, begin: () throws -> Void) async throws {
        stop()
        let current = UUID()
        request = current
        let permitted = await permission()
        // Stop or a newer start owns the device now; an old permission reply cannot reclaim it.
        guard request == current else { throw CancellationError() }
        if Task.isCancelled {
            request = nil
            stopDevice()
            throw CancellationError()
        }
        guard permitted else { request = nil; throw CaptureLifecycleError.microphoneDenied }
        do { try begin() }
        catch {
            request = nil
            stopDevice()
            throw error
        }
    }
    public func stop() {
        request = nil
        stopDevice()
    }
}
