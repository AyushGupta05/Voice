# Decision Register

This is the authoritative list of project decisions (F04 §7). Architectural decisions also have an ADR file in [`02-design/01-architecture/adr/`](02-design/01-architecture/adr/).

Status vocabulary:

- **Accepted**: the project owner approved it explicitly, in the brief or in a direct answer.
- **Proposed — awaiting approval**: a recommendation that is not yet binding.
- **Open**: no recommendation yet.
- **Rejected** / **Superseded**.

| ID | Decision | Stage | Status | Basis / owner action | Links |
|---|---|---|---|---|---|
| DEC-001 | Use the neutral working title "Fiji Modernisation Project" in new planning docs. Rename nothing (repo, remote, `fidelis` package, UI) until a name is chosen. | 00 | **Accepted** for the working title (brief §1.1). The final product name is **Open**. | Owner to choose a name: Click-to-Batch, Fidelis, Mikra or another | F01 |
| DEC-002 | Documentation-stage Git workflow: branch `docs/analysis-design`; commit 1 (`08a1433`) snapshots the prototype as found; documentation commits per phase; no push or merge. | 00 | **Accepted** (owner answer, 2026-10-17) | — | F04 §8 |
| DEC-003 | Pin upstream source analysis to tags matching the installed jars. Keep the research cache outside the repository at `C:\Users\arnav\Documents\dev\fiji-research-cache`. Vendor no upstream source. | 00 | **Accepted** (owner answer, 2026-10-17) | — | F03 |
| DEC-004 | Treat the existing prototype as experimental evidence, not the approved architecture. Preserve it unmodified. | 00 | **Accepted** (brief §1.2) | — | F02 |
| DEC-005 | Choose the licence for the project's original code, and the redistribution model: bundle Fiji, the JDK and models, distribute through a Fiji update site, or install the official archive by checksum. The decision also covers the PyAV/FFmpeg (x264/x265) and NVIDIA/Intel DLLs in the speech environment. | 01 | **Open** | Owner decision, informed by A24 §10 and lawyer questions Q-01–Q-10 (not legal advice). A21 notes that update-site distribution avoids redistributing Fiji itself. | A24, A21 |
| DEC-006 | Documentation layout adaptation: keep the authoritative machine-readable catalogues in a single `docs/data/` folder, and keep the pre-existing `docs/*.md` unedited as prototype records P01–P05. To support this, two narrow negations were added to `.gitignore` so that `docs/data/` and probe `*.log` files are tracked. | 00 | Proposed — awaiting approval | Avoids duplicate authoritative copies (brief Part 10) | F04, INDEX |
| DEC-007 | Whether hosted LLM providers may be used at all, and which data classes may leave the machine. Proposed baseline: text and metadata only, never pixels, opt-in per session, with an LLM-free default build. | 01 | Proposed — awaiting approval | A17 CR-A17-08/09, A25, FR-NL-009/010 | A17, A25, A26 |
| DEC-010 | Architecture direction: option **O2**. A modern workspace UI (Swing + FlatLaf, registered as a SciJava `UserInterface`) runs inside a persistent non-headless Fiji JVM, with the main window hidden, on a UI-agnostic engine service. It is distributed as a Fiji update site. Speech runs in a separate persistent faster-whisper worker. The NL interpreter is deterministic, with an optional local LLM. O4 (an out-of-process web UI) stays possible behind the same serialisable engine API. | 01→02 | Proposed — awaiting approval | A21 comparison matrix; A15; probes P42, P47, P49, P80–P87. Owner input is needed on team skills (Java/Swing versus web). | A21, ADR-001 |
| DEC-011 | Relationship with `fiji/fiji-llm`: interoperate rather than depend. Reuse its conventions (`AiToolPlugin`/MCP) for any LLM tool exposure (LONG scope). Do not depend on it for the MVP. Do not copy its unauthenticated loopback pattern. | 01→02 | Proposed — awaiting approval | A06, A18 F9, A21 V11 | A21, ADR-006 |
| DEC-012 | Hackathon demonstration strategy: demonstrate the existing prototype (T0) and the documentation baseline. Build a clearly labelled in-JVM spike only if the owner authorises implementation work before the formal milestones. | 01 | Proposed — awaiting approval | A31 §2 | A31 |

Design decisions (DEC-020 onwards) and their ADRs are added in the design stage (D02).
