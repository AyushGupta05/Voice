---
id: A27
title: Non-functional Requirements Specification
stage: 01-analysis
status: Baselined (awaiting approval)
priority: P0
source_revisions: [see source analysis documents]
related_requirements: [all NFR-*]
related_decisions: [DEC-005, DEC-007, DEC-010]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# A27 — Non-functional Requirements Specification

## Executive Summary

These non-functional requirements cover performance, reliability, usability, accessibility, privacy, security, maintainability, scientific fidelity, packaging and platform. Conventions are the same as in [A26](A26-functional-requirements.md).

Quantitative targets appear only where measured evidence justifies them (P60, P85 and the other probes). Where no evidence exists yet, the requirement is to **measure first and then set the target** (brief Part 13), and it is labelled as such.

## Performance (PERF)

| ID | Requirement | Rationale / evidence | Pri | Acceptance criteria | Sources |
|---|---|---|---|---|---|
| NFR-PERF-001 | Before the PARITY milestone, the project shall measure viewer frame time (pan and zoom) and render latency for 2D planes of 1024², 2048² and 4096² at 8, 16 and 32 bits, and for a composite of 3 or more channels. NFR targets for the viewer shall then be set from those measurements. | No viewer evidence yet. Avoids invented targets. | PARITY | I: a measurement report exists in `docs/04-verification/`, and the targets are added to this document. | CR-A23-03 |
| NFR-PERF-002 | With a warm, persistent speech worker on the reference CPU (Core Ultra 9 285H), transcription latency for a 2 s command clip shall be ≤ 0.5 s. | Measured warm time is 0.18–0.21 s for a 1.94 s clip (P60). The target is about 2× that, for headroom. | MVP | T: the median over 20 runs of FX-060-series clips is ≤ 0.5 s. | CR-A16-03 |
| NFR-PERF-003 | Memory used by undo snapshots and previews shall be accounted against a configurable budget, by default a fraction of the max heap (D05 sets the default), and the remaining budget shall be visible in the UI. | Heap is the binding resource (A23 §1) | HACK | T: the budget accounting matches snapshot sizes, and the UI shows the remaining budget. | CR-A23-02 |
| NFR-PERF-004 | Pixel data passed from the engine to UI components shall be copies or renderings, never live references to engine arrays. | Shared-array hazards (A09 H8) | MVP | I/T: the API returns immutable copies or rendered images. A mutation test shows that the engine data is unaffected. | CR-A09-05 |
| NFR-PERF-005 | Engine listeners shall do no image processing on the AWT EDT. They shall only schedule UI refreshes. | Listeners are delivered on the EDT (P80) | MVP | T: instrumentation shows listener execution time on the EDT ≤ 5 ms per event (PROPOSED budget, about one third of a 60 Hz frame). | CR-A23-04 |
| NFR-PERF-006 | The UI shall stay responsive (input events processed) while the engine executes long operations. | Single engine worker (FR-SYS-004) | MVP | T: during a 10 s stack filter, the UI responds to pan and zoom, and to opening a menu. EDT stall ≤ 100 ms (PROPOSED; perceptual threshold). | A18 §4 |

## Reliability (REL)

| ID | Requirement | Rationale / evidence | Pri | Acceptance criteria | Sources |
|---|---|---|---|---|---|
| NFR-REL-001 | A failure in any single action (exception, failed postcondition, cancelled dialog) shall leave the engine able to accept the next action. | `IJ.run` swallows exceptions; long sessions | MVP | T: a fault-injection suite of 10 failing actions, followed by a successful action, all within one session. | A18, A20 |
| NFR-REL-002 | The speech worker failing or being missing shall not affect any non-speech function. | Process isolation | MVP | T: killing the worker mid-session leaves all other journeys passing, and voice reports "unavailable". | CR-A16-02 |
| NFR-REL-003 | Headless batch runners shall enforce a timeout and terminate their JVM. The interactive engine shall use the bounded quit sequence (FR-SYS-005). | Exit hangs (P14, P85) | HACK | T: a deliberately hanging macro is terminated at the timeout, with no orphan process. | CR-A08-08, CR-A07-06 |
| NFR-REL-004 | IJ1 progress and status shall be captured by a chained legacy hook that delegates to the existing hooks. If a probe shows this interferes with imagej-legacy, the product shall instead state that per-slice progress is unavailable. | `DefaultLegacyHooks` forwards neither (A20) | HACK | T (validation probe in D04): the hooks chain, stack-filter progress events arrive, and legacy behaviour is unchanged (A20 R-A20-01 checklist). | CR-A20-03, CR-A07-05 (superseded, A26 §14-C2) |
| NFR-REL-005 | The engine shall publish lifecycle events (queued, started, progress if available, completed, failed, cancelled) with operation IDs for every action, and a post-action state digest (open images and `changes`, ROI/Overlay/ROI-list counts, Results row counts, globals hash and delta, new log lines, pending dialogs). | Observability; there is no Results listener | MVP | T: an event-sequence test for every route (R1–R4, A18). The digest fields are present. | CR-A20-02, CR-A18-04 |

