---
id: A30
title: Scientific Correctness and Evaluation Strategy
stage: 01-analysis
status: Draft
priority: P0
source_revisions: [imagej/ImageJ@v1.54p (03382be1), Fiji archive 20261013-1517 (lock f6695397)]
related_requirements: []
related_decisions: []
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# A30 — Scientific Correctness and Evaluation Strategy

## Executive Summary

The product never reimplements Fiji's algorithms (PR-1, CR-A01-01). The main correctness risk is therefore not that an algorithm differs. It is that the **invocation** differs. Examples:

- the wrong image or plane is targeted;
- a dialog key is missing, so a remembered value is used (A11, A17);
- a hidden global setting differs (A11 §6, A05 UP-04/05/06);
- display-range scaling changes a conversion (A09, P37);
- ROI geometry is rounded (A10);
- results are rounded on export (P37);
- the headless runtime differs from the GUI one (P39, A15).

The evaluation strategy therefore compares the product's results with a **reference execution of the same pinned Fiji** through an independent path: a standard ImageJ macro run in classic Fiji. The comparison covers pixels, metadata, ROI geometry, measurements and macro replay, under stated tolerances. Visual similarity is never accepted as evidence (brief Part 8 A30).

## Purpose and Scope

This document defines what "equivalent to original Fiji" means and how future implementation proves it. Test architecture, fixture wiring and CI are D18. Fixtures are A29 and [`docs/data/datasets.csv`](../../data/datasets.csv).

## Research Questions and Answers

| Question | Answer | Evidence |
|---|---|---|
| What is the oracle? | The pinned Fiji runtime (lock `f6695397…`), executing a standard `.ijm` that encodes the same action with *all* keys and globals explicit. Run headless (`-macro`) where A15 classifies the command as headless-safe, and in GUI-mode Fiji otherwise. | PROPOSED; runtime per F03 |
| Is a second implementation (e.g. ImageJ2 Ops) an oracle? | No. Ops are separate implementations (A11 §4). They are compared only when the product deliberately uses an Op (CR-A11-08). | A11 |
| Are IJ1 results deterministic? | For most filters, yes, given identical inputs, parameters and globals (INFERRED). Exceptions: noise commands use an unseeded `java.util.Random` unless `random("seed", n)` is called (`ImageProcessor.java#L117`, `ByteProcessor.java#L873-L876`, `Functions.java#L1194-L1201`); multithreaded reductions; anything time- or memory-dependent. | VERIFIED (randomness); INFERRED (others) |
| Is floating-point arithmetic reproducible across JVMs? | Since Java 17, all floating-point operations are strict (JEP 306), so results on the same pinned Zulu 21 are expected to be bit-reproducible for single-threaded code. Thread partitioning can change the order of reductions. | INFERRED (JEP 306 is documented Java behaviour; not probed) |
| Can recorded outputs be compared at full precision? | Only through the API or explicitly formatted output. `ResultsTable` CSV export rounds to the display precision (default 3) (P37, `ResultsTable.java#L57`). | VERIFIED |

## Technical Details

### 1. Equivalence dimensions and default tolerances (PROPOSED)

