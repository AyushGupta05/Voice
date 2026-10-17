---
id: DEV-06
title: Team plan for the hackathon (three workstreams)
stage: 03-development
status: Proposed (team approval pending)
priority: P0
source_revisions: [reverez/Voice development/fidelis-workstation @ 215fdb8ead50ba97a302f1d259ee77b3173938c6, Fiji archive 20261013-1517]
related_requirements: [FR-SYS-001, FR-PROC-001, FR-ROI-001, FR-MEAS-003, FR-HIST-002, FR-MACRO-001, FR-NL-001, FR-SPEECH-004, NFR-FID-001]
related_decisions: [DEC-010, DEC-029, DEC-031, DEC-040, DEC-048]
last_reviewed: 2026-10-17
approval: Awaiting approval by Arnav, Ayush and Alex
---

# Team plan for the hackathon (three workstreams)

Written for Arnav, Ayush and Alex and for the three Claude Code sessions that will work for them.
Planned on Saturday 17 October 2026, 15:00 BST; the presentation is on Sunday 18 October around 13:00 BST.
**The assignments are provisional until all three developers approve them on the master issue #2.**

## 1. Goal

Deliver a verified, rehearsed live demonstration of Fidelis: the real Fiji running in one persistent
engine behind a modern, coherent workspace that is recognisably Fiji, with native image and ROI tools,
real measurements, inspectable history and real ImageJ macros, and local speech and typed instructions
that propose real operations for confirmation. Scientific results must equal the pinned Fiji's own output.
The Java workstation in `fidelis-app/` is the foundation; the earlier Python/React prototype is fallback
evidence only.

## 2. Verified baseline

