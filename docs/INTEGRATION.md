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

## Java bridge in Fidelis (`sc.fidelis.penny`)

`sc.fidelis.penny.FidelisBridge` serves the contract inside the Fidelis JVM; `PennyOps` turns each operation into a plan
of complete engine requests submitted through `Engine.submit` (origin `NL_SPOKEN`/`NL_TYPED`, `transcriptRef` = the
gateway's `taskId`) and assesses it with Fidelis's `ConfirmationPolicy`. `Params` fills COMMAND parameters from the
catalogue schema, `Highlight` builds the screen payload through `PennyUi`, `PennyCommands` lists the catalogue ids, and
`PennyPlans` is what the workspace's Confirm and Cancel buttons call. `Bootstrap` starts the bridge after the engine is
registered and `Bootstrap.exit` stops it. The decisions taken while implementing it (Set Measurements only when a column
is missing, the value-aware `black` rule, `undo {steps}`, `undoSteps`, `/health` behind the token) are recorded in
[FIDELIS-PROTOCOL.md](FIDELIS-PROTOCOL.md).

### Starting Fidelis with the bridge on macOS

1. Once per checkout: Fiji at `.local/fiji/Fiji.app` (`python3 script/setup-fiji.py`; a git worktree under
   `.local/worktrees/` uses the main checkout's), and Penny's bridge token at `.local/state/bridge-token` (Penny's setup
   or `./script/run-fidelis.sh` creates it). For the tests: `python3 -I fidelis-app/tools/fetch_junit.py`.
2. Build: `python3 -I fidelis-app/tools/build.py` (`--no-tests` for the app only).
3. Run: `python3 -I fidelis-app/tools/run.py --isolated-home`. The bridge starts by itself when the token is found
   (walking up from the jar, `-Dfidelis.repo.dir` and the working directory, so a worktree finds the main checkout's
   token) and logs `[fidelis-penny] bridge listening on 127.0.0.1:49558 (confirm=auto)`. Penny's launcher
   `./script/run-fidelis.sh` does the same with `--prop luma.root=<checkout> --prop fidelis.penny=true` and points the
   gateway at it.
4. Check it: `curl -s -H "Authorization: Bearer $(cat .local/state/bridge-token)" http://127.0.0.1:49558/health`
   (requests without the token get 401, requests with an `Origin` header 403).
5. Smoke with the bridge, without a window: `python3 -I fidelis-app/tools/run.py --smoke --isolated-home --prop
   fidelis.penny=true --prop fidelis.penny.port=49568` prints `[fidelis] penny smoke: … self-check OK`.

| Setting (`run.py --prop KEY=VALUE`) | Effect |
|---|---|
| `fidelis.penny=true` / `false` | `true`: log why when no token is found (the bridge starts whenever a token is found); `false`: never start |
| `fidelis.penny.port=N` | listen on `127.0.0.1:N` (default 49558; the stand-alone Fiji bridge uses 49557, QuPath 49556, the gateway 49555) |
| `fidelis.penny.confirm=always` | every plan waits for confirmation (default `auto`: only explicit plans wait) |
| `luma.root=<dir>` (or `LUMA_ROOT`) | where `.local/state/bridge-token` is read; `LUMA_BRIDGE_TOKEN` (32+ characters) overrides the file |

Only one Fidelis JVM per checkout (and per port) at a time.

### Verification status (bridge, 17 Oct 2026, macOS arm64)

- Engine changes for review (Arnav), all opt-in (requests without the new keys behave as before):
  `DefaultEngine.runOp` refuses a request whose reserved `expectedHistorySize` no longer matches the history
  (PRECONDITION_FAILED, `STATE_CHANGED`) and `Options.RESERVED` lists the key; `CommandRunner.runLegacy` ignores
  reserved keys instead of refusing them; `DirectOps` applies an explicit TargetScope plane to threshold/display
  operations and accepts `ROI_LIST_ADD {fromDocument, replace}` (copy another document's ROI list).
- segment_cells now works on a duplicate (`<title>-mask`) and copies the objects to the original's ROI list, so the
  original keeps its pixels and `measure {scope:"rois"}` reads the real signal (the gateway's request; it matches
  stand-alone Penny Fiji). `context.document.binary` is true for a mask.
- Implemented: the 28 operations of the contract plus `dialog` and `dialog_action` (any catalogue command through Penny,
  its original Fiji dialog filled or cancelled through the bridge).
- Protocol-tested (`python3 -I fidelis-app/tools/test.py --select-class …`, JUnit in a live Fiji JVM with the product
  engine): `sc.fidelis.penny.FidelisBridgeTest` (10: token, Origin, approval, context binding, contextKey, plans and
  workspace hooks, failures, legacy Sharpen and its refused undo, particle counts with display on/off, macro
  confirmation, discarded plans, `confirm=always`, highlight geometry),
  `PennyCatalogueTest` (5: every hard-coded catalogue id with its schema, recipe provenance, explicit `true` defaults,
  the value-aware `black` rule, operation names), `FidelisBridgeJourneyTest` (6: the Blobs voice journey over HTTP; the
  exported macro replayed in plain ImageJ gives the same 67 objects and 21886 px²;  segment_cells leaves the original untouched and makes a binary mask; Li after Otsu matches Li on a
  fresh copy, 68; per-ROI
  measurements on a 3-slice stack use each ROI's plane, 62.0 and 162.0 as read from the pixels; a second Analyze Particles
  replaces the ROI list), `FidelisCatalogueWalkTest` (3: 36 catalogue commands across File/Edit/Image/Process/Analyze/
  Plugins complete, stop at their dialog and cancel cleanly, or are refused with a reason, none hangs; Variance… filled and
  pressed OK through `dialog_action`; `commands` finds TrackMate, Bio-Formats Importer, Coloc 2, 3D Objects Counter,
  Skeletonize, Directionality and Analyze Skeleton). The four classes together: 28 tests, 0 failed (FidelisBridgeTest
  includes the execution-binding test, a workspace action queued before step 3 stops the plan there, and the
  competing-analysis test, a workspace Analyze Particles right after Penny's stops the plan before it copies objects). With the NL, UI-confirmation
  and speech-to-execution classes in the same run: 297 tests, 0 failed (4 skipped: the Whisper interpreter is not
  installed on this Mac). Full suite (`test.py`, all classes, before the last two bridge fixes): 412 tests, 6 failed, none
  in or using the bridge: `EngineNativeSyncTest` ×3 (known, engine work in progress), `MacroReplayTest` ×2
  (`verification/reference_runner.py` looks only for Windows `.exe`/`.bat` launchers) and
  `InteractiveJourneyTest` ×1 (Subtract Background's `separate` field); the last two run now that the FX fixtures are
  cached.
- Real-app-tested: the full workspace started with `run.py` and the bridge on a spare port, driven over HTTP the way the
  gateway does (context, then the approved change bound to it): open Blobs, `segment_cells` 67 objects (mean 326.657 px²,
  0.19 s), whole-image measure, `run_macro` waiting then confirmed, `undo`, history showing `NL_SPOKEN` steps with the
  task id. Smoke with and without `fidelis.penny=true` through `Bootstrap`.
- Review: the repository's autoreview helper (Codex, branch diff against `integration/fidelis-penny`). The first run was
  a reviewer failure (its one finding used an absolute path and was discarded; no review produced). The second reported
  11 findings (3 P1: context check before plan construction, ImageJ statistics mutating the live processor's ROI, per-ROI
  plane; 8 P2: atomic confirm, keeping the bridge busy until the engine ends, failure wording, highlight with `add:false`,
  `true` defaults, `display auto` channel, `undoSteps` as an upper bound, `object_stats` units); all were checked against
  the code, accepted and fixed with tests. The third reported 5 (3 P1: segmentation replacement must check the undo
  history, the context binding must hold at execution, reads must not call ImageJ's side-effecting getters; 2 P2: ROI
  planes resolved against the starting plane, `object_stats` with an empty latest population); all accepted and fixed
  (pre-step foreign-operation wait and history check, plane pinning, `LiveRead`, a replacement allow-list later superseded
  when segment_cells moved to a duplicate). The fourth
  reported 5 (2 P1: the approval must hold when the engine dequeues a step, and the history cursor must be taken before
  submission; 3 P2: dialog answers bound to their dialog, the cancel marker set before the press, the smoke exit status);
  all accepted and fixed, the first with an opt-in engine check (`expectedHistorySize`, subcode `STATE_CHANGED`) that
  the engine owner should review. The fifth reported 6 (3 P1: the step baseline taken before validation, undo
  snapshot identities for the automatic replacement, plane binding of threshold/display steps; 3 P2: statistics after a
  competing action, failures without a record, queued EDT edits after a timeout); all accepted and fixed (the
  replacement finding was resolved by segmenting a duplicate, which needs no undo at all). The sixth reported 5: the
  selection before duplicating (P1), mask-ROI planes, EDT task start tracking and results while foreign work is still
  active (P2) were fixed; the remaining P1 (a native canvas selection edit recorded 250 ms late can slip past the
  execution binding) is documented as a residual needing an engine-level selection fingerprint.
- Not yet tested: with the workspace's `PennyUi` implementation (screen targets are empty until it is registered), with
  the gateway and a voice turn end to end, and `segment_cells` on stacks or multichannel images.
