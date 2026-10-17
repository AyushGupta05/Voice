---
id: F04
title: Engineering and Documentation Standards
stage: 00-foundation
status: Draft for review
priority: P0
source_revisions: n/a (project convention)
related_requirements: []
related_decisions: [DEC-001, DEC-002]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# F04 — Engineering and Documentation Standards

## Executive Summary

These are the conventions every document, data file and future agent-assisted change in this repository must follow. They exist so that requirements, decisions and evidence stay traceable, and so that a later coding session can locate the single authoritative statement of any fact. Working title for all new planning documents: **Fiji Modernisation Project** (DEC-001). Existing names (`click-to-batch` repository, `fidelis` Python package, "Fidelis" UI label) are left unchanged until a naming decision is approved.

## 1. Evidence classes

Every non-trivial technical assertion carries one of four tags, inline in bold or in a table column named `Evidence`.

| Tag | Meaning | Minimum support |
|---|---|---|
| **VERIFIED** | Confirmed by source inspection, official documentation, or a reproducible local test/probe. | A citation (§4) to the exact source, or a probe file under `docs/01-analysis/references/probes/` with its output. |
| **INFERRED** | Reasoned conclusion from verified facts; not directly tested. | The facts it rests on, cited. |
| **PROPOSED** | A design choice or recommendation by this project. | Rationale and the requirement(s) it serves. |
| **UNKNOWN** | Open question. | How it could be verified, and what depends on it. |

Rules:

- Documentation saying something *should* work is not VERIFIED behaviour of the pinned runtime. Write "documented (VERIFIED as documentation)" and keep runtime behaviour INFERRED until probed.
- A VERIFIED claim about upstream code cites a revision. A tag name is acceptable only if the SHA is recorded in `docs/data/upstream-manifest.yaml`.
- Do not upgrade a tag silently. If later evidence changes a claim, edit the claim and note the change in the document's *Change log*.

## 2. Document identifiers and locations

| Prefix | Series | Location |
|---|---|---|
| `F01–F04` | Foundation | `docs/00-foundation/` |
| `A01–A32` | Analysis | `docs/01-analysis/<category>/` |
| `D01–D20` | Design | `docs/02-design/<category>/` |
| `ADR-nnn` | Architecture decision records | `docs/02-design/01-architecture/adr/` |
| `P01–P05` | Prototype-era records (pre-existing `docs/*.md`, preserved unedited) | `docs/` |

Filenames: `<ID>-<kebab-title>.md`, e.g. `A15-gui-dependence-reuse-boundaries.md`. IDs never change. A retired document keeps its ID and status `Superseded by <ID>`.

`docs/INDEX.md` is the authoritative list of documents. `docs/DECISIONS.md` is the authoritative list of decisions. `docs/WORK-PROGRESS.md` is the recovery checkpoint for interrupted work.

## 3. Item identifiers

| ID | Item | Authoritative home |
|---|---|---|
| `FR-<AREA>-nnn` | Functional requirement (areas: `IMG`, `VIEW`, `ROI`, `PROC`, `MEAS`, `MACRO`, `PLUG`, `IO`, `HIST`, `CMD`, `NL`, `SPEECH`, `WS` workspace, `SYS`) | A26 |
| `NFR-<AREA>-nnn` | Non-functional requirement (`PERF`, `REL`, `USE`, `A11Y`, `PRIV`, `SEC`, `MAINT`, `FID` fidelity, `PKG`, `PLAT`) | A27 |
| `CR-Axx-nn` | Candidate requirement raised in analysis doc Axx; consolidated into FR/NFR by A26/A27, which record the mapping | originating doc |
| `R-nnn` | Risk / assumption | `docs/data/risks.csv` (candidates `R-Axx-nn` in originating doc) |
| `DEC-nnn` | Decision (any stage) | `docs/DECISIONS.md` |
| `IF-nnn` | Interface/contract | D09 (catalogue) |
| `UI-nnn` | Screen, panel or major UI surface | D12 |
| `CMP-nnn` | Design component | D01 / D14 |
| `J-nn` | End-to-end user journey | D16 |
| `FX-nnn` | Test fixture / dataset | `docs/data/datasets.csv` |
| `PAR-nnn` | Parity matrix row | `docs/data/parity-matrix.csv` |

Requirement priority vocabulary (from brief Rule 7):

- `MVP` — minimum viable demonstration.
- `HACK` — hackathon target (by 18 Oct 2026).
- `PARITY` — post-hackathon Fiji feature parity.
- `LONG` — long-term modernisation.

Requirement statements use **shall**. Each has an ID, statement, rationale, priority, acceptance criteria (testable), dependencies and source (doc/section or CR id).

## 4. Citation format

