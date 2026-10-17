# Fidelis bridge contract (Penny × Fidelis)

Penny's gateway drives the **Fidelis workstation** (`fidelis-app/`) the same way it drives stand-alone Fiji
(`docs/FIJI-PROTOCOL.md`), through a local bridge — but every change goes through the Fidelis engine
(`Engine.submit`), so Fidelis's history, undo, provenance and macro export stay authoritative. This page is the contract
the three integration parts build against: the Java bridge (`sc.fidelis.penny`), the gateway profile (`LUMA_APP=fidelis`)
and the workspace voice surface (`sc.fidelis.penny.ui`).

## Process and transport

- The bridge starts inside the Fidelis JVM from `Bootstrap` when a Penny bridge token exists (`<root>/.local/state/bridge-token`,
  root from `-Dluma.root`, `LUMA_ROOT`, or the checkout containing `fidelis-app/`), or with `-Dfidelis.penny=true`.
  It is off when no token is found.
- HTTP on `127.0.0.1:49558` (`-Dfidelis.penny.port`). `GET /health` → `{application:"Fidelis", version, adapter:{adapterVersion,
  protocolVersion:1, operations:[…]}}`. `POST /execute` with `Authorization: Bearer <token>`, body
  `{operation, arguments, expectedContext?, approved?, spoken?, taskId?, utterance?}` → `200 {result}` or `4xx/409 {error}`.
  Requests with an `Origin` header are refused (browsers).
- `spoken:true` → requests use `Origin.NL_SPOKEN`, else `Origin.NL_TYPED`. Every engine request carries
  `params.transcriptRef = taskId` (reserved key; the engine records it in `HistoryRecord.transcriptRef`).
- Mutating operations need `approved:true` and `expectedContext` equal to the current `contextKey` (same rule as Fiji).

## Confirmation (the Fidelis contract, kept)

Fidelis's rule is that language never runs anything without the user's confirmation; Penny's rule is "act immediately".
The bridge resolves it with Fidelis's own `ConfirmationPolicy` on the planned `Proposal`:

