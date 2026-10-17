## What and why

Closes #<issue>. <!-- One purpose per PR. Link requirement or decision IDs where relevant (FR-…, NFR-…, DEC-…, ADR-…). -->

Workstream: <!-- engine | voice | ui | integrator | documentation | prototype -->

## Changed files

<!-- Short summary per area, then the ownership check output:
     python -I fidelis-app/tools/check_ownership.py --stream <engine|voice|ui|integrator> -->

## Verification

<!-- Exact commands and their result lines. For workstation PRs: -->
- [ ] `python -I fidelis-app/tools/build.py` -> `[build] OK`
- [ ] The issue's required tests (paste the `[fidelis-tests]` lines)
- [ ] Full suite on this PR head: `[fidelis-tests] N tests finished, M failed` (any failure links its issue)
- [ ] Scientific behaviour changed? Parity evidence against `verification/baselines` (E1 pixels, E4 ROI vertices, E5 Results doubles, E6 globals; docs A30/D18). Visual similarity is not evidence.
- [ ] UI changed? Screenshots attached that were opened and inspected (say what was checked)
- [ ] Live speech claimed? Real microphone runs with the laptop, device and measured numbers
- [ ] Documentation PR? `python -I docs/tools/check_docs.py` -> 0 errors

## Contracts

- [ ] No change to `sc.fidelis.engine.api`, `NlService` or `SpeechService`, **or** the linked `needs:contract-review` issue has `CONTRACT ACK` from the affected owners and `CONTRACT APPROVED` from the integrator

## Checklist

- [ ] Base is `development/fidelis-workstation` on **reverez/Voice** (never `Sleep-On-It/Voice`, `AyushGupta05/Voice` or `Sleep-On-It/Fidelis`)
- [ ] No upstream Fiji/ImageJ/plugin source, runtimes, model weights, large datasets, build output or credentials added
- [ ] Documentation and the workstream handoff file updated where behaviour changed
- [ ] Claims are honest: nothing untested or proposed is described as working
