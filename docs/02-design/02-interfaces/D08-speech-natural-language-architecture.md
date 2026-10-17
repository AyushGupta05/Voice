---
id: D08
title: Speech and Natural Language Architecture
stage: 02-design
status: Draft (provisional on DEC-010)
priority: P0
source_revisions: [SYSTRAN/faster-whisper@v1.2.1 (65882eee), Systran/faster-whisper-tiny.en@0d3d19a3, imagej/ImageJ@v1.54p]
related_requirements: [FR-NL-001…013, FR-SPEECH-001…007, NFR-PERF-002, NFR-REL-002, NFR-USE-003, NFR-PRIV-001, NFR-PKG-004, NFR-PKG-005]
related_decisions: [ADR-005, ADR-007, DEC-007, DEC-022, DEC-023]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# D08 — Speech and Natural Language Architecture

## Executive Summary

Speech recognition and command selection are **separate stages with separate failure modes**:

```text
microphone → [CMP-43 capture] → PCM → [CMP-44 faster-whisper worker] → transcript (editable)
typed text ───────────────────────────────────────────────────────────┘
          → [CMP-40 interpreter: normalise → grammar | retrieval+slots | (optional) LLM] → CommandPlan
          → [CMP-41 validator + policy] → preview (UI-18) → confirm → ActionRequest(s) → engine
```

No stage executes anything by itself. Only a confirmed or policy-permitted `ActionRequest` reaches the engine (FR-NL-004, FR-SPEECH-004). Every stage is deterministic except the optional LLM. The LLM only *proposes*; the deterministic validator and policy *decide* (ADR-007).

## 1. Speech pipeline (CMP-43 ↔ CMP-44; ADR-005)

| Stage | Design | Requirement | Evidence |
|---|---|---|---|
| Activation | Explicit push-to-talk or toggle in UI-11. A persistent recording indicator is shown. Auto-stop at 30 s. | FR-SPEECH-002 | A25 |
| Capture | Java Sound `TargetDataLine`, mono, 16-bit PCM. 16 kHz requested. If 16 kHz is unsupported, capture at 48 kHz and decimate ×3 with an anti-alias filter in Java (**V-09**). Held in memory only. | FR-SPEECH-002 | **UNKNOWN**: device support; probe in milestone 1 |
| Transport | IF-06 JSON lines over the worker's stdin/stdout. PCM is base64-encoded in one message per utterance (≤ 30 s × 32 kB/s ≈ 0.96 MB raw). | FR-SPEECH-001 | ADR-005 |
| VAD | faster-whisper `vad_filter=True` (Silero). Empty result if no speech is detected. | FR-SPEECH-003 | P61 |
| Decoding | `language="en"`, `beam_size=5`, `condition_on_previous_text=False`, `initial_prompt` = vocabulary prompt (§1.2), CPU int8 | FR-SPEECH-005, FR-SPEECH-007 | P60/P61 |
| Output | Transcript plus segments (timings, `avg_logprob`, `no_speech_prob`), the model identity (revision and SHA-256), the prompt used, and timings | FR-SPEECH-005, FR-NL-011 | — |
| Persistence | None by default. Opt-in storage for evaluation. | FR-SPEECH-002, NFR-PRIV-003 | — |

### 1.1 IF-06 protocol (illustrative)

```json
{"id":"s-41","type":"transcribe","sampleRate":16000,"pcm16le_b64":"…","prompt":"ImageJ commands: Gaussian Blur, Median, Subtract Background, Threshold, Convert to Mask, Analyze Particles, Measure, ROI, sigma, Otsu; images: cells.tif; channels: DAPI, GFP"}
{"id":"s-41","type":"result","text":"Blur the GFP channel with sigma two microns","segments":[{"start":0.0,"end":2.1,"avgLogprob":-0.21,"noSpeechProb":0.02}],"speech":true,"model":{"name":"Systran/faster-whisper-tiny.en","revision":"0d3d19a3…","sha256":{"model.bin":"…"}},"timingMs":{"vad":12,"decode":190}}
{"id":"s-42","type":"error","code":"AUDIO_TOO_LONG","message":"Clip exceeds 30 s"}
```

