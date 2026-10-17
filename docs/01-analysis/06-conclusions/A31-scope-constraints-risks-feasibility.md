---
id: A31
title: Scope, Constraints, Risks and Feasibility Conclusions
stage: 01-analysis
status: Draft
priority: P0
source_revisions: [see A01–A30]
related_requirements: [FR-*, NFR-*]
related_decisions: [DEC-010, DEC-011, DEC-012]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# A31 — Scope, Constraints, Risks and Feasibility Conclusions

## Executive Summary

- **What exists today (VERIFIED):** an integration prototype. It has 4 commands on 8-bit PNG only, runs a new headless Fiji process per workflow (about 4–5 s each, P01), recognises speech locally with faster-whisper and a regex grammar, and passes 23 tests (F02). Its architecture cannot be extended to the product (A15, A21).
- **What the analysis supports (PROPOSED, DEC-010):** a persistent non-headless Fiji JVM hosting an engine service and a modern Swing workspace, with a separate speech worker. Every element of this design has been demonstrated **in isolation** in the pinned runtime by probes P42, P47, P49, P80–P87. None has been integrated into a product.
- **Hackathon reality (INFERRED):** the hackathon ends on 18 October 2026. Building the MVP on the new architecture requires:
  - a Java build toolchain, which is absent (A22);
  - the engine service, workspace, viewer, ROI tools, history and NL/voice integration.

  Completing and verifying this within the remaining hackathon time is **not credible**. The honest hackathon deliverable is a combination of three things:
  1. the existing prototype as a working integration demonstration;
  2. this documentation baseline, including the architecture evidence;
  3. optionally, a small in-JVM spike showing the workspace pattern (DEC-012).
- **Feasibility:** MVP is feasible after the hackathon, conditional on DEC-010 approval and a Java toolchain. PARITY is feasible for the core command set and plugin categories C1–C3, with C4–C6 as legacy fallback. Full parity across all plugins is **not** a realistic claim (PR-9).

## 1. Delivery tiers

| Tier | Definition | Contents (requirements with this priority) | Status |
|---|---|---|---|
| **T0 Existing demo** | The prototype as found (F02) | 4 commands, voice/typed grammar, per-run Fiji, stage previews, generated macro | Exists; 23 tests pass |
| **MVP** | Minimum viable demonstration of the *product architecture* | All `MVP` FR/NFR in A26/A27. The 10-command MVP set (§3). The journeys marked MVP in D16. | Not started |
| **HACK** | What the brief calls hackathon target functionality. Here it means *the scope that would have been the hackathon target with full time*, achievable soon after the hackathon. | All `HACK` FR/NFR. Core set of 54 commands (`commands-core-parameters.csv`). ROI list, undo, B&C and threshold panels, one C4 and one C5 fallback demo. | Not started |
| **PARITY** | Post-hackathon parity for audited subsystems | All `PARITY` FR/NFR. Full catalogue reachable. C1–C3 driven; C4–C6 legacy fallback. Accessibility. Batch on folder. | Planned |
| **LONG** | Long-term modernisation | Cross-platform, external clients (MCP/web), optional LLM tools aligned with fiji-llm, macro toolsets, BDV integration | Exploratory |

## 2. Hackathon plan (PROPOSED — DEC-012)

| Item | Feasible by 18 Oct? | Evidence / reasoning |
|---|---|---|
| Present the T0 prototype live (typed and voice → Fiji → previews → macro) | **Yes** | Re-verified 2026-10-17 (P00). Speech fixture and integration tests pass. |
| Present the documentation baseline, with probe evidence and screenshots of the real runtime | **Yes** | This repository |
| O2 spike: a Groovy script run in Fiji (`--run`) that opens a Swing/FlatLaf window containing a viewer panel painting `ImagePlus.getBufferedImage()`, a command box executing `IJ.run(imp, …)` on a worker, and a history list emitting macro lines | **Possible but unverified.** Every building block is VERIFIED in probes (P42 hidden-window execution, P84 rendering, P87 FlatLaf panel, P81 `Recorder.recordString`). The integration is not verified. | Must be clearly labelled as a spike. It is not the MVP and must not be presented as the product. |
| MVP on the new architecture | **No** (INFERRED) | No build toolchain installed (A22). Engine, viewer, ROI, history and NL integration all remain to be built and verified (A30). |

## 3. MVP command set (PROPOSED)

The set is chosen to cover A03 workflow W02 (threshold + particle counting) end to end on real microscopy data. It exercises the result-affecting globals (BlackBackground, the measurement set) and the most common filters:

| # | command_id | Why |
|---|---|---|
| 1 | `ij1:File>Open...` (with `IJ.openImage` routing) | IO fidelity (FR-IMG-003) |
| 2 | `ij1:Process>Subtract Background...` | Common pre-processing; has an IJ1 preview |
| 3 | `ij1:Process>Filters>Gaussian Blur...` | Preview, `scaled` unit semantics (A17) |
| 4 | `ij1:Image>Adjust>Threshold...` (threshold set via `setThreshold`/`setAutoThreshold`, non-destructive) | Threshold overlay (FR-PROC-015) |
| 5 | `ij1:Process>Binary>Convert to Mask` | BlackBackground global (FR-MEAS-001) |
| 6 | `ij1:Process>Binary>Watershed` | Binary op depending on the globals |
| 7 | `ij1:Analyze>Set Measurements...` | Global measurement flags (AC5) |
| 8 | `ij1:Analyze>Measure` | Results accumulation (FR-MEAS-004) |
| 9 | `ij1:Analyze>Analyze Particles...` | Results + ROI list + mask outputs |
| 10 | `ij1:File>Save As>Tiff...` | Full-fidelity output (FR-IMG-005) |

