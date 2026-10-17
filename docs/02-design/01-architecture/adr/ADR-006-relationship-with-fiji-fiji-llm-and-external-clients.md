---
id: ADR-006
title: Relationship with fiji/fiji-llm and external clients
stage: 02-design
status: Proposed — awaiting approval
priority: P0
source_revisions: [see Context]
related_requirements: [FR-NL-012, FR-SYS-003, NFR-SEC-002]
related_decisions: [DEC-011]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# ADR-006 — Relationship with fiji/fiji-llm and external clients

## Context
`fiji/fiji-llm` (BSD-2) provides in-JVM chat and an MCP server on 127.0.0.1:9090. The server has no authentication and accepts requests without an Origin header. It runs commands by menu path only (A06, A18 F9, A21 V11).

## Decision (PROPOSED)
- Do not depend on fiji-llm for the MVP.
- Keep the engine API (IF-01…03) serialisable, so that a LONG-scope external transport (IF-10) can expose it with authentication (NFR-SEC-002).
- Align any LLM tool exposure with fiji-llm's `AiToolPlugin`/MCP conventions (FR-NL-012). The product's tools would add the typed parameters and validation that fiji-llm's command tool lacks.
- Prefer contributing improvements upstream to forking.

## Consequences
There is no MCP server in MVP/HACK scope. The same JVM can host fiji-llm if a user installs it; possible interference through shared IJ1 static state must then be tested.
