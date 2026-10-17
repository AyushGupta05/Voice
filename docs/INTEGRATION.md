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

| Item | Value |
|---|---|
| Profile files | `apps/fidelis.ts` (routes, sentences, readiness, confirm binding), `apps/fidelis-tools.ts` (schemas), `apps/fidelis-instructions.ts` (model instructions) |
| Bridge | `http://127.0.0.1:49558`, token `.local/state/bridge-token` (shared with QuPath and Fiji) |
| Tools | 31: `fidelis_<operation>` for each of the 28 contract operations, plus local `fidelis_knowledge`, `fidelis_find_images`, `fidelis_action_status` ([reference](TOOL-REFERENCE-FIDELIS.md), [registry](tool-schemas-fidelis.json)) |
| Risks | contract kind `read`/`view`/`data`/`code` unchanged; `session` (`open_path`) takes the `data` decision path |
| Guide and knowledge | [skills/fidelis/SKILL.md](../skills/fidelis/SKILL.md) (also the MCP guide resource) and `.local/upstream/imagej-docs` |
| Launcher | `./script/run-fidelis.sh` (the `launcherHint` in the gateway's `/health`) |
| Bundle id | `com.azul.zulu.java`, the identifier embedded in the Zulu `java` launcher of Fiji's bundled JDK that runs Fidelis (`LUMA_FIDELIS_BUNDLE_ID` overrides). The companion uses it to find the windows holding the canvas; it is shared with any other Zulu `java` GUI process and not yet confirmed on a live run. |
| Desktop tools | none: Fidelis has no `ui`/`ui_action` operations and its dialogs are the workspace's own |

### What the bridge receives

Every call is `POST /execute` with `Authorization: Bearer <token>` and exactly the contract body:

```json
{"operation": "segment_cells", "arguments": {}, "expectedContext": "k1", "approved": true,
 "spoken": true, "taskId": "4c0b…", "utterance": "Find the nuclei."}
```

- `data`, `code` and `session` operations carry `approved: true` and `expectedContext`, the `contextKey` observed when
  the action was proposed (the same rule as Fiji). View operations carry `expectedContext` only inside a conversation
  task; reads carry neither. `expectedControl` is never sent to Fidelis.
- `spoken`, `taskId` and `utterance` are present for every call made by a conversation task, read calls included, and
  absent for direct `/tools/call` and MCP calls. `spoken` is true for voice and false for typed requests. `taskId` is the
  gateway's task UUID, the same value as `taskId` on the `/events` `tool`, `text_ack` and `text_result` events, so a
  History step's `transcriptRef` can be matched to the conversation. `utterance` is the latest user utterance of the task,
  trimmed and at most 4000 characters.
- Under `LUMA_APPROVALS=review` the three fields are captured when the action is proposed and sent when the researcher
  decides it, so the History step still names its task. They are not written to the audit journal.

The envelope comes from two async-local stores in `packages/gateway/src/tool-request.ts` (`toolTask`, as before, and
`toolUtterance`), set by the text/voice conversation and the model backend. A profile opts in with
`conversationEnvelope: true`; QuPath and Fiji bodies are unchanged.

### Confirmation

A result `{status: "awaiting_confirmation", planId, reasons, steps}` is never reported as done:

- The spoken answer is local and immediate: "Ready to run N steps; say yes to go ahead." (N = `steps.length`).
- The request stops there. Even "invert it with a macro and then measure" does not continue to the measurement; the
  model gets no further round for that request.
- With `context.pending` set, a whole-utterance "yes", "yes please", "go ahead", "do it", "run it" or "confirm" runs
  `fidelis_confirm {planId}` at once, and "no", "cancel", "stop that", "don't run it", "never mind" runs
  `fidelis_cancel_plan`. Without a pending plan these words go to the model. A bare "okay" never confirms.
- `fidelis_confirm` is bound to the plan observed at dispatch: the gateway fills `planId` from `context.pending.planId`,
  refuses with "Nothing is waiting for confirmation." when there is none, and refuses a `planId` that is no longer
  pending. The confirm result is spoken from what the plan returned (a count, measurement, threshold or step).
- Acknowledgments never claim a waiting plan ran (`fidelis_run_macro` says "Preparing the macro.").

### Instant routes (no model round)

| Said (whole utterance) | Tool |
|---|---|
| "yes / go ahead / confirm" with a pending plan | `fidelis_confirm {planId}` |
| "no / cancel / stop that" with a pending plan | `fidelis_cancel_plan` |
| "open the X image" | the focused or open document if it matches (`fidelis_context`, `fidelis_activate_image`), else `fidelis_find_images` then `fidelis_open_path {path}` |
| "what am I looking at" | answered from `context.document` |
| "find / segment / count the nuclei" | `fidelis_segment_cells {}` |
| "stricter / looser" | `fidelis_segment_cells {method: "Li" / "Huang"}` |
| "zoom in / out", "fit to window", "zoom to the selection" | `fidelis_zoom` |
| "how many (nuclei) are there" | `fidelis_rois {limit: 1}` (or `fidelis_object_stats` when the context has no ROI count) |
| "show me the nuclei" (ROIs present) | `fidelis_highlight {rois: "all"}` |
| "mean intensity of the nuclei" (ROIs present) | `fidelis_measure {scope: "rois"}` |
| "brighter / darker" | `fidelis_display {min, max}` from the live display range, when the context reports one |
| "auto contrast" | `fidelis_display {auto: true}` |
| "next / previous slice" | `fidelis_stack {slice}`, when the context reports the current position |
| "undo / go back", "redo" | `fidelis_undo`, `fidelis_redo` |

Data routes run instantly only under the default `LUMA_APPROVALS=auto`; under `review` they go to the model.

### Readiness

The Fidelis context carries no adapter identity and Fidelis has no separate bridge artifact, so readiness reads the
bridge's own `GET /health` (sent with the bearer token) alongside every `context` call and compares
`adapter.operations` with the 28 operations the tools need. `adapterReadiness.status` is `operations_match` when all are
declared (no build-identity comparison: a complete but stale build passes), `restart_required` with
`missingOperations` otherwise, `build_unverified` for a protocol other than 1 or a malformed list, and `unavailable`
when `/health` cannot be read. `npm run doctor` with `LUMA_APP=fidelis` reads the bridge's `/health` directly and checks
`fidelis-app/build/fidelis-app.jar` and `.local/fiji/Fiji.app/jars`.

### Launcher

`./script/run-fidelis.sh [Fidelis args]` refuses while any Fidelis JVM runs (`java @…/fidelis-app/build/run.args`: this
checkout's, reported and left alone, or another worktree's, which would hold the same port) or while port 49558 is
taken; creates the bridge token if missing; builds with `python3 -I fidelis-app/tools/build.py --no-tests`
(`FIDELIS_SKIP_BUILD=1` skips it); writes `.local/state/app = fidelis`; exports `LUMA_ROOT`; and starts
`python3 -I fidelis-app/tools/run.py --isolated-home --prop luma.root=<checkout> --prop fidelis.penny=true -- …` in
the background, logging build and run output to `.local/fidelis.log`. `run.py --prop KEY=VALUE` (repeatable) is the new
JVM system-property pass-through; it behaves the same on Windows and macOS.

### Contract details the gateway relies on

The bridge should match these readings of [FIDELIS-PROTOCOL.md](FIDELIS-PROTOCOL.md):

1. `GET /health` declares exactly the operation names of the table, including `undo`, `redo` and `cancel_plan`
   (at most 64 names, each `^[a-z][a-z0-9_]{0,63}$`), with `protocolVersion: 1`, and accepts a bearer header.
2. `measure.scope` is required (`roi`, `all` or `rois`); `zoom.direction` is optional when `fit` or `selection` is given;
   `highlight.rois` is `all` or `indices`; `select_roi.points` is `[[x, y], …]` (polygon, 3 or more points);
   `activate_image.id` is a `"doc-N"` string; `analyze_particles` takes the catalogue keys (`size` and `circularity` as
   `"min-max"` strings, `show`, and the booleans `pixel`, `display`, `exclude`, `clear`, `include`, `summarize`,
   `overlay`, `add`, `composite`); `measure.measurements` are Set Measurements keys (`area`, `mean`, `min`, `integrated`,
   `shape`, `feret's`, …).
3. `measure` with scope `rois`: `rows` is the number of rows this call measured, or `summary.<column>.count` says so;
   the summary covers only those rows. Column names are ImageJ's (`Mean`, `Area`), matched case-insensitively.
4. An explicit plan answers HTTP 200 `{result: {status: "awaiting_confirmation", planId, reasons, steps}}` even though the
   request carried `approved: true`; `steps` is an array. While it waits, `context.pending` holds `{planId, reasons}` and
   `contextKey` may change. `confirm` with a `planId` that is not pending answers 409.
5. Optional context fields the instant routes use when present: `document.displayRange: [min, max]` (or
   `document.display: {min, max}`) for "brighter/darker", and `document.position: {channel, slice, frame}` for
   "next/previous slice". Without them those phrases go to the model.
6. Results speak only what the bridge reports: `segment_cells`/`analyze_particles` `count`, `averageSize`, `unit`;
   `threshold` `lower`, `upper`, `method`; `undo`/`redo`/`command` `step.label`; `open_path` `document.{title,width,height}`;
   `rois` `total`; `object_stats` `n`, `area.{mean,median}`, `unit`.

### Verification status

- Implemented and protocol-tested against a loopback fake bridge (`packages/gateway/test/fidelis.test.ts`): schemas
  against the contract table, the request body and envelope (auto and review policies), readiness from `/health`,
  instant routes, local sentences, the awaiting-confirmation stop, "yes"/"no" handling and the confirm binding.
- Not yet tested with the real Fidelis bridge, which is being written in parallel; no voice run against Fidelis yet.
