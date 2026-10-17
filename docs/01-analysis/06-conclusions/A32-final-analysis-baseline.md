---
id: A32
title: Final Analysis Baseline
stage: 01-analysis
status: Baselined (awaiting approval)
priority: P0
source_revisions: [docs/data/upstream-manifest.yaml]
related_requirements: [all]
related_decisions: [DEC-001, DEC-005, DEC-007, DEC-010, DEC-011, DEC-012]
last_reviewed: 2026-10-17
approval: Awaiting approval — the analysis is NOT declared approved by this document
---

# A32 — Final Analysis Baseline

## Executive Summary

The analysis stage produced 32 analysis documents (A01–A32), 4 foundation documents, 6 machine-readable catalogues in `docs/data/` and 49 probe IDs (scripts plus raw outputs) against the pinned runtime. Its central conclusion: **faithful Fiji modernisation is feasible only with a persistent, GUI-capable (non-headless, main window hidden) Fiji JVM and an engine component inside that JVM.** Modern UI technology can sit on top. The scientific engine, macro language and plugins stay unchanged. The proposed direction is an in-JVM Swing workspace (A21, DEC-010), which awaits owner approval. This document summarises the verified findings and states which parts of the analysis are complete, which are incomplete, and why.

## 1. Most important verified findings

| # | Finding | Evidence |
|---|---|---|
| 1 | Headless Fiji cannot host the ROI Manager, Recorder, `NonBlockingGenericDialog`, OME-TIFF export or plugin windows. `Measure` keeps only one row. Window-based commands fail silently. | P35, P39, P41, P46; A08 P13; A15 |
| 2 | A non-headless JVM that never shows the Fiji main window runs all of these, including hidden windows, batch mode, hidden ROI Manager and Recorder, and dialogs driven from code. Windows must be hidden by explicit policy. | P42, P47, P82, P83 |
| 3 | The UI is a SciJava plugin slot. A new `UserInterface` can be registered at runtime. FlatLaf is active. JavaFX with d3d and WebView initialises inside Fiji. | P48, P49, P87 |
| 4 | `IJ.run` is synchronous on the calling thread, binds macro options and the temp image per thread, renames the thread `Run$_…` permanently, and **swallows plugin exceptions**. | A15 §6; A08; P80 |
| 5 | The Recorder is exact for real GUI actions but loses programmatic calls (thread-name gating) and the options of preview dialogs (a `GenericDialog` ordering defect). `Recorder.recordString` injects exact text. | P81; `Recorder.java#L151-L157`; `GenericDialog.java#L1510-L1589` |
| 6 | `CommandListener` sees every `Executer` command with its options and can veto it. It misses macro built-ins, IJ2 commands and `ModuleService` legacy runs, which SciJava module events cover. No IJ1 API notifies Results changes. | P31, P80 |
| 7 | Results depend on JVM-global settings (BlackBackground, measurement flags, conversion scaling, remembered dialog values, modifier keys). Display range changes 8-bit conversion output. CSV export rounds to 3 decimals. | A11, A17, A19, P37 |
| 8 | Undo is a single static slot, reset on image switch, with no whole-stack undo. Cancelling a stack filter leaves partial results. | P33, P86; A19 |
| 9 | 1,390 commands in the installed catalogue (944 runtime IJ1-table labels). 26 label whitespace collisions. Only 4 of 1,872 modules declare themselves headless-safe. | A08; `commands.csv` |
| 10 | Pixel access in the JVM is sub-millisecond to low-millisecond. Encoding dominates any out-of-process transfer. GUI JVM start-up takes 4.5–5.3 s and about 450 MB. `System.exit` hangs in GUI mode. | P84, P85 |
| 11 | Local speech (faster-whisper tiny.en, CPU) transcribes a 2 s command in about 0.2 s warm. Without VAD it hallucinates on silence. Vocabulary prompts both help and hurt. GPU needs extra NVIDIA DLLs. | P60–P64 |
| 12 | A local 8B LLM with schema-constrained output matched only 5 of 16 expected plans and emitted placeholder values. Deterministic validation must decide execution. | P65 |
| 13 | NL control of Fiji is not novel. The official `fiji/fiji-llm` exists (in-JVM, MCP, no auth token, parameterless command runs). | A06; fiji-llm source |
| 14 | The core stack is public domain or BSD. Many Fiji plugins are GPL, 161 jars declare no licence, and the speech venv bundles x264/x265 and NVIDIA/Intel DLLs. BBBC data is NC-SA. | P70, P73; A24, A29 |
| 15 | The macro language is full-privilege (`exec`, `eval`, `call`, `File.delete`, network). The updater uses SHA-1 without signatures and has an HTTP fallback. | A25 |

## 2. Product requirements (baseline)

- **Functional:** [A26](../05-requirements/A26-functional-requirements.md). 108 FRs across 13 areas: SYS 11, CMD 7, PROC 16, IMG 11, VIEW 6, ROI 7, MEAS 5, HIST 10, MACRO 7, PLUG 3, NL 13, SPEECH 7, WS 5. All 223 candidate requirements are mapped (A26 §15), and four conflicts are resolved (A26 §14).
- **Non-functional:** [A27](../05-requirements/A27-non-functional-requirements.md). 43 NFRs. Performance targets are set only where measured, with "measure first" obligations elsewhere.
- **Parity:** [A28](../05-requirements/A28-fiji-functional-parity-matrix.md) and `parity-matrix.csv` (52 subsystems):
  - complete: 23
  - complete (new): 2
  - partial: 18
  - legacy fallback: 5
  - deferred: 2
  - unsupported: 2
