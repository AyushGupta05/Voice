import AppKit
import SwiftUI
import Darwin
import Combine
import LumaPresentation

final class CompanionPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
}
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    private var model: CompanionModel!
    private var panel: CompanionPanel!
    private var statusItem: NSStatusItem!
    private var terminationSignal: DispatchSourceSignal?
    private var expansion: AnyCancellable?
    private var screenChanges: AnyCancellable?
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        // Build/relaunch uses SIGTERM; run the same child cleanup as a normal Quit.
        Darwin.signal(SIGTERM, SIG_IGN)
        terminationSignal = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
        terminationSignal?.setEventHandler { NSApp.terminate(nil) }
        terminationSignal?.resume()
        model = CompanionModel()
        let screen = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        panel = CompanionPanel(contentRect: NSRect(x: screen.midX - 210, y: screen.maxY - 72, width: 420, height: 56), styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.title = "Penny"
        panel.contentView = NSHostingView(rootView: CompanionSurface(model: model))
        panel.isOpaque = false; panel.backgroundColor = .clear; panel.hasShadow = true
        panel.level = .floating; panel.isMovableByWindowBackground = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.isReleasedWhenClosed = false
        panel.delegate = self
        expansion = model.$expanded.removeDuplicates().sink { [weak self] expanded in
            self?.resizePanel(expanded: expanded)
        }
        screenChanges = NotificationCenter.default.publisher(for: NSApplication.didChangeScreenParametersNotification)
            .sink { [weak self] _ in
                guard let self else { return }
                self.resizePanel(expanded: self.model.expanded)
            }
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.image = NSImage(systemSymbolName: "waveform", accessibilityDescription: "Penny scientific companion")
        statusItem.button?.target = self; statusItem.button?.action = #selector(togglePanel)
        let menu = NSMenu()
        menu.addItem(withTitle: "Show Penny", action: #selector(showPanel), keyEquivalent: "l").target = self
        menu.addItem(.separator())
        menu.addItem(withTitle: "Quit Penny", action: #selector(quit), keyEquivalent: "q").target = self
        // Right click opens the menu; a normal click toggles the companion.
        statusItem.button?.sendAction(on: [.leftMouseUp, .rightMouseUp])
        statusItem.menu = nil
        panel.orderFrontRegardless()
        model.start()
    }
    @objc private func togglePanel() {
        if NSApp.currentEvent?.type == .rightMouseUp {
            let menu = NSMenu()
            menu.addItem(withTitle: "Show Penny", action: #selector(showPanel), keyEquivalent: "").target = self
            menu.addItem(withTitle: "Quit Penny", action: #selector(quit), keyEquivalent: "").target = self
            statusItem.menu = menu; statusItem.button?.performClick(nil); statusItem.menu = nil
        } else if panel.isVisible { panel.orderOut(nil) } else { showPanel() }
    }
    @objc private func showPanel() { NSApp.unhide(nil); panel.orderFrontRegardless() }
    func windowDidChangeScreen(_ notification: Notification) { resizePanel(expanded: model.expanded) }
    private func resizePanel(expanded: Bool) {
        guard let panel else { return }
        let visible = panel.screen?.visibleFrame ?? NSScreen.main?.visibleFrame ?? panel.frame
        let frame = PanelLayout.frame(current: panel.frame, visible: visible, expanded: expanded)
        model.detailsHeight = min(600, max(0, visible.height - 56))
        if panel.frame != frame { panel.setFrame(frame, display: true) }
    }
    @objc private func quit() { NSApp.terminate(nil) }
    func applicationWillTerminate(_ notification: Notification) { model.stop() }
}
@main
enum LumaApp {
    @MainActor static func main() {
        let app = NSApplication.shared
        let delegate = AppDelegate()
        app.delegate = delegate
        app.run()
    }
}
