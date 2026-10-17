import AppKit
import SwiftUI
import Foundation

struct Activity: Identifiable {
    let id = UUID()
    let title: String
    let detail: String
    let time = Date()
    var isError = false
    var icon = "checkmark.circle.fill"
}

@MainActor
final class CompanionModel: ObservableObject {
    @Published var connected = false
    @Published var bridgeConnected = false
    @Published var imageName = "No image open"
    @Published var selectionLabel = "Select a region in QuPath"
    @Published var activities: [Activity] = []
    @Published var approvals: [[String: Any]] = []
    @Published var context: [String: Any] = [:]
    @Published var voiceReason = "Checking voice connection…"
    @Published var voiceActive = false
    @Published var textReady = false
    @Published var textBusy = false
    @Published var eventsReady = false
    @Published var textSending = false
    @Published var textReason = "Text needs verified AWS credits."
    @Published var input = ""
    @Published var knowledgeQuery = ""
    @Published var knowledgeResults: [[String: Any]] = []
    @Published var selectedTab = 0
    @Published var busy = false
    @Published var microphoneLevel: Float = 0
    @Published var measurementReport: MeasurementReport?
    private let root: URL
    private var token = ""
    private var gateway: GatewaySupervisor?
    private var socket: URLSessionWebSocketTask?
    private var polling: Task<Void, Never>?
    private var receiving: Task<Void, Never>?
    private var reconnect: Task<Void, Never>?
    private var lastDispatchFailure = ""
    private var stopping = false
    private let session = URLSession(configuration: .ephemeral)
    let pointer = PointerOverlay()
    let audio = AudioIO()

