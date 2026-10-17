# Contributing to Fidelis

Fidelis is at an early stage: a working **prototype** plus a **design proposal** awaiting approval. These conventions keep the two apart and keep `main` trustworthy.

## Two tracks, kept separate

| Track | Lives in | Rules |
|---|---|---|
| **Prototype (T0)** | `backend/`, `frontend/`, `speech/`, `scripts/`, `tests/`, `fiji/`, `external/` | Working integration evidence and a hackathon demonstration. Fix bugs, but do not grow it into the product. |
| **Proposed architecture** | `docs/` (analysis A01–A32, design D01–D20, ADRs). New code goes in new top-level modules once DEC-010 is approved. | Design changes go through the decision register (`docs/DECISIONS.md`). Code must not start until the relevant decision is accepted. |

Never present a proposal as implemented. Tag claims VERIFIED, INFERRED, PROPOSED or UNKNOWN (docs F04 §1).

## Branch names

`<type>/<short-kebab-description>`, where `<type>` is one of:

| Type | Use |
|---|---|
| `docs/` | Documentation and design proposals |
| `spike/` | Time-boxed, throwaway validation experiments (Fiji/Java probes). Never merged as product code. |
| `feat/` | Product features (after milestone approval) |
| `fix/` | Bug fixes |
| `proto/` | Changes to the prototype track |
| `ci/` | CI and repository tooling |
| `repo-setup/` | Repository administration |

## Pull requests

- Every change to `main` goes through a pull request. No direct pushes, force pushes or history rewriting on `main`.
- **One reviewer approval** is required, from a teammate other than the author when one is available.
- Keep PRs small and single-purpose. Describe *what was verified and how* (commands and results), and link the requirement or decision IDs involved (FR-…, NFR-…, DEC-…).
- PRs that change scientific behaviour must state their verification evidence (docs A30/D18: numerical comparison with a reference Fiji run; visual similarity is not evidence).
- Documentation PRs must pass `python -I docs/tools/check_docs.py` (0 errors) and `python -I docs/tools/build_traceability.py` (no gaps; commit the regenerated `docs/data/traceability.csv`).

## Issues

Use the single issue form and pick one category: architecture, experiment, UI/UX, integration, verification, prototype or documentation. Link decisions and requirements by ID. Prefer one well-scoped issue over many speculative ones.

## Local checks

```powershell
./.venv/Scripts/python.exe -m pytest -m "not integration"
./.venv/Scripts/python.exe -m ruff check .
npm.cmd --prefix frontend run build
```

Integration tests need the pinned Fiji and the speech environment (`$env:FIDELIS_INTEGRATION = "1"`). See README.

## Licence of contributions

By contributing, you agree that your original contributions are licensed under the Apache License 2.0, as scoped in [NOTICE](NOTICE). Do not add upstream Fiji, ImageJ or plugin source code, GPL-licensed code, model weights or large datasets to the repository without a recorded decision.
