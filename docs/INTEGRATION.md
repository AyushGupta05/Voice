---
id: INT-02
title: Penny × Fidelis integration
stage: 03-development
status: Implemented on integration/fidelis-penny
priority: P0
source_revisions: [integration/fidelis-penny]
related_requirements: [FR-NL-001, FR-SPEECH-004]
related_decisions: [DEC-048]
last_reviewed: 2026-10-17
approval: Integration record; review by Arnav and Alex pending
---

# Penny × Fidelis integration

The wire contract is [FIDELIS-PROTOCOL.md](FIDELIS-PROTOCOL.md). This page records how each integration part behaves.

## Voice in the workspace

Penny's conversation runs inside the Fidelis workspace (the planned voice panel, #18). There is a one-line **Penny strip** under
the command bar, and it grows into a **plan card** while the bridge shows a plan. The **Mic** button gives Penny the
microphone, and typed requests reach her through an **Ask Penny** row. The code is in `fidelis-app/src/main/java/sc/fidelis/penny/voice`
(the gateway client and audio) and `.../penny/ui` (the strip, the canvas highlight and the `PennyUi` hooks). The workspace
changes are small and additive: `LiveVoice`, plus hooks in `CommandBar`, `Banners`, `Workspace` and `ScreenshotRun`.

### The strip

Screenshots, all in the default **light** theme: `docs/03-development/screenshots/penny-listening.png`,
`penny-thinking.png`, `penny-running.png`, `penny-answer-flash.png` and `penny-plan-confirm.png`. The opt-in dark theme
(`--theme dark`) is shown in `penny-plan-confirm-dark.png`. The workspace defaults to FlatLightLaf (`Theme.install`).

- It sits at the top of the `Banners` slot, above the dialog-pending banner and the proposal sheet. It uses the dialog
  banner's style: a 3 px bar on the left, compact 12 px type, FlatLaf colours and `Theme` helpers. The bar is the LaF accent
  while Penny is active, the warning colour while a plan waits and the border colour when idle.
- The left side shows an animated 16 px mark, **Penny** and a state word:

  | State | Mark | When |
  |---|---|---|
  | Offline / Reconnecting… | hollow dot | gateway socket not connected |
  | Ready | quiet dot | connected, voice off |
  | Starting… | spinner | `start_voice` sent, session or microphone not live yet |
  | Listening | four bars driven by the microphone level | `voice_state listening` and the microphone is capturing |
  | Thinking | slow pulse | `task_state working` |
  | Running | slow pulse | a plan reported by the bridge is running |
  | Speaking | ripple | `voice_speaking` until the last reply chunk has played |

  The animation timer runs only while an animated state is on screen.
- The conversation line shows the live partial transcript in italic, newest words kept. Then it shows `“what you said”  ·
  acknowledgement` and finally `“what you said”  →  answer`. Errors appear in the error colour. When space is short, the
  request is shortened first so the answer stays readable, and the full text is in the tooltip. Speech the gateway marks
  as not addressed to Penny is not shown.
- **Stop task** or **Stop speaking** appears while a task runs or Penny talks. It sends `stop_task` and drops the rest of the
  reply locally, the same as the Swift companion's Stop.
- The strip is hidden while Penny's gateway has never been reachable. After a disconnect it shows *Reconnecting…* for 8 s,
  then hides until the socket returns. A plan keeps it visible.

### The plan card (`PennyUi.showPlan/planProgress/planFinished/clearPlan`)

- One row holds **Plan · <document>** (from `context.document`, `title` or `image`), the step trail and the status on the
  right. The trail reads ✓ done › ● running › ○ pending, with ✗ for the failed step, and wraps when narrow. The status shows
  *Step k of n* while running, *Done · n steps · undo in History*, *Stopped at step k of n* or *Needs your confirmation*.
- Below it are the macro preview (`macroPreview`, one monospace line, the full macro in the tooltip) and the utterance (only
  when it differs from the one in the strip).
- While the plan waits (`showPlan(plan, false)`), each reason appears on its own line beside **Cancel** and **Confirm and
  run**. Confirm is never the default button.
  - **Confirm** first sends `POST http://127.0.0.1:49558/execute` with `{operation:"context", arguments:{}, spoken:false}`.
    It then sends `{operation:"confirm", arguments:{planId}, expectedContext:<contextKey>, approved:true, spoken:false}`, so
    the plan runs only against the state the user saw. Both requests carry `Authorization: Bearer <.local/state/bridge-token>`
    and no Origin header. Port: `-Dfidelis.penny.port`.
  - **Cancel** sends `{operation:"cancel_plan", arguments:{planId}, spoken:false}`, without approval.
  - If the bridge refuses (4xx or 409), the card shows *Could not confirm: <error>* and keeps waiting. Nothing ran.
  - If `confirm` returns 200 and no progress hook reported the run, the card marks the plan done. While hooks report
    progress, `planFinished` decides the outcome.
- A finished plan collapses 6 s after *Done*. A stopped plan stays until it is dismissed (×) or the next plan arrives.

