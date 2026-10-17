---
id: D09
title: Internal APIs and Event Contracts
stage: 02-design
status: Draft (provisional on DEC-010)
priority: P0
source_revisions: [scijava/scijava-common@scijava-common-2.101.0, imagej/ImageJ@v1.54p]
related_requirements: [FR-SYS-003, FR-SYS-006, FR-IMG-001, FR-HIST-001, FR-HIST-008, FR-PROC-005, FR-PROC-007, NFR-REL-005, NFR-PERF-004, NFR-PERF-005, NFR-SEC-002]
related_decisions: [ADR-001, ADR-006, ADR-011]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# D09 — Internal APIs and Event Contracts

## Executive Summary

The engine exposes three in-process interfaces to the UI and the interaction services:

- **IF-01**: action submission;
- **IF-02**: the event stream;
- **IF-03**: queries.

All payloads are immutable, serialisable value objects (FR-SYS-003), so the same contracts could later be carried over an authenticated external transport (IF-10, LONG scope, NFR-SEC-002). This document defines operations, message schemas, ordering guarantees, threading and versioning, and gives sequence diagrams for the key flows. It is a design contract, not code. Payloads are shown as JSON for readability; in-process they are Java value objects with the same fields.

## 1. Conventions

- **Versioning:** every message carries `"v": 1`. Fields are only ever *added* within a major version.
- **Identifiers:** `sessionId`, `docId`, `operationId`, `requestId` (D05 §6).
- **Time:** ISO-8601 UTC, plus a monotonic `seq` per session for ordering.
- **Threading:**
  - IF-01 calls return immediately with an `operationId`. They never block the caller, so they are safe on the EDT.
  - IF-03 queries are answered from the last committed digest and caches without entering the engine worker, except `renderPlane`, which runs on a render executor that holds a short read lock and copies (NFR-PERF-004).
  - IF-02 events are delivered to UI subscribers **on the EDT** in `seq` order, and to non-UI subscribers on their own executor (NFR-PERF-005).
- **Errors:** a uniform envelope `{code, message, details, recoverable, suggestions[]}` using the D06 §7 codes.

## 2. IF-01 Action submission

| Operation | Input | Output | Notes |
|---|---|---|---|
| `submit(ActionRequest)` | D06 §2 | `{operationId}` or an immediate error (`ENGINE_NOT_READY`, `UNKNOWN_COMMAND`) | Validation proceeds asynchronously; outcomes arrive on IF-02 |
| `submitPlan(CommandPlan, confirmationToken?)` | D08 §2.3 | `{planId, operationIds[]}` | Steps are queued in order. Validation runs per step before execution (FR-NL-002). |
| `confirm(operationId, token)` / `decline(operationId)` | — | ack | Moves `AWAITING_CONFIRMATION` → `VALIDATING` / `CANCELLED` |
| `cancel(operationId)` | — | ack | Cooperative cancel (D06 §6; FR-PROC-013) |
| `undo(docId)` / `redo(docId)` | — | `{operationId}` | DIRECT operations (FR-HIST-004) |
| `quit(policy)` | `{unsaved: ASK\|SAVE\|DISCARD}` | ack | D03 §6 |

## 3. IF-02 Events (authoritative list)