Other message types are `hello` (version and model handshake; the worker refuses on hash mismatch, FR-SPEECH-001), `ping`/`pong`, and `shutdown`. The worker exits on stdin EOF (D03 §1).

### 1.2 Vocabulary prompt (FR-SPEECH-005)

The prompt is built from:
- core-set command labels;
- parameter vocabulary (sigma, radius, Otsu and the other `AutoThresholder` method names, micron);
- open document titles;
- channel names.

It is capped at about 200 characters (PROPOSED). P61 shows that longer prompts both fix and introduce errors, so the length must be tuned with FX-060/061. The prompt is recorded with each transcript.

## 2. NL interpretation (CMP-40)

### 2.1 Normalisation

- Lower-case, then strip filler words and punctuation.
- Convert number words to numerals, including decimals ("two point five" → 2.5) (FR-SPEECH-006).
- Normalise units: µm/um/micron(s)/micrometre(s) → `µm`; nm; px/pixel(s).
- Split sequence markers ("then", "and then", "after that") into steps (as in the prototype grammar, F02).

### 2.2 Front ends

| Front end | Mechanism | Coverage | When used |
|---|---|---|---|
| **A: controlled grammar** | Deterministic patterns per core command (`<verb> [the] [<target>] [with] <param> <value> [<unit>]`), generated from schema labels plus a curated synonym table for the MVP/core sets | High precision on the expected phrasings | First |
| **B: catalogue retrieval + slot filling** | Lexical retrieval (token and fuzzy matching over labels, menu paths and synonyms) → top-k commands. Slots are filled from schema types and choices: numbers with units, choice names such as threshold methods, boolean phrases ("for all slices" → `stack`). | Broad (whole catalogue for command identity; slots only where a schema exists) | When A finds no match, or for search-like phrasing ("find maxima") |
| **C: LLM, optional** (CMP-42) | A local provider by default (e.g. Ollama). Inputs: the instruction, the top-k candidate schemas from B, and document metadata (type, dimensions, calibration, channel names). Output constrained by JSON schema to `CommandPlan`. | Paraphrase-heavy instructions | Only if enabled (FR-NL-009) and A/B did not produce a confident plan |

### 2.3 CommandPlan (IF-07)

```json
{
  "planId": "plan-17",
  "source": {"text": "blur the GFP channel with sigma two microns", "origin": "NL_SPOKEN", "transcriptRef": "s-41", "frontEnd": "A"},
  "steps": [{
    "commandId": "ij1:Process>Filters>Gaussian Blur...",
    "params": {"sigma": {"value": 2.0, "provenance": "user", "unitSpoken": "µm"},
               "scaled": {"value": true, "provenance": "derived:unit"},
               "stack": {"value": false, "provenance": "default-nonconsequential?"}},
    "target": {"docId": "doc-3", "scope": {"c": 2}, "provenance": "deixis:channel-name"}
  }],
  "ambiguities": [],
  "llm": null
}
```

Every slot carries a **provenance** value:
- `user`: stated by the user;
- `derived:<rule>`: from deterministic rules;
- `default`: allowed only for non-consequential parameters;
- `llm`: proposed by the model.

The validator rejects plans in which a consequential parameter has provenance `default` or `llm`, unless the user has confirmed it explicitly (FR-NL-003). Note that `stack` **is** consequential (`commands-core-parameters.csv`), so in the example above it must be resolved. The validator raises a clarification: "Apply to the current slice only, or to all 5 slices?"

### 2.4 Deixis and unit rules (FR-NL-005, FR-NL-008)