### The canvas highlight and view hooks (`WorkspacePennyUi`)

- `flash(document, imageRects, ellipses, label)` draws the outlines **inside the hosted IJ1 canvas**. The canvas is a
  heavyweight AWT component, so a glass pane or `JLayer` would be painted under it. Instead, on every animation frame
  `CanvasFlash` repaints the canvas the way IJ1 does (`ImageCanvas.paint`) and draws the outlines on the same graphics.
  The flash cannot appear over other windows, and it never touches the `ImagePlus` overlay or ROI.
  - Animation: outlines pop in (scale 1.35 → 1, staggered 25 ms each for up to 30 targets), hold, start fading at 3.0 s and
    are gone 0.8 s later. Then the canvas is repainted clean.
  - Colour: the LaF accent (read every frame, so a theme switch follows), over a light halo, so the outline reads on bright
    and dark image regions alike. The label chip is the accent with white text.
  - Up to 12 targets get a soft fill and a glow. More targets get thin outlines only, so the image stays readable
    (`penny-answer-flash.png`: 71 ROIs).
  - Geometry follows the canvas's source rectangle and magnification on every frame.
  - A null document means the focused view, and nothing is drawn for a document whose tab is not showing.
  - `ScreenshotRun` composites the flash into the PNG, and `penny-flash-onscreen.png` is the on-screen (Robot) capture.
- `zoom(document, how)`: `in`/`out` take one step of the IJ1 ladder (`DocumentView.zoomStep`), `fit` and `selection` call
  `fit`/`zoomToSelection`, and `actual`, `100`, `100%` or `1:1` call `actualPixels`.
- `canvasOnScreen` returns the canvas's global screen rectangle, in Java (macOS point) coordinates, clipped to the visible
  viewport of its document view. `toScreen` returns an image-pixel rectangle as covered screen points, clipped to that
  rectangle, or null when it is off screen. `magnification` returns the view's magnification or NaN.
- Queries from other threads wait for the EDT for at most 500 ms. Actions are posted to the EDT and never block.
- `Workspace.launch` registers the implementation, and the registration ends when the workspace window closes.

### Mic and typed requests (the least surprising split)

- **Mic.** While Penny's gateway is reachable, the Mic toggles Penny's **continuous conversation**. The button reads *Stop*,
  and the red recording indicator shows *Penny live m:ss* in the command bar and the status bar. Press again to stop.
  Otherwise the Mic keeps today's local Whisper push-to-talk (*Recording N s / 30 s*), unchanged. A local recording or
  transcription already in progress keeps the Mic until it finishes. Only one client holds the microphone: the gateway
  refuses a second voice owner, and the strip shows its *already active in another companion* message.
- **Typed requests.** While Penny is reachable, the command bar shows two rows below the command matches:
  - **Ask Penny: “…”** (*runs at once · undo in History*). This is the default row for instruction-like text, and Enter
    sends `{type:"text", text}` to Penny.
  - **Interpret locally: “…”** (*preview before anything runs*). This is Fidelis's own `DefaultNlService` with its proposal
    sheet, unchanged, and is reached with the arrow keys or the mouse.

  Command matches stay the default for command-like text. Without Penny there is only the original *Interpret as
  instruction* row. If a send fails, the request goes to the local interpreter and the status bar says so.

  Why: Penny's plans already follow Fidelis's confirmation policy through the bridge. Ordinary steps run at once and are
  undoable; explicit cases wait in the plan card. So sending to Penny is the natural default when she is there, and the
  local preview stays one keystroke away.

### The voice client (`PennyVoiceClient`)

- **Connection.** The client opens `ws://127.0.0.1:49555/events` (`-Dfidelis.penny.gateway`) with
  `Authorization: Bearer <.local/state/companion-token>` and no Origin header. The token is found from `-Dluma.root`,
  `LUMA_ROOT`, or the first folder at or above `fidelis.repo.dir`, `user.dir` or `fiji.dir` that holds `.local/state`; a git
  worktree finds its enclosing checkout this way. The token is re-read on every attempt.
- **Hello.** The client sends `{type:"hello", clientId:"fidelis-<uuid>", client:"fidelis-workspace", desktop:false}`. The
  client id is stable for the JVM, so the gateway reattaches the conversation after a reconnect.
- **Reconnect.** The client reconnects with backoff (0.5 s, doubling to 15 s) and re-arms voice that was on when the socket
  dropped. It also restarts voice when the gateway itself ends a session (time limit, stream closed, gateway closing,
  disconnected). A refused token is reported as such.
- **Voice.** The client sends `start_voice{requestId:uuid}` and then waits for `voice_state connecting{sessionId}`. Only at
  `ready_for_audio` does it open the microphone.
  - Microphone: Java Sound, preferring 24 kHz, then 48, 16 or 44.1 kHz; mono, or the first channel of stereo.
  - Processing: resampled to 24 kHz (running-average low-pass plus linear interpolation), then an automatic gain as in
    `AudioIO.swift`: toward an RMS of 0.07, at most 40×, adapting only above the 0.0015 noise floor. The Mac microphone is
    quiet.
  - Frames: 100 ms of PCM16 (4800 bytes, 6400 base64 characters), sent as `audio{sessionId, audio}` and never faster than
    the gateway's 48 bytes/ms bound. Frames are dropped rather than queued past a 1 s backlog.
  - `stop_voice{requestId}` closes the microphone at once.
  - Three seconds of exact digital silence is reported. That is what macOS delivers without microphone permission for the
    app that started the JVM.
