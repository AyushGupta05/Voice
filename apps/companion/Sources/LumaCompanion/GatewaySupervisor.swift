import Foundation

/// Owns only the gateway this companion starts; externally launched development gateways are reused.
@MainActor
final class GatewaySupervisor {
    private let root: URL
    private var process: Process?
    private var output: FileHandle?
    private var nextAttempt = Date.distantPast

    init(root: URL) { self.root = root }
    func ensureRunning() throws {
        guard process?.isRunning != true, Date() >= nextAttempt else { return }
        nextAttempt = Date().addingTimeInterval(5)
        guard let node = Bundle.main.object(forInfoDictionaryKey: "LumaNode") as? String,
              FileManager.default.isExecutableFile(atPath: node) else {
            throw CompanionError.message("Rebuild Penny to configure the local Node.js executable.")
        }
        try FileManager.default.createDirectory(at: root.appendingPathComponent(".local"), withIntermediateDirectories: true)
        let log = root.appendingPathComponent(".local/gateway.log")
        if !FileManager.default.fileExists(atPath: log.path) { FileManager.default.createFile(atPath: log.path, contents: nil) }
        try? output?.close()
        let handle = try FileHandle(forWritingTo: log)
        try handle.seekToEnd()
        let child = Process()
        child.executableURL = URL(fileURLWithPath: node)
        child.arguments = ["--import", "tsx", root.appendingPathComponent("packages/gateway/src/server.ts").path]
        child.currentDirectoryURL = root
        var environment = ProcessInfo.processInfo.environment
        environment["LUMA_ROOT"] = root.path
        // `open -n` drops the shell environment, so the active application comes from the launcher's state file.
        if environment["LUMA_APP"] == nil,
           let app = try? String(contentsOf: root.appendingPathComponent(".local/state/app"), encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines),
           !app.isEmpty { environment["LUMA_APP"] = app }
        child.environment = environment
        child.standardOutput = handle; child.standardError = handle
        do { try child.run() }
        catch { try? handle.close(); throw error }
        process = child; output = handle
    }
    func stop() {
        if let process, process.isRunning { process.terminate() }
        process = nil
        try? output?.close(); output = nil
    }
}
