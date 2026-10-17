---
id: INT-01
title: Fidelis bridge contract (Penny voice over the Fidelis engine)
stage: 03-development
status: Implemented on integration/fidelis-penny (verification in progress)
priority: P0
source_revisions: [integration/fidelis-penny]
related_requirements: [FR-NL-001, FR-SPEECH-004, FR-HIST-002, FR-MACRO-001]
related_decisions: [DEC-048]
last_reviewed: 2026-10-17
approval: Integration contract written by Ayush's session; review by Arnav (engine) and Alex (UI) pending
---

# Fidelis bridge contract (Penny × Fidelis)

Penny's gateway drives the **Fidelis workstation** (`fidelis-app/`) the same way it drives stand-alone Fiji
(`docs/FIJI-PROTOCOL.md`), through a local bridge — but every change goes through the Fidelis engine
(`Engine.submit`), so Fidelis's history, undo, provenance and macro export stay authoritative. This page is the contract
the three integration parts build against: the Java bridge (`sc.fidelis.penny`), the gateway profile (`LUMA_APP=fidelis`)
and the workspace voice surface (`sc.fidelis.penny.ui`).

## Process and transport

- The bridge (`sc.fidelis.penny.FidelisBridge`) starts inside the Fidelis JVM from `Bootstrap`, right after the product
  engine is registered, when a Penny bridge token is found, or with `-Dfidelis.penny=true` (which still needs a token and
  logs why it stays off without one). `-Dfidelis.penny=false` keeps it off. Token: `LUMA_BRIDGE_TOKEN` (32+ characters),
  else `<root>/.local/state/bridge-token`; root from `-Dluma.root`, `LUMA_ROOT`, else the first directory holding
  `.local/state/bridge-token` walking up from the Fidelis jar, `-Dfidelis.repo.dir` and the working directory (a git
  worktree under `<root>/.local/worktrees/` finds the main checkout). `Bootstrap.exit` stops it. A plain `--smoke` never
  starts it (there is no engine in smoke); `run.py --smoke --prop fidelis.penny=true` creates the engine, starts the bridge and runs a
  self-check over HTTP (`/health` 200, `context` 200, an `Origin` request 403).
- HTTP on `127.0.0.1:49558` (`-Dfidelis.penny.port`), 4 handler threads. **Every** request, `/health` included, needs
  `Authorization: Bearer <token>` (as the Fiji bridge); any request with an `Origin` header is refused with 403 first.
  `GET /health` → `{application:"Fidelis", version, engine, confirmPolicy, adapter:{adapterVersion:"fidelis-bridge-1",
  protocolVersion:1, operations:[…28], sourceSha256, sourceShaCovers}}` (`sourceSha256` is the SHA-256 of the loaded
  `sc.fidelis.penny` classes). `POST /execute` body `{operation, arguments, expectedContext?, approved?, spoken?, taskId?,
  utterance?}` → `200 {result}`; 400 `{error}` for a malformed body, unknown operation or bad argument; 401/403/404/413;
  409 `{error, …}` for refusals and engine failures (below).
- `spoken:true` → requests use `Origin.NL_SPOKEN`, else `Origin.NL_TYPED`. Every engine request carries
  `params.transcriptRef = taskId` (reserved key; the engine records it in `HistoryRecord.transcriptRef`); without a `taskId`
  the plan id (`penny-plan-…`) is used, so Penny's steps are always traceable. Steps a confirmed macro runs are recorded by
  the engine as `MACRO` children of the `NL_*` CODE step and carry the same `transcriptRef`.
- Data, session and code operations (`open_path command segment_cells measure threshold analyze_particles filter convert
  duplicate undo redo run_macro confirm`) need `approved:true` and `expectedContext` equal to the current `contextKey`
  (same rule as Fiji); a view operation that sends an `expectedContext` is checked too. Reads never are.
- `contextKey` = SHA-256 over the focused document's id, title and plane position (c,z,t), its active ROI (type, bounds,
  vertex count), its ROI-list size, the Results row count, the **history size** and the list of open documents (id/title).
  Every completed change therefore changes it; results carry the new `contextKey`.