| Item | Value (verified 17 Oct 2026, 15:00 BST, lead machine) |
|---|---|
| Repository | `reverez/Voice` (fork of `Sleep-On-It/Voice`; issues enabled for this plan) |
| Integration branch | `development/fidelis-workstation` @ `215fdb8ead50ba97a302f1d259ee77b3173938c6` (handoff 3e843ba plus Arnav's UI concept gallery) |
| Build | `fidelis-app/tools/build.py` OK (87 main, 48 test sources) |
| Smoke | `run.py --smoke --isolated-home`: `Invert pixel(10,10) 40 -> 215 : OK`, exit 0 in 6.1 s |
| Full suite | **378 tests, 1 failed**: `EngineScienceTest.analyzeParticlesFillsTheRoiListAndResults` fails only after `ConfirmationUiTest` (passes alone 15/0). Order-dependent shared-JVM state; tracked in #4. The same code passed 378/0 earlier. |
| Feature branches | `feat/arnav-engine`, `feat/ayush-voice`, `feat/alex-ui` at `215fdb8` |

## 3. Gap analysis

Evidence: `HANDOFF.md`, `ACTIVE-DEVELOPMENT.md`, `VERIFICATION-RESULTS.md`, the source at `215fdb8`, and today's test runs.

### Fully implemented and verified
- Persistent non-headless Fiji JVM with hidden main window; orderly exit (smoke, quit tests, `--close-window` exit 0).
- Real IJ1 canvases hosted in the FlatLaf workspace (viewport, native rectangle drag, compare view; screenshots inspected).
- Typed execution with complete options, Guard, postconditions, snapshot undo/redo, rollback (engine tests).
- J-12 cell counting exact against the pinned Fiji on FX-001, FX-003 (71 particles; Area 65024, Mean 155.9435746801181), FX-030 BBBC001 (345) and FX-031 BBBC004 (300), through the engine API, through the workspace and by replaying the exported macro in reference Fiji.
- Speech-to-execution from a fixture WAV; NL eval 124/124 on the real catalogue, 0 unsafe executions; ten J-12 phrases executed on the real engine with exact numbers.
- Confirmation policy in the proposal sheet; vocabulary prompt wiring; quit path.

### Implemented but insufficiently tested
- The **interactive path** (menus and native canvas tools, provenance NATIVE): exercised piecemeal, never as a full journey against the baseline (#5).
- **Live microphone** capture: never verified by a person (#11).
- Suite reliability: order-dependent failure today (#4); one unreproduced compare-view flake (#16).

### Partially implemented
- Command schemas: 53 of 953 complete; commands for a channel workflow missing (#7).
- Channels: per-channel display range and sliders only; no composite mode or visibility (#17).
- Macro: generated macro shown read-only and exported; no editor or in-app run (#19).
- Results export: no full-precision export (DEC-040) (#7).
- Layout: Results and ROI list are tabs in a 380 px dock (#15).
- Lifecycle events: session operations may not emit QUEUED/RUNNING (#5).

### Not implemented
- Durable history journal promised by the `Engine.quit` javadoc (#9).
- Multi-step spoken plans with channel references (#13); voice assistant panel (#18).
- A second, multi-channel journey with reference parity (J-15, #8).
- Keyboard shortcut parity tests (#16); dark theme and concept-level polish (#20).

### High-risk architectural defects
- Shared mutable IJ1 state across tests and UI instances (listener leaks, singletons): affects test trust and long sessions (#4, #16).
- Native Results synchronisation tracks only a row count (#5).
- Two independently generated macro-line previews (NL, UI) next to the recorded line: the trust claim "what you see is what is recorded" is violated for Convert to Mask (#6).

### Critical blockers for the live demo
1. Gating suite must be reliable (#4).
2. Every laptop must run the pinned runtime (#3).
3. Live microphone on the demo laptop (#11).
4. "Count the cells" must give the J-12 result including ROIs (#12).
5. Demo layout must show image, ROIs, Results and history together (#15).
6. Integration, freeze and rehearsal (#10).

### Optional post-demo enhancements
Journal (#9), polish (#20), further schemas, plugin coverage, legacy dialogs as forms, CI for the Java build, Bio-Formats journeys.

## 4. Workstreams

| Workstream | Owner | GitHub | Branch | Scope | Issues (priority order) |
|---|---|---|---|---|---|
| Engine, science, integration | Arnav | `reverez` (admin) | `feat/arnav-engine` | `sc.fidelis.engine`, verification, catalogue data, integration | #4, #5, #6, #7, #8, #10, #9 |
| Voice and language | Ayush | `AyushGupta05` (**invitation pending**) | `feat/ayush-voice` | `sc.fidelis.nl`, `sc.fidelis.speech`, `speech/` | #11, #12, #14, #13 |
| Desktop UI | Alex | **to be confirmed** | `feat/alex-ui` | `sc.fidelis.ui` | #15, #16, #19, #17, #18, #20 |
| All | each | | | own environment | #3 |

Ownership by file is defined in `WORKSTREAM-CONTRACTS.md` and checked by `fidelis-app/tools/check_ownership.py`.
Every stream has independent work from the first minute: Arnav #4, Ayush #11 and #12, Alex #15 and #19.

## 5. Milestones and schedule (Europe/London, BST)

| Milestone | Due | Contents | Exit criterion |
|---|---|---|---|
| M0 Baseline and collaboration readiness | Sat 16:30 (#4 by I1, 19:00) | #3, #4, plan approval, collaborator access | three environment evidence comments; plan approved; #4 merged at I1 |
| M1 Critical demo and correctness defects | Sat 21:30 | #5, #11, #12, #15, #16 | P0s merged; suite green twice at I2 |
| M2 Integrated end-to-end workflow | Sun 09:00 | #6, #7, #8, #13, #14, #17, #18, #19 | the workflow runs through GUI, typed and spoken paths; J-15 exact if merged |
| M3 Demo freeze and release readiness | freeze Sun 10:30, ready 12:00 | #10 | rehearsed twice; demo SHA recorded |
| M4 Stretch | never blocks; last merge Sun 09:00 | #9, #20 | merged only if green |

Integration checkpoints: **I1** Sat 19:00, **I2** Sat 21:30, **I3** Sat 23:30, **I4** Sun 09:00, **freeze** Sun 10:30.
Overnight (00:00 to 08:00) sessions may continue autonomously on their own branches, but nothing merges
between I3 and I4. Rest is planned: M2 is due at 09:00 so that nobody needs to work through the night.

Consolidation note: M1 and M2 run in parallel streams; they are kept separate because their exit
criteria differ (defects versus integrated workflow). M4 has no due date by design.

## 6. Dependencies

```
#3 environments -> every stream's first PR
#4 test isolation -> I1 merges (gating suite)
#7 Split Channels schema -> #8 J-15 baseline -> #13 multi-step channel plans (exact numbers)
#11 live mic, #12 phrases -> #14 spoken regression, #18 voice panel
#15 layout -> #20 polish
#6 contract (engine) -> NL and UI adoption (in #6)
#17 contract -> engine ops (Arnav) -> channels strip (Alex)
P0 issues (#3, #4, #5, #11, #12, #15) -> #10 freeze
```

The native GitHub "blocked by" relationships carry the hard dependencies; soft ones are stated in each issue.

## 7. Critical demo requirements

The demo script is `DEMO-RUNBOOK.md`. For the freeze revision (#10):
- Open a real microscopy image (Blobs; BBBC001), use a native ROI tool, run real Fiji commands from the menus and the command bar, show exact measurements and the ROI list together with the history.
- Export the macro and show it replaying (in the workstation's macro editor if #19 merged, otherwise through `verification/replay.py` in reference Fiji).
- Speak an instruction on the demo laptop's microphone, show the proposal and the confirmation, execute, and show the same numbers as the GUI path.
- Backup path: typed instruction if the microphone fails; pre-captured real screenshots in `docs/03-development/screenshots/`; the earlier prototype as last resort.
- Every number shown must come from the engine at run time; the README claims only what the freeze revision verified.

## 8. Risk register (team level)

| Risk | Likelihood | Impact | Mitigation | Owner |
|---|---|---|---|---|
| TR-1 Gating suite unreliable (order-dependent) | high (observed) | high | #4 first; full suite twice per checkpoint | Arnav |
| TR-2 Collaborator access not granted in time | medium | high | invite at once; until then work on a local branch and share patches by PR from a branch pushed by the integrator | Arnav |
| TR-3 Live microphone fails on the demo laptop | medium | high | #11 measured early; typed fallback rehearsed | Ayush |
| TR-4 Integration conflicts between streams | medium | medium | strict ownership + checker; contract-first merges; small PRs | all |
| TR-5 Late merges destabilise the demo | medium | high | freeze 10:30; M4 never blocks; last P1 merge at I4 | Arnav |
| TR-6 Different Fiji or display settings on another laptop | medium | medium | #3 pin check by regenerating FX-001; DPI noted | each |
| TR-7 Claude session interruption or usage limits | high | medium | checkpoints every 20 to 30 minutes; handoff files; recovery procedure in GITHUB-WORKFLOW | each |
| TR-8 Concept images mistaken for implemented features | low | high | concepts are labelled design targets; README states only verified behaviour | Arnav |
| TR-9 Multi-channel journey not exact in time | medium | low | J-15 is P1; J-12 on Blobs and BBBC001 remains the core demo | Arnav |

## 9. Integration authority

Arnav is the coordinator and integrator: he merges PRs into `development/fidelis-workstation`, approves
contract changes, runs the checkpoints and records them on #2, and decides the freeze revision with the
others. Nobody merges their own PR. Tagging a demo revision needs all three approvals on #2.

## 10. Pending human decisions

- Invite `AyushGupta05` as a collaborator on `reverez/Voice` (Ayush cannot fork it: his `AyushGupta05/Voice` is in the same fork network).
- Confirm Alex's GitHub username, then invite.
- Approve this plan (comment `APPROVE PLAN` on #2) and review the planning PR.
- Optional: `gh auth refresh -s project` if a GitHub Projects board is wanted; the status labels already provide the board columns.
- Decide whether to tag `demo-2026-10-18` at the freeze.
