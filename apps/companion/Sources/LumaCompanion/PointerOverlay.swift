import AppKit
import SwiftUI

@MainActor
final class PointerOverlay {
    private var panel: NSPanel?
    private var hideTask: Task<Void, Never>?
    func show(x: Double, y: Double, label: String) {
        hideTask?.cancel()
        let fxPoint = CGPoint(x: x, y: y)
        guard let screen = NSScreen.screens.first(where: { screen in
            guard let id = screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? CGDirectDisplayID else { return false }
            return CGDisplayBounds(id).contains(fxPoint)
        }) ?? NSScreen.main else { return }
        let id = screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? CGDirectDisplayID ?? CGMainDisplayID()
        let bounds = CGDisplayBounds(id)
        let mappedX = screen.frame.minX + (x - bounds.minX) / bounds.width * screen.frame.width
        let mappedY = screen.frame.maxY - (y - bounds.minY) / bounds.height * screen.frame.height
        if panel == nil {
            let p = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 180, height: 90), styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
            p.isOpaque = false; p.backgroundColor = .clear; p.hasShadow = false
            p.level = .statusBar; p.ignoresMouseEvents = true
            p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
            panel = p
        }
        panel?.contentView = NSHostingView(rootView: PointerView(label: label))
        panel?.orderFrontRegardless()
        NSAnimationContext.runAnimationGroup { context in
            context.duration = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion ? 0 : 0.35
            panel?.animator().setFrameOrigin(NSPoint(x: mappedX - 24, y: mappedY - 65))
        }
        hideTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2.5))
            guard !Task.isCancelled else { return }
            self?.panel?.orderOut(nil)
        }
    }
}
struct PointerView: View {
    let label: String
    var body: some View {
        HStack(alignment: .top, spacing: 5) {
            Image(systemName: "cursorarrow").font(.system(size: 30, weight: .bold)).foregroundStyle(Color.mint).shadow(color: .mint.opacity(0.4), radius: 12)
            Text(label).font(.system(size: 11, weight: .medium)).foregroundStyle(.white).padding(.horizontal, 10).padding(.vertical, 7).background(.black.opacity(0.85), in: Capsule())
        }.padding(20).frame(width: 180, height: 90, alignment: .topLeading)
    }
}
