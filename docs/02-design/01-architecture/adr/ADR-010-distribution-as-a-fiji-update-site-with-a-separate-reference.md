---
id: ADR-010
title: Distribution as a Fiji update site with a separate reference install for verification
stage: 02-design
status: Proposed — awaiting approval
priority: P0
source_revisions: [see Context]
related_requirements: [FR-SYS-008, FR-SYS-009, FR-SYS-010, NFR-FID-004, NFR-PKG-001…007]
related_decisions: [DEC-026]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# ADR-010 — Distribution as a Fiji update site with a separate reference install for verification

## Context
Bundling Fiji raises redistribution and licence obligations (A24). fiji-llm sets a precedent for distributing through an update site. Users' Fiji installs drift from the pinned version (A22).

## Decision (PROPOSED)
- Distribute the product's jars through a Fiji update site.
- Distribute the speech worker as a separately built environment (D19).
- Run verification against a pinned, lock-checked reference install (NFR-FID-004).
- Warn at start-up when the installed versions do not match (FR-SYS-010).

## Consequences
Licence work focuses on the product's own artefacts and the speech bundle. DEC-005 remains open.
