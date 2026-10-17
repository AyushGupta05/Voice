---
id: DEV-07
title: Workstream contracts and ownership
stage: 03-development
status: Proposed (team approval pending)
priority: P0
source_revisions: [development/fidelis-workstation @ 215fdb8, sc.fidelis.engine.api as implemented]
related_requirements: [FR-PROC-001, FR-HIST-002, FR-MACRO-001, FR-NL-001, FR-SPEECH-004, NFR-MAINT-004]
related_decisions: [DEC-010, DEC-020, DEC-021, DEC-023]
last_reviewed: 2026-10-17
approval: Awaiting approval by the three developers
---

# Workstream contracts and ownership

Written for the three Claude Code sessions (engine, voice, UI) and their developers. The **implemented
code is authoritative**: this page summarises it so that nobody needs to re-derive it, and when the two
disagree the code wins and this page is corrected. Signatures below were read from
`development/fidelis-workstation` @ `215fdb8`.

## 1. Ownership

`python -I fidelis-app/tools/check_ownership.py --stream engine|voice|ui|integrator` classifies every
changed path of your branch with the rules below (first match wins) and fails when a path is outside
your stream. Run it before every push that opens or updates a PR.

| Stream | Owner | Owns (write) |
|---|---|---|
| `engine` | Arnav | `fidelis-app/src/main/java/sc/fidelis/engine/**` (except `api`), `fidelis-app/src/test/java/sc/fidelis/{engine,verification,testkit}/**`, `fidelis-app/src/test/resources/META-INF/**`, `verification/**`, `fixtures/**`, `docs/data/commands*.csv`, `docs/03-development/VERIFICATION-RESULTS.md`, `docs/03-development/handoffs/ARNAV.md` |
| `voice` | Ayush | `sc/fidelis/{nl,speech}/**` (main and test, except the two interfaces below), `fidelis-app/src/test/resources/sc/fidelis/{nl,speech}/**`, `speech/**`, `external/stt-model*`, `docs/03-development/SPEECH-RESULTS.md`, `docs/03-development/handoffs/AYUSH.md` |
| `ui` | Alex | `fidelis-app/src/main/java/sc/fidelis/ui/**`, `fidelis-app/src/test/java/sc/fidelis/ui/**`, `fidelis-app/src/main/resources/sc/fidelis/ui/**`, `docs/03-development/screenshots/**`, `docs/03-development/handoffs/ALEX.md` |
| `integrator` | Arnav (as coordinator) | everything of `engine` plus `Main.java`, `Bootstrap.java`, `fidelis-app/tools/**`, the other `docs/03-development/*` files, `docs/**`, `.github/**`, `CLAUDE.md`, `README.md`, `CONTRIBUTING.md`, root configuration |
| `contract` | nobody alone | `fidelis-app/src/main/java/sc/fidelis/engine/api/**`, `sc/fidelis/nl/NlService.java`, `sc/fidelis/speech/SpeechService.java` (section 4) |
| `frozen` | nobody | the earlier prototype: `backend/`, `frontend/`, `tests/`, `scripts/`, `fiji/`, `external/` (except the speech model pin) |

Reading anything is always allowed. Using another stream's public classes is allowed. Editing them is
not: open a comment on the owning issue, or a new issue for the owner.

Explicit integration exceptions (already planned, each in its own PR by the owner of the files):
- #4 (test isolation): Arnav changes `testkit`; if a UI test must call the new primitive, Alex changes it.
- #6 (macro preview): Arnav adds the engine method; Ayush and Alex adopt it in their own files.
- #17 (channels strip): Arnav implements the two DIRECT operations in the engine; Alex builds the UI.

## 2. Architecture invariants (do not break)

