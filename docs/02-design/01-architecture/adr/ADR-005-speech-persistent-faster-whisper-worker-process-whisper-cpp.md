---
id: ADR-005
title: Speech: persistent faster-whisper worker process; whisper.cpp as alternative
stage: 02-design
status: Proposed — awaiting approval
priority: P0
source_revisions: [see Context]
related_requirements: [FR-SPEECH-001…007, NFR-PERF-002, NFR-REL-002, NFR-PKG-004, NFR-PKG-005]
related_decisions: [DEC-022]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# ADR-005 — Speech: persistent faster-whisper worker process; whisper.cpp as alternative

## Context
A16 compares faster-whisper 1.2.1 and whisper.cpp v1.9.5.
- faster-whisper is already integrated and verified, at about 0.2 s per warm transcription (P60). Starting a process per request costs about 0.7 s (P63).
- whisper.cpp offers GBNF grammars and native packaging, but its Java bindings are stale (A16).
- The speech venv raises licence questions about its native DLLs (A24, P73).

## Decision (PROPOSED)
CMP-44 is a persistent Python 3.11 faster-whisper worker:
- model: tiny.en, or a larger English model if the evaluation supports it;
- CPU, int8;
- Silero VAD on;
- a JSON-lines protocol on stdin/stdout (IF-06);
- started and supervised by CMP-43;
- model files pinned by hash.

Audio is captured in Java and sent as 16 kHz mono PCM, so the worker need not decode containers. **Correction (Phase 8):** PyAV cannot be dropped, because `faster_whisper/__init__.py#L1` imports `audio.py`, which imports `av` at L15 (VERIFIED at v1.2.1). The FFmpeg/x264/x265 licence question (A24 §5.6, NFR-PKG-005) must therefore be resolved by a PyAV build without GPL codecs, by a patched import, or by whisper.cpp (DEC-005).

## Alternatives considered
| Option | Assessment |
|---|---|
| whisper.cpp via its CLI or a native library | Preferred if a Python runtime must be avoided or a constrained grammar proves necessary |
| In-JVM JNA bindings | Rejected: stale, mismatched struct (A16) |
| Hosted ASR | Rejected (PR-8) |

## Consequences
A Python runtime ships with the product, or is built on first use (D19). The evaluation sets decide the model size (NFR-USE-003).
