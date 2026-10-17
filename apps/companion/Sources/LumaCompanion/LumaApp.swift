import AppKit
import SwiftUI
import Darwin

final class CompanionPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
}
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var model: CompanionModel!
    private var panel: CompanionPanel!
    private var statusItem: NSStatusItem!
    private var terminationSignal: DispatchSourceSignal?
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        // Build/relaunch uses SIGTERM; run the same child cleanup as a normal Quit.
        Darwin.signal(SIGTERM, SIG_IGN)
        terminationSignal = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
        terminationSignal?.setEventHandler { NSApp.terminate(nil) }
        terminationSignal?.resume()
        model = CompanionModel()
        let screen = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        panel = CompanionPanel(contentRect: NSRect(x: screen.maxX - 450, y: screen.midY - 300, width: 420, height: 600), styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.contentView = NSHostingView(rootView: CompanionView(model: model))
        panel.isOpaque = false; panel.backgroundColor = .clear; panel.hasShadow = true
        panel.level = .floating; panel.isMovableByWindowBackground = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.isReleasedWhenClosed = false
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.image = NSImage(systemSymbolName: "waveform", accessibilityDescription: "Luma scientific companion")
        statusItem.button?.target = self; statusItem.button?.action = #selector(togglePanel)
        let menu = NSMenu()
        menu.addItem(withTitle: "Show Luma", action: #selector(showPanel), keyEquivalent: "l").target = self
        menu.addItem(.separator())
        menu.addItem(withTitle: "Quit Luma", action: #selector(quit), keyEquivalent: "q").target = self
        // Right click opens the menu; a normal click toggles the companion.
        statusItem.button?.sendAction(on: [.leftMouseUp, .rightMouseUp])
        statusItem.menu = nil
        panel.orderFrontRegardless()
        model.start()
    }
    @objc private func togglePanel() {
        if NSApp.currentEvent?.type == .rightMouseUp {
            let menu = NSMenu()
            menu.addItem(withTitle: "Show Luma", action: #selector(showPanel), keyEquivalent: "").target = self
            menu.addItem(withTitle: "Quit Luma", action: #selector(quit), keyEquivalent: "").target = self
            statusItem.menu = menu; statusItem.button?.performClick(nil); statusItem.menu = nil
        } else if panel.isVisible { panel.orderOut(nil) } else { showPanel() }
    }
    @objc private func showPanel() { NSApp.unhide(nil); panel.orderFrontRegardless() }
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
