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
                    Text("Knowledge").tag(1)
                }.pickerStyle(.segmented).labelsHidden()
                Group {
                    if model.selectedTab == 0 { session } else { knowledge }
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
                Text(model.textReady ? "Type in Luma to operate QuPath until voice is available." : "You can still use the quick actions and connect an MCP client to operate QuPath locally.").foregroundStyle(.secondary)
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
                    Button { model.stopTextTask() } label: { Image(systemName: "stop.fill").font(.system(size: 10)).frame(width: 25, height: 25).background(Color.white.opacity(0.08), in: Circle()) }.buttonStyle(.plain).help("Stop unstarted work; completed actions remain")
                }
                Button { model.sendText() } label: { Image(systemName: "arrow.up").font(.system(size: 12, weight: .semibold)).frame(width: 25, height: 25).background(Color.white.opacity(0.08), in: Circle()) }.buttonStyle(.plain).disabled(model.input.isEmpty || !model.textReady || !model.connected || !model.eventsReady || model.textSending).help(model.textReady ? "Send message using verified AWS credits" : model.textReason)
            }.padding(.horizontal, 13).padding(.vertical, 10).background(Color.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 12))
            HStack {
                if !model.voiceReady {
                    Button { showingFunding = true } label: { HStack(spacing: 5) { Circle().fill(Color.orange).frame(width: 4, height: 4); Text(model.voiceReason).font(.system(size: 10)).lineLimit(1).truncationMode(.tail) }.foregroundStyle(secondaryText) }.buttonStyle(.plain).help(model.voiceReason)
                }
                Spacer()
                Button { model.toggleVoice() } label: {
                    HStack(spacing: 7) { Image(systemName: model.voiceActive || model.voiceStarting ? "stop.fill" : "waveform"); Text(model.voiceActive || model.voiceStarting ? "Stop voice" : "Start voice") }.font(.system(size: 11, weight: .semibold)).padding(.horizontal, 15).padding(.vertical, 10).background(mint, in: Capsule()).foregroundStyle(Color.black.opacity(0.85))
                }.buttonStyle(HoverButtonStyle()).disabled(!model.connected || !model.eventsReady || !model.voiceReady)
            }
        }.padding(.horizontal, 22).padding(.bottom, 20).padding(.top, 12)
    }
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
