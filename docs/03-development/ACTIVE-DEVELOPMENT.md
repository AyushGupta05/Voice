# ACTIVE DEVELOPMENT — Fidelis workstation (persistent progress record)

Read this first after any interruption or context reset. Then read `git log --oneline -20` on the
integration branch and the agent worktrees listed below.

## Repositories and branches

| Role | Location |
|---|---|
| Canonical remote | `reverez/Voice` (public fork of `Sleep-On-It/Voice`; contains the full Fidelis project). Remote name `voice` in the local checkouts. |
| Preserved original | `Sleep-On-It/Fidelis`: do not modify. `Sleep-On-It/Voice`, `AyushGupta05/Voice` and Ayush's forks: never touch. |
| **Integration branch** | `development/fidelis-workstation` in worktree `C:\Users\arnav\Documents\dev\fidelis-dev` (from fork `main` @ `fa70497`) |
| Architecture-gate review docs | `review/architecture-gate` in `C:\Users\arnav\Documents\dev\fidelis-arch-gate` (lead) |
| Validation spikes | `spike/architecture-gate` in `C:\Users\arnav\Documents\dev\fidelis-spikes` (Sonnet spike agent, still running EXP-02…05; **do not touch**) |
| Prototype (fallback demo) | `backend/`, `frontend/`, `speech/` on every branch; unchanged |

## Architecture in force (implementation choice, recorded; DEC-010 remains formally "proposed")

**Hybrid B** from the architecture-gate review, confirmed by spike EXP-01 (PASS for strategy S3):

- One persistent, non-headless Fiji JVM; Fiji's own main window is never shown (A15 mode M2).
- An in-process engine service (`sc.fidelis.engine`) executes typed actions through
  `IJ.run(imp, label, completeOptions)` on one worker thread; success judged by postconditions.
- **Real IJ1 `ImageCanvas` per document**, hosted in the FlatLaf workspace: `HostCanvas extends
  ImageCanvas` with `canEnlarge()` returning null, kept inside a hidden `ImageWindow`; the host drives
  the viewport (`setDrawingSize`, `setSourceRect`, `setMagnification`, in that order) and forwards
  wheel events to the hidden window. IJ1's current image follows the focused document
  (`WindowManager.setCurrentWindow`). Native tools, ROI algebra, read-out and zoom are IJ1's own code.
- Native interactions are *observed* (`CommandListener`, `RoiListener`, digests) and recorded with
  provenance NATIVE; typed actions are TYPED; original dialogs are LEGACY_DIALOG; the rest UNRECORDABLE.
- Structured history is authoritative; `.ijm` export is generated from it (D07 rules).
- Speech: persistent faster-whisper worker (existing `speech/` code), transcript → deterministic
  command resolution → preview/confirm → the same engine.

Evidence: `fidelis-spikes/spikes/arch-gate/exp01-native-canvas/RESULT.md` (committed `ce7dbf6`),
scratchpad `ARCH-REVIEW.md` (to be committed under `docs/02-design/07-architecture-gate/`).

## Toolchain (M0, verified 2026-10-17)

No Maven/Gradle on this machine; the build uses the JDK bundled with the pinned Fiji.

```
python fidelis-app/tools/fetch_junit.py        # once: pinned JUnit console runner (sha256 b016ef6b…)
python fidelis-app/tools/build.py              # javac → build/classes, build/test-classes, build/fidelis-app.jar
python fidelis-app/tools/run.py --smoke --isolated-home   # engine self-test: real Invert verified, exit 0 (3.6 s)
python fidelis-app/tools/run.py --isolated-home            # launch the application
python fidelis-app/tools/test.py               # JUnit 5 in a live Fiji JVM; isolated user.home
```
Use the repo's `.venv` Python (`C:\Users\arnav\Documents\dev\click-to-batch\.venv\Scripts\python.exe -I`).
JVM flags mirror Fiji's launcher dry-run (`tools/fijienv.py`); updater autocheck is always disabled.

## Shared contract (Section 6 of the mission) — `sc.fidelis.engine.api`

`Engine` (facade), `ActionRequest` (+ `Operations`), `ActionResult` (+ `EngineError`, `StateDigest`),
`CommandSchema`, `DocumentId`/`DocumentInfo`/`TargetScope`, `RoiGeometry`, `HistoryRecord`,
`MeasurementRow`, `EngineEvent` (sealed), `EngineListener`, `Enums`. **Changes need lead review** and
a line in the "Contract changes" section below.

## Agent team and file ownership

| Agent | Model | Worktree / branch | Owns (write) | Reads |
|---|---|---|---|---|
| Lead (integration) | Fable 5.1 | `fidelis-dev` / `development/fidelis-workstation` | `docs/03-development/`, `fidelis-app/tools/`, `engine/api`, merges | everything |
| A1 Fiji Engine | Sonnet high | `fidelis-agent-engine` / `agent/engine` | `fidelis-app/src/main/java/sc/fidelis/engine/**` (except `api`), `src/main/resources/sc/fidelis/engine/**` | api, docs A08–A15, D04–D07 |
| A2 Desktop UI | Sonnet high | `fidelis-agent-ui` / `agent/ui` | `fidelis-app/src/main/java/sc/fidelis/ui/**`, `src/main/resources/sc/fidelis/ui/**` | api, D11–D15, EXP-01 |
| A3 Voice & NL | Sonnet high | `fidelis-agent-voice` / `agent/voice` | `fidelis-app/src/main/java/sc/fidelis/{speech,nl}/**`, `speech/` (worker protocol only) | api, A16–A17, D08 |
| A4 Verification | Sonnet high | `fidelis-agent-verify` / `agent/verify` | `fidelis-app/src/test/**`, `verification/**`, `fixtures/manifest.*` | everything |