| Source type | Format |
|---|---|
| Upstream code | `` `imagej/ImageJ@v1.54p:ij/ImagePlus.java#L1234` `` (repo@tag-or-short-SHA:path#line). The tag→SHA mapping lives in the upstream manifest. |
| Local installed binary | `` `Fiji/jars/ij-1.54p.jar!/IJ_Props.txt` `` or `` `javap ij.CommandListener` (probe P-xx) `` |
| Probe output | `` `probes/<file>` `` relative to `docs/01-analysis/references/` |
| Web page | Markdown link plus `(accessed 2026-10-17)` on first use in a document |
| Paper | Author (year), short title, DOI link; full entry in `docs/01-analysis/references/BIBLIOGRAPHY.md` |
| Repository file | Relative Markdown link, e.g. `[fiji.py](../../backend/src/fidelis/adapters/fiji.py#L101)` |

Every external source used in any document also appears in `BIBLIOGRAPHY.md`, with its URL, the version or revision, and the date it was checked.

## 5. Document structure

Front matter (YAML) is mandatory:

```yaml
---
id: A15
title: GUI Dependence and Reuse Boundaries
stage: 01-analysis            # 00-foundation | 01-analysis | 02-design | ...
status: Draft | In review | Baselined (awaiting approval) | Approved | Superseded by <ID>
priority: P0 | P1 | P2
source_revisions: [imagej/ImageJ@v1.54p, imagej/imagej-legacy@imagej-legacy-3.0.0]
related_requirements: [FR-PLUG-001]
related_decisions: [DEC-010]
last_reviewed: 2026-10-17
approval: Awaiting approval     # only the project owner changes this to Approved
---
```

Body headings are drawn from the following list. Use only the ones relevant to the document, and never leave an empty heading:

1. Executive Summary
2. Purpose and Scope
3. Research Questions
4. Sources and Evidence
5. Verified Findings
6. Technical Details
7. Diagrams and Inventories
8. Constraints and Limitations
9. Requirements or Design Implications
10. Alternatives and Trade-offs
11. Risks and Unknowns
12. Decisions Required
13. References
14. Change log

Analysis documents end with **Requirement implications** (as `CR-Axx-nn` items) and **Risks** (as `R-Axx-nn` items) when they have any.

Writing rules:

- Direct technical prose. No marketing language, filler introductions or adjectives such as "seamless", "robust" or "powerful".
- No invented numbers. Use a quantitative target only with a justification, and label it PROPOSED.
- State each fact once, in its authoritative document. Elsewhere, link to it with a one-line summary.
- British English spelling.
- Code identifiers go in backticks, exactly as they appear in source.

## 6. Diagrams and data

- **Mermaid** is used for component, sequence, state and dependency diagrams. Keep each diagram under about 40 nodes and split it if it grows larger.
- **Markdown tables** are for small inventories (under about 40 rows).
- **CSV/YAML/JSON under `docs/data/`** is used for anything reused for planning or automated checks: command catalogue, parity matrix, risks, datasets, traceability and verification coverage. The data file is authoritative, and Markdown documents summarise and link to it. CSV is UTF-8 with a header row and `;`-separated multi-value cells.
- **Screenshots** go in `docs/01-analysis/references/screenshots/` and must be captures of the real pinned Fiji runtime. Each one is listed in that folder's README with the capture date, runtime version and what it shows. Mockups are never presented as screenshots.
- **Wireframes** in design documents are ASCII or Mermaid diagrams, labelled *functional wireframe — not a visual design*.

## 7. Decisions

- Each decision gets a `DEC-nnn` row in `docs/DECISIONS.md` with title, stage, status, owner, date and links.
- Architectural decisions also get an ADR file. Sections: Context, Decision, Status, Alternatives considered (with reasons for rejection), Consequences, Validation needed, Related requirements.
- Status values are `Proposed — awaiting approval`, `Accepted` (the owner approved it), `Rejected` and `Superseded`. Agents may only create `Proposed` items. Only the project owner moves an item to Accepted.

## 8. Git workflow

- Documentation-stage work happens on branch `docs/analysis-design`. `main` is not modified, and nothing is pushed or merged without explicit owner authorisation.
- Commit 1 on the branch (`08a1433`) is the snapshot of the prototype exactly as found. Later documentation-stage commits must not modify `backend/`, `frontend/`, `speech/`, `scripts/`, `tests/`, `fiji/` or `external/`. Check with `git diff 08a1433 --stat -- <those paths>`.
- Commits are grouped by phase and use imperative subject lines.
- No destructive Git operations: no force-push, history rewriting or `reset --hard` on shared refs.

## 9. Verification conventions

- A statement that tests pass names the command, the date, the environment and the result counts, and links the raw output.
- **Scientific equivalence** is established by numerical comparison against a reference execution of the original Fiji, with stated tolerances (A30, D18). Visual similarity is not equivalence.
- Probes are diagnostic scripts run against the pinned Fiji to gather evidence. They are not application code. They live in `docs/01-analysis/references/probes/` with a README giving the exact command, the date and the runtime pin hash (`verify_runtime` output).

## 10. Rules for future agent-assisted work

1. Read `docs/INDEX.md`, `docs/WORK-PROGRESS.md` and `docs/DECISIONS.md` before starting.
2. Do not treat `Proposed` decisions as approved requirements.
3. Before proposing new processing code, check A08/A11 and the parity matrix. If Fiji already implements the operation, call it. Do not reimplement it.
4. Keep claims tagged. Never fabricate a test result, commit, citation, API or benchmark.
5. Every change to a requirement or decision updates its authoritative home first, then its references.
6. Update `WORK-PROGRESS.md` at each checkpoint.

## Change log

- 2026-10-17: Initial version.