| Dimension | What is compared | Default criterion | Justification |
|---|---|---|---|
| **E1 Pixel data, integer types** (8/16-bit, RGB) | Raw pixel arrays per plane; SHA-256 of the little-endian buffers plus a per-plane diff when they differ | **Exact equality** | The same code on the same runtime with the same inputs must be bit-identical. Any difference is an invocation fault. |
| **E2 Pixel data, 32-bit float** | Per-plane arrays; NaN positions must match exactly | Exact by default. Where a command is classified "thread-order sensitive" (to be determined per command in D18), max abs diff ≤ 4 ULP of the reference value. | Strict FP since Java 17 (INFERRED); reduction order is the only expected source of difference |
| **E3 Image metadata** | Type, dimensions (W,H,C,Z,T), stack order, calibration (pixel size, units, origin, value function), frame interval, LUTs, display range per channel, composite mode, properties/info, title rules | Exact (strings and numbers), except fields documented as volatile, such as timestamps | Calibration errors change physical results (A14) |
| **E4 ROI geometry** | ROI type, float vertices, stroke and position (C/Z/T), name and group; mask bitmap from `Roi.getMask()` | Exact float vertices **and** an identical mask | A10 F1–F4; non-integer geometry is critical |
| **E5 Measurements** | Every column of each Results row as a double (full precision through the API, not CSV), plus the row count | Exact for statistics computed by the same code path. A per-column relative tolerance of 1e-12 is allowed only for columns flagged thread-order sensitive. | P37 rounding, P39 accumulation |
| **E6 Global state** | Values of the result-affecting globals (A11 §6) before and after each step | Exact; any unexpected change is a failure | UP-04/05/06 |
| **E7 Macro replay** | The product-generated `.ijm` for a session, run in classic Fiji on the original inputs, reproduces E1–E6 | Same criteria as E1–E6 | G-4 reproducibility; CR-A12-05 |
| **E8 NL/voice resolution** | The predicted command plan against the labelled expected plan (command, keys, values, target) | Accuracy metrics (A16 CR-A16-10, A17 §12), plus **zero** unsafe executions | Not numerical equivalence; a separate gate |
| **E9 Scientific plausibility** | Workflow outputs against independent ground truth, e.g. BBBC001/004 counts (A29) | Reported, not pass/fail for parity | Validates workflows and demos, not invocation fidelity |

E9 is deliberately separate from E1–E7. A workflow can be faithful to Fiji and still scientifically wrong, for example from a poor threshold choice. That is the user's method, not a parity failure.

### 2. Reference execution protocol (PROPOSED)

```mermaid
sequenceDiagram
  participant T as Test harness
  participant P as Product (system under test)
  participant R as Reference Fiji (pinned, separate JVM)
  T->>P: Load fixture FX-nnn; perform action list A (via UI or API)
  P-->>T: Outputs O_p (pixels, metadata, ROIs, Results via API) + history H
  T->>T: Generate reference macro M_ref from A (independent generator, all keys + globals explicit)
  T->>R: Run M_ref on FX-nnn (headless -macro if A15 headless-safe, else GUI-mode)
  R-->>T: Outputs O_r (dumped via probe-style script to raw/JSON)
  T->>T: Compare O_p vs O_r under E1–E6
  T->>R: Run P's exported macro M_p (from H) on FX-nnn
  R-->>T: Outputs O_m
  T->>T: Compare O_m vs O_r (E7)
```

Notes:

1. `M_ref` comes from a *separate, minimal* generator, or is hand-written for the core commands. It must not reuse the product's macro generator, so that a shared bug cannot hide (INFERRED good practice).
2. Reference outputs for each fixture and action list are stored as hashes plus compact JSON (not full images) and keyed by the runtime lock hash. **Changing the Fiji pin invalidates all baselines**, and the full regeneration and comparison must be re-run (F03 change rules).
3. Commands that need a GUI JVM (A15 classification) have their reference run in GUI-mode Fiji, using the updater-autocheck-disabled launch (A04; CR-A04-16) and probe-style scripts that call `System.exit(0)`.
4. Nondeterministic commands (noise and similar) are tested only with a fixed seed set through the macro function `random("seed", n)`. If no seed mechanism exists, the command is excluded from E1/E2 and listed as such.

### 3. Test levels (feeds D18)

| Level | Scope | Example obligations |
|---|---|---|
| L0 Static | Catalogue integrity | Every MVP/HACK command in `commands.csv` has a parameter schema (A08) and a consequence class (CR-A19-01) |
| L1 Invocation unit | Option-string generation | Generated option strings contain every key, with booleans explicit (CR-A11-02, CR-A12-02) |
| L2 Engine equivalence | E1–E6 against the reference for each supported command on edge-case fixtures (FX-070–FX-081) | Every command marked *complete* in A28 |
| L3 Workflow equivalence | E1–E7 for the D16 journeys on real samples (FX-003…, FX-040…) | Every HACK journey |
| L4 Plausibility | E9 against BBBC ground truth | Reported only |
| L5 Interaction evaluation | E8 on the labelled NL/speech sets (FX-060–FX-062) | Zero unsafe executions; accuracy reported per model |

### 4. Coverage obligations per requirement area (PROPOSED)