## Usability (USE)

| ID | Requirement | Rationale / evidence | Pri | Acceptance criteria | Sources |
|---|---|---|---|---|---|
| NFR-USE-001 | Direct manipulation, menu and keyboard actions shall keep Fiji's immediacy. The product shall add no confirmation steps for AC0–AC3 actions from these modalities. | PR-2; must-stay-familiar | MVP | T: a policy test shows AC0–AC3 from menu, keyboard or canvas executes without a confirm event. | CR-A19-03 |
| NFR-USE-002 | Progress shall be shown as determinate only from a real counter. It shall otherwise be indeterminate. Time-estimated or simulated progress shall never appear. | PR-5; brief §1.4 | MVP | I: a UI review checklist. T: the progress component rejects updates without a counter source. | CR-A20-01 |
| NFR-USE-003 | Before release, labelled evaluation sets for NL instructions (A17 §12) and recorded human speech (FX-060–062; several speakers, accents and noise conditions) shall exist. Command-level accuracy shall be reported per model and prompt configuration. The unsafe-execution rate shall be 0. | All current speech evidence is synthetic; safety gate | PARITY (sets exist); LONG (release gate) | T: evaluation report in `docs/04-verification/`. | CR-A16-10, CR-A17-12 |
| NFR-USE-004 | The UI shall use Fiji's terminology (command labels, parameter names and menu paths) without renaming. | Familiarity; tutorials | MVP | I: terminology review against `commands.csv`. | A02, A04 |

## Accessibility (A11Y)

| ID | Requirement | Rationale / evidence | Pri | Acceptance criteria | Sources |
|---|---|---|---|---|---|
| NFR-A11Y-001 | All product-rendered controls (tools, menus, tables, plots, parameter panels, history) shall be keyboard-operable and shall expose accessible names, roles and values to assistive technology through Java Accessibility. | Legacy canvases expose nothing (A04, A05) | PARITY | T: an automated accessible-tree inspection lists names and roles for every control. D: manual check with Windows Narrator. | CR-A04-15, CR-A05-07 |
| NFR-A11Y-002 | Product-rendered surfaces shall meet WCAG 2.1 AA contrast and keyboard criteria (PROPOSED as the target standard), and shall scale consistently with the OS display scale. | UP-13/14; HiDPI | PARITY | T: contrast checks on the theme tokens. D: rendering checked at 100/150/200 % (NFR-PLAT-002). | CR-A05-07 |
| NFR-A11Y-003 | The image canvas shall expose to assistive technology the document identity, the current C/Z/T position, the pixel read-out under the keyboard cursor, and the active ROI summary. A keyboard-driven cursor shall be provided for pixel inspection. | The canvas is otherwise opaque to assistive technology (D14 gap) | PARITY | T: accessible-tree inspection while the keyboard cursor moves. D: Narrator check. | D13/D14 (design gap) |

## Privacy (PRIV)