Reference fixtures: FX-030 (BBBC001 subset; NC-SA, cache only), FX-003… ImageJ samples (Blobs), and synthetic FX-07x (A29).

## 4. Constraints

| Constraint | Source | Effect |
|---|---|---|
| Documentation-only stage; no implementation | Brief Part 2 | No spike is built in this stage (DEC-012 asks for approval to build one) |
| Pinned Fiji 2.18.1-SNAPSHOT / ImageJ 1.54p, Windows 11 | F03, A22 | All evidence is single-platform |
| No Maven, Gradle or Rust on the machine | A22 | Implementation needs a toolchain decision (D19) |
| Fiji is GPL-3.0+ as a bundle; the project licence is undecided | A24, DEC-005 | Distribution via update site keeps Fiji itself unredistributed (A21), but the product licence must still be chosen |
| Team skill profile unknown | A21 | Affects the DEC-010 decision and the hackathon spike |
| Prior work exists (fiji-llm etc.) | A06 | No novelty claims for NL control. The differentiator is the combination (A06). |

## 5. Critical risks (from `docs/data/risks.csv`; highest impact first)

| Risk (register ID / source) | Likelihood | Impact | Mitigation | Owner doc |
|---|---|---|---|---|
| Inheriting the prototype architecture by inertia (R-001 / R-F02-01) | Medium | High | ADR-001 evidence; this document | D02 |
| Global prefs side effects in a persistent session (R-002 / R-F02-02) | High | High | FR-MEAS-001 capture and restore; FR-HIST-007 | D05, D06 |
| ROI Manager singleton consistency (R-A28-02) | Medium | High | Early validation spike in implementation milestone 1 | D04 |
| IJ1 windows popping up over the workspace (R-A15-01, R-A21-03) | High | Medium | Window policy and tracker (FR-PLUG-002) | D04, D12 |
| A plausible but wrong NL command changes results unnoticed (R-A17-01) | Medium | High | Validator, consequential-parameter rule, preview (FR-NL-002…004) | D08 |
| Speech accuracy on real users lower than on TTS fixtures (R-A16-01) | High | High | Editable transcript; evaluation sets (NFR-USE-003); keyboard path | D08, D18 |
| Team lacks Swing experience (R-A21-01) | Medium | High | Groovy spike; O4 behind the same engine API | DEC-010 |
| Plugin or `IJ.getImage()` terminates the JVM (R-A15-05) | Low–Medium | High | Supervisor and replay recovery (FR-SYS-007) | D03 |
| Large stacks plus snapshots exhaust the heap (R-A23-01) | Medium | High | Snapshot budget; AC4 handling | D05 |
| Licence obligations of speech-worker native DLLs (A24 §5.6) | Medium | Medium | NFR-PKG-005; WAV-only input option (CR-A24-05) | D19 |

## 6. Blockers and decisions required before implementation

| Item | Type | Blocks | Ref |
|---|---|---|---|
| Approve or modify the architecture direction (O2) | Decision | All design-dependent implementation | DEC-010 |
| Relationship with fiji-llm | Decision | NL tool layer design (LONG items only) | DEC-011 |
| Hackathon demonstration strategy, including whether a spike may be built | Decision | Any implementation before the formal milestones | DEC-012 |
| Hosted LLM policy | Decision | FR-NL-010/011 scope | DEC-007 |
| Project code licence and distribution model | Decision | Release (not the MVP) | DEC-005 |
| Product name | Decision | Branding only | DEC-001 |
| Java build toolchain (Maven wrapper or install) | Environment | MVP implementation | A22 |
| Validation probes: legacy hook chaining (NFR-REL-004), ROI Manager singleton bridging (FR-ROI-004), viewer frame times (NFR-PERF-001) | Verification | Final design commitments in D04/D13 | A20, A23 |

## 7. Feasibility conclusions

| Question | Conclusion | Evidence tag |
|---|---|---|
| Can a modern UI drive real Fiji with persistent state? | Yes, in M2 with an in-JVM engine | VERIFIED components (P42, P82, P83); integration INFERRED |
| Can macros remain the reproducibility format? | Yes, if they are generated from the product's own structured history (not the Recorder) and verified by replay | VERIFIED Recorder limits (P81); E7 method (A30) |
| Can existing plugins keep working? | C1–C3: yes, driven. C4–C6: yes, as legacy windows. No universal guarantee. | VERIFIED categories (A13); per-plugin UNKNOWN |
| Can voice and typed NL be safe and useful? | Safe: yes, by construction (validator, preview, no code generation). Useful on real speech: UNKNOWN until evaluated. | A16/A17 probes |
| Is the hackathon MVP feasible? | No, for the new architecture. Yes, for the T0 demonstration plus the documentation. | INFERRED (§2) |

## Change log

- 2026-10-17: Initial version by the lead.