- **Not explicit** (AC0–AC3 typed commands, view changes): the plan is shown in the workspace's Penny card and **runs at
  once** (policy `fidelis.penny.confirm=auto`, the default, Ayush's decision of 17 Oct 2026). It is undoable from History.
- **Explicit** (CODE/macros, AC4+ such as close/save, a `black` global change, an unfilled consequential slot): the card
  waits; the user presses *Confirm and run* or says "yes"/"go ahead" (gateway instant route → `confirm`). The bridge
  answers `{status:"awaiting_confirmation", planId, reasons[], steps[]}` without running.
- `fidelis.penny.confirm=always` makes every plan wait (the strict Fidelis behaviour).

## Operations

Results are JSON objects; every result that concerns something visible may include `highlight` (below). Ids: documents are
Fidelis `DocumentId` strings (`"doc-3"`); `imageId` is the IJ1 id. Commands are catalogue ids (`ij1:<menu path>`).

| Operation | Kind | Arguments | Result |
|---|---|---|---|
| `context` | read | | `{contextKey, application:"Fidelis", engine:{state}, document:{id, imageId, title, width, height, channels, slices, frames, bitDepth, unit, pixelWidth, pixelHeight, path, changed}, documents:[{id,title,focused}], roi:{type,bounds}\|null, counts:{rois, resultsRows, history}, lastStep:{actionId,label,origin,outcome}\|null, globals:{…}, viewer:{magnification, canvasScreen:{x,y,width,height}}, pending:{planId, reasons}\|null}` |
| `images` | read | | `{documents:[{id,title,width,height,focused,changed}]}` |
| `open_path` | session | `path` | SESSION `OPEN` → `{document, highlight}` |
| `activate_image` | view | `id` (doc id) or `title` | `Engine.focus` → `{document}` |
| `commands` | read | `query`, `limit?` | `Engine.search` → `{commands:[{commandId,label,menuPath,schemaComplete}]}` |
| `command` | data | `commandId`, `params?` (missing keys filled from the schema defaults) | one COMMAND step → `{step}` |
| `segment_cells` | data | `channel?`, `method?` (Otsu), `minSize?` (0), `watershed?` (true) | plan: Duplicate is **not** used; on the focused document: 8-bit if needed → Gaussian Blur σ1 → `SET_AUTO_THRESHOLD{method,dark}` → Convert to Mask → Fill Holes → Watershed → Set Measurements (area mean centroid bounding shape) → Analyze Particles (add, display, clear) → `{count, averageSize, unit, steps:[…], highlight}` |
| `measure` | data | `measurements?:[…]`, `scope:roi\|all\|rois` | Set Measurements + Measure (or the ROI list's Measure) → `{rows, lastRow, summary:{column:{min,max,mean}}, unit}` |
| `threshold` | data | `method?`, `dark?`, `lower?`, `upper?` | `SET_AUTO_THRESHOLD` or `SET_THRESHOLD` → `{lower, upper, method}` |
| `analyze_particles` | data | Analyze Particles schema keys (`size`, `circularity`, `exclude`, `include`, …) | → `{count, averageSize, unit, highlight}` |
| `filter` | data | `name: gaussian\|median\|mean\|unsharp\|subtract_background\|enhance_contrast\|invert\|smooth\|sharpen\|find_edges`, `sigma?`, `radius?`, `rolling?`, `saturated?` | the catalogue command → `{step}` |
| `convert` | data | `to: 8-bit\|16-bit\|32-bit\|RGB` | `ij1:Image>Type>…` |
| `duplicate` | data | `title?` | `ij1:Image>Duplicate...` → `{document}` |
| `select_roi` | view | `type: rectangle\|oval\|polygon\|none`, `x,y,width,height` or `points` (image px) | `SET_ROI`/`CLEAR_ROI` → `{roi, highlight}` |
| `rois` | read | `limit?` | `Engine.rois(focused)` → `{total, rois:[{index,name,bounds}]}` |
| `results` | read | `limit?`, `offset?` | `Engine.results()` → `{columns, rows, total}` |
| `object_stats` | read | | computed from `results()` (Area, Mean…) → `{n, area:{mean,median,min,max}, unit}` |
| `display` | view | `min?`, `max?`, `auto?` | `SET_DISPLAY_RANGE` (auto: from the image's min/max) |
| `stack` | view | `slice?`, `channel?`, `frame?` | `SET_POSITION` |
| `zoom` | view | `direction: in\|out`, `fit?`, `selection?` | the focused `DocumentView` (`zoomStep`, `fit`, `zoomToSelection`) via `PennyUi` |
| `highlight` | view | `rois: all\|indices`, `indices?`, `label?` | flashes ROI-list entries on the hosted canvas via `PennyUi.flash`; returns `{highlight}` |
| `undo` / `redo` | data | | DIRECT `UNDO`/`REDO` → `{step}` |
| `history` | read | `limit?` | `Engine.history()` → `{steps:[{actionId,label,origin,provenance,outcome}]}` |
| `macro` | read | | `Engine.exportMacro()` → `{macro, steps}` |
| `run_macro` | code | `code` | CODE `ijm` — always explicit: returns `awaiting_confirmation` with the capability reasons; runs after `confirm` |
| `confirm` | data | `planId?` (default: the pending plan) | runs the pending plan → its result |
| `cancel_plan` | view | | discards the pending plan |

`step` = `{actionId, label, commandId, outcome, durationMs, macroLines[]}`. A failed engine result returns HTTP 409
`{error: EngineError.message, code, suggestions}`; nothing is reported as success unless the engine said COMPLETED.

## Highlight and the workspace (`sc.fidelis.penny.PennyUi`)

The bridge never draws; it calls the `PennyUi` hooks the workspace registers (`PennyUi.register(impl)`). With no UI
(tests) a no-op implementation is used. Highlights are drawn **inside the Fidelis canvas** (a glass layer over the hosted
IJ1 canvas), so they can never appear over other windows. The returned `highlight` payload keeps Penny's screen-geometry
shape (`{label, targets:[{x,y,width,height,shape}], pointer:{screenX,screenY}, viewer:{x,y,width,height}}`) for the
companion cursor, computed from the hosted canvas's screen location.

## Voice in the workspace

The workspace shows Penny's conversation in a strip under the command bar (the planned voice panel, #18): live transcript,
what Penny is doing (listening · thinking · running "Step 2 of 6" · speaking), the spoken answer, and the plan card with
Confirm when a plan is explicit. It connects to the gateway's `/events` websocket (`ws://127.0.0.1:49555/events`,
`Authorization: Bearer <.local/state/companion-token>`): client `hello`, `start_voice{requestId}`, `audio{sessionId,
audio:base64 PCM16 mono 24 kHz}`, `stop_voice`, `text{text, spoken}`; server `connected`, `voice_state`, `voice_transcript
{partial,text}`, `text_ack`, `text_result`, `voice_speaking`, `audio{audio, sampleRate:24000, final, epoch}`, `interrupted`,
`tool`, `action`, `voice_error`. The workspace Mic button starts Penny's live voice when the gateway is reachable and falls
back to Fidelis's local Whisper push-to-talk otherwise. Only one voice client should hold the microphone: when Fidelis
owns voice, the Swift companion's bar stays idle.
