---
id: DEV-02
title: Implementation agent brief
stage: 03-development
status: Living record
priority: P0
source_revisions: [Fiji archive 20261013-1517 (lock f6695397 / 9403b619 LF), imagej/ImageJ@v1.54p, integration branch development/fidelis-workstation]
related_requirements: [FR-SYS-002, FR-VIEW-001, FR-PROC-001, NFR-MAINT-004]
related_decisions: [DEC-010, ADR-002]
last_reviewed: 2026-10-17
approval: Development record (not a design approval)
---

# Fidelis development brief (shared by all implementation agents)

You are one of four implementation agents building **Fidelis**, a modernisation of Fiji/ImageJ. The lead (Fable) integrates your work. Today is 2026-10-17; the hackathon ends tomorrow. Deliver working, verified code in small commits.

## What Fidelis is (non-negotiable)
A modern scientific desktop workstation that **runs the real Fiji**: the same algorithms, commands, macro language, ROI tools and plugins, presented in one coherent FlatLaf/Swing window, with typed and spoken commands as additional ways to drive the same engine. **One real scientific engine, multiple ways to interact with it.** Not an AI dashboard. No simulated results, no fake viewer, no claims of untested functionality, no reimplemented ImageJ algorithms, no arbitrary code execution from natural language.

## Where you work
- Your own git worktree and branch (given in your task). Commit there often with messages ending in the line `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. **Do not push, do not merge, do not touch other worktrees or `main`.** The lead merges into `development/fidelis-workstation`.
- **File ownership is strict** (see `docs/03-development/ACTIVE-DEVELOPMENT.md` in your worktree). Never edit files outside your ownership. The contract package `sc.fidelis.engine.api` and the interfaces `sc.fidelis.nl.NlService` / `sc.fidelis.speech.SpeechService` are **frozen**: if you need a change, write the exact proposed change in your final report (and keep working with a local adapter), do not edit them.
- Never modify the Fiji installation `C:\Users\arnav\Documents\dev\Fiji`. Never write to the Windows registry. Never touch any GitHub repository.
- Other agents and a validation-spike agent are running Fiji JVMs concurrently; that is fine, but **run only one JVM at a time from your worktree**, always with `--isolated-home` / `tools/test.py`, and kill only processes you started (the tools have timeouts).

## Toolchain (all from your worktree root; Python = `C:\Users\arnav\Documents\dev\click-to-batch\.venv\Scripts\python.exe -I`)
```
python fidelis-app/tools/fetch_junit.py                  # once per worktree (pinned JUnit runner)
python fidelis-app/tools/build.py                        # javac with Fiji's bundled JDK; must stay green
python fidelis-app/tools/run.py --isolated-home          # launches the app (M2: Fiji window never shown)
python fidelis-app/tools/run.py --smoke --isolated-home  # engine self-test
python fidelis-app/tools/test.py [--select-class FQCN]   # JUnit 5 in a live Fiji JVM
```
Build output is git-ignored. The JVM flags mirror Fiji's launcher (`tools/fijienv.py`). The catalogue CSVs are on the classpath at `sc/fidelis/engine/data/commands.csv` and `commands-core-parameters.csv`.

## Architecture in force (read `docs/03-development/ACTIVE-DEVELOPMENT.md`, section "Architecture in force")
Hybrid B, proven by spike EXP-01 (`C:\Users\arnav\Documents\dev\fidelis-spikes\spikes\arch-gate\exp01-native-canvas\RESULT.md`, read-only; also `common/embed.groovy` there shows the working host pattern in Groovy):
- Persistent non-headless Fiji JVM, main window hidden. `Bootstrap` already creates the context with the legacy layer and installs FlatLaf.
- **Real IJ1 `ImageCanvas` per document** (`sc.fidelis.engine.api.HostCanvas`, `canEnlarge()==null`) inside a **hidden `ImageWindow`** (`new ImageWindow(imp, hostCanvas)` then `setVisible(false)`), canvas re-parented into the workspace. Host drives the viewport: `setDrawingSize`, `setSourceRect`, `setMagnification` (that order), then bounds; forward mouse-wheel to the hidden window's `mouseWheelMoved`; make a document current with `WindowManager.setCurrentWindow(win)` on focus/click (clicking a re-parented canvas does NOT do this by itself). Never call `zoomOut`/`unzoom`/`Out [-]` on the canvas (they re-pack the hidden window); implement zoom-out through the host using `ImageCanvas.getLowerZoomLevel`. `Container.printAll` does not capture the heavyweight canvas: composite `canvas.paint(g)` at the canvas location for screenshots.
- Typed actions go through `Engine.submit(ActionRequest)` and execute as `IJ.run(imp, exactLabel, completeOptions)` on one engine worker thread (never the EDT). Success is judged by postconditions, never by `IJ.run` returning: IJ1 swallows plugin exceptions (`Executer`), and in a GUI JVM shows an "Exception" `TextWindow` instead.
- Native canvas interactions are IJ1's own and are **observed** (`Executer.addCommandListener`, `Roi.addRoiListener`, `ImagePlus.addImageListener`, post-action digests) and recorded with provenance NATIVE. History is authoritative; macros are generated from it.
- Result-affecting globals (`Prefs.blackBackground`, `Analyzer.getMeasurements()/getPrecision()`, `ImageConverter.getDoScaling()`) are captured, shown and never set silently.
- Known hazards from the probes: `IJ.getImage()` opens a modal "No Image" dialog when there is no current image; preview dialogs drop options in the Recorder; `System.exit` can deadlock on the SciJava shutdown hook (use `Bootstrap.exit`); macro `selectImage` on a hidden-window image can wait ~1 s.

## Documentation you may need

In your worktree `docs/`: `INDEX.md`; A08 (commands), A09–A12 (IJ1 state, ROIs, processing, macros), A15, A16–A17 (speech/NL), D04 (integration), D06 (contract), D07 (macro generation rules G1–G11), D08 (speech/NL pipeline), D09 (events), D12–D15 (UI), D16 J-12 (the demo journey), D17 (errors), D18 (verification). `docs/data/commands-core-parameters.csv` has exact macro keys with source lines.

## Quality bar (mandatory)
- Every feature you report must have been **run**: a test, a launch with observed output, or a screenshot you inspected. Say exactly what you ran.
- Scientific outputs are verified by numbers (pixel values, measurement values), never by appearance.
- No weakening or deleting tests to go green. Diagnose root causes.
- Keep `build.py` green at every commit. Keep the baseline smoke and tests passing.
- Use British English in docs; code comments concise.

## Reporting
Commit at each working increment. Your **final message** (under 700 words): files added/changed; what works (with the exact evidence); what is incomplete; any contract-change requests (exact signatures); any blocker; any hazard other agents must know.
