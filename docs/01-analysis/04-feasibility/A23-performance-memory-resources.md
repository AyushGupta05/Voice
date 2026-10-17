---
id: A23
title: Performance, Memory and Resource Analysis
stage: 01-analysis
status: Draft
priority: P1
source_revisions: [Fiji archive 20261013-1517 (lock f6695397), imagej/ImageJ@v1.54p]
related_requirements: []
related_decisions: [DEC-010]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# A23 — Performance, Memory and Resource Analysis

## Executive Summary

The measured numbers (P01, P60, P63, P84, P85, P87) show three things.

1. **Moving pixels within the JVM costs almost nothing.** Rendering costs more, and encoding (PNG/JPEG) costs most. An in-JVM UI (A21 O2) can render 2048² planes using IJ1's own rendering in 1.7–15 ms. An out-of-process UI would pay 6–48 ms per 1024² encoded viewport, or would need raw shared-memory transfer.
2. **Memory is dominated by image data and snapshots.** It is not dominated by the runtime (about 450 MB working set at start-up; about 256 MB more if JavaFX WebView is used). Snapshot undo (A19) must therefore be budgeted against the heap, which defaults to 75 % of RAM.
3. **Speech is cheap with a persistent worker.** Transcription takes about 0.2 s once warm. Process start-up dominates if the worker is not persistent.

There are no measurements yet for large microscopy data (multi-GB stacks, Bio-Formats pyramids) or for viewer frame rates. These are validation items for D13 and D18, not assumptions.

## Measured Evidence (VERIFIED; Core Ultra 9 285H, 31.4 GB RAM, Windows 11)

| Measurement | Result | Probe |
|---|---|---|
| Headless Fiji process, start → trivial macro → exit | 4.14–4.69 s (5 runs) | P01 |
| GUI JVM launch → `IJ.getInstance()!=null` | 4.5–5.3 s (4 runs) | P85 |
| GUI JVM footprint after start-up | Working set 437–468 MB; private about 505 MB; heap used about 63 MB | P85 |
| JavaFX + WebView initialisation inside Fiji | Toolkit 290 ms; WebView load 769 ms; working set 480 → 736 MB | P87 |
| `getPixels` + direct `ByteBuffer` copy, 2048² | 0.15 / 0.37 / 1.35 ms median (8/16/32-bit) | P84 |
| `ImagePlus.getBufferedImage()`, 2048² | 1.7 / 12.6 / 15.1 ms median | P84 |
| PNG encode, 1024² crop | 13–48 ms (content-dependent) | P84 |
| JPEG encode, 1024² crop | 6–25 ms | P84 |
| ImagePlus → Dataset wrap (legacy bridge, on demand) | Under 1 ms after warm-up for a 40 MB stack (pixels shared) | P41 J1 (A07) |
| faster-whisper tiny.en model load | 0.196–0.210 s | P60 |
| Transcription of a 1.94 s clip | 0.18–0.21 s warm; 0.26–0.29 s first call | P60 |
| Prototype speech request end to end (new process each time) | 942–1008 ms, of which about 0.7 s is process overhead | P63 |
| Local LLM (`qwen3:8b`, Ollama) plan generation | Median 1.18 s; first call 50.4 s (model load) | P65 |

Caveat: some timings were taken while other research agents were running Fiji concurrently (noted in P01). They are indicative, not benchmarks.

## Technical Analysis

### 1. Image memory model

- IJ1 stores each plane as a Java primitive array: `byte[]`, `short[]`, `float[]` or `int[]` for RGB. A stack of W×H×N at bit depth b uses W·H·N·(b/8) bytes of heap. A 2048×2048×100 16-bit stack is about 0.8 GB. **VERIFIED** structure (A09); arithmetic.
- **Duplication hazards:**
  - snapshot undo (one copy per undoable step);
  - preview on a duplicate (CR-A11-06);
  - `getBufferedImage` (an 8-bit or RGB rendering per displayed plane);
  - ImageJ2 `Dataset` wraps, which share memory and add none (P41 J1);
  - PyImageJ or out-of-process copies, which O2 avoids.
- **Virtual stacks** keep slices on disk and load them on demand. Edits to virtual stacks are not reversible by snapshot unless the slice is copied. These are AC4 (A19).
- The heap defaults to 75 % of physical RAM (A22). On a 32 GB machine that leaves about 8 GB for the OS, the speech worker (about 0.2 GB model plus the Python runtime; not measured as a working set) and an optional LLM (several GB if a local model runs).

