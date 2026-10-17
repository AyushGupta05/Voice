---
id: ADR-007
title: NL interpretation: deterministic validator decides; LLM optional; no code generation
stage: 02-design
status: Proposed — awaiting approval
priority: P0
source_revisions: [see Context]
related_requirements: [FR-NL-001…013, FR-SPEECH-004]
related_decisions: [DEC-023]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# ADR-007 — NL interpretation: deterministic validator decides; LLM optional; no code generation

## Context
A17 compares four approaches: a grammar, catalogue retrieval with slot filling, LLM function calling, and LLM macro generation. In P65 a local 8B LLM matched 5 of 16 plans and produced placeholder values. The macro language runs with full privilege (A25).

## Decision (PROPOSED)
CMP-40 has two deterministic front ends and one optional one:
- (a) a controlled-language grammar;
- (b) catalogue retrieval with slot filling;
- (c) optionally, an LLM with schema-constrained output.

All three produce a `CommandPlan` (IF-07). CMP-41 validates every plan deterministically against `CommandSchema` and the live state, then decides by policy whether to confirm, clarify or refuse. No path executes generated code.

## Consequences
The interpreter's coverage grows as schemas are added. The LLM can improve recall but never decides. The evaluation set gates releases (NFR-USE-003).