| Expression | Resolution rule | Ambiguity → clarify when |
|---|---|---|
| "this image", "the image", no explicit target | The focused document (CMP-01) | No document focused |
| "the selection", "this region" | The active ROI on the focused document | No ROI, or several selected ROIs in the list |
| "channel N" / channel name | Composite channel index N (1-based), or a name match in channel labels | Name matches more than one channel |
| "slice N", "frame N", "all slices" | C/Z/T scope; "all slices" → `stack` | Hyperstack with both Z > 1 and T > 1 when the dimension is unspecified |
| "the last result", "previous image" | Most recent RESULTS_ROWS action / most recent NEW_IMAGE document | No such action |
| Units µm/nm with a calibrated image | If the image's unit is compatible: value converted to the image unit, and `scaled` set where the command supports it (e.g. Gaussian `sigma=v scaled`). The preview shows both calibrated and pixel equivalents. | Image uncalibrated, or units incompatible |
| No unit given for a unit-sensitive parameter | **Clarify** (pixels or calibrated). Never assume (A17 P65 finding). | Always |

### 2.5 Validator and policy (CMP-41)

- **Validation:** D06 §3 order, plus plan-level checks:
  - every step's `commandId` exists (FR-NL-001);
  - no AC7 actions (FR-NL-007);
  - consequential-slot provenance (FR-NL-003);
  - live-state preconditions, re-checked before execution (FR-NL-002).
- **Policy table (FR-NL-006), deterministic:**

| Validator outcome | Decision |
|---|---|
| Valid, every step AC0/AC1, unambiguous | Auto-execute; show the result in history |
| Valid, any step AC2–AC6 | Preview (UI-18) with menu path, macro text and units → confirm |
| Missing or ambiguous consequential slot; deixis ambiguity | Clarify: a question listing the explicit choices |
| Unknown command, or out-of-scope request | Refuse, with "closest catalogue commands" suggestions |
| AC7 requested ("write a macro that…", "delete files") | Refuse, explaining that the NL path never generates code |
| LLM output fails the schema, or uses placeholders or out-of-range values | Discard the LLM plan; fall back to A/B or clarify (P65) |

### 2.6 LLM client (CMP-42; optional; DEC-007)

- **Provider interface (IF-08):** `propose(instruction, candidateSchemas, metadata) → CommandPlan | refusal`. The default provider is a **local** endpoint (`http://127.0.0.1:<port>` of a user-installed server). This is an outbound client connection only; the product opens no listener (NFR-SEC-002).
- **Payload rules (FR-NL-010):**
  - no pixels, thumbnails or screenshots;
  - metadata-derived strings (titles, channel names, ROI names) passed only inside delimited, length-limited data fields;
  - path components redacted by default.
- **Logging:** model identity and digest, prompt-template version, raw output, the user's edits, and the final executed step (FR-NL-011).
- **Hosted providers:** LONG scope, and only if DEC-007 allows them. Opt-in per session, with an exact outbound-payload preview, and API keys held in the OS credential store (FR-WS-005).

## 3. Evaluation hooks (NFR-USE-003)

- Every transcript, plan, validator outcome and user decision is logged in an evaluation format (opt-in persistence), so that labelled sets FX-060/061/062 can be scored:
  - transcript WER per utterance;
  - command-identity accuracy;
  - slot accuracy;
  - clarification rate;
  - **unsafe-execution count, which must be 0**.
- Configurations under comparison: model size (tiny.en against larger English models), with and without the prompt, and front-end mixes (A only; A+B; A+B+C).

## 4. Failure handling (summary; detail in D17)

| Failure | Behaviour |
|---|---|
| Worker missing or crashed | Voice disabled with a message. Typed NL is unaffected (NFR-REL-002). |
| No speech detected | "No speech detected"; nothing executes (FR-SPEECH-003) |
| Low-confidence transcript (`avgLogprob` or `noSpeechProb` past PROPOSED thresholds, tuned on FX-061) | The transcript is shown, highlighted for review; the plan is still validated |
| LLM unavailable or timed out | A/B front ends only; no blocking |

## Requirement gaps / clarifications

- **FR-NL-010 scope.** The source CRs differ. CR-A17-08 asks for a payload preview for *any* language model; CR-A25-02 asks for it only for payloads that *leave the machine*. **PROPOSED clarification for owner decision (DEC-007):** a mandatory preview for any payload sent to a non-local endpoint; for a local model, the payload is inspectable on demand and logged. A26 is not changed until the decision is made.

## Change log

- 2026-10-17: Initial version by the lead.
