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

How the three integration parts implement [FIDELIS-PROTOCOL.md](FIDELIS-PROTOCOL.md): the Java bridge inside Fidelis
(`sc.fidelis.penny`), Penny's gateway profile (`LUMA_APP=fidelis`) and the workspace voice surface
(`sc.fidelis.penny.ui`). Each part keeps its own section.

## Gateway profile

`LUMA_APP=fidelis` selects the profile in `packages/gateway/src/apps/fidelis.ts`. Without the variable, a gateway
started with `LUMA_ROOT` reads `.local/state/app`, which `./script/run-fidelis.sh` writes as `fidelis`. Everything else
in the gateway (runtime, decisions and audit, voice, text, MCP, knowledge, readiness) is shared with QuPath and Fiji.
It is coded to the bridge's revised contract of 17 October (undo `steps`, `cancel_plan {planId}`, segment `replace`,
`context.adapter`, `displayRange` and `position`) plus the integrator's `dialog` / `dialog_action` operations.

| Item | Value |
|---|---|
| Profile files | `apps/fidelis.ts` (routes, sentences, confirm binding), `apps/fidelis-tools.ts` (schemas), `apps/fidelis-instructions.ts` (model instructions) |
| Bridge | `http://127.0.0.1:49558`, token `.local/state/bridge-token` (shared with QuPath and Fiji), bearer on every request including `/health` |
| Tools | 33: `fidelis_<operation>` for each of the 30 operations (the 28 of the contract table plus `dialog` and `dialog_action`), and local `fidelis_knowledge`, `fidelis_find_images`, `fidelis_action_status` ([reference](TOOL-REFERENCE-FIDELIS.md), [registry](tool-schemas-fidelis.json)) |
| Risks | contract kind `read`/`view`/`data`/`code` unchanged; `session` (`open_path`) takes the `data` decision path; `dialog` is read, `dialog_action` data |
| Guide and knowledge | [skills/fidelis/SKILL.md](../skills/fidelis/SKILL.md) (also the MCP guide resource) and `.local/upstream/imagej-docs` |
| Launcher | `./script/run-fidelis.sh` (the `launcherHint` in the gateway's `/health`) |
| Bundle id | `com.azul.zulu.java`, the identifier embedded in the Zulu `java` launcher of Fiji's bundled JDK that runs Fidelis (`LUMA_FIDELIS_BUNDLE_ID` overrides). The companion uses it to find the windows holding the canvas; it is shared with any other Zulu `java` GUI process and not yet confirmed on a live run. |
| Desktop tools | none: Fidelis's dialogs are reached through `dialog` / `dialog_action` |

### What the bridge receives

Every call is `POST /execute` with `Authorization: Bearer <token>` and exactly the contract body:

```json
{"operation": "segment_cells", "arguments": {}, "expectedContext": "k1", "approved": true,
 "spoken": true, "taskId": "4c0b…", "utterance": "Find the nuclei."}
```

- `data`, `code` and `session` operations carry `approved: true` and `expectedContext`, the `contextKey` observed when
  the action was proposed. View operations carry `expectedContext` inside a model round and on every instant route
  (below); reads carry neither. `expectedControl` is never sent to Fidelis.
- `spoken`, `taskId` and `utterance` are present for every call made by a conversation task, read calls included, and
  absent for direct `/tools/call` and MCP calls. `spoken` is true for voice and false for typed requests. `taskId` is the
  gateway's task UUID, the same value as `taskId` on the `/events` `tool`, `text_ack` and `text_result` events, so a
  History step's `transcriptRef` can be matched to the conversation. `utterance` is the latest user utterance of the
  task, trimmed and at most 4000 characters.
- Under `LUMA_APPROVALS=review` the three fields are captured when the action is proposed and sent when the researcher
  decides it, and the resulting `tool` event keeps its `taskId`. They are not written to the audit journal.

The envelope comes from two async-local stores in `packages/gateway/src/tool-request.ts` (`toolTask`, as before, and
`toolUtterance`), set by the text/voice conversation and the model backend. A profile opts in with
`conversationEnvelope: true`; QuPath and Fiji bodies are unchanged.

### Confirmation

A result `{status: "awaiting_confirmation", planId, reasons, steps}` is never reported as done:

- The spoken answer is local and immediate: "Ready to run N steps; say yes to go ahead." (N = `steps.length`).
- The request stops there, spoken or typed. Even "invert it with a macro and then measure" does not continue to the
  measurement: later calls in the same model response are cancelled before dispatch and the model gets no further
  round for that request (the profile's `pausesRequest` hook).
- The gateway's decision record keeps `status: "completed"` (the request itself completed) and adds
  `outcome: "awaiting_confirmation"` to the audit journal, `fidelis_action_status` and the tool envelope, so no reader
  reports the plan as run. Under `LUMA_APPROVALS=review` the "Running it now." after approval is skipped when the plan
  waits. Acknowledgments never claim a waiting plan ran (`fidelis_run_macro` says "Preparing the macro.").
- With `context.pending` set, a whole-utterance "yes", "yes please", "go ahead", "do it", "run it" or "confirm" runs
  `fidelis_confirm {planId}` at once, and "no", "cancel", "stop that", "don't run it", "never mind" runs
  `fidelis_cancel_plan {planId}`. Without a pending plan these words go to the model. A bare "okay" never confirms.
- Only the researcher confirms: `fidelis_confirm` is refused ("Only the researcher can confirm this plan: say "yes" or
  press Confirm in Fidelis.") unless the latest words of the conversation task are, as a whole, an assent in the same
  grammar as the instant route ("yes", "yes please", "okay, go ahead, run it", "confirm"); "yes, if…" or "confirm
  whether…" are not. Neither the model on its own nor a direct `/tools/call` or MCP client can confirm. A typed "yes"
  reaches the model, which may then confirm; the Fidelis card's own Confirm never passes through Penny.
- Spoken "stop", "cancel" or "cancel that" is consumed by the voice transport as barge-in; for Fidelis
  (`controlRoutes`) it is then offered to the instant routes only, so it cancels a waiting plan and otherwise does
  nothing more (it never reaches the model).
- The assent belongs to the plan that was pending when those words arrived (the request's first observation): if
  another plan replaced it since, the confirm is refused ("The plan changed after you said yes; check the new plan and
  say yes again."). The gateway fills `planId` from that plan and refuses one that is no longer pending, or "Nothing is
  waiting for confirmation." The confirm result is spoken from what the plan returned (a count, measurement, threshold
  or step); a single confirmed measurement names no place, since the gateway cannot tell `roi` from `all`.

### Original Fiji dialogs

A legacy command whose original dialog opens answers `{status: "dialog", dialog: {title, fields, buttons}}`. That is
not an outcome: no local sentence is spoken, the request goes back to the model, which fills the fields by key and
presses a button with `fidelis_dialog_action {fields, press}` (default "OK"; `fidelis_dialog` reads a waiting dialog),
and the request finishes from the command's result ("Ran Gaussian Blur."). The same holds when a confirmed plan or
another instant route opens a dialog: the profile's `needsModel` hook sends the request on to the model with what the
step returned, instead of finishing as "Done.". The instructions say any Fiji menu command
or plugin is available through `fidelis_commands` (by name or menu path) and `fidelis_command`, never to leave a dialog
open, and `fidelis_run_macro` (always confirmed) for anything else.

### Instant routes (no model round)

| Said (whole utterance) | Tool |
|---|---|
| "yes / go ahead / confirm" with a pending plan | `fidelis_confirm {planId}` |
| "no / cancel / stop that" with a pending plan | `fidelis_cancel_plan {planId}` |
| "open the X image" | the focused or open document if it matches (`fidelis_context`, `fidelis_activate_image`), else `fidelis_find_images` then `fidelis_open_path {path}` |
| "what am I looking at" | answered from `context.document` |
| "find / segment / count the nuclei" | `fidelis_segment_cells {}` (a repeat replaces the previous segmentation) |
| "stricter / looser" | `fidelis_segment_cells {method: "Li" / "Huang"}` |
| "zoom in / out", "fit to window", "zoom to the selection" | `fidelis_zoom` |
| "how many (nuclei) are there" | `fidelis_rois {limit: 1}` (or `fidelis_object_stats` when the context has no ROI count); an empty list is answered "0 objects in the ROI list." |
| "show me the nuclei" (ROIs present) | `fidelis_highlight {rois: "all"}` |
| "brighter / darker" | `fidelis_display {min, max}` from `document.displayRange` |
| "auto contrast" | `fidelis_display {auto: true}` |
| "next / previous slice" | `fidelis_stack {slice}` from `document.position` |
| "undo / go back", "undo the last N steps", "redo" | `fidelis_undo {}`, `fidelis_undo {steps: N}` (≤ 20), `fidelis_redo` |

Data routes run instantly only under the default `LUMA_APPROVALS=auto`; under `review` they go to the model. Every
Fidelis instant route is bound to the context it was chosen from (`bindInstantRoutes`): if the `contextKey` changed
before dispatch (another plan became pending, the focus moved), the route is refused with "Fidelis changed before that
ran; nothing was done. Say it again.", and view routes carry `expectedContext`. There is no instant "mean intensity of
the nuclei": after `segment_cells` the focused document is its mask (below).

### Readiness

`adapterReadiness` compares the operations the running bridge declares with the 30 the tools need: from
`context.adapter.operations`, or the bridge's `GET /health` when the context has none. The status is `operations_match`
when all are declared (no build-identity comparison: a complete but stale build passes), `restart_required` with
`missingOperations` otherwise (a bridge without `dialog`/`dialog_action` reports those two), `build_unverified` for a
protocol other than 1 or a malformed list, and `unavailable` when neither can be read. `npm run doctor` with
`LUMA_APP=fidelis` does the same and checks `fidelis-app/build/fidelis-app.jar` and `.local/fiji/Fiji.app/jars`.

### Launcher

`./script/run-fidelis.sh [Fidelis args]` refuses while any Fidelis JVM runs (`java @…/fidelis-app/build/run.args`,
paths with spaces included: this checkout's is reported and left alone, another worktree's would hold the same port) or
while port 49558 is taken; creates the bridge token if missing; builds with
`python3 -I fidelis-app/tools/build.py --no-tests` (`FIDELIS_SKIP_BUILD=1` skips it); writes
`.local/state/app = fidelis`; exports `LUMA_ROOT`; and starts
`python3 -I fidelis-app/tools/run.py --isolated-home --prop luma.root=<checkout> --prop fidelis.penny=true -- …` in the
background, logging build and run output to `.local/fidelis.log`. `run.py --prop KEY=VALUE` (repeatable) is the new
JVM system-property pass-through; it behaves the same on Windows and macOS.

### Companion sockets

The gateway's `/events` socket now attaches a client for desktop (Accessibility) requests only when its `hello` does
not say `desktop: false`, so the Fidelis workspace (`{type: "hello", clientId: "fidelis-…", client: "fidelis-workspace",
desktop: false}`) connecting after the Swift companion never takes desktop control from it; a socket without a hello is
not attached either. One client holds voice at a time; a second gets "Voice is already active in the Luma companion;
stop it there first." (or "in the Fidelis workspace").

### What the bridge could still add

1. `document.binary` (from `ImageProcessor.isBinary()`) in `context`. After `segment_cells` the focused document is its
   mask, so a `measure` of the ROIs reads mask values, not intensities. The gateway speaks a measured mean as an
   intensity locally only when `document.binary` is `false`; without the field (or with a uniform 0/255 ROI mean) the
   model phrases the answer and is told never to report a mask's Mean as an intensity.
2. Measuring object intensities on the original pixels after segmenting (for example Set Measurements redirected to a
   kept original), so "mean intensity of the nuclei" can become an instant route again.
3. `dialog` and `dialog_action` rows in the contract table, with `{status: "dialog", dialog}` documented on `command`.

### Verification status

- Implemented and protocol-tested against a loopback fake bridge (`packages/gateway/test/fidelis.test.ts`): schemas
  against the contract table, the request body and envelope (auto and review policies), readiness from
  `context.adapter` and `/health`, instant routes and their context binding, local sentences, the
  awaiting-confirmation stop, "yes"/"no" handling, the researcher-only and plan-bound confirm, and a command whose
  dialog is filled and pressed. Desktop-socket attachment and the voice-holder message are tested in
  `packages/gateway/test/server.test.ts`.
- Not yet tested with the real Fidelis bridge; no voice run against Fidelis yet.