## Plans and confirmation (the Fidelis contract, kept)

Fidelis's rule is that language never runs anything without the user's confirmation; Penny's rule is "act immediately".
Every operation that reaches the engine is a **plan**: an ordered list of complete `ActionRequest`s built up front. The
bridge resolves the two rules with Fidelis's own `ConfirmationPolicy.assess` on the plan as a READY `Proposal` (with per
step slot provenance), using its value-aware constructor `ConfirmationPolicy(engine::schema, engine::globals)`:

- **Not explicit** (AC0–AC3 typed commands, DIRECT view/selection/threshold/undo steps, open): the plan is shown in the
  workspace card (`PennyUi.showPlan(plan, true)`, then `planProgress` per step and `planFinished`) and **runs at once**
  (`-Dfidelis.penny.confirm=auto`, the default, Ayush's decision of 17 Oct 2026; the policy's `required` flag is not used).
- **Explicit** (CODE/macros, AC4+ such as a legacy command without a catalogued schema, AC5 such as Set Measurements, AC6,
  a `black` value that differs from the current BlackBackground global, a consequential slot filled only from a bare
  catalogue default): the card waits (`showPlan(plan, false)`) and the bridge answers
  `{status:"awaiting_confirmation", planId, operation, reasons[], steps[labels], macroPreview[], document, contextKey, note}`
  without running anything. `confirm` (gateway "yes"/"go ahead" route) or the workspace's *Confirm and run* runs it.
- `-Dfidelis.penny.confirm=always` makes every plan wait (the strict Fidelis behaviour); `context.confirmPolicy` reports it.
- Slot provenance: keys the gateway gave are USER; values an operation's documented recipe sets (segment_cells' σ 1, Otsu,
  the Analyze Particles reporting convention, filter defaults below) are DERIVED; catalogue defaults are DEFAULT, except an
  off-by-default flag, which is DERIVED when consequential (as `DefaultNlService` does). `black` is never filled from a
  default. So a `command` call that leaves a consequential number to its catalogue default is explicit.
- `black` (value-aware rule, added for the bridge): Convert to Mask on a single image takes its polarity from the global
  BlackBackground preference. segment_cells passes `black` = the **current** global, so the polarity is stated explicitly in
  history and in the exported macro (`setOption("BlackBackground", …)`) without changing the preference; the policy's
  original constructor (used by the NL service and the UI) still treats every `black` as a change.
- One plan waits at a time. It is discarded (card cleared with `clearPlan`) when another plan runs or replaces it, on
  `cancel_plan`, after 15 minutes, or when `confirm` finds the `contextKey` differs from the one it was planned against;
  a late `confirm` then answers 409 naming the plan and why. `confirm` needs `approved` + `expectedContext` like any change.
- Running: plans run one at a time on the bridge's own thread (`fidelis-penny-plan`, never the EDT); each step is awaited
  (up to 30 min, for an original Fiji dialog the user must answer) and the plan **stops at the first step the engine does
  not report COMPLETED**. The HTTP request waits up to 115 s; after that it answers 409 `{error, planId, running:true}` and
  the plan continues (later changes get 409 "still running" until it ends; `context.running` shows it).
