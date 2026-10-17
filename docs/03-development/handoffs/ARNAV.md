---
id: DEV-12
title: Workstream handoff - Arnav (engine)
stage: 03-development
status: Living record
priority: P1
source_revisions: [reverez/Voice feat/arnav-engine]
related_requirements: [NFR-MAINT-004]
related_decisions: [DEC-048]
last_reviewed: 2026-10-17
approval: Development record (not a design approval)
---

# Workstream handoff - Arnav (engine)

Owned by Arnav's session. Update it at every checkpoint (docs/03-development/GITHUB-WORKFLOW.md section 7).
A restarted session reads this file first; keep it short, factual and current.

## Current state

| Field | Value |
|---|---|
| Repository / branch | `reverez/Voice`, `feat/arnav-engine` (remote `voice` in the lead's worktree `C:\Users\arnav\Documents\dev\fidelis-engine`) |
| Last pushed commit | `ed39356` Isolate every test class in the shared Fiji JVM (#4), on top of `9f1ef7a` (merged planning PR #21) |
| Current issue | #4 (in progress): isolation implemented; collecting the 3 default-order + 2 random-order full-suite runs for the PR |
| Last full-suite result on this branch | run 1 of 3 on `ed39356` (default order): **378 tests, 0 failed**, 0 `listener failed`, 0 `LinkageError` |
| Open PRs | none yet (PR for #4 opens once the five runs are recorded) |
| Background processes | none expected at a restart; `test.py` kills its JVM on its 900 s bound and writes `fidelis-app/build/test-timeout-threads.txt` first |

## Done

- #3: preflight `PREFLIGHT PASS` on the lead machine (1379 Fiji files match the lock), recorded on #3.
- #4 root causes (all reproduced on 9f1ef7a, details in the #4 PROGRESS comment and the `ed39356` message):
  1. `Workspace` never removes its engine listener; disposed test workspaces kept receiving events (`listener failed on StateDigestEvent ... ImageProcessor.getMin()`).
  2. `EngineScienceTest` asserted order on the session-wide `exportMacro()`; now on `exportMacroSince(historyStart)`.
  3. Test discovery loaded `ij.*` before the legacy patcher (`LinkageError: duplicate class definition`), after which an unpatched `ImageWindow.dispose` hung the run (thread dump kept in the session scratchpad; `test.py` now dumps threads itself on timeout).
- Isolation primitive: `sc.fidelis.testkit.SessionIsolation` + `IsolationState` (auto-registered extension; one `[isolation]` line per class naming the leaks it reset); `FijiFixture.patchEarly/requirePatched`; `DefaultEngine.listeners()/resyncResults()` (implementation-only); `test.py --random-order SEED [--random-methods]`; `verification/test_verification.py` no longer needs the git-ignored raw dump.

## Next steps

1. Finish #4 evidence: runs 2 and 3 (default order), then `--random-order 20261017` and `--random-order 42`; record the five `[fidelis-tests] N tests finished, 0 failed` lines; open the PR (`Closes #4`, ownership check `--stream integrator` passes because `tools/test.py` is integrator-owned; note the `Workspace` listener leak for Alex on #16).
2. #5: `InteractiveJourneyTest` (menu-bar dispatch + native canvas events on FX-003), native Results drift test and fix (`Results` tracks only a row count), event-order test for SESSION/DIRECT/COMMAND/CODE (session ops: check QUEUED/RUNNING emission in `DefaultEngine.submit` line ~232 and `DirectOps`), replay through `MacroReplay`.
3. #6 contract proposal (post `CONTRACT PROPOSAL:` early), #7 Split Channels schema first.

## Blockers and open questions

- None. `Workspace` listener removal on dispose is Alex's (#16); the isolation extension makes the suite independent of it.

## Notes for the next session

- Commands: `python -I fidelis-app/tools/build.py`; `python -I fidelis-app/tools/test.py [--random-order SEED]`; one JVM per worktree.
- Logs of the #4 runs are in `%TEMP%\full-iso-*.log` on the lead machine (not committed).