Hybrid B, as implemented (`ACTIVE-DEVELOPMENT.md`, `HANDOFF.md` section 3):
- One persistent, **non-headless** Fiji JVM; the Fiji main window stays hidden. Never `System.exit`: use `Bootstrap.exit` (close images, bounded `context.dispose()`, `Runtime.halt`).
- Each document is a real IJ1 `ImagePlus` with a `HostCanvas` (`canEnlarge()==null`) inside a hidden `ImageWindow` with `setLayout(null)`; the canvas is re-parented into the workspace. Viewport order: drawing size, source rect, magnification, bounds. Never call `zoomOut`/`unzoom` on the canvas. Focus follows `Engine.focus(id)` (which calls `WindowManager.setCurrentWindow`), never clicks.
- All mutations go through `Engine.submit` and run on the engine's single worker thread, never on the EDT. The UI must not mutate pixels through `Engine.imagePlus(id)`.
- Typed commands run as `IJ.run(imp, exactLabel, completeOptions)`. Success is decided by postconditions and the Guard (error text, exception windows, modal dialogs), not by `IJ.run` returning.
- IJ1 singletons (ROI Manager, Results, Log) are resolved on every use, never cached. Images IJ1 opens behind the engine's back are adopted synchronously before any native command runs (adoption lock). Images carrying `HostCanvas.TRANSIENT_PROPERTY` are never adopted.
- Result-affecting globals (`blackBackground`, measurements, precision, conversions) are captured and shown, never set silently. `black` on a single-image Convert to Mask is an explicit AC5 global change.
- History is authoritative; macros are generated from it (D07). Speech and NL never execute anything without a user confirmation.

## 3. Contract reference

### 3.1 Identifiers

| Thing | Form | Example | Notes |
|---|---|---|---|
| Document | `DocumentId(String value)` | `doc-3` | engine-assigned, session-scoped; `DocumentInfo.imagePlusId` is the IJ1 id (negative) |
| Command | `commandId` | `ij1:Process>Filters>Gaussian Blur...` | `ij1:` + IJ1 menu path + exact label; `CommandSchema.label` is passed to `IJ.run` |
| Operation | `ActionRequest.Operations.*` | `SET_AUTO_THRESHOLD` | DIRECT and SESSION kinds |
| Request | `requestId` | UUID | client-generated by the factory methods |
| Running operation | `operationId` | `op-12` | engine-assigned; used by `cancel` and events |
| History step | `actionId` | `act-7` | monotonic |
| Transcript | `transcriptRef` | speech transcript id | reserved request key, stripped from options, stored in `HistoryRecord.transcriptRef` |
| Parent step | `parentActionId` | `act-5` | reserved request key; child steps observed inside a CODE action |

### 3.2 Requests (`ActionRequest`)

`record ActionRequest(String requestId, Origin origin, ActionKind kind, String commandId, String operation, Map<String,Object> params, TargetScope target, String confirmationToken, String replayOf)`

Factories: `command(origin, commandId, params, target)`, `direct(origin, operation, params, target)`,
`session(origin, operation, params, target)`, `code(origin, language, text, target, confirmationToken)`,
`withConfirmation(token)`.

- `COMMAND`: `params` must contain **every** key of the command's `CommandSchema` (booleans explicit). The engine rejects missing keys before running (VALIDATION_FAILED).
- `DIRECT`/`SESSION` operations (current set): SESSION `OPEN(path)`, `SAVE_AS(path, format)`, `CLOSE(discard)`, `NEW_IMAGE(title, type, width, height, slices, fill[, channels, frames])`; DIRECT `SET_ROI(roi)`, `CLEAR_ROI`, `ROI_LIST_ADD`, `ROI_LIST_SELECT(index)`, `ROI_LIST_DELETE(index)`, `ROI_LIST_RENAME(index, name)`, `SET_DISPLAY_RANGE(min, max[, channel])`, `SET_POSITION(channel, slice, frame)`, `SET_THRESHOLD(lower, upper)`, `SET_AUTO_THRESHOLD(method, dark)`, `RESET_THRESHOLD`, `UNDO`, `REDO`.
- `CODE`: `params.text`, `params.language`; requires a capability confirmation token (the first submit returns AWAITING_CONFIRMATION with a token).
- `TargetScope(DocumentId document, Integer channel, Integer slice, Integer frame, boolean allPlanes, Integer roiIndex)`; `of(doc)`, `wholeStack(doc)`, `withRoi(index)` (`-1` = active selection). Typed actions never rely on IJ1's implicit current image.
- `Origin`: `MENU, SEARCH, SHORTCUT, CANVAS, PANEL, NL_TYPED, NL_SPOKEN, MACRO, REPLAY, OBSERVED`.

