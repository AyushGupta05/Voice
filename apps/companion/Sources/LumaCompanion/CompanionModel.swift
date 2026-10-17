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
    @Published var voiceReady = false
    @Published var voiceStarting = false
    @Published var voiceSpeaking = false
    @Published var textReady = false
    @Published var textBusy = false
    @Published var eventsReady = false
    @Published var textSending = false
    @Published var textReason = "Text needs verified AWS credits."
    @Published var input = ""
    @Published var knowledgeQuery = ""
    @Published var knowledgeResults: [[String: Any]] = []
    @Published var selectedTab = 0
    @Published var expanded = false
    @Published var detailsHeight: CGFloat = 600
    @Published var runningActionIDs = Set<String>()
    @Published var decidingActionIDs = Set<String>()
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
    private var voiceSessionID: String?
    private var voiceRequestID: String?
    private var providerListening = false
    private var capturing = false
    private var playbackEpoch = 0
    private var playbackSuppressed = false
    private var audioPending: [Data] = []
    private var audioPendingBytes = 0
    private var audioSending: Task<Void, Never>?
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
        resetVoice()
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
            voiceReady = (health["voice"] as? [String: Any])?["enabled"] as? Bool ?? false
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
            let actions = health["actions"] as? [[String: Any]] ?? []
            approvals = actions.filter { $0["status"] as? String == "pending" }
            runningActionIDs = Set(actions.filter { $0["status"] as? String == "running" }.compactMap { $0["id"] as? String })
            if !approvals.isEmpty && !expanded { selectedTab = 1; expanded = true }
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
                    self.eventsReady = false; self.textBusy = false; self.resetVoice(); self.scheduleReconnect()
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
            if let highlight = nested["highlight"] as? [String: Any] { self.pointer.highlight(highlight) }
            else if let pointer = nested["pointer"] as? [String: Any], let x = pointer["screenX"] as? Double, let y = pointer["screenY"] as? Double { self.pointer.show(x: x, y: y, label: "Right here") }
            activities = Array(activities.prefix(40))
            Task { await refresh() }
        case "action":
            if let action = event["action"] as? [String: Any], let id = action["id"] as? String {
                if action["status"] as? String == "running" { runningActionIDs.insert(id) }
                else { runningActionIDs.remove(id) }
            }
            if let action = event["action"] as? [String: Any], action["status"] as? String == "pending" { selectedTab = 1; expanded = true }
            if let action = event["action"] as? [String: Any], let status = action["status"] as? String, ["failed", "denied", "completed", "expired"].contains(status) {
                let detail = action["error"] as? String ?? friendly(action["tool"] as? String ?? "Action")
                let warning = action["auditWarning"] as? String
                activities.insert(Activity(title: status.capitalized, detail: detail + (warning.map { "\n" + $0 } ?? ""), isError: status == "failed" || warning != nil), at: 0)
                activities = Array(activities.prefix(40))
            }
            Task { await refresh() }
        case "error":
            if let requestID = event["requestId"] as? String, requestID != voiceRequestID { return }
            activities.insert(Activity(title: "Needs attention", detail: event["message"] as? String ?? "Unknown error", isError: true), at: 0)
            resetVoice()
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
            guard event["requestId"] as? String == voiceRequestID, event["sessionId"] as? String == voiceSessionID, voiceSessionID != nil,
                  let epoch = event["epoch"] as? Int, epoch == playbackEpoch, !playbackSuppressed,
                  event["sampleRate"] as? Int == 24000, let base64 = event["audio"] as? String,
                  let data = Data(base64Encoded: base64) else { return }
            let final = event["final"] as? Bool == true
            audio.play(pcm: data, completion: final ? { [weak self] in Task { @MainActor in
                guard let self, self.voiceSessionID == event["sessionId"] as? String, self.playbackEpoch == epoch else { return }
                self.voiceSpeaking = false
            }} : nil)
        case "interrupted":
            guard event["requestId"] as? String == voiceRequestID, event["sessionId"] as? String == voiceSessionID else { return }
            playbackEpoch = event["epoch"] as? Int ?? playbackEpoch
            playbackSuppressed = false; voiceSpeaking = false; audio.interruptPlayback()
        case "voice_speaking":
            guard event["requestId"] as? String == voiceRequestID, event["sessionId"] as? String == voiceSessionID, let epoch = event["epoch"] as? Int, epoch >= playbackEpoch else { return }
            playbackEpoch = epoch; playbackSuppressed = false; audio.interruptPlayback(); voiceSpeaking = true
        case "voice_transcript":
            guard event["requestId"] as? String == voiceRequestID, event["sessionId"] as? String == voiceSessionID else { return }
            if event["partial"] as? Bool == false, let text = event["text"] as? String {
                activities.insert(Activity(title: "You", detail: text, icon: "mic"), at: 0)
                activities = Array(activities.prefix(40))
            }
        case "voice_error":
            guard event["requestId"] as? String == voiceRequestID else { return }
            activities.insert(Activity(title: "Voice needs attention", detail: event["message"] as? String ?? "Speech is unavailable.", isError: true), at: 0)
        case "voice_state":
            guard let requestID = event["requestId"] as? String, requestID == voiceRequestID else { return }
            let state = event["state"] as? String
            if state == "connecting", let id = event["sessionId"] as? String { resetVoice(keepingRequest: true); voiceSessionID = id; voiceStarting = true }
            else if state == "ready_for_audio", let id = event["sessionId"] as? String, id == voiceSessionID { startMicrophone(sessionID: id) }
            else if state == "listening", event["sessionId"] as? String == voiceSessionID { providerListening = true; voiceActive = capturing; voiceStarting = !capturing; pointer.follow(true) }
            else if state == "idle", event["sessionId"] == nil || event["sessionId"] as? String == voiceSessionID { resetVoice() }
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
        guard !decidingActionIDs.contains(id) else { return }
        decidingActionIDs.insert(id)
        defer { decidingActionIDs.remove(id) }
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
        playbackSuppressed = true; voiceSpeaking = false; audio.interruptPlayback()
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
        if voiceActive || voiceStarting || voiceSessionID != nil { stopVoice() }
        else if voiceReady && eventsReady { let id = UUID().uuidString; voiceRequestID = id; voiceStarting = true; send(["type": "start_voice", "requestId": id]) }
    }
    private func stopVoice() {
        if let id = voiceRequestID { send(["type": "stop_voice", "requestId": id]) }
        resetVoice()
    }
    private func resetVoice(keepingRequest: Bool = false) {
        if !keepingRequest { voiceRequestID = nil }
        voiceSessionID = nil; capturing = false; providerListening = false
        voiceActive = false; voiceStarting = false; voiceSpeaking = false; microphoneLevel = 0
        pointer.follow(false)
        audioSending?.cancel(); audioSending = nil; audioPending.removeAll(); audioPendingBytes = 0
        audio.stop(); playbackEpoch = 0; playbackSuppressed = false
    }
    private func startMicrophone(sessionID: String) {
        Task { [weak self] in
            guard let self else { return }
            do {
                try await self.audio.start { [weak self] data, level in Task { @MainActor in
                    guard let self, self.voiceSessionID == sessionID else { return }
                    // Without echo cancellation, send silence while Luma speaks so its own voice is not transcribed.
                    let muted = self.voiceSpeaking && !self.audio.echoCancelled
                    self.microphoneLevel = muted ? 0 : level
                    self.enqueueAudio(muted ? Data(count: data.count) : data, sessionID: sessionID)
                }}
                guard self.voiceSessionID == sessionID else { return }
                self.capturing = true; self.voiceActive = self.providerListening; self.voiceStarting = !self.providerListening
            } catch {
                guard self.voiceSessionID == sessionID else { return }
                self.stopVoice()
                self.activities.insert(Activity(title: "Microphone unavailable", detail: error.localizedDescription, isError: true), at: 0)
                self.expanded = true
            }
        }
    }
    private func enqueueAudio(_ data: Data, sessionID: String) {
        guard audioPendingBytes + data.count <= 48000 else {
            stopVoice()
            activities.insert(Activity(title: "Voice stopped", detail: "Audio could not be sent in time. Try another session.", isError: true), at: 0)
            return
        }
        audioPending.append(data); audioPendingBytes += data.count
        guard audioSending == nil, let connection = socket else { return }
        audioSending = Task { [weak self] in
            guard let self else { return }
            defer { if self.voiceSessionID == sessionID { self.audioSending = nil } }
            while !Task.isCancelled && self.voiceSessionID == sessionID && !self.audioPending.isEmpty {
                let chunk = self.audioPending.removeFirst(); self.audioPendingBytes -= chunk.count
                do {
                    let payload = try JSONSerialization.data(withJSONObject: ["type": "audio", "sessionId": sessionID, "audio": chunk.base64EncodedString()])
                    try await connection.send(.string(String(decoding: payload, as: UTF8.self)))
                } catch {
                    if self.voiceSessionID == sessionID { self.stopVoice() }
                    return
                }
            }
        }
    }
    func toggleDetails() {
        if !approvals.isEmpty { selectedTab = 1; expanded = true }
        else { expanded.toggle() }
    }
    private func send(_ event: [String: Any]) {
        guard let socket, let data = try? JSONSerialization.data(withJSONObject: event), let text = String(data: data, encoding: .utf8) else { return }
        Task { do { try await socket.send(.string(text)) } catch { activities.insert(Activity(title: "Gateway disconnected", detail: "Reconnect to continue.", isError: true), at: 0) } }
    }
    func friendly(_ name: String) -> String {
        let labels = ["qupath_project_images": "Project images", "qupath_analysis_catalog": "Analysis options", "qupath_measurement_summary": "Measured results", "qupath_open_path": "Open image or project", "qupath_open_project_image": "Open project image", "qupath_inspect_import": "Inspect annotation file", "qupath_import_annotations": "Import annotations"]
        if let label = labels[name] { return label }
        return ["qupath_context": "Read current selection", "qupath_focus": "Focused your view", "qupath_overlays": "Updated overlays", "qupath_highlight": "Pointed it out", "qupath_checkpoint": "Saved a checkpoint", "qupath_objects": "Found objects", "qupath_measurements": "Read measurements", "qupath_annotate": "Created a region", "qupath_classify": "Classified cells", "qupath_restore": "Restored earlier results"][name] ?? name.replacingOccurrences(of: "qupath_", with: "").replacingOccurrences(of: "_", with: " ").capitalized
    }
    func summary(_ value: [String: Any], toolName: String? = nil) -> String {
        if toolName == "qupath_project_images" {
            return value["open"] as? Bool == false ? "No project is open" : "Project images matched: \(value["total"] ?? 0)"
        }
        if toolName == "qupath_analysis_catalog" { return "Measurement names: \(value["measurementNameCount"] ?? 0) · Classes: \(value["classificationCount"] ?? 0)" }
        if let highlight = value["highlight"] as? [String: Any] {
            let shown = highlight["shown"] as? Int ?? 0, total = highlight["total"] as? Int ?? shown
            let label = (highlight["label"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Highlighted"
            return shown < total ? "\(label) · \(shown) of \(total) visible" : label
        }
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
