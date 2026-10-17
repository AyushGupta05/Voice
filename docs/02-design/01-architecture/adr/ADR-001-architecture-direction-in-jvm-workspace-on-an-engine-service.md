---
id: ADR-001
title: Architecture direction: in-JVM workspace on an engine service (O2)
stage: 02-design
status: Proposed — awaiting approval
priority: P0
source_revisions: [see Context]
related_requirements: [FR-SYS-001, FR-SYS-003, FR-SYS-009, FR-VIEW-001, FR-PLUG-001, FR-ROI-001, NFR-SEC-002, NFR-MAINT-001]
related_decisions: [DEC-010]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# ADR-001 — Architecture direction: in-JVM workspace on an engine service (O2)

## Context
A21 compares eight options (O1–O8) against the brief's 20 criteria. Every viable option needs a component inside the Fiji JVM (A15 §8.3). In-JVM UI technology is available and works in the pinned runtime: FlatLaf is active, and JavaFX and WebView initialise (P87). The deciding differences between O2 and O4 are display and ROI fidelity, the handling of legacy windows, the absence of a network port, and the IPC and state-synchronisation burden (A21, "O2 versus O4").

## Decision (PROPOSED)
Adopt **O2**: a workspace UI built in Swing with FlatLaf and registered as a SciJava `UserInterface`. It runs on a UI-agnostic engine service (CMP-20…31). Both run inside the persistent non-headless Fiji JVM (ADR-002). The product is distributed as a Fiji update site (ADR-010). Speech runs out of process (ADR-005). JavaFX is a contingency for specific components only, for example a GPU canvas if NFR-PERF-001 measurements require one.

## Alternatives considered
| Option | Reason not chosen |
|---|---|
| O1 Patch or restyle Fiji's UI | Requires modifying upstream UI code (brief Rule 1); AWT limitations |
| O3 JavaFX shell | Adds AWT/Swing↔FX focus and modality interop with legacy dialogs. Swing is already Fiji's toolkit. Kept as a component-level contingency. |
| O4 Out-of-process web UI | Rendering and ROI semantics would have to be reimplemented or streamed (P84 encode cost). Legacy windows would be foreign to the shell. Needs an authenticated server. Remains possible later behind IF-01…03 (ADR-006, IF-10). |
| O5 PyImageJ host | Adds a JPype runtime boundary with documented macOS limits and no gain in capability (A15 §10) |
| O6 Per-run headless (prototype) | Cannot hold state. ROI Manager, Recorder and plugin windows fail headless (A15). |
| O7 napari + napari-imagej | Replaces the IJ1 interaction model (PR-2) |
| O8 WebView panels in the JVM | A third technology in the process; +256 MB (P87); no gain in fidelity |

## Consequences
- **Positive:**
  - one process for the science and the UI;
  - exact IJ1 rendering and ROI objects;
  - legacy windows can be managed in-process;
  - no network port;
  - packaging as an update site.
- **Negative:**
  - the UI must be built in Swing, and the team's Swing skill is **UNKNOWN**;
  - start-up is bound by the Fiji JVM (4.5–5.3 s, P85);
  - cross-platform behaviour is unverified (U-A15-4).

## Validation needed
- A spike, either if authorised early (DEC-012) or as milestone 1: an engine with hidden windows, a Swing viewer, an `IJ.run` worker and history (A31 §2).
- NFR-PERF-001 measurements.
- A repeat of P42/P87 on macOS before any cross-platform claim.
