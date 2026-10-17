# Capability matrix

There are 32 MCP tools. See [task coverage](SOFTWARE-COVERAGE.md) for import, analysis and navigation routes. Breadth comes from structured scientific operations, the installed menu catalog, visible JavaFX dialog controls, task knowledge and reviewed Groovy execution. This is not a claim that every QuPath command or third-party plugin has been tested.

| Surface | Behavior | Approval | Evidence / limit |
|---|---|---|---|
| Active image, metadata, calibration, selection and ROI | Real application API | No | Read from running public slides through actual MCP |
| Objects and measurements | Scoped listing and measured values | No | Actual measurements read from the 899-cell public-slide fixture; truncation explicit |
| Full-scope measurement summary | Finite counts, missing values, descriptive statistics, class groups and histogram bins | No | Synthetic actual-library contract passes; max one million objects / five million numeric values, 20 measurements, 50 displayed class groups; new native plots compile, foreground check pending |
| Analysis catalog | Actual measurement/class names, visible watershed defaults/ranges/choices, recent workflow names | No | Compiles against pinned APIs; actual application observation pending new bridge load |
| Project/image opening | Exact local paths or project entry IDs/source fingerprints through native QuPath opening | Yes | Unsaved changes refused; native dialogs/dispatch require real-app verification; no URL or .qpdata endpoint |
| GeoJSON annotations | Inspect/hash/validate then add leaf annotations with a checkpoint | Import: yes | Isolated actual QuPathGUI → MCP test passes: exact imported fields/scores, changed-file/duplicate rejection and empty baseline restoration; normal file dialogs still unverified |
| Focus and pointer | Center image/ROI, zoom, return real screen target | No | Corrected mouse-anchored zoom behavior; actual center verified at (51100, 27100) |
| Highlight (Clicky-style cursor) | Screen outlines for objects, image regions or visible controls; cursor flies along an arc with a label and follows the pointer while listening | No | Display only; outlines only objects visible in the viewer; real QuPath check below |
| Overlays | Detections, annotations, fills, opacity and exact class filter | No | Display-only; does not change counts |
| Annotation creation | Rectangle/ellipse on active z/t plane | Yes | Tested on disposable H&E slide through native approval |
| Cell detection | QuPath watershed plugin, explicit calibrated/pixel parameters | Yes | Real OS-2 fixture detected 899 cells with explicit Hematoxylin OD parameters |
| Threshold classification | Existing measurement >= threshold; current-label filter is separate from assigned Positive/Negative labels | Yes; candidate count preview | Real fixture yielded 49 Positive / 850 Negative at nuclear DAB OD mean >= 0.2; empty scopes now refused before proposal; candidate/binding contracts pass; not a diagnosis |
| Hierarchy checkpoints, comparison and restore | Bounded in-memory hierarchy snapshots | Restore: yes | Real fixture compared counts and restored the empty baseline; max 6, 200k objects/64 MiB each |
| Image type | Explicit QuPath interpretation | Yes | Native approval tested; not restored by hierarchy checkpoints |
| Deletion | Selected objects only, retain children | Yes | Checkpoint before mutation; selection cleared; TMA cores refused in favor of grid-aware commands |
| Menu catalog | Live exact paths and enabled state | No | Real measurement commands discovered through MCP |
| Menu invocation | Dispatch exact discovered command | Yes | May open modal dialog; dispatched is not completed |
| JavaFX UI | Observe visible windows, controls, labels and options | No | Real image-type dialog inspected; OS-owned dialogs excluded |
| JavaFX control action | Fire, edit text, select exact combo/choice option or enabled tab title | Yes | Image-type dialog toggle and Apply verified; bound to reviewed dialog fingerprint and revalidated at dispatch; password fields excluded |
| Groovy execution | Full QuPath scripting context | Yes | Requires open project + image; full local privileges; exact-source review; external effects not recoverable |
| Knowledge | Authored guides + pinned official documentation | No | 67 documents indexed in the current local checkout |
| API source lookup | Search/read bounded immutable Java source at the pinned commit | No | Actual 879-file lookup, authenticated gateway tests and actual model search/read/declaration proof pass; base QuPath source only, no source execution |
| AWS-credit OpenAI text | Per-peer follow-ups, full answers, Stop, same Runtime and real human approvals | Data actions: yes | Real authenticated WebSocket → GPT-OSS-120B → exact QuPath focus passed; text-only, no viewer vision; native controls compile, foreground checks pending |
| Actual model scientific sequence | Fixed OS-2 image/ROI/parameters, detection, measured classification, comparison, empty baseline restore | Five test-only simulated decisions under explicit user authorization | Full real-model run passed in 21.5 s / eight requests; initial stopped attempt also preserved; ordinary approvals unchanged; see [test scope](SCIENTIFIC-AGENT-TEST.md) |
| Native companion | Compact top bar; expandable conversation/results, approvals and knowledge; automatic review expansion | N/A | Bar and native text-to-real-agent/human opening decision inspected; actual OS-2 metadata present, native type dialog pending; six status/display-layout contracts plus eight audio/lifecycle tests pass |
| AWS speech voice (Transcribe → Bedrock agent → Polly) | Continuous transcription, barge-in, short spoken replies, credit-guarded per request | Credit proof + $200 reserve cap | Real AWS loopback and full gateway → QuPath pipeline pass with synthesized audio (4.3 s speech-end to reply audio); real microphone pending |
| Realtime voice transport | Continuous PCM protocol, VAD, function calls, interruption events | Funding gate | Local protocol fixture passes; no live provider session; production activation intentionally absent |
| GPT-Live client delegation | Separate transport and provider-injected backend, timestamped conversation, actual Runtime tools, human-decision waits | Funding gate | Local protocol/backend tests and a scripted display-only real-QuPath integration; no live model, microphone or provider session |