Rules: never write outside your ownership; never run two Fiji JVMs from the same worktree at once;
always `--isolated-home` / test.py; never modify `C:\Users\arnav\Documents\dev\Fiji`; commit on your
own branch only; the lead merges.

## Active agents (started 2026-10-17 ~12:20, all Sonnet 5.5 high effort, background)

| Agent | Status | Branch | Task |
|---|---|---|---|
| A1 Engine | running | `agent/engine` | E1–E7: DefaultEngine, catalogue/schemas, R1 execution with postconditions and dialog watch, observation, ROI Manager singleton bridge, globals, snapshot undo, history/macro export, engine tests |
| A2 UI | running | `agent/ui` | Workspace per D12 W-1 with hosted real IJ1 canvases (EXP-01 S3), Fiji menu bar from `Menus.getMenuBar()`, tool palette, command bar + proposal sheet, parameters/ROI/results/history/log/activity panels, status read-out, themes, `--screenshot` flag, UI test |
| A3 Voice/NL | **merged** 13:05 (7f046e0, 2b42cdc, db9093b) | `agent/voice` | `speech/server.py` persistent worker + `WhisperSpeechService`; `DefaultNlService` (grammar + catalogue retrieval + deterministic validator/policy); eval set ≥30 cases; tests |
| A4 Verify | running | `agent/verify` | fixtures manifest + cache (blobs, OME-TIFF, BBBC metadata), independent reference runner + comparators, J-12 baselines, parity tests, GUI smoke harness, VERIFICATION-RESULTS.md |
| Spike (prior session) | **finished** 12:50 | `spike/architecture-gate` (pushed) | EXP-01…05 PASS/INCONCLUSIVE per `spikes/arch-gate/README.md`; EXP-06 script only (NOT RUN) |

Shared brief: scratchpad `DEV-BRIEF.md` (copied to `docs/03-development/DEV-BRIEF.md` at the next integration).

## Milestones

| Milestone | Status | Evidence |
|---|---|---|
| M0 Integration baseline (branch, toolchain, contract, launch) | **Done** 2026-10-17 | build OK; smoke OK; 2 JUnit tests pass |
| M1 Real Fiji execution (engine) | Not started | |
| M2 Modern workspace (hosted canvas, menus, panels) | Not started | |
| M3 ROIs and results | Not started | |
| M4 Macros and history | Not started | |
| M5 Speech and NL | **Core done** (services + tests); UI wiring pending (M2) | 129 tests pass on the integration branch; eval 103/103, 0 unsafe; fixture transcribes; live microphone unverified (needs a person) |
| M6 End-to-end demo (J-12 cell counting) | Not started | |
| M7 Hardening | Not started | |

## Working capabilities (verified by running)

- Engine bootstrap: SciJava context with the legacy layer, FlatLaf, real `IJ.run` on a synthetic image, orderly exit (EXP-04 recipe: close images → dispose → halt; 3.6 s total).
- Speech: persistent `speech/server.py` worker (hash-verified tiny.en, VAD) through `WhisperSpeechService`; typed/spoken text → `DefaultNlService` proposals with deterministic policy (no code generation, consequential slots only from the user).
- Launching `java.exe` directly (tools/run.py) gives a **DPI-aware** JVM: `dpiScale=1.50` on the 150 % display. The spikes' Jaunch-launched JVM was DPI-unaware (blurry); the product launcher avoids that.

## Spike facts every workstream must respect (from EXP-01…05)

- Host window: `canEnlarge()==null` **and** `win.setLayout(null)`; viewport order size → source rect → magnification; never `zoomOut`/`unzoom` on the canvas.
- Current image follows `WindowManager.setCurrentWindow(win)` only (not clicks/focus); make the target current **before** any `RoiManager.select`.
- Error signals are non-uniform; a plugin exception on a hidden-window document can be silent → postconditions decide success. Modal `MessageDialog`s block the worker → the dialog watch must capture and dispose them.
- Macros ending with `setBatchMode(false)` re-show hidden windows → re-apply the window policy after CODE actions. First macro `selectImage` on a hidden window costs ~1 s.
- Shutdown: close images, then `context.dispose()` (1.45 s) or `IJ.getInstance().quit()` (0.44 s); never bare `System.exit` with documents open.
- Native canvas paint 0.14–1.7 ms median at 2048²; IJ1 caches the converted image.

## Known failures / open issues

- Test JVM exit: fixed by `ExitListener` (dispose with bound, then halt); verify after rebuild.

## Contract changes

- (none since baseline)

## Demo readiness

Not yet. Fallback: the prototype (`README.md`, "What works today").

## Resume procedure

1. `cd C:\Users\arnav\Documents\dev\fidelis-dev && git status && git log --oneline -10`.
2. Build and smoke (commands above). If they fail, fix the baseline before anything else.
3. For each agent worktree: `git -C <worktree> log --oneline -5`, `git status`; merge finished work
   into the integration branch only after build + tests + a real run.
4. Update this file and commit.