- Completed result: `{status:"completed", planId, operation, confirmed?, …operation fields…, steps:[step], undoSteps,
  contextKey}`; `step` = `{actionId, label, commandId, outcome, durationMs, macroLines[]}` (`label` is the plan's label,
  `commandId` the engine's command id or DIRECT operation); `undoSteps` = steps that changed pixels/type (what `undo
  {steps}` would have to revert). Failure: 409 `{error, code, subcode, suggestions, state, planId, failedStep,
  failedLabel, completedSteps[], step?, note}`; `error` is the engine's `EngineError.message`, prefixed with
  "Step k of n (label) did not complete:" for multi-step plans. Nothing is reported as done unless the engine said
  COMPLETED; earlier completed steps stay in History.
- The workspace calls back through `sc.fidelis.penny.PennyPlans`: `confirm(planId)` → `CompletableFuture<Map>` (runs off the
  EDT, same checks as `confirm`, the plan keeps its NL origin and transcriptRef), `cancel(planId)`, `bridgeRunning()`.

## Operations

Results are JSON objects; every result that concerns something visible may include `highlight` (below). Ids: documents are
Fidelis `DocumentId` strings (`"doc-3"`); `imageId` is the IJ1 id. Commands are catalogue ids (`ij1:<menu path>>label`),
hard-coded ones listed in `sc.fidelis.penny.PennyCommands` and checked by `PennyCatalogueTest`. Operations that need an
image use the **focused** document (409 "No image is open/focused" otherwise).

| Operation | Kind | Arguments | Result |
|---|---|---|---|
| `context` | read | | `{contextKey, application:"Fidelis", version, adapter, engine:{state}, document:{id, imageId, title, width, height, channels, slices, frames, bitDepth, type, unit, pixelWidth, pixelHeight, path, changed, position:{channel,slice,frame}, displayRange:[min,max]}\|null, documents:[{id,title,focused}], roi:{type,bounds:{x,y,width,height},name?}\|null, counts:{rois, resultsRows, history}, lastStep:{actionId,label,origin,outcome}\|null, globals:{blackBackground, measurements, precision, …}, viewer:{magnification\|null, canvasScreen:{x,y,width,height}\|null}\|null, pending:{planId, operation, reasons, steps}\|null, running:{planId, operation}\|null, confirmPolicy}` |
| `images` | read | | `{documents:[{id,imageId,title,width,height,focused,changed}]}` |
| `open_path` | session | `path` (absolute, `~/` expanded; must be a file) | SESSION `OPEN` → `{opened, document, highlight}` |
| `activate_image` | view | `id` (doc id or IJ1 id) or `imageId` or `title` | `Engine.focus` (not a history step) → `{document, contextKey}` |
| `commands` | read | `query`, `limit?` (1–50, 10) | `Engine.search`, else a token scan of `catalogue()` → `{commands:[{commandId,label,menuPath,schemaComplete,consequence,parameters?:[{key,type,default,choices,consequential}]}]}` |
| `command` | data | `commandId`, `params?` | one COMMAND step; missing keys from the schema (provenance above); a command without a catalogued schema takes no params and may open its original dialog → `{step, resultsRowsAdded, document? (new image), globalsChanged?, highlight}` |
| `segment_cells` | data | `channel?`, `method?` (Otsu), `dark?` (default: not `dark` when the image shows an inverting LUT, else `dark`), `minSize?`, `maxSize?` (calibrated units unless `pixel:true`), `exclude?`, `watershed?` (true), `gaussian?` (true), `sigma?` (1) | on the focused document, no Duplicate: [SET_POSITION channel] → [8-bit if RGB/indexed colour] → Gaussian Blur σ → `SET_AUTO_THRESHOLD{method,dark}` → Convert to Mask (polarity = current BlackBackground, stated) → Fill Holes → [Watershed] → [Set Measurements area mean centroid bounding shape ∪ current, **only when Area is not recorded**] → Analyze Particles (size, circularity 0–1, show Nothing, display, clear, add; exclude/include false) → `{count, averageSize, totalArea, unit, rois, resultsRows, document, threshold:{method,dark}, watershed, highlight, steps, undoSteps}`. `count` = Results rows the engine attributed to the Analyze Particles action; the image becomes the mask |
| `measure` | data | `measurements?:[…]` (area, mean, min/max, standard, modal, centroid, center, perimeter, bounding, fit, shape, feret, integrated, median, skewness, kurtosis, area_fraction, stack), `scope:roi\|all\|rois` (`manager` = `rois`) | Set Measurements (current ∪ requested) **only when a requested column is not recorded** (AC5 → explicit), then `roi`: Measure (the selection, or the whole image without one); `all`: [Select None] Measure [restore the selection]; `rois`: one Measure per ROI-list entry (≤ 1000) then the selection restored → `{rows, totalRows, columns, lastRow, summary:{area:{min,max,mean,count}, …}, unit, scope, highlight}` (summary keys lower-case without '.') |
| `threshold` | data | `method?` (Otsu), `dark?` (as segment_cells), or `lower` + `upper` | `SET_AUTO_THRESHOLD` or `SET_THRESHOLD` → `{lower, upper, method, dark?, highlight}` (levels read back from the image) |
| `analyze_particles` | data | Analyze Particles schema keys (`size`, `circularity`, `show`, `display`, `exclude`, `clear`, `include`, `summarize`, `add`, …); aliases `minSize`, `maxSize`, `minCircularity`, `addToManager` | defaults show Nothing, display, clear, add → `{count, countedFrom, averageSize, totalArea, unit, rois, resultsRows, document, highlight}`; `count` is the Results rows of that action, or the ROI list when `display` is off and `add` on (a second run replaces the ROI list; verified) |
| `filter` | data | `name: gaussian\|median\|mean\|unsharp\|subtract_background\|enhance_contrast\|invert\|smooth\|sharpen\|find_edges`, `sigma?` (2), `radius?` (2; unsharp 1), `mask?` (0.6), `rolling?` (50), `light?`, `sliding?`, `saturated?` (0.35), `normalize?`, `equalize?`, `stack?` | the catalogue command (sharpen and find_edges have no catalogued options: they run at once through the engine's legacy route, which refuses any params, so their history step carries no `transcriptRef`, and keeps no undo snapshot, so `undo` answers 409 "nothing to undo" and `undoSteps` does not count them) → `{step, highlight}` |
| `convert` | data | `to: 8-bit\|16-bit\|32-bit\|RGB` | `ij1:Image>Type>…` → `{step, document}` |
| `duplicate` | data | `title?` | `ij1:Image>Duplicate...` (whole stack for stacks) → `{document, step, highlight}` |
| `select_roi` | view | `type: rectangle\|oval\|polygon\|none`, `x,y,width,height` or `points:[[x,y],…]` (image px), `name?` | `SET_ROI`/`CLEAR_ROI` → `{roi, highlight}` |
| `rois` | read | `limit?` (100), `offset?` | `Engine.rois(focused)` → `{document, total, rois:[{index,name,type,bounds}]}` |
| `results` | read | `limit?` (50, ≤ 500), `offset?` | `Engine.results()` → `{columns, rows:[{row, Label?, <column>: value}], total, offset}` (non-finite values as "NaN") |
| `object_stats` | read | | from the rows of the latest completed Analyze Particles (else all rows) → `{n, unit, source, area:{mean,median,min,max,count}, mean?, circularity?, perimeter?, integratedDensity?}` |
| `display` | view | `min?`, `max?`, `auto?` (from the image's raw min/max), `channel?` | `SET_DISPLAY_RANGE` → `{min, max, step}` |
| `stack` | view | `slice?`, `channel?`, `frame?` | `SET_POSITION` → `{position, step}` |
| `zoom` | view | `direction: in\|out`, `fit?`, `selection?` | `PennyUi.zoom(doc, "in"\|"out"\|"fit"\|"selection")` (not a history step) → `{zoom, document, viewer, highlight}` |
| `highlight` | view | `rois: all\|indices\|active`, `indices?`, `label?`, `limit?` (120) | `PennyUi.flash` + payload → `{highlight, total}`; 409 when there is nothing to show |
| `undo` / `redo` | data | `steps?` (1–20) | DIRECT `UNDO`/`REDO`, repeated, stopping at the first refusal → `{step, undoneActionId, undoneActionIds, document, highlight}` |
| `history` | read | `limit?` (20) | `Engine.history()` → `{total, steps:[{actionId,label,commandId,origin,provenance,outcome,transcriptRef,time,macroLines,note?}]}` |
| `macro` | read | | `Engine.exportMacro()` → `{macro, steps}` |
| `run_macro` | code | `code` (≤ 32000 characters, ImageJ macro language) | CODE `ijm`: the bridge submits it once without a token, the engine answers AWAITING_CONFIRMATION with its capability token and reason; the plan waits (always explicit) → `awaiting_confirmation`; after `confirm` → `{step, resultsRowsAdded, log, documentsAdded}` |
| `confirm` | data | `planId?` (default: the waiting plan) | runs the waiting plan → its result with `confirmed:true` |
| `cancel_plan` | view | `planId?` | `{cancelled, planId?}` |

Verified numbers (17 Oct 2026, macOS arm64, pinned Fiji 1.54p, FX-003 Blobs 256×254, uncalibrated): `segment_cells` with
defaults finds **67** objects, mean area **326.657 px²**, total 21886 px² (Gaussian σ1, Otsu not-dark because Blobs has an
inverting LUT, Watershed); measuring every ROI-list entry reproduces the mean area exactly; the macro Fidelis exports from
that voice session, replayed in plain ImageJ in the same JVM, gives 67 rows and 21886 px²; `minSize:20` gives 65. These are
not comparable with the J-12 baseline's 71 (a different pipeline: Subtract Background 50, σ2, Otsu dark, size ≥ 20).

## Highlight and the workspace (`sc.fidelis.penny.PennyUi`)

The bridge never draws; it calls the `PennyUi` hooks the workspace registers (`PennyUi.register(impl)`). With no UI
(tests) a no-op implementation is used. Highlights are drawn **inside the Fidelis canvas** (a glass layer over the hosted
IJ1 canvas), so they can never appear over other windows. The returned `highlight` payload keeps Penny's screen-geometry
shape (`{label, targets:[{x,y,width,height,shape}], pointer:{screenX,screenY}, viewer:{x,y,width,height}}`) for the
companion cursor, computed from the hosted canvas's screen location.

As implemented (`sc.fidelis.penny.Highlight`): ROI-list or selection bounds (rectangle/oval bounds, else the box around
the vertices, image pixels) go to `PennyUi.flash(doc, rects, ellipses, label)` (ellipses only when every ROI is an oval;
at most `limit`, default 120) and each one through `PennyUi.toScreen(doc, rect)`; `null` drops that target. The payload is
`{label (≤ 80 chars), total, shown, truncated, kind:"image", targets:[{x, y, width, height, shape:"rect"|"ellipse"}],
pointer:{screenX, screenY} (centre of the targets) or {}, viewer: PennyUi.canvasOnScreen(doc) as {x,y,width,height} or
null, coordinateSystem:"screen-top-left", flash, note?}`; targets are at least 8×8 points around their centre. Whole-image
results (open, filter, undo, measure without a selection) use the visible canvas as the single target, without a flash.
With no workspace registered (`PennyUi.NONE`, tests, smoke) `targets` is empty and `viewer` null. `context.viewer` reads
`PennyUi.magnification` and `canvasOnScreen`. All hooks are called from the bridge's threads (HTTP or plan thread), never
from the EDT; an exception in a hook is logged and ignored.

### Notes for the other integration parts (17 Oct 2026)

- Gateway: send `spoken`, `taskId` (becomes `transcriptRef`) and `utterance` with every `/execute`; handle
  `status:"awaiting_confirmation"` (speak the `reasons`, route "yes"/"go ahead" to `confirm` with the `planId`, "no" to
  `cancel_plan`); a 409 with `running:true` means the plan is still running in Fidelis. `undo {steps:n}` reverts n pixel
  steps (segment_cells reports `undoSteps`). The readiness `operations` list is in `/health` and `context.adapter`.
- Workspace: register a `PennyUi` at launch; wire *Confirm and run* / *Cancel* on a waiting card to
  `PennyPlans.confirm(planId)` / `PennyPlans.cancel(planId)`. Single-step view plans (select_roi, display, stack, undo) also
  produce a running card and `planFinished`; showing those briefly or not at all is the workspace's choice.
- Engine (for its owner): `CodeRunner.scan` reports `exec, eval, call, runMacro` for a plain `run("Invert");`, so the
  macro card's engine reason over-states the capabilities; the bridge passes the engine's wording through unchanged.

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