## Analysis semantics

`scope=region` means object centroids inside the selected ROI on the same image plane. `scope=selection` means selected objects themselves. A first page of objects or measurements is not the entire population; use total, offset and truncation.

“Positive” in the structured classifier is a threshold class replacing existing classes. Its denominator, threshold, measurement and segmentation quality must be stated. It is not automatically a tumor-cell Ki67 score.

## Recovery boundary

Hierarchy checkpoints preserve objects, measurements and classes. They do not restore files, image type, stains, viewport, trained models, arbitrary plugin effects or external script effects. They are volatile and disappear when QuPath closes. “Undo anything” would be an inaccurate claim.

## Detection completion and responsiveness

Detection orchestration runs outside the JavaFX thread so QuPath can show its native progress/cancellation UI. The operation captures the approved annotation set before work begins. QuPath can log and swallow individual tile failures, so a returned plugin boolean is not a proof of complete segmentation. The adapter returns `executionFinished: true` with `completionVerified: false` and asks the researcher to inspect contours and logs. The fixed public fixture separately verifies actual counts and measured values.

Primary selection is part of approval binding, not merely the unordered set of selected objects. Restore clears detached selections. Object focus navigates to the ROI's image plane; multi-plane datasets still need a dedicated real-stack validation beyond the current single-plane fixture.

Detection parameters are checked against the active plugin parameter list before execution. Unsupported choices and hidden/unavailable settings are rejected rather than silently using defaults. Workflow history records the upstream plugin class and executed parameters for replay. Deferred UI/menu failures are available in `context.lastDispatch` and shown in the companion.

## Broader recovery and UI contracts

Checkpoints use QuPath's native binary hierarchy serialization in private memory, not precision-limited JSON. The synthetic contract test preserves ROI coordinates, class probability, image/root measurements and metadata, UUIDs and TMA-grid relationships. Serialized client/file uploads are never accepted. The 64 MiB capture limit is enforced while writing.

Approval keys include the active project even when no image is open, and hash selection identity into a bounded key. The approval panel shows the project. Dialog fingerprints include list/table/tree selections and common scalar controls, with bounded selection previews. Check/radio menu commands apply native selection semantics before firing their event. Numeric detection parameters are normalized before QuPath parsing and checked afterward against approved values, including scientific notation.

Approved scripts now execute on a worker using QuPath script parameters captured from the reviewed project and image. Scripts that directly manipulate JavaFX controls must marshal those fragments to JavaFX, as in QuPath’s script editor. Arbitrary scripts can still query changing UI state explicitly; prefer exact object IDs for scientific targets. Main-window commands are refused while a modal dialog is active, including a second check immediately before deferred dispatch.

Context includes `selectionCount` and `selectionTruncated`; the array is a preview of at most 100 objects. Query paginated objects for full IDs. Unnamed image servers use QuPath's display-name fallback. RichTextFX source editors include a complete source digest and selected range/caret in UI fingerprints. Explicit script execution requires both an open project and an image: absent QuPath thread bindings would otherwise fall back to later GUI defaults. This is an explicit prototype restriction; structured tools still work on standalone images.

Native editable source-window actions are refused. QuPath’s Run button captures context later in its own worker, which cannot be safely bound by a pre-fire fingerprint. Use `qupath_run_script`, which captures the reviewed project/image before background execution. Source-editor text remains fingerprinted for observation; this does not imply its Run action is exposed.