    init() {
        let path = ProcessInfo.processInfo.environment["LUMA_ROOT"]
            ?? Bundle.main.object(forInfoDictionaryKey: "LumaRoot") as? String
            ?? FileManager.default.currentDirectoryPath
        root = URL(fileURLWithPath: path)
        gateway = GatewaySupervisor(root: root)
    }
    func start() {
        stopping = false
        polling?.cancel()
        polling = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refresh()
                try? await Task.sleep(for: .seconds(2))
            }
        }
        connectEvents()
    }
    func stop() {
        stopping = true
        textBusy = false
        eventsReady = false
        polling?.cancel(); receiving?.cancel(); reconnect?.cancel()
        socket?.cancel(with: .goingAway, reason: nil)
        audio.stop()
        gateway?.stop()
    }
    private func loadToken() throws {
        token = try String(contentsOf: root.appendingPathComponent(".local/state/companion-token"), encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    private func request(_ path: String, body: [String: Any]? = nil) async throws -> [String: Any] {
        if token.isEmpty { try loadToken() }
        var request = URLRequest(url: URL(string: "http://127.0.0.1:49555\(path)")!)
        request.timeoutInterval = 130
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let body {
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, response) = try await session.data(for: request)
        guard let result = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw CompanionError.message("Invalid gateway response") }
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw CompanionError.message(result["error"] as? String ?? "Gateway request failed") }
        return result
    }
    func refresh() async {
        do {
            let health = try await request("/health")
            connected = true
            bridgeConnected = health["bridgeError"] == nil
            voiceReason = (health["voice"] as? [String: Any])?["reason"] as? String ?? "Voice funding has not been verified."
            let text = health["text"] as? [String: Any] ?? [:]
            textReady = text["configured"] as? Bool ?? false
            textReason = text["reason"] as? String ?? "Text needs verified AWS credits."
            context = health["context"] as? [String: Any] ?? [:]
            if let dispatch = context["lastDispatch"] as? [String: Any], dispatch["status"] as? String == "failed",
               let id = dispatch["id"] as? String, id != lastDispatchFailure {
                lastDispatchFailure = id
                activities.insert(Activity(title: "QuPath action stopped", detail: dispatch["error"] as? String ?? "Review the action again.", isError: true), at: 0)
            }
            imageName = (context["image"] as? [String: Any])?["name"] as? String ?? "No image open"
            let selected = context["selection"] as? [[String: Any]] ?? []
            let selectionCount = context["selectionCount"] as? Int ?? selected.count
            if selectionCount == 0 { selectionLabel = "Select a region in QuPath" }
            else if selectionCount == 1 && !selected.isEmpty { selectionLabel = selected[0]["name"] as? String ?? "1 \(selected[0]["kind"] as? String ?? "object") selected" }
            else { selectionLabel = "\(selectionCount) objects selected" }
            approvals = (health["actions"] as? [[String: Any]] ?? []).filter { $0["status"] as? String == "pending" }
        } catch {
            // HTTP polling can fail while the task's WebSocket is still alive; retain Stop.
            connected = false; bridgeConnected = false; textReady = false
            voiceReason = "Starting the local gateway…"
            if !stopping {
                do { try gateway?.ensureRunning() }
                catch { voiceReason = "Gateway unavailable: \(error.localizedDescription)" }
            }
        }
    }
    private func connectEvents() {
        guard !stopping else { return }
        eventsReady = false
        do {
            try loadToken()
            var request = URLRequest(url: URL(string: "ws://127.0.0.1:49555/events")!)
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            let connection = session.webSocketTask(with: request)
            receiving?.cancel()
            socket?.cancel(with: .goingAway, reason: nil)
            socket = connection
            connection.resume()
            receiving = Task { [weak self] in
                do {
                    while !Task.isCancelled {
                        let message = try await connection.receive()
                        let data: Data
                        switch message { case .string(let text): data = Data(text.utf8); case .data(let bytes): data = bytes; @unknown default: continue }
                        guard let self, self.socket === connection else { return }
                        if let event = try JSONSerialization.jsonObject(with: data) as? [String: Any] { self.receive(event) }
                    }
                } catch {
                    guard let self, self.socket === connection else { return }
                    self.eventsReady = false; self.textBusy = false; self.scheduleReconnect()
                }
            }
        } catch { scheduleReconnect() }
    }
    private func scheduleReconnect() {
        guard !stopping else { return }
        reconnect?.cancel()
        reconnect = Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled else { return }
            self?.connectEvents()
        }
    }
    private func receive(_ event: [String: Any]) {
        switch event["type"] as? String {
        case "connected": eventsReady = true
        case "tool":
            let name = event["name"] as? String ?? "Action"
            if name == "qupath_context" { Task { await refresh() }; return }
            let result = event["result"] as? [String: Any] ?? [:]
            let nested = result["result"] as? [String: Any] ?? result
            if name == "qupath_measurement_summary", let data = try? JSONSerialization.data(withJSONObject: nested),
               let report = try? JSONDecoder().decode(MeasurementReport.self, from: data) {
                measurementReport = report
            }
            activities.insert(Activity(title: friendly(name), detail: summary(nested, toolName: name)), at: 0)
            if let pointer = nested["pointer"] as? [String: Any], let x = pointer["screenX"] as? Double, let y = pointer["screenY"] as? Double { self.pointer.show(x: x, y: y, label: "Right here") }
            activities = Array(activities.prefix(40))
            Task { await refresh() }
        case "action":
            if let action = event["action"] as? [String: Any], action["status"] as? String == "pending" { selectedTab = 1 }
            if let action = event["action"] as? [String: Any], let status = action["status"] as? String, ["failed", "denied", "completed"].contains(status) {
                activities.insert(Activity(title: status.capitalized, detail: action["error"] as? String ?? friendly(action["tool"] as? String ?? "Action"), isError: status == "failed"), at: 0)
            }
            Task { await refresh() }
        case "error":
            activities.insert(Activity(title: "Needs attention", detail: event["message"] as? String ?? "Unknown error", isError: true), at: 0)
            voiceActive = false; audio.stop()
        case "text_result":
            if let text = event["text"] as? String {
                activities.insert(Activity(title: "Luma", detail: text, icon: "sparkle"), at: 0)
                activities = Array(activities.prefix(40))
            }
        case "text_progress":
            if let text = event["text"] as? String {
                activities.insert(Activity(title: "Luma", detail: text, icon: "clock"), at: 0)
                activities = Array(activities.prefix(40))
            }
        case "text_error":
            textBusy = false
            activities.insert(Activity(title: "Text task stopped", detail: event["message"] as? String ?? "Inspect the application before retrying.", isError: true), at: 0)
        case "task_state": textBusy = event["state"] as? String == "working"
        case "audio":
            if let base64 = event["audio"] as? String, let data = Data(base64Encoded: base64) { audio.play(pcm: data) }
        case "interrupted": audio.interruptPlayback()
        case "voice_state":
            voiceActive = event["state"] as? String == "listening"
            if !voiceActive { audio.stop() }
        default: break
        }
    }
    func call(_ name: String, _ arguments: [String: Any] = [:]) async {
        busy = true
        defer { busy = false }
        do {
            let result = try await request("/tools/call", body: ["name": name, "arguments": arguments])
            if let pending = result["result"] as? [String: Any], pending["status"] as? String == "approval_required" { selectedTab = 1 }
            await refresh()
        } catch { activities.insert(Activity(title: "Couldn’t complete action", detail: error.localizedDescription, isError: true), at: 0) }
    }
    func decide(_ id: String, approved: Bool) async {
        do { _ = try await request("/approvals/\(id)", body: ["approved": approved]); await refresh() }
        catch { activities.insert(Activity(title: "Action needs review", detail: error.localizedDescription, isError: true), at: 0); await refresh() }
    }
    func search() async {
        guard knowledgeQuery.count >= 2 else { return }
        do {
            let response = try await request("/tools/call", body: ["name": "qupath_knowledge", "arguments": ["query": knowledgeQuery]])
            knowledgeResults = (response["result"] as? [String: Any])?["results"] as? [[String: Any]] ?? []
        } catch { activities.insert(Activity(title: "Search unavailable", detail: error.localizedDescription, isError: true), at: 0) }
    }
    func sendText() {
        let draft = input
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        guard !textSending else { return }
        guard text.utf16.count <= 4000 else {
            activities.insert(Activity(title: "Message too long", detail: "Use a message of up to 4000 characters.", isError: true), at: 0)
            return
        }
        guard textReady else {
            activities.insert(Activity(title: "Text setup needed", detail: textReason, isError: true), at: 0)
            return
        }
        guard eventsReady, let connection = socket else {
            activities.insert(Activity(title: "Reconnecting", detail: "Your draft is kept. Wait for the conversation connection.", isError: true), at: 0)
            return
        }
        textSending = true
        Task {
            defer { textSending = false }
            do {
                let data = try JSONSerialization.data(withJSONObject: ["type": "text", "text": text])
                try await connection.send(.string(String(decoding: data, as: UTF8.self)))
                if input == draft { input = "" }
                selectedTab = 0
                activities.insert(Activity(title: "You", detail: text, icon: "bubble.right"), at: 0)
                activities = Array(activities.prefix(40))
            } catch {
                activities.insert(Activity(title: "Delivery unconfirmed", detail: "Your draft is kept. Inspect the session before retrying.", isError: true), at: 0)
            }
        }
    }
    func stopTextTask() {
        send(["type": "stop_task"])
        textBusy = false
    }
    func reviewImportSource(_ path: String) {
        let source = URL(fileURLWithPath: path)
        guard path.hasPrefix("/"), ["json", "geojson"].contains(source.pathExtension.lowercased()) else { return }
        NSWorkspace.shared.open([source], withApplicationAt: URL(fileURLWithPath: "/System/Applications/TextEdit.app"), configuration: NSWorkspace.OpenConfiguration()) { [weak self] _, error in
            if let error {
                Task { @MainActor in self?.activities.insert(Activity(title: "Source review unavailable", detail: error.localizedDescription, isError: true), at: 0) }
            }
        }
    }
    func toggleVoice() {
        if voiceActive { send(["type": "stop_voice"]); audio.stop(); voiceActive = false }
        else { send(["type": "start_voice"]) }
    }
    private func send(_ event: [String: Any]) {
        guard let socket, let data = try? JSONSerialization.data(withJSONObject: event), let text = String(data: data, encoding: .utf8) else { return }
        Task { do { try await socket.send(.string(text)) } catch { activities.insert(Activity(title: "Gateway disconnected", detail: "Reconnect to continue.", isError: true), at: 0) } }
    }
    func friendly(_ name: String) -> String {
        let labels = ["qupath_project_images": "Project images", "qupath_analysis_catalog": "Analysis options", "qupath_measurement_summary": "Measured results", "qupath_open_path": "Open image or project", "qupath_open_project_image": "Open project image", "qupath_inspect_import": "Inspect annotation file", "qupath_import_annotations": "Import annotations"]
        if let label = labels[name] { return label }
        return ["qupath_context": "Read current selection", "qupath_focus": "Focused your view", "qupath_overlays": "Updated highlights", "qupath_checkpoint": "Saved a checkpoint", "qupath_objects": "Found objects", "qupath_measurements": "Read measurements", "qupath_annotate": "Created a region", "qupath_classify": "Classified cells", "qupath_restore": "Restored earlier results"][name] ?? name.replacingOccurrences(of: "qupath_", with: "").replacingOccurrences(of: "_", with: " ").capitalized
    }
    func summary(_ value: [String: Any], toolName: String? = nil) -> String {
        if toolName == "qupath_project_images" {
            return value["open"] as? Bool == false ? "No project is open" : "Project images matched: \(value["total"] ?? 0)"
        }
        if toolName == "qupath_analysis_catalog" { return "Measurement names: \(value["measurementNameCount"] ?? 0) · Classes: \(value["classificationCount"] ?? 0)" }
        if toolName == "qupath_inspect_import" { return "\(value["annotations"] ?? 0) annotations validated for review. No objects imported." }
        if let imported = value["imported"] { return "\(imported) annotations imported" }
        if value["alreadyActive"] as? Bool == true { return "That image is already active" }
        if value["dispatched"] as? Bool == true { return "Dispatched to QuPath. Inspect the application to confirm completion." }
        if value["completionVerified"] as? Bool == false { return "Operation finished. Inspect the result and log in QuPath." }
        if let total = value["total"] { return "\(total) objects in scope" }
        if let selected = value["selected"] { return "\(selected) objects selected" }
        if let counts = value["counts"] as? [String: Any] { return counts.keys.sorted().prefix(3).map { "\(counts[$0]!) \($0)" }.joined(separator: " · ") }
        if value["pointer"] != nil { return "Centered on your selected region" }
        if value["updated"] != nil { return "Display updated in QuPath" }
        if let label = value["label"] as? String { return label }
        return "Operation returned from QuPath"
    }
}

enum CompanionError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
}