| Event | Key fields | Emitted when | Requirement |
|---|---|---|---|
| `EngineStateChanged` | `state` (STARTING, INITIALISING_FIJI, BUILDING_CATALOGUE, READY, FAILED), `reason` | Start-up and shutdown transitions | FR-SYS-006 |
| `OperationStateChanged` | `operationId`, `requestId`, `state` (D06 §6), `error?`, `consequenceClass`, `requiresConfirmation?` | Every lifecycle transition | NFR-REL-005 |
| `OperationProgress` | `operationId`, `kind` (DETERMINATE\|STAGE), `current`, `total`, `source` (`legacyHook`\|`productLoop`\|`planStep`), `statusText?` | Only from real counters (A20 §1) | NFR-USE-002 |
| `DialogPending` | `operationId`, `windowTitle`, `dialogClass`, `fields[]` (read-only summary) | Modal dialog detected during an operation | FR-PROC-007 |
| `StateDigest` | `operationId?`, `seq`, `documents{added, removed, changed{docId, pixelsChanged, typeChanged, displayChanged, roiChanged, dims}}`, `results{docId, rowsAdded, tableTitle}`, `rois{docId, added, removed, modified}`, `globals{delta}`, `log{newLines[] (sanitised)}`, `windows{opened[], closed[]}` | After every completed, failed or observed action | FR-IMG-001, NFR-REL-005 |
| `HistoryAppended` | `record` (IF-05) | A record is committed (executed or observed) | FR-HIST-001, FR-HIST-008 |
| `ObservedAction` | `label`, `options?`, `moduleInputs?`, `sourceWindow?`, `confidence` (FULL\|PARTIAL\|NONE) | `CommandListener` or module event not caused by the product, or a digest-only change | FR-HIST-008 |
| `LegacyWindowChanged` | `windowId`, `title`, `class`, `category` (IJ1_TOOL\|PLUGIN\|DIALOG\|IMAGE_VISIBLE), `state` (OPENED\|CLOSED\|FOCUSED) | Window tracker | FR-PLUG-001/002 |
| `CatalogueChanged` | `added[]`, `removed[]`, `version` | Catalogue rebuilt (e.g. plugin installed) | FR-CMD-002 |
| `SnapshotBudgetChanged` | `usedBytes`, `budgetBytes` | After a snapshot or eviction | NFR-PERF-003 |
| `SpeechStateChanged` | `state` (OFF, STARTING, READY, RECORDING, TRANSCRIBING, UNAVAILABLE), `reason?` | Worker lifecycle | FR-SPEECH-002, NFR-REL-002 |

**Ordering guarantees:**

1. For one operation, `OperationStateChanged(RUNNING)` precedes any `OperationProgress`, which precedes its terminal state, which precedes the corresponding `StateDigest`, which precedes `HistoryAppended`.
2. Digests are complete for the operation that produced them.
3. IJ1 `ImageListener` callbacks (asynchronous, EDT) are **not** forwarded as authoritative events. They only trigger a coalesced digest when no operation is running, to detect observed changes (A20 R-A20-02).

Example `StateDigest`:

```json
{"v":1,"type":"StateDigest","seq":512,"operationId":"op-7f3a-58",
 "documents":{"changed":[{"docId":"doc-3","pixelsChanged":true,"typeChanged":false,"displayChanged":false,"roiChanged":false,"dims":{"w":1024,"h":1024,"c":3,"z":5,"t":1,"bitDepth":16}}],"added":[],"removed":[]},
 "results":[],"rois":[],"globals":{"delta":{}},
 "log":{"newLines":[]},"windows":{"opened":[],"closed":[]}}
```

## 4. IF-03 Queries

| Query | Returns | Notes |
|---|---|---|
| `listDocuments()` | `DocumentInfo[]`: docId, title, type, dims, calibration summary, status, provenance summary, virtual-stack flag, global-calibration flag | FR-IMG-011 |
| `renderPlane(docId, c, z, t, region?, scaleHint?)` | ARGB raster copy from `getBufferedImage`-equivalent rendering, including overlays. Cached by (docId, position, displayStateHash). | ADR-009, FR-VIEW-001/005 |
| `pixelValue(docId, x, y, c, z, t)` | `{locationString, valueString, raw[], calibrated[]}` formatted per `getLocationAsString`/`getValueAsString` | FR-VIEW-003 |
| `subtitle(docId)` | The legacy subtitle fields | FR-VIEW-002 |
| `rois(docId)` | Ordered `RoiInfo[]` with float geometry, positions, names and properties | FR-ROI-001/004 |
| `results(docId, since?)` | Rows at full precision, with column metadata | FR-MEAS-003 |
| `globals()` | `GlobalsSnapshot` plus "unknown since" markers | FR-MEAS-002 |
| `catalogue(query?)` / `schema(commandId)` | Catalogue entries and `CommandSchema` (IF-04) | FR-CMD-001…005 |
| `history(range)` / `exportMacro(range)` | `HistoryRecord[]` / `.ijm` text plus `.workflow.json` (IF-05) | FR-HIST-002 |
| `legacyWindows()` | Tracked windows | FR-PLUG-002 |
| `engineInfo()` | Fiji/ImageJ versions, runtime lock hash (if a reference install), engine mode, supported-version check | FR-SYS-010 |