Proposed additive operations (not yet in the contract; see section 4): `EXPORT_RESULTS` (#7), `SET_COMPOSITE_MODE`, `SET_ACTIVE_CHANNELS` (#17), and `Engine.previewMacroLines` (#6).

### 3.3 Results and errors (`ActionResult`)

`record ActionResult(String operationId, OperationState state, EngineError error, StateDigest digest, HistoryRecord record, String confirmationToken, long durationMs)`; `succeeded()` means COMPLETED.

- `OperationState`: `QUEUED, VALIDATING, AWAITING_CONFIRMATION, RUNNING, WAITING_FOR_DIALOG, COMPLETED, FAILED, CANCELLED, ROLLED_BACK, PARTIAL`.
- `EngineError(ErrorCode code, String message, String subcode, Map<String,String> context, boolean recoverable, List<String> suggestions)`. Codes: `ENGINE_NOT_READY, UNKNOWN_COMMAND, VALIDATION_FAILED, PRECONDITION_FAILED, CONFIRMATION_REQUIRED, CAPABILITY_CONFIRMATION_REQUIRED, DIALOG_PENDING, EXECUTION_FAILED, POSTCONDITION_FAILED, CANCELLED, PARTIAL_RESULT, TIMEOUT, ENGINE_LOST, SNAPSHOT_BUDGET_EXCEEDED, IO_ERROR, UNSUPPORTED_IN_MODE`. Subcode `NOT_RUN` means a CommandListener vetoed the command.
- No-op rule (E8): unchanged pixels are **not** a failure; an identity completes with a `no-op: ...` note in `HistoryRecord.errorSummary` and keeps no undo snapshot. A COMPLETED result therefore does not imply that pixels changed: read `digest.changed[].pixelsChanged`.
- After a failure or cancellation the engine rolls back to the pre-action snapshot when one exists (ROLLED_BACK).
- `StateDigest(added, removed, changed[DocumentChange(id, pixelsChanged, typeChanged, displayChanged, roiChanged, dimensionsChanged)], resultsRowsAdded, globalsDelta, newLogLines, windowsOpened, windowsClosed)`.

### 3.4 Engine facade (`Engine`)

Lifecycle `state()`, `addListener`, `removeListener`, `quit(discardUnsaved)` (returns only when refused because of unsaved changes; the javadoc's "flushes the history journal" is not implemented yet, #9). Actions `submit`, `cancel(operationId)`. Documents `documents()`, `document(id)`, `open(path)`, `focused()`, `focus(id)`, `imagePlus(id)` (hosting only), `hostWindow(id)`. ROIs/results `rois(id)`, `activeRoi(id)`, `results()` (mirrors the IJ1 table; a `clear` rebuilds it), `resultColumns()`. Catalogue `catalogue()` (953), `schema(id)` (53 complete), `search(query, limit)` (weak on long clauses: also scan `catalogue()`). History `history()`, `exportMacro()`, `globals()` (keys such as `blackBackground`, `measurements`, `precision`), `undoBudget()`.

Implementation-only (not in the interface; usable by tests and the integrator): `DefaultEngine.exportMacroSince(int)`, `DefaultEngine.create(ImageJ)`, `Engines.current()`, `HostCanvas.TRANSIENT_PROPERTY`.

### 3.5 Events (`EngineEvent`, sealed)

Delivered on the EDT in sequence order; listeners must not block. Per operation: `OperationStateChanged(RUNNING)` before any `OperationProgress`, before the terminal `OperationStateChanged`, before `StateDigestEvent`, before `HistoryAppended`. Others: `EngineStateChanged`, `DialogPending`, `DocumentsChanged`, `DocumentFocusChanged`, `LegacyWindowChanged` (declared; the engine does not emit it yet, the UI tracks windows itself), `ResultsChanged(totalRows)`, `LogLine`. Known gap: session operations may not emit QUEUED/RUNNING (#5).

### 3.6 Data records

- `DocumentInfo(id, title, imagePlusId, width, height, nChannels, nSlices, nFrames, type, bitDepth, unit, pixelWidth, pixelHeight, sourcePath, sourceSha256, changed, virtualStack)`; pixel data is never exposed.
- `CommandSchema(commandId, label, menuPath, parameters, schemaComplete, headlessOk, consequence, preconditions, postconditionFamily, createsNewImage, hiddenGlobals)` with `ParameterSchema(key, type, defaultValue, choices, min, max, consequential, unit, defaultIsRemembered, visibleWhen, help, source)` and `Summary(commandId, label, menuPath, shortcut, framework, compatCategory, notes)`. `ConsequenceClass`: `AC0_READ_ONLY` ... `AC7_CODE`.
- `RoiGeometry` (float vertices, IJ1 conventions), `MeasurementRow` (full-precision doubles keyed by column, with document and action attribution), `MeasurementRow.Columns(headings, precision)`.
- `HistoryRecord(actionId, time, origin, provenance, commandId, label, params, target, macroLines, macroExact, globalsBefore, globalsDelta, outcome, errorSummary, transcriptRef)`; `Provenance`: `NATIVE, TYPED, LEGACY_DIALOG, UNRECORDABLE`.

### 3.7 Language and speech (owned by the voice stream, consumed by the UI)

- `NlService.interpret(String text, Origin origin, DocumentId focused) -> Proposal(Status status, String normalisedText, List<ActionRequest> requests, List<String> menuPaths, List<String> macroPreview, List<Map<String,String>> slotProvenance, List<String> questions, String refusalReason)`; `Status`: `READY, NEEDS_CLARIFICATION, REFUSED`. Never executes.
- Published, non-interface API (voice stream; changes need a comment on the consuming UI issue): `DefaultNlService.confirmation(Proposal) -> ConfirmationPolicy.Decision(boolean required, boolean explicit, List<String> reasons)`; `DefaultNlService.vocabularyPrompt(List<String> titles)` (at most `VOCABULARY_MAX_CHARS = 560`); `VoiceToProposal.Result(proposal, transcriptRef, transcriptText, lowConfidence, ...)` with `confirmation()`, `requiresConfirmation()`, `requiresExplicitConfirmation()`, `proposalWithTranscriptRef()`.
- `SpeechService`: `state()` (`UNAVAILABLE, STARTING, READY, RECORDING, TRANSCRIBING`), `unavailableReason()`, `addListener/removeListener(Listener{stateChanged, level(rms)})`, `startRecording()`, `stopRecording() -> CompletableFuture<Transcript>`, `transcribeFile(Path)`, `setVocabularyPrompt(String)`; `Transcript(id, text, speechDetected, durationSeconds, segments, model, promptUsed, latencyMs)`. Audio never leaves the machine.
- Confirmation semantics: `required` for every spoken proposal and typed AC2+; `explicit` for AC4+, CODE, a `black` parameter, an unfilled consequential slot, or not READY. The UI must not submit until Confirm; explicit cases need a separate deliberate act (not the default button, not a spoken "yes").

### 3.8 Service wiring (UI-owned)

`Bootstrap` registers the product engine with `sc.fidelis.ui.EngineProvider.register(...)`; `Workspace.launch` builds `DefaultNlService(engine)` and `WhisperSpeechService` and registers them in `ServiceRegistry`. Tests use `sc.fidelis.testkit.ProductEngine.get()` (one `DefaultEngine` per test JVM, registered with `EngineProvider`).

### 3.9 Threading and lifecycle

- Engine methods are callable from any thread; mutations run in submission order on one worker. Never block the EDT on a future; complete UI work in `whenComplete` + `SwingUtilities.invokeLater`.
- The speech worker is a child Python process; construct `WhisperSpeechService` off the EDT; close it on quit (process-tree kill).
- One JVM per worktree at a time; always `--isolated-home` for `run.py`; `test.py` isolates `user.home` itself.

## 4. Changing a shared contract

1. Open (or use the planned) issue labelled `needs:contract-review`. Post the exact signature, semantics, default behaviour for existing implementers (`StubEngine`, `FakeEngine`, `HarnessSelfCheckEngine`), and the macro lines it records.
2. Mention the affected owners by issue reference; each acknowledges with a comment `CONTRACT ACK`.
3. The integrator approves with `CONTRACT APPROVED`.
4. The contract change merges first, in its own small PR (additive: new default methods, new operation constants, new optional parameters). Implementations that depend on it merge after.
5. Never introduce a second definition of an existing concept (a parallel request type, a UI-side command id scheme, a second history model).

Breaking changes (removing or changing an existing signature or meaning) are out of scope for the hackathon.

## 5. Cross-component testing before integration

- Every PR: `build.py`, the owner's test classes, and the full `test.py` on the PR head.
- Contract PRs: all three streams' test classes (`--select-class` for `sc.fidelis.engine.*`, `sc.fidelis.nl.*`, `sc.fidelis.ui.*`) plus `EngineParityTest` and `WorkspaceJourneyTest`.
- PRs that change scientific behaviour: the parity evidence (E1 pixels, E4 ROI vertices, E5 Results doubles, E6 globals) against `verification/baselines`; visual similarity is not evidence.
- The integrator runs the full suite twice on the merged head at every checkpoint (#10).
