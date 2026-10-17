# Decision Register

The authoritative list of project decisions (F04 §7). Architectural decisions link to an ADR file in [`02-design/01-architecture/adr/`](02-design/01-architecture/adr/).

Status vocabulary:

- **Accepted**: the project owner approved it explicitly, in the brief or in a direct answer.
- **Proposed — awaiting approval**: a recommendation that is not yet binding.
- **Open**: no recommendation yet.
- **Rejected** / **Superseded**.

| ID | Decision | Stage | Status | Basis / owner action | Links |
|---|---|---|---|---|---|
| DEC-001 | Use the neutral working title "Fiji Modernisation Project" in new planning docs. Rename nothing (repo, remote, `fidelis` package, UI) until a name is chosen. | 00 | **Accepted** (working title, per brief §1.1). The final product name is **Open**. | Owner to choose a name: Click-to-Batch, Fidelis, Mikra or another | F01 |
| DEC-002 | Documentation-stage Git workflow: branch `docs/analysis-design`; commit 1 (`08a1433`) snapshots the prototype as found; documentation commits per phase; no push or merge. | 00 | **Accepted** (owner answer, 2026-10-17) | — | F04 §8 |
| DEC-003 | Pin upstream source analysis to tags matching the installed jars. Keep the research cache outside the repository at `C:\Users\arnav\Documents\dev\fiji-research-cache`. Vendor no upstream source. | 00 | **Accepted** (owner answer, 2026-10-17) | — | F03 |
| DEC-004 | Treat the existing prototype as experimental evidence, not the approved architecture. Preserve it unmodified. | 00 | **Accepted** (brief §1.2) | — | F02 |
| DEC-005 | The licence for the project's original code, and the redistribution model for bundled Fiji, JDK and models | 01 | **Open** | Owner decision, informed by A24 (not legal advice) | A24 |
| DEC-006 | Documentation layout adaptation: put the authoritative machine-readable catalogues in a single `docs/data/` folder (not spread across stage folders), and keep the pre-existing `docs/*.md` unedited as prototype records P01–P05. | 00 | Proposed — awaiting approval | Avoids duplicate authoritative copies (brief Part 10) | F04, INDEX |

Architecture and design decisions (DEC-010 onwards) are added in Phase 5.