## 5. IF-05 History record (summary)

The fields are listed in D06 §8. The record is serialised as JSON lines in the journal and in `.workflow.json`. Its `macroLines` are derived from the typed parameters by the D07 rules, and it carries `schemaVersion` and `catalogueVersion` for future migration.

## 6. Key sequences

### 6.1 Menu command with complete schema (J-04 Measure)

```mermaid
sequenceDiagram
  participant UI as Workspace UI (EDT)
  participant E as Engine facade (IF-01..03)
  participant W as Engine worker
  participant F as Fiji (IJ1)
  UI->>E: submit(ActionRequest{MENU, Analyze>Measure, target doc-3})
  E-->>UI: operationId op-58
  E->>W: enqueue
  W->>W: VALIDATING (schema, preconditions, consequence class + policy)
  W-->>UI: OperationStateChanged(RUNNING)
  W->>F: IJ.setKeyUp(ALL); globals snapshot; IJ.run(imp,"Measure","")
  F-->>W: returns (no exception signal)
  W->>W: postconditions RESULTS_ROWS (+1 row), log scan, dialog check
  W-->>UI: OperationStateChanged(COMPLETED)
  W-->>UI: StateDigest(results doc-3 rowsAdded=1)
  W-->>UI: HistoryAppended(record with run("Measure"))
  UI->>E: results(doc-3, since=…)
  E-->>UI: rows (full precision)
```

### 6.2 Command that unexpectedly opens a dialog

```mermaid
sequenceDiagram
  participant UI as UI
  participant W as Engine worker
  participant F as Fiji
  participant T as Window tracker / dialog watch
  Note over W: command has no schema
  W->>F: IJ.run(imp, "SomePlugin", "")
  F->>F: GenericDialog.showDialog() (modal, blocks worker)
  T-->>UI: DialogPending(op-60, "SomePlugin")
  UI->>UI: banner "Waiting for input in a Fiji dialog" + bring dialog to front
  Note over UI,F: user fills the native dialog and presses OK
  F-->>W: returns
  W-->>UI: OperationStateChanged(COMPLETED) + StateDigest + HistoryAppended(options captured via CommandListener if available)
```

### 6.3 Observed action from a legacy plugin window

```mermaid
sequenceDiagram
  participant P as Plugin window (C4)
  participant F as Fiji
  participant O as Observation hub
  participant UI as UI
  P->>F: IJ.run("Gaussian Blur...", "sigma=3") via Executer
  F->>O: CommandListener.commandExecuting("Gaussian Blur...") + Macro.getOptions()
  F-->>O: ImageListener.imageUpdated (EDT, async)
  O->>O: coalesce → digest (no product operation running)
  O-->>UI: ObservedAction(label, options, confidence=FULL) + StateDigest + HistoryAppended(observed)
```

## 7. Synchronisation rules

| Rule | Statement |
|---|---|
| SY-1 | UI caches (rendered planes, ROI copies, result rows) are invalidated only by `StateDigest` flags or by explicit view-state changes |
| SY-2 | While an operation is RUNNING, the UI shows the pre-operation state, marked as busy. It does not render intermediate pixel states, except real per-slice progress text. |
| SY-3 | DIRECT operations from the ROI editor are optimistic in the UI (the drawn shape stays visible) and are committed on `COMPLETED`. On failure, the shape reverts. |
| SY-4 | Observed changes outside an operation are coalesced into at most one digest per 250 ms (PROPOSED) |

## 8. IF-10 External transport (LONG; disabled by default)

The transport is a transport-only adapter carrying IF-01/02/03 messages. It must:
- bind to loopback;
- check Host and Origin, including the WebSocket handshake;
- require a per-launch random token;
- enforce an allow-list of operations (no AC7).

It is required only if DEC-010 changes to O4, or for LONG-scope MCP or external clients (ADR-006, NFR-SEC-002).

## Change log

- 2026-10-17: Initial version by the lead.
