# Desktop control (any window on screen)

Luma's in-app bridge (`qupath_ui` / `qupath_ui_action`) sees JavaFX controls inside QuPath only. The desktop layer lets the agent operate everything else the researcher can see: OS open/save panels, native alerts and sheets, third-party plugin windows, custom panes and, if asked, any other application. The companion cursor flies to the target before every action so the researcher can follow along.

## Pieces

| Where | What |
|---|---|
| `apps/companion/Sources/LumaCompanion/DesktopControl.swift` | macOS Accessibility (`AXUIElement`) inspection and actions, synthetic mouse/keyboard via `CGEvent` as the fallback, permission prompt. Main actor. |
| `CompanionModel.swift` | Answers `desktop_request` events from the gateway with `desktop_response`, showing the highlight through `PointerOverlay` first. |
| `packages/gateway/src/desktop-bridge.ts` | `DesktopBridge`: sends requests to the connected companion over `/events`, correlates `requestId`, times out (inspect 10 s, others 15 s), rejects clearly with no companion. `withDesktop(adapter, bridge)` routes `desktop_*` operations. |
| `packages/gateway/src/desktop-tools.ts` | `qupath_desktop_inspect` (read), `qupath_desktop_act`, `qupath_desktop_type`, `qupath_desktop_key`, `qupath_desktop_click` (code). |
| `script/build_and_run.sh` | Adds `NSAccessibilityUsageDescription` to `Luma.app`'s Info.plist. |

## Permission setup

1. Launch Luma (`./script/build_and_run.sh`). The first `qupath_desktop_*` call asks macOS for the Accessibility permission once.
2. Open **System Settings → Privacy & Security → Accessibility** and enable **Luma**.
3. If Luma is not listed, drag `dist/Luma.app` into the list. The app is signed ad hoc with a stable designated requirement (`identifier "science.luma.companion"`), so the grant survives rebuilds; a resign with a different identifier would need re-granting.
4. Until it is granted every desktop tool returns `trusted:false` with the instruction above and performs nothing. The in-app tools keep working.

The permission covers both Accessibility reads/actions and posting synthetic input. Luma never asks for Screen Recording; `qupath_snapshot` captures the viewer through QuPath itself.

## How the agent uses it

- Use `qupath_ui` / `qupath_ui_action` first. They see JavaFX directly, with reliable disabled state and fingerprints.
- `qupath_desktop_inspect` lists windows of QuPath by default (`apps: ["frontmost"]` or other bundle ids are accepted) plus Apple's open/save panel service process for that host. Each element carries a path-based `id` (`<pid>/w<window>/<child path>`), role, title/label/value, `enabled`, action names and a global screen `frame` (top-left origin, same as `PointerOverlay`).
- Ids are valid for 30 s and only for elements seen by the latest inspections. `qupath_desktop_act` re-reads the element, refuses when it vanished or changed role, raises its window, flies the cursor there, waits about 250 ms, then tries the Accessibility action (`AXPress`, `AXConfirm`, `AXFocused`, `AXSelected`, `AXValue`) and falls back to moving the real mouse to the element centre and clicking, or typing. The reply says `method: "ax"` or `"synthetic"`.
- File dialogs: `qupath_desktop_key {"keys":["cmd+shift+g"]}` opens the Go-to-folder sheet, `qupath_desktop_type {"text":"/absolute/path","submit":true}` fills it, then `qupath_desktop_key {"keys":["return"]}` opens. Inspect again afterwards: dispatch is never completion.
- `qupath_desktop_click` takes exact global coordinates for canvases or custom-drawn controls. `qupath_desktop_type`/`qupath_desktop_key` go to whatever has keyboard focus after bringing QuPath (or `app`) forward.

## Verified so far

- A CLI harness built from the same `DesktopControl.swift` inspected the running `QuPath - OS-2.ndpi` window (`trusted:true`): tabs, buttons, text fields, the image-list outline rows and the toolbar all come back with frames and actions.
- JavaFX reports `enabled:false` over Accessibility for controls `qupath_ui` shows enabled (for example "Open project..."). The flag is therefore advisory in `act`; the reply carries a warning and the toolkit ignores presses on truly disabled controls.
- Accessibility exposes nothing while the Mac is at the login window or locked; `inspect` then returns zero windows with `frontmost: com.apple.loginwindow`.

Not yet verified in the real app: acting (press/type) on QuPath through the companion socket, open/save panel contents, and the Accessibility prompt flow from `Luma.app` itself.

## Safety notes

- The desktop tools are `code` risk: they run through `Runtime.decide()` like every other action, with an audit record, and `LUMA_APPROVALS=review` puts a card in front of them.
- They are not sandboxed to QuPath: a shortcut or click goes to whatever is in front. The companion raises the target window first and the cursor shows the target, but the researcher should keep QuPath visible while Luma operates and can press Stop at any time.
- No undo. Hierarchy checkpoints do not cover file dialogs, plugin windows or other applications.
- Nothing is typed from the gateway's own memory: text comes from the model's tool call, which is logged. Do not put secrets in `qupath_desktop_type`.
- Elements expire after 30 s so a stale inspection can never click a control that has moved.