- **Verification strategy:** [A30](../05-requirements/A30-scientific-correctness-evaluation.md). Reference execution of the same pinned Fiji, with E1–E9 dimensions and exact tolerances by default.

## 3. Critical compatibility constraints

1. Engine mode M2 is mandatory for interactive use (FR-SYS-001). Headless only for batch steps verified to be headless-safe (FR-PROC-009).
2. Every command gets an explicit target and complete options (FR-IMG-002, FR-PROC-003). Do not rely on IJ1 focus or remembered values.
3. The history is the source of reproducibility, not the Recorder (FR-HIST-001/002/009).
4. Plugins in C4–C6 remain native windows (FR-PLUG-001). There is no universal compatibility claim (PR-9).
5. Display rendering uses ImageJ's own rendering, or a renderer proven equivalent (FR-VIEW-001).

## 4. Recommended architectural direction (PROPOSED — DEC-010)

Option O2 (A21): the workspace UI (Swing + FlatLaf, a registered SciJava `UserInterface`) and a UI-agnostic engine service both run inside the Fiji JVM, distributed as a Fiji update site. Speech runs in a separate persistent faster-whisper worker. The NL interpreter is deterministic, with an optional local LLM. The engine API is serialisable, so O4-style external clients remain possible. Alternatives and the matrix scores are in A21.

## 5. Unresolved decisions

DEC-001 (name), DEC-005 (licence and distribution), DEC-006 (documentation layout), DEC-007 (hosted LLM policy), DEC-010 (architecture), DEC-011 (fiji-llm relationship) and DEC-012 (hackathon strategy). All are *Open* or *Proposed — awaiting approval* in [`DECISIONS.md`](../../DECISIONS.md).

## 6. Source and dependency inventory

[`docs/data/upstream-manifest.yaml`](../../data/upstream-manifest.yaml): 24 components, each with its tag or SHA, licence declaration and usage. The research cache can be recreated from it (F03). Per-jar licences are in `probes/P70-jar-licences.csv` (A24).

## 7. Implementation feasibility

Summarised from A31 §7. MVP is feasible after DEC-010 approval and a Java toolchain. HACK-scope features follow soon after. PARITY covers C1–C3 driven and C4–C6 as fallback. The hackathon itself can show the T0 prototype, the documentation baseline and an optional spike (DEC-012).

## 8. Requirements ready for design

All FRs and NFRs are ready for design, **except** those that depend on validation probes still pending:

- NFR-REL-004 (hook chaining for progress);
- FR-ROI-004 (ROI Manager singleton bridging; an implementation detail to validate in D04);
- NFR-PERF-001 (viewer measurements);
- FR-SYS-009/010 (update-site packaging details; D19).

The design will treat these as provisional, with the conditions recorded.

## 9. Analysis completeness assessment (brief Part 17 — Analysis gate)

| Gate item | Assessment | Notes |
|---|---|---|
| Fiji's major functional subsystems understood | **Satisfied** | A07–A15 with source-pinned citations and runtime probes |
| Existing interface behaviours inventoried | **Satisfied, with gaps** | A04 and 17 real screenshots. Expanded menus and popup menus were not captured (the screen saver was active, G4). The command catalogue is complete for the installed distribution. |
| Image state and scientific operation semantics documented | **Satisfied** | A09–A11, A19 |
| Macro and plugin compatibility constraints understood | **Satisfied** | A12, A13, A15. Per-plugin runtime classification beyond the static scan is UNKNOWN (A13 U-A13-2). |
| Voice interaction requirements defined | **Satisfied for design; accuracy unverified** | A16/A17. No human speech data yet (NFR-USE-003). |
| Feasible integration options compared | **Satisfied** | A21 (8 options × 20 criteria) |
| Representative datasets and fixtures identified | **Satisfied** | A29 and `datasets.csv` (52 fixtures). Licences recorded; nothing downloaded. |
| Requirements traceable and testable | **Satisfied** | A26/A27 acceptance criteria and CR mapping. Design traceability follows in D20 and `traceability.csv`. |
| Risks and unknowns documented | **Satisfied** | `risks.csv` (133), plus per-document unknowns |
| Scope and compatibility levels explicitly defined | **Satisfied** | A28 statuses, A31 tiers, PR-9 |

**Known limitations of this analysis:**

- Single-platform evidence (Windows 11).
- Synthetic speech only.
- No large-dataset performance data.
- Third-party update sites not audited.
- Some agent-written documents contain statements later refined by other probes. The Phase 8 review annotates these, for example A07/A15 on progress forwarding (A26 §14-C2) and A18 on `CommandListener` coverage (updated).

The analysis is **baselined for design**. It is **not approved**: approval is the owner's decision.

## Change log

- 2026-10-17: Baseline by the lead.
