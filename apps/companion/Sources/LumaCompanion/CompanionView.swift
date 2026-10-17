import SwiftUI
import AppKit

private let mint = Color(red: 0.64, green: 0.92, blue: 0.79)
private let secondaryText = Color.white.opacity(0.5)
private let hairline = Color.white.opacity(0.08)

struct CompanionView: View {
    @ObservedObject var model: CompanionModel
    var height: CGFloat = 600
    @State private var showingFunding = false
    var body: some View {
        VStack(spacing: 0) {
            header
            VStack(alignment: .leading, spacing: 18) {
                contextCard
                Picker("Workspace", selection: $model.selectedTab) {
                    Text("Session").tag(0)
                    Text(model.approvals.isEmpty ? "Actions" : "Actions · \(model.approvals.count)").tag(1)
                    Text("Knowledge").tag(2)
                }.pickerStyle(.segmented).labelsHidden()
                Group {
                    if model.selectedTab == 0 { session }
                    else if model.selectedTab == 1 { actions }
                    else { knowledge }
                }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }.padding(.horizontal, 22).padding(.top, 8)
            footer
        }
        .frame(width: 420, height: height)
        .background(Color(red: 0.075, green: 0.085, blue: 0.09))
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .overlay(RoundedRectangle(cornerRadius: 24).stroke(hairline, lineWidth: 1))
        .environment(\.colorScheme, .dark)
        .font(.system(size: 13))
        .sheet(isPresented: $showingFunding) {
            VStack(alignment: .leading, spacing: 18) {
                Image(systemName: "waveform").font(.system(size: 26)).foregroundStyle(mint)
                Text("Voice connection").font(.system(size: 24, weight: .semibold))
                Text(model.voiceReason).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                Text(model.textReady ? "Type in Luma to operate QuPath while voice setup is pending." : "You can still use the quick actions and connect an MCP client to operate QuPath locally.").foregroundStyle(.secondary)
                Button("Got it") { showingFunding = false }.buttonStyle(.borderedProminent).tint(mint).foregroundStyle(.black)
            }.padding(28).frame(width: 360)
        }
    }
    private var header: some View {
        HStack(spacing: 10) {
            BuddyMark(size: 30)
            Text("luma").font(.system(size: 21, weight: .semibold, design: .rounded)).tracking(-0.8)
            Spacer()
            HStack(spacing: 6) {
                Circle().fill(model.bridgeConnected ? mint : Color.orange).frame(width: 5, height: 5)
                Text(model.bridgeConnected ? "QuPath connected" : model.connected ? "Waiting for QuPath" : "Gateway offline").font(.system(size: 10, weight: .medium)).foregroundStyle(secondaryText)
            }
            Button { NSApp.hide(nil) } label: { Image(systemName: "minus").font(.system(size: 11)).foregroundStyle(secondaryText) }
                .buttonStyle(.plain).help("Hide Luma; reopen from the menu bar")
        }.padding(.horizontal, 22).padding(.top, 22).padding(.bottom, 20)
    }
    private var contextCard: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 10).fill(mint.opacity(0.09))
                Image(systemName: "viewfinder").font(.system(size: 21, weight: .light)).foregroundStyle(mint)
            }.frame(width: 44, height: 44)
            VStack(alignment: .leading, spacing: 5) {
                Text(model.imageName).font(.system(size: 12, weight: .medium)).lineLimit(1).truncationMode(.middle)
                Text(model.selectionLabel).font(.system(size: 11)).foregroundStyle(secondaryText).lineLimit(1)
            }
            Spacer(minLength: 0)
            Image(systemName: "link").font(.system(size: 11)).foregroundStyle(secondaryText)
        }.padding(12).background(Color.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 14)).overlay(RoundedRectangle(cornerRadius: 14).stroke(hairline))
    }
    private var session: some View {
        VStack(alignment: .leading, spacing: 18) {
            if model.activities.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Less navigating.\nMore discovering.").font(.system(size: 27, weight: .medium)).tracking(-0.8).lineSpacing(1).fixedSize(horizontal: false, vertical: true)
                    Text("Your scientific tools, one conversation away.").font(.system(size: 12)).foregroundStyle(secondaryText)
                }.padding(.top, 8).padding(.bottom, 2)
            }
            HStack(spacing: 8) {
                quickAction("Focus here", icon: "scope") { await model.call("qupath_focus") }
                quickAction("Highlights", icon: "circle.dotted") { await model.call("qupath_overlays", ["detections": true, "fill": true]) }
                quickAction("Checkpoint", icon: "clock.arrow.circlepath") { await model.call("qupath_checkpoint", ["label": "Researcher checkpoint"]) }
            }.disabled(!model.bridgeConnected || (model.context["image"] as? [String: Any]) == nil)
            if !model.activities.isEmpty {
                HStack {
                    Text("IN THIS SESSION").font(.system(size: 9, weight: .semibold)).tracking(1.4).foregroundStyle(secondaryText)
                    Spacer()
                    if model.busy || model.textBusy { ProgressView().controlSize(.mini) }
                }
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 16) {
                        if let report = model.measurementReport {
                            MeasurementReportView(report: report, currentContextKey: model.context["contextKey"] as? String)
                        }
                        ForEach(model.activities) { activity in
                            HStack(alignment: .top, spacing: 10) {
                                Image(systemName: activity.isError ? "exclamationmark.circle" : activity.icon).foregroundStyle(activity.isError ? Color.orange : mint).font(.system(size: 13)).padding(.top, 2)
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(activity.title).font(.system(size: 12, weight: .medium))
                                    Text(activity.detail).textSelection(.enabled).font(.system(size: 11)).foregroundStyle(secondaryText).fixedSize(horizontal: false, vertical: true)
                                    if let payload = activity.payload { ToolResultView(payload: payload) }
                                }
                                Spacer(minLength: 0)
                            }
                        }
                    }.padding(.vertical, 2)
                }.scrollIndicators(.hidden)
            } else {
                Spacer(minLength: 0)
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "cursorarrow.rays").foregroundStyle(mint)
                    Text("Select something in QuPath. Luma follows the image, region and objects you’re working with.").font(.system(size: 12)).foregroundStyle(secondaryText).lineSpacing(3).fixedSize(horizontal: false, vertical: true)
                }.padding(14).background(mint.opacity(0.035), in: RoundedRectangle(cornerRadius: 12))
                Spacer(minLength: 0)
            }
        }
    }
    private func quickAction(_ title: String, icon: String, action: @escaping () async -> Void) -> some View {
        Button { Task { await action() } } label: {
            VStack(spacing: 8) {
                Image(systemName: icon).font(.system(size: 16, weight: .light)).foregroundStyle(mint)
                Text(title).font(.system(size: 10, weight: .medium)).foregroundStyle(.white.opacity(0.8))
            }.frame(maxWidth: .infinity).padding(.vertical, 13).background(Color.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 12))
        }.buttonStyle(HoverButtonStyle()).help(title)
    }
    private var actions: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if model.approvals.isEmpty {
                    Image(systemName: "checkmark.shield").font(.system(size: 30, weight: .ultraLight)).foregroundStyle(mint).padding(.top, 20)
                    Text("You’re in control.").font(.system(size: 23, weight: .medium)).tracking(-0.5)
                    Text("Luma acts right away and journals every change with a hierarchy checkpoint. Anything that needs your decision appears here.").foregroundStyle(secondaryText).lineSpacing(3)
                }
                ForEach(model.approvals, id: \.approvalID) { action in
                    VStack(alignment: .leading, spacing: 12) {
                        HStack { Image(systemName: "hand.raised").foregroundStyle(.orange); Text("Ready for your review").font(.system(size: 12, weight: .semibold)) }
                        Text(model.friendly(action["tool"] as? String ?? "Action")).font(.system(size: 19, weight: .medium))
                        Text((action["context"] as? [String: Any])?["image"].flatMap { $0 as? [String: Any] }?["name"] as? String ?? model.imageName).font(.system(size: 11)).foregroundStyle(secondaryText)
                        if let project = (action["context"] as? [String: Any])?["project"] as? [String: Any] {
                            Text("Project: \(project["name"] as? String ?? "Unnamed")").font(.system(size: 11)).foregroundStyle(secondaryText)
                        }
                        if let target = (action["context"] as? [String: Any])?["reviewTarget"] as? [String: Any] {
                            Text("\(target["window"] as? String ?? "QuPath") → \(target["text"] as? String ?? target["type"] as? String ?? "Control")").font(.system(size: 12, weight: .medium)).foregroundStyle(mint)
                            if action["tool"] as? String == "qupath_import_annotations" {
                                Text(pretty(target["classCounts"] ?? [])).font(.system(size: 10, design: .monospaced)).textSelection(.enabled)
                                if target["classCountsTruncated"] as? Bool == true {
                                    Text("Showing 50 of \(target["classCount"] as? Int ?? 0) classes. Review the full source before approval.").font(.system(size: 11)).foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true)
                                }
                                if let conversion = target["geometryConversion"] as? [String: Any], let changed = conversion["featuresChanged"] as? Int, changed > 0 {
                                    Text("QuPath conversion changes \(changed) feature geometries at its native precision. Review source and contours before accepting the result.").font(.system(size: 11)).foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true)
                                    Text("Maximum area change: \(String(describing: conversion["maxAbsoluteAreaChangePixels2"] ?? "unknown")) px²").font(.system(size: 10)).foregroundStyle(secondaryText)
                                }
                                if let path = target["path"] as? String {
                                    Button("Review full source in TextEdit…") { model.reviewImportSource(path) }.buttonStyle(.bordered)
                                }
                                Text("Source stays unchanged. Imported annotations must use this image’s full-resolution pixel coordinates.").font(.system(size: 11)).foregroundStyle(secondaryText).fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        Text(pretty(action["arguments"] ?? [:])).font(.system(size: 10, design: .monospaced)).textSelection(.enabled).padding(10).frame(maxWidth: .infinity, alignment: .leading).background(.black.opacity(0.25), in: RoundedRectangle(cornerRadius: 8))
                        Text(action["risk"] as? String == "code" ? "Runs with full local privileges. Files and external effects may not be recoverable." : action["tool"] as? String == "qupath_image_type" ? "Changes image interpretation. Hierarchy checkpoints do not restore this setting." : "A hierarchy checkpoint will be created. Review the scope and parameters before continuing.").font(.system(size: 11)).foregroundStyle(.orange.opacity(0.85)).fixedSize(horizontal: false, vertical: true)
                        HStack {
                            Button("Decline") { Task { await model.decide(action.approvalID, approved: false) } }.buttonStyle(.bordered)
                            Spacer()
                            Button("Approve action") { Task { await model.decide(action.approvalID, approved: true) } }.buttonStyle(.borderedProminent).tint(mint).foregroundStyle(.black)
                        }
                    }.padding(14).background(Color.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 14)).overlay(RoundedRectangle(cornerRadius: 14).stroke(Color.orange.opacity(0.2)))
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }.scrollIndicators(.hidden)
    }
    private var knowledge: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                TextField("Find a QuPath workflow…", text: $model.knowledgeQuery).textFieldStyle(.plain).onSubmit { Task { await model.search() } }
                Button { Task { await model.search() } } label: { Image(systemName: "magnifyingglass").foregroundStyle(mint) }.buttonStyle(.plain).help("Search QuPath guidance")
            }.padding(12).background(Color.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 10))
            if model.knowledgeResults.isEmpty {
                Text("Application knowledge, close at hand.").font(.system(size: 21, weight: .medium)).tracking(-0.5).padding(.top, 12)
                Text("Search detection, measurements, classification, export, TMA or scripting.").foregroundStyle(secondaryText).lineSpacing(3)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    ForEach(Array(model.knowledgeResults.enumerated()), id: \.offset) { _, item in
                        VStack(alignment: .leading, spacing: 7) {
                            Text(item["title"] as? String ?? "Guide").font(.system(size: 13, weight: .medium)).foregroundStyle(mint)
                            Text(item["excerpt"] as? String ?? "").font(.system(size: 11)).foregroundStyle(secondaryText).lineLimit(9).textSelection(.enabled)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }.scrollIndicators(.hidden)
        }
    }
    private var footer: some View {
        VStack(spacing: 12) {
            Divider().overlay(hairline)
            HStack(spacing: 10) {
                TextField("Ask Luma to do something…", text: $model.input).font(.system(size: 12)).textFieldStyle(.plain).onSubmit { model.sendText() }
                if model.textBusy {
                    Button { model.stopTextTask() } label: { Image(systemName: "stop.fill").font(.system(size: 10)).frame(width: 25, height: 25).background(Color.white.opacity(0.08), in: Circle()) }.buttonStyle(.plain).help("Stop unstarted work; completed actions and human decisions remain")
                }
                Button { model.sendText() } label: { Image(systemName: "arrow.up").font(.system(size: 12, weight: .semibold)).frame(width: 25, height: 25).background(Color.white.opacity(0.08), in: Circle()) }.buttonStyle(.plain).disabled(model.input.isEmpty || !model.textReady || !model.connected || !model.eventsReady || model.textSending).help(model.textReady ? "Send message using verified AWS credits" : model.textReason)
            }.padding(.horizontal, 13).padding(.vertical, 10).background(Color.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 12))
            HStack {
                Button { showingFunding = true } label: { HStack(spacing: 5) { Circle().fill(Color.orange).frame(width: 4, height: 4); Text(model.textReady ? "Text ready · Voice setup" : "Voice setup needed").font(.system(size: 10)) }.foregroundStyle(secondaryText) }.buttonStyle(.plain).help(model.voiceReason)
                Spacer()
                Button { model.toggleVoice() } label: {
                    HStack(spacing: 7) { Image(systemName: model.voiceActive || model.voiceStarting ? "stop.fill" : "waveform"); Text(model.voiceActive || model.voiceStarting ? "Stop voice" : "Start voice") }.font(.system(size: 11, weight: .semibold)).padding(.horizontal, 15).padding(.vertical, 10).background(mint, in: Capsule()).foregroundStyle(Color.black.opacity(0.85))
                }.buttonStyle(HoverButtonStyle()).disabled(!model.connected || !model.eventsReady || !model.voiceReady)
            }
        }.padding(.horizontal, 22).padding(.bottom, 20).padding(.top, 12)
    }
}
private extension Dictionary where Key == String, Value == Any {
    var approvalID: String { self["id"] as? String ?? "unknown" }
}
private func pretty(_ value: Any) -> String {
    guard let data = try? JSONSerialization.data(withJSONObject: value, options: [.prettyPrinted, .sortedKeys]), let text = String(data: data, encoding: .utf8) else { return String(describing: value) }
    return text
}
struct BuddyMark: View {
    var size: CGFloat = 30
    var body: some View {
        HStack(spacing: size * 0.14) {
            Capsule().fill(Color.black.opacity(0.8)).frame(width: size * 0.09, height: size * 0.29)
            Capsule().fill(Color.black.opacity(0.8)).frame(width: size * 0.09, height: size * 0.29)
        }.frame(width: size, height: size).background(mint, in: RoundedRectangle(cornerRadius: size * 0.35))
    }
}
struct HoverButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.opacity(configuration.isPressed ? 0.65 : 1).contentShape(Rectangle()).onHover { over in if over { NSCursor.pointingHand.push() } else { NSCursor.pop() } }
    }
}