| ID | Requirement | Rationale / evidence | Pri | Acceptance criteria | Sources |
|---|---|---|---|---|---|
| NFR-PRIV-001 | With networking disabled, all core functions (open, process, measure, macros, speech) shall work, and the product (including Fiji start) shall make no outbound connection. | Offline labs; T-17/T-22 | HACK | T: a packet capture during a scripted session shows zero outbound connections. Updater autocheck disabled. JDK telemetry state confirmed (A25 unknown resolved). | CR-A25-13 |
| NFR-PRIV-002 | By default, logs, manifests and crash reports shall not contain absolute user paths or image metadata. They shall use project-relative paths or hashed identifiers, with an opt-in verbose mode. | T-19 | HACK | T: a log scan after a scripted session finds no absolute paths. | CR-A25-09 |
| NFR-PRIV-003 | Human speech recordings for evaluation shall be collected only with written consent covering storage location, licence and withdrawal, and shall be stored with speaker metadata limited to accent group and recording condition. | Personal data | PARITY | I: consent records exist for every FX-06x recording. | CR-A29-06 |

## Security (SEC)

| ID | Requirement | Rationale / evidence | Pri | Acceptance criteria | Sources |
|---|---|---|---|---|---|
| NFR-SEC-001 | The product shall apply the A25 threat-model controls assigned to it, and the threat model shall be re-reviewed when the architecture decision (DEC-010) is approved. | Preliminary model | HACK | I: A25 review record. | A25 |
| NFR-SEC-002 | The product shall open no network listening port unless an optional external-client feature is enabled. Any local HTTP or WebSocket endpoint shall bind to loopback, validate `Host` (no test hosts in release builds), validate `Origin` on every request including the WebSocket handshake, and require a per-launch random session token. | The prototype and fiji-llm both accept Origin-less, unauthenticated requests (A25, A21 V11) | MVP (no port); LONG (if an external client is added) | T: a port scan shows no listener by default. When enabled, requests without the token, or with a bad Origin or Host, are rejected (including WebSocket). | CR-A21-04, CR-A25-08 |

## Maintainability (MAINT)

| ID | Requirement | Rationale / evidence | Pri | Acceptance criteria | Sources |
|---|---|---|---|---|---|
| NFR-MAINT-001 | Product code shall not patch, fork or copy upstream Fiji/ImageJ source. Integration shall use public APIs, SciJava plugin points, and the hook and listener mechanisms documented in A07, A15 and A18. | Brief Rule 1/3; upstream evolution | MVP | I: dependency review. No `ij.*` class is redefined by the product. | Brief, CR-A07-01 |
| NFR-MAINT-002 | Every test fixture shall have a row in `docs/data/datasets.csv` (source, licence, size, SHA-256 once fetched, coverage) before any test uses it. | Traceability, licence control | MVP | T: a CI check that every fixture path used in tests has a catalogue row. | CR-A29-01 |
| NFR-MAINT-003 | Synthetic fixtures FX-070–FX-081 shall come from versioned generator scripts with byte-identical output across runs. | Determinism | HACK | T: two generator runs give equal SHA-256. | CR-A29-04 |
| NFR-MAINT-004 | Tests and probes that launch Fiji shall pass `-Dimagej.updater.disableAutocheck=true`, terminate Fiji without saving, and isolate or restore ImageJ prefs (`IJ_Prefs.txt` and the `JavaSoft\Prefs` registry keys). | Prefs written by GUI runs (G8, P52) | MVP | T: the harness compares prefs before and after, with no difference. | CR-A04-16, CR-A22-06 |
| NFR-MAINT-005 | Per-commit CI shall use only fixtures under 5 MB (PROPOSED threshold). Larger fixtures shall run in a scheduled or manual suite. | CI runtime | HACK | I: CI configuration. | CR-A29-05 |
| NFR-MAINT-006 | Product requirements, decisions and the design shall stay traceable through `docs/data/traceability.csv`. Changes shall update the authoritative document first (F04 §10). | Brief Rule 4 | MVP | T: the documentation checker (Phase 8) reports no dangling IDs. | Brief Part 14 |

## Scientific fidelity (FID)