### 2. Rendering strategy implications

| Strategy | Cost per frame (2048² plane) | Fidelity | Notes |
|---|---|---|---|
| R-a: IJ1 rendering (`getBufferedImage`/`getImage`) drawn by Java2D in-JVM | 1.7–15 ms to render, plus the Java2D draw (not measured) | Exact (IJ1's own LUT, display range and composite logic) | Recommended baseline for O2 (CR-A21-05). Cache the rendered plane and re-render only on data or display changes. Zoom and pan reuse the cached image. |
| R-b: Java2D/JavaFX GPU texture from raw pixels with a custom LUT shader | Not measured | Must be proven equal to IJ1 (A30 E3) | A contingency for large or high-frame-rate viewing (R-A21-02) |
| R-c: out-of-process (O4) encoded tiles | 6–48 ms encode per 1024² tile, plus transfer and decode | Exact if the JVM renders, at an encode cost | Becomes interactive only with tiling and caching. Raw shared memory (Appose) avoids encoding but needs a client-side LUT (same fidelity issue as R-b). |
| R-d: BigDataViewer for multi-GB or pyramidal data | Not measured | BDV's own rendering | A C4 fallback for very large data. Not integrated in MVP scope. |

**INFERRED:** for typical 2D microscopy planes up to about 4096², R-a with caching gives interactive zoom and pan, because the per-frame work after the first render is a Java2D scaled draw. This must be validated in D13 with frame-time measurements before it becomes a commitment.

### 3. Threading and responsiveness

- All state-mutating actions run on one engine worker (A18). Long operations do not block the UI thread, provided the UI never calls the engine synchronously from the EDT. **PROPOSED**
- `ImageListener` events arrive on the EDT asynchronously (P80). Heavy work in listeners would stall the UI. Listeners must only enqueue refreshes.
- IJ1 parallelises stack filters across `Prefs.getThreads()` threads (`ij/Prefs.java#L691-L702`). The engine worker therefore does not limit multi-core use for those filters.

### 4. Speech resources

- A persistent worker removes about 0.7 s per request (P63 versus P60), so CR-A16-03 holds.
- CPU int8 tiny.en is fast enough for short commands. Larger English models trade latency for accuracy. They are not measured (download over 50 MB was not permitted in this stage). They are evaluated with FX-060–062 (A29) before any default changes.

## Requirement implications

- **CR-A23-01** — The viewer shall cache rendered planes and re-render only when pixel data, display range, LUT, composite mode, overlay or plane position changes. *Rationale:* §2 R-a cost.
- **CR-A23-02** — Snapshot memory for undo and previews shall be accounted against a configurable budget, by default a fraction of the max heap. The remaining budget shall be visible in the UI. *Rationale:* §1; A19 CR-A19-05.
- **CR-A23-03** — Before the PARITY milestone, the project shall measure viewer frame time (pan and zoom) and render latency for 2D planes of 1024², 2048² and 4096² at 8, 16 and 32 bits, and for a composite with 3 or more channels. It shall set NFR targets from those measurements. *Rationale:* no evidence yet; avoids invented targets (brief Part 13).
- **CR-A23-04** — Engine listeners shall perform no image processing on the EDT. They shall only schedule UI refreshes. *Rationale:* P80 EDT delivery.
- **CR-A23-05** — The product shall detect virtual stacks and images whose size exceeds the snapshot budget, and shall handle them as AC4 (A19). *Rationale:* §1.

## Risks

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R-A23-01 | Large stacks plus snapshots exhaust the heap | Medium | High | CR-A23-02; AC4 handling; virtual stacks |
| R-A23-02 | Java2D rendering is insufficient at high zoom or for large planes | Medium | Medium | CR-A23-03 measurement; R-b/R-d contingencies |
| R-A23-03 | Concurrent LLM and speech workloads degrade responsiveness on lab laptops | Medium | Medium | LLM optional; CPU tiny/base models; show resource state |

## References

- Probes P01, P41, P60, P63, P65, P80, P84, P85, P87.
- `imagej/ImageJ@v1.54p`: `ij/Prefs.java#L691-L702`.
- A09, A16, A19, A21, A22.

## Change log

- 2026-10-17: Initial draft by the lead.
