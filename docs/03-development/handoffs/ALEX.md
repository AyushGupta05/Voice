---
id: DEV-14
title: Workstream handoff - Alex (ui)
stage: 03-development
status: Living record
priority: P1
source_revisions: [reverez/Voice feat/alex-ui]
related_requirements: [NFR-MAINT-004]
related_decisions: [DEC-048]
last_reviewed: 2026-10-17
approval: Development record (not a design approval)
---

# Workstream handoff - Alex (ui)

Owned by Alex's session. Update it at every checkpoint (docs/03-development/GITHUB-WORKFLOW.md section 7).
A restarted session reads this file first; keep it short, factual and current.

## Current state

| Field | Value |
|---|---|
| Developer / GitHub | Alex Kurkar, `akurkar07` (collaborator access on reverez/Voice requested in #2; `permissions.push` was `false` on 17 Oct 16:00) |
| Branch | `feat/alex-ui` (local clone of reverez/Voice, remote `origin`) |
| Last pushed commit | `9f1ef7a` (baseline); local commits not yet pushed (no push permission) |
| Current issue | #15 demo layout: implemented, tests green, PR pending push access |
| Machine | Windows 11, 1920x1200 at 125 % (`dpiScale=1.25`, logical desktop 1536x960); `FIDELIS_FIJI_DIR=C:\Users\al3xk\dev\Fiji` (archive 20261013-1517, SHA-256 `9201287a…794c54`, 1379 files match `external/fiji.lock.json`); Python 3.11 via `py -3.11 -I` |
| Last UI-test result on this branch | `WorkspaceLayoutTest`, `WorkspaceViewportTest`, `WorkspaceU2Test`, `ConfirmationUiTest`: see the #15 PROGRESS comments for the exact lines |
| Last full-suite result on this branch | see the #15 / #3 comments (recorded when run) |
| Open PRs | none yet (push blocked) |

## Done

- #3 environment on this machine: pinned Fiji downloaded, hash-verified and extracted outside the clone; lock check 1379/0;
  `build.py` OK (87 main / 48 test sources at baseline, 50 test sources with the layout test); `run.py --smoke --isolated-home`
  -> `Invert pixel(10,10) 40 -> 215 : OK`, exit 0 in 9.2 s, `dpiScale=1.25`; fixtures fetched (FX-001/003/004/005/030/031/040).
- #15 demo layout (`sc.fidelis.ui.Docks` rewritten, no new library): right column = Parameters/B&C/Threshold tab group above the
  ROI list; bottom row = Results beside History & macro/Log/Activity; four draggable dividers; `applyDefaultDividers()` /
  `resetLayout()` derive the defaults from the frame size (right 380 px, 420 px at >= 1800 px; bottom 30 % bounded 220-320 px;
  Results 46 % of the row; tools 52 % of the column). Collapse/restore of both docks kept.
- History table: fixed widths for Time/Origin/Provenance/Outcome, tooltip with the full parameters, one-touch collapsible macro pane.
- `Workspace`: engine listener removed on frame dispose (first item of #16, needed so a disposed test workspace stops hosting
  canvases); interactive launches clamp the frame to the screen, screenshot runs keep the exact size (undecorated when larger
  than the desktop, because Windows clamps decorated frames).
- `--scenario j12` prints the measured layout (`frame WxH; layout documents=… results=… ; canvas … at 200%; results columns visible N`).
- New `WorkspaceLayoutTest` (+ `LayoutScenario`): runs the real J-12 through the workspace on the product engine, then asserts
  canvas + Results (71 rows, >= 6 columns) + ROI list (71) + History showing, non-overlapping, inside the frame at 1500x940 and
  1920x1080; dividers move their panels; reset restores the defaults; collapse/restore of both docks.
- Screenshots opened and inspected: `docs/03-development/screenshots/j12-layout-1500x940.png`, `j12-layout-1920x1080.png`.

## Next steps

1. When push access lands: `git push origin feat/alex-ui`, open the #15 PR (body drafted in the #15 comments), `READY FOR REVIEW:`.
2. #16: compare flake loop (20x `WorkspaceU2Test`), shortcut parity table and test (listener disposal already done here).
3. #19 macro editor dock.

## Blockers and open questions

- Push permission on reverez/Voice (`akurkar07`): requested in #2 on 17 Oct.
- Observed while testing (for #4, integrator): the test JVM's isolated `build/test-home` keeps IJ_Prefs between runs
  (`blackBackground=true`, `measurements=area mean min` were already set at the start of a fresh test JVM after an earlier
  J-12 run). `LayoutScenario.stop()` restores `blackBackground`, measurements and precision and clears Results through the
  engine, but the on-disk prefs are outside the UI stream.

## Notes for the next session

- Read `CLAUDE.md` and `docs/03-development/ALEX-EXECUTION.md` before anything else.
- Use `py -3.11 -I` (the plain `python` on this machine is 3.10). `FIDELIS_FIJI_DIR` is set for the user; a shell opened
  before 17 Oct 16:00 needs `$env:FIDELIS_FIJI_DIR = "C:\Users\al3xk\dev\Fiji"`.
- Evidence at 1920x1080 on this 1536x960 logical desktop comes from the undecorated screenshot frame; interactive runs are clamped.