| ID | Requirement | Rationale / evidence | Pri | Acceptance criteria | Sources |
|---|---|---|---|---|---|
| NFR-FID-001 | Every command whose parity status (A28) is *complete* shall have an automated reference-equivalence test (A30 E1–E6) against the pinned Fiji, using a reference macro produced independently of the product's generator. | G-1 | MVP (MVP set); HACK (journeys); PARITY (all complete) | R: equivalence suite results recorded per command. | CR-A30-01, CR-A30-02, CR-A12-05 |
| NFR-FID-002 | Equivalence tolerances shall be exact for integer pixel types, metadata, ROI geometry and measurements. Any other tolerance (A30 §1) shall be justified per command and listed in the verification report, together with the reference mode (headless or GUI). | Transparency | MVP | I: the report lists the tolerance and mode per command. | CR-A30-07, A30 §1 |
| NFR-FID-003 | Visual similarity shall not count as evidence of scientific equivalence. Nondeterministic commands shall be tested with fixed seeds or excluded from pixel-equivalence claims. | Brief A30 | MVP | I: test review. | CR-A30-05, CR-A30-08 |
| NFR-FID-004 | Verification runs shall use the pinned reference Fiji, with lock checks before and after each run, shall fail if staged updates exist, and shall key baselines by the lock hash, regenerating them when the pin changes. | `apply-update` on launch; F03 | MVP | T: a CI check of the lock hash against the baseline key. | CR-A22-02, CR-A30-04 |
| NFR-FID-005 | No fixture, including BBBC ground truth, shall be used to train or fine-tune a model in this project. | Scope; NC-SA | MVP | I: policy review. | CR-A29-07 |

## Packaging and licensing (PKG)

| ID | Requirement | Rationale / evidence | Pri | Acceptance criteria | Sources |
|---|---|---|---|---|---|
| NFR-PKG-001 | Every bundled or downloaded third-party component shall be recorded with its name, exact version, SHA-256, declared licence(s), notice path and source location, in a machine-readable inventory under `docs/data/`. | 161 jars without declared licences (P70) | HACK | T: an inventory completeness check against the build output. | CR-A24-01 |
| NFR-PKG-002 | A bundled-distribution release shall not proceed until every bundled component with no declared licence has an identified licence, with evidence. | Undeclared licences | LONG | I: release checklist. | CR-A24-02 |
| NFR-PKG-003 | Original project code shall not import classes from GPL- or AGPL-declared jars unless DEC-005 allows it. | Keeps the licence decision open | MVP | T: an import scan (`jdeps`) in CI against the P70 GPL list. | CR-A24-03 |
| NFR-PKG-004 | Any redistributable speech runtime shall include the licence and notice texts of every installed package. | Missing notices (A24 §5.7) | PARITY | T: a notice check for each package in the bundle. | CR-A24-04 |
| NFR-PKG-005 | A redistributable speech runtime shall contain no GPL-licensed or proprietary native libraries (x264, x265, cuDNN, Intel OpenMP) unless their redistribution terms have been confirmed and recorded. | P73 findings | PARITY | T: a DLL scan of the bundle against an allow-list. | CR-A24-05 |
| NFR-PKG-006 | Packaging shall keep `Fiji/licenses/`, the JDK `legal/`, `DISCLAIMER`, `readme.txt` and all jar-embedded licence files unmodified. | Notice duties | PARITY | T: a hash comparison of the licence files before and after packaging. | CR-A24-06 |
| NFR-PKG-007 | The repository shall contain only fixtures whose licence permits redistribution under the project licence. Other fixtures shall be fetched to a git-ignored cache and verified by SHA-256. | BBBC NC-SA; ImageJ samples unlicensed | MVP | T: a CI check that committed fixtures are marked redistributable in `datasets.csv`. | CR-A29-02 |

## Platform (PLAT)

| ID | Requirement | Rationale / evidence | Pri | Acceptance criteria | Sources |
|---|---|---|---|---|---|
| NFR-PLAT-001 | Windows 11 x64 shall be supported for HACK and PARITY. macOS and Linux shall be listed as untested until verification probes (A15 U-A15-4) pass. | Only Windows was probed | HACK | I: release notes state the supported platforms. | CR-A22-04 |
| NFR-PLAT-002 | The UI shall render correctly at 100 %, 150 % and 200 % OS display scaling on Windows. | 150 % dev display; Fiji HiDPI issue | HACK | D/T: rendering checks at the three scales (layout without clipping; image pixels mapped correctly). | CR-A22-05 |
| NFR-PLAT-003 | The product shall run on the Java version bundled with the supported Fiji (Zulu 21 at the pin) without requiring a separate JDK. | Single runtime | MVP | T: the engine runs with `--java-home` set to the bundled JDK only. | A22 |

## Change log

- 2026-10-17: Baseline consolidated by the lead.
