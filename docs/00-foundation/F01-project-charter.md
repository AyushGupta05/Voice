---
id: F01
title: Project Charter
stage: 00-foundation
status: Draft
priority: P0
source_revisions: n/a
related_requirements: []
related_decisions: [DEC-001, DEC-002, DEC-004]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# F01 — Project Charter

## Purpose

Modernise the **Fiji/ImageJ desktop experience**: its interface, organisation, interactivity, accessibility and workflow support. The scientific functionality, macro language, plugin ecosystem and interaction semantics developed by the Fiji/ImageJ community are preserved and reused. Typed natural-language and spoken instructions are added as further ways to drive the *same* operations, with the user remaining in control.

Working title: **Fiji Modernisation Project**. Candidate names are Click-to-Batch, Fidelis and Mikra. The name is undecided (DEC-001).

Origin: the *Reinvent the Wheel: A Tools for Science Hack*, London, 16–18 October 2026.

## Stakeholders

| Stakeholder | Interest | Role in this stage |
|---|---|---|
| Project owner (repository owner) | Product direction, approvals, naming, licence | Approves decisions marked *Proposed* |
| Hackathon team and judges | A credible demonstration by 18 Oct 2026 | Consumers of the MVP/HACK scope (A31) |
| Scientific users: microscopists, biologists, image analysts, students | Correct results, familiar operations, less friction | Represented through the personas in A02 |
| Macro/plugin developers | Their existing code keeps working | Represented in A02 and A13 |
| Fiji/ImageJ upstream community | Respectful reuse, licence compliance, no fragmentation | Upstream: the source of truth for algorithms (F03) |

## Principles (stable IDs, referenced throughout)

| ID | Principle | Consequence |
|---|---|---|
| **PR-1 Fidelity** | Scientific results come from original Fiji/ImageJ code paths. | No reimplemented algorithm stands in for a Fiji command (Rule 3). Parity is verified numerically (A30). |
| **PR-2 Familiarity** | Command names, menu semantics, parameter names, the macro language and key behaviours (selections, display range versus pixel data, Results) stay recognisable. | A04 and A28 define what must be preserved. |
| **PR-3 One state, many inputs** | Mouse, keyboard, menus, command search, typed instructions, speech and macros all act on one shared scientific state. | No parallel "AI-only" pipeline (A18). |
| **PR-4 User authority** | Natural-language and voice input *propose*. The user sees the resolved command and its parameters and confirms consequential actions. The language model never gets unrestricted execution. | A17, A19 |
| **PR-5 Observable truth** | The UI shows real state and real events only. There are no simulated timelines or decorative progress. | A20, D15 |
| **PR-6 Reproducibility** | Every applied operation is recorded as standard ImageJ macro text plus provenance (inputs, versions, parameters, preferences that affect results). | A12, D07 |
| **PR-7 Reuse over rebuild** | Prefer an existing Fiji, ImageJ2, SciJava or Bio-Formats capability over new code. New code is UI, orchestration and adapters. | F03, A21 |
| **PR-8 Local-first privacy** | Image data stays on the user's machine. Speech runs locally. Any external service is opt-in and receives no pixel data. | A25 |
| **PR-9 Honest compatibility** | Support is stated per compatibility category (A13) and per parity status (A28), never as universal Fiji compatibility. | Documentation and UI messaging |

## Scope boundaries

Product scope, goals and non-goals are defined in **A01**. Delivery tiers (MVP / HACK / PARITY / LONG) and feasibility are in **A31**. The charter sets only the outer boundary:

- **In scope:** a new desktop workspace over the existing Fiji runtime; command, macro and plugin access; ROI, measurement, stack and channel interaction; history and recording; natural-language and voice command resolution; verification against original Fiji.
- **Out of scope:**
  - new image-processing algorithms that duplicate Fiji;
  - a new macro language;
  - forks of Fiji or ImageJ source;
  - training of image models;
  - cloud hosting of image data;
  - guarantees of universal third-party plugin compatibility.

## Constraints

- **Time:** the hackathon ends on 18 October 2026. Analysis and design must separate demonstration scope from parity work (Rule 7).
- **Platform:** the development and test machine is Windows 11 x64 (A22). Other platforms are untested.
- **Licensing:** Fiji is a GPL-3.0+ distribution with components under mixed licences, and the project's own licence is undecided (A24).
- **Process:** this stage produces documentation only. No application implementation, no renaming, and no push or merge without authorisation (brief Part 2).

## Success criteria for this stage (Stage 00–02)

1. Every gate in brief Part 17 is assessed honestly in A32 (analysis) and D20 (design).
2. Every requirement in A26/A27 is testable, sourced and traceable to a design element (D20 traceability).
3. The recommended architecture (ADR-001) is justified against the 20 criteria in A21 with tagged evidence.
4. Every decision the owner must take is listed in `DECISIONS.md` with status *Proposed — awaiting approval*.

Product success criteria are in A01 §6.
