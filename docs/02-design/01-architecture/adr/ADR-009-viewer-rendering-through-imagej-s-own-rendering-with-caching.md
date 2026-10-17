---
id: ADR-009
title: Viewer rendering through ImageJ's own rendering with caching
stage: 02-design
status: Proposed — awaiting approval
priority: P0
source_revisions: [see Context]
related_requirements: [FR-VIEW-001, FR-VIEW-005, NFR-PERF-001]
related_decisions: [DEC-025]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# ADR-009 — Viewer rendering through ImageJ's own rendering with caching

## Context
`getBufferedImage` takes 1.7–15 ms per 2048² plane (P84) and applies IJ1's LUT, display-range and composite logic. Reimplementing rendering risks fidelity (A21).

## Decision (PROPOSED)
CMP-02 obtains rendered planes from IJ1 (`ImagePlus.getImage()`/`getBufferedImage()`, with overlays). It caches them per plane and display state, and draws them scaled with Java2D. A GPU path (JavaFX or a custom LUT) may be added only if NFR-PERF-001 measurements require it, and only after it passes the E3 rendering-equality tests.

## Consequences
Very large or pyramidal images need a different path: BigDataViewer as a C4 fallback (LONG scope).