- **Replies.** `audio{audio, sampleRate:24000, epoch, final}` plays through a 24 kHz Java Sound line. `PlaybackGate` applies
  the Swift companion's epoch rules: `voice_speaking` with an epoch at least the current one starts a reply (`queued` keeps
  the acknowledgement playing), and only the current epoch plays. `interrupted{epoch}` flushes at once, and a local Stop
  suppresses the rest of the reply.
- **Barge-in.** Java Sound has no echo cancellation. While Penny plays, microphone frames are held back 300 ms and then sent
  as silence, so Penny does not transcribe herself. A frame counts as the user when it is louder than an absolute floor
  (0.02 RMS) and twice the echo expected from what is playing now. The expected echo is the loudest reply audio of the last
  400 ms times a learned coupling. After 300 ms of such frames, playback stops locally and the held frames go out as real
  audio, so the transcriber hears the start of the sentence. The gateway's own interruption follows from the transcript.
- **Typed text and events.** `text{text, spoken?}` sends typed requests (`spoken` only for a reviewed local Whisper
  transcript), and `stop_task` stops the task. Events consumed: `connected`, `voice_state`, `voice_transcript`,
  `voice_speaking`, `audio`, `interrupted`, `voice_error`, `error`, `text_ack`, `text_progress`, `text_result`,
  `text_error`, `task_state` and `tool`. `desktop_request` is answered with a `desktop_response` error, because the
  workspace does not operate the desktop.
- **Threading.** All state lives on one loop thread, and listeners are called there. The panel and the command bar hop to
  the EDT. No socket, audio or HTTP work runs on the EDT.

### Switches

- `--no-penny` or `-Dfidelis.penny.voice=false` turns the client off.
- `--screenshot` runs never connect, so evidence runs cannot disturb a live gateway or start a paid voice session.
- `--penny-demo listening|thinking|speaking|plan|running|answer` (with `--screenshot`, after a scenario) puts the strip and
  Mic in one state without a gateway. Numbers in the demo answer and the outlines come from the engine's Results and ROI
  list.

  ```
  python3 -I fidelis-app/tools/run.py --isolated-home -- --screenshot docs/03-development/screenshots/penny-answer-flash.png --scenario j12 --theme dark --penny-demo answer
  ```

### Verification (17 Oct 2026, macOS, pinned Fiji)

- **Protocol-tested.** No live gateway was used, and no AWS session or audio hardware was touched.
  - `sc.fidelis.penny.voice.PennyVoiceClientTest` (8 tests) runs against an in-test RFC 6455 gateway. It checks the
    handshake (token, no Origin), hello, the session flow, frame size and rate bounds, transcript, ack and result,
    epochs and interruption, reconnect with the same client id and voice re-arm, typed text, `stop_task`, `desktop_request`,
    a refused start and a refused token.
  - `VoiceAudioLogicTest` (6) covers resampling from 48, 44.1, 24 and 16 kHz, gain, `PlaybackGate` and barge-in.
  - `sc.fidelis.penny.ui.PennyPanelTest` (5) covers the strip states and the plan card, with Confirm and Cancel against an
    in-test HTTP bridge: request bodies, `expectedContext`, approval, auth and no Origin.
  - `PennyUiGeometryTest` (1) covers a real hosted canvas: geometry at 100 % and 200 %, viewport clipping at 3200 %, zoom
    hooks, and a flash that leaves the overlay and ROI untouched.
  - `sc.fidelis.ui.CommandBarLiveVoiceTest` (2) covers Mic routing and fallback, the Ask Penny row and Enter.
  - All of these pass. The existing workspace UI classes (`ConfirmationUiTest`, `WorkspaceDisposeTest`,
    `WorkspaceLayoutTest`, `WorkspaceShortcutTest`, `WorkspaceU2Test`, `WorkspaceViewportTest`, `WorkspaceMacroEditorTest`)
    pass with the strip mounted.
- **Real-app-tested.** The screenshots above come from the real J-12 run in the light theme, plus one dark variant. A Robot
  capture of the live screen shows the flash painted inside the on-screen canvas (`penny-flash-onscreen.png`; that capture
  was taken in the dark theme before the switch to light). Java Sound on this Mac reports 24 kHz mono capture and playback
  as supported; this was checked with `AudioSystem.isLineSupported`, without opening a device.
- **Not yet verified.**
  - A real microphone and speaker session against the live gateway: deliberately not run, because it costs AWS credits and
    the live demo owns the gateway.
  - The barge-in thresholds on real speakers and headsets.
  - Windows audio.
  - The bridge driving the plan hooks end to end. The bridge is built in parallel against `PennyUi`.