| Area | Minimum dimensions | Key edge-case fixtures (A29) |
|---|---|---|
| IMG / IO | E1, E3 | 16-bit above 4095, 32-bit NaN, signed-16 calibration (CT DICOM), anisotropic voxels (Fly Brain), 5D (Mitosis), virtual stack |
| PROC | E1/E2, E6 | Each supported filter on 8/16/32-bit and a stack; with and without ROI |
| ROI | E4 | Sub-pixel polygons, every ROI type, C/Z/T positions, `.zip` round trip |
| MEAS | E5, E6 | Calibrated versus uncalibrated, redirect, multi-ROI (ROI Manager), accumulation across many Measure calls |
| MACRO | E7 | Every HACK journey, exported and replayed |
| NL / SPEECH | E8 | FX-060–FX-062 |

The authoritative coverage matrix will be `docs/data/verification-coverage.csv`, created in D18 once A28 assigns parity statuses.

## Constraints and Limitations

- The oracle is the same software, so this strategy cannot detect *algorithmic* bugs in Fiji itself. That is outside the project's scope (PR-1, PR-7). Known upstream defects found during analysis, such as `Roi.containsPoint` with sub-pixel ROIs (A10), are tracked as risks rather than fixed.
- Headless and GUI runtimes differ, for example in Measure accumulation (P39) and the ROI Manager (P35). For each command, the reference mode must match the mode the product uses, or the difference must be part of the comparison.
- Plugin categories C4–C6 (A13) cannot be driven headlessly. Their parity evidence is limited to E3/E6 checks around a user-operated legacy window (A28 "legacy fallback").

## Requirement implications

- **CR-A30-01** — For every command whose parity status is *complete*, the project shall maintain an automated L2 test comparing product output with a reference execution of the pinned Fiji under E1–E6. *Rationale:* §1, G-1.
- **CR-A30-02** — The reference macro used in equivalence tests shall be produced independently of the product's macro generator. *Rationale:* to avoid common-mode faults.
- **CR-A30-03** — Equivalence tests shall read measurements at full double precision through the API or through explicitly formatted output, never through default-precision CSV. *Rationale:* P37.
- **CR-A30-04** — Every reference baseline shall be keyed by the Fiji runtime lock hash and regenerated whenever the pin changes. *Rationale:* F03.
- **CR-A30-05** — Tests of nondeterministic commands shall set a seed with `random("seed", n)`. Commands without a seed mechanism shall be excluded from pixel-equivalence claims and listed. *Rationale:* `Functions.java#L1194-L1201`.
- **CR-A30-06** — Each journey in D16 at HACK priority shall pass the E7 macro-replay test in classic Fiji. *Rationale:* G-4.
- **CR-A30-07** — The verification report shall list, per command, the reference mode used (headless or GUI) and any accepted tolerance other than exact. *Rationale:* transparency; P39.
- **CR-A30-08** — Visual similarity (screenshots, rendered PNG comparison) shall not count as evidence of scientific equivalence. *Rationale:* brief A30.

## Risks

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R-A30-01 | GUI-mode reference runs are slow or flaky (dialogs, focus), so the equivalence suite is skipped | Medium | High | Prefer headless-safe commands for the MVP; dialog-free option strings; timeouts and screen-saver-independent scripts (G4 experience) |
| R-A30-02 | Thread-order nondeterminism in 32-bit reductions causes false failures | Low | Low | Per-command ULP tolerance flag, set only from evidence |
| R-A30-03 | Baselines quietly go stale after a runtime update | Medium | High | CR-A30-04; CI checks that the lock hash matches the baseline |
| R-A30-04 | The independent reference generator also omits a key, so both sides use the same remembered default | Medium | High | L1 test asserting that every schema key is present; reset plugin statics by running the reference in a fresh JVM |

## References

- `imagej/ImageJ@v1.54p`: `ij/process/ImageProcessor.java#L117, L2948`, `ij/process/ByteProcessor.java#L873-L876`, `ij/macro/Functions.java#L1190-L1205`, `ij/measure/ResultsTable.java#L57`.
- JEP 306, *Restore Always-Strict Floating-Point Semantics*: https://openjdk.org/jeps/306 (accessed 2026-10-17).
- Probes P35, P37 and P39 (G2), P50 (G4). Documents A09–A12, A15, A28 and A29.

## Change log

- 2026-10-17: Initial draft by the lead.
