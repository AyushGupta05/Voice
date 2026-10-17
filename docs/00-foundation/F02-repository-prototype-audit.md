---
id: F02
title: Repository and Prototype Audit
stage: 00-foundation
status: Draft
priority: P0
source_revisions: [click-to-batch@08a1433]
related_requirements: []
related_decisions: [DEC-002, DEC-004]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# F02 — Repository and Prototype Audit

## Executive Summary

The repository holds a small, well-tested **integration prototype**, not an application architecture. It shows that a Python service can drive a real, byte-pinned Fiji installation through generated ImageJ macros, and that local faster-whisper transcription can feed a constrained command grammar. All 23 of its tests pass when re-run independently. However, it executes **one short-lived headless Fiji process per workflow**, so it cannot hold interactive scientific state (open images, ROIs, Results, undo, plugin dialogs). Its four supported operations are reached through a regex grammar, and it accepts only 8-bit PNGs.

Worth reusing:

- the runtime pinning and verification approach;
- process isolation of speech;
- strict typed validation and the "transcript is never executed directly" rule;
- the honest verification record.

The React UI and the per-run execution model should not be inherited (see A21, ADR-001).

## 1. Repository state (VERIFIED, 2026-10-17)

| Item | Finding |
|---|---|
| Path | `C:\Users\arnav\Documents\dev\click-to-batch`, which matches the brief |
| Remote | `origin https://github.com/reverez/click-to-batch.git` (fetch/push) |
| Branches | `main` (HEAD `e48b5bc`, 2 commits: `5b8c6f7` scaffold, `e48b5bc` docs), `origin/main` |
| Working tree at audit start | 22 modified/deleted tracked files and 21 untracked paths. **The entire "Fidelis" prototype was uncommitted.** |
| Action taken | Branch `docs/analysis-design` created. Commit `08a1433` snapshots the working tree exactly as found (approved by owner). `main` untouched; nothing pushed. |
| Naming | The repo, directory and remote are still `click-to-batch`. The Python package, UI label and workspace are `fidelis` (renamed by the previous session, uncommitted until the snapshot). New planning docs use the working title "Fiji Modernisation Project" (DEC-001). |
| Ignored local state | `.env` (sets `FIJI_EXECUTABLE`), `.venv` (Python 3.14.3), `.venv-stt` (Python 3.11.16), `external/models/tiny.en`, `data/inputs/` (4 uploaded PNGs before this stage; 5 after the re-run in §3), `outputs/` (18 run directories after the re-run, plus test XML and two screenshots), `frontend/dist`, and stale `*.egg-info` for both the old `click_to_batch` and new `fidelis` packages |
| CLAUDE.md | None present |
| External runtime | `C:\Users\arnav\Documents\dev\Fiji`: Fiji 2.18.1-SNAPSHOT, ImageJ 1.54p, Zulu 21.0.7, 557 jars, 99 `plugins/` entries, Jaunch launcher. Pinned by `external/fiji.lock.json` (1,379 hashes). `verify_runtime` passes (lock SHA `f6695397…`). |

**Incident record.** During reconnaissance in this stage, a `jar xf` command wrote a stray `IJ_Props.txt` into the Fiji root. It was byte-identical to the jar entry and outside the paths the lock hashes. The file was deleted, and the pin was re-verified as passing before any further work.

## 2. Inventory of implemented functionality

| Area | Files | What it actually does | Evidence |
|---|---|---|---|
| HTTP API | [`backend/src/fidelis/main.py`](../../backend/src/fidelis/main.py) (233 LOC) | FastAPI with endpoints `/api/health`, `/commands/preview`, `/images` (upload/get), `/speech/transcribe`, `/jobs` (create/get/replay/artifacts) and `/macros/preview`. Uses an in-memory `jobs` dict, a single-thread executor, a 409 response when busy, a 100-job cap, a loopback Host allow-list and an Origin check. | VERIFIED (source) |
| Command grammar | [`commands.py`](../../backend/src/fidelis/commands.py) (73 LOC) | Pydantic `Action` with `operation ∈ {invert, blur, threshold, measure}`, `sigma` in 0.1–10, `level` in 0–255 and `extra="forbid"`. `parse_command` splits on "then" and full-matches each clause against 4 regexes, rejecting the whole input on any failure. `action_macro` emits macro text. | VERIFIED |
| Fiji adapter | [`adapters/fiji.py`](../../backend/src/fidelis/adapters/fiji.py) (150 LOC) | `verify_runtime` hashes the install against the lock. `build_macro` writes `setBatchMode(true); open(src); run("8-bit")`, then for each stage `saveAs PNG`, `getStatistics`, appends to the CSV and `File.append` progress, ending in `System.exit(0)` via `eval("script")`. `execute` launches `fiji-windows-x64.exe --java-home=… --allow-multiple --headless -macro`, polls `progress.txt` every 150 ms, applies a 120 s timeout with `taskkill /T /F`, and checks the return code, completion marker and stage files. | VERIFIED |
| Speech | [`adapters/speech.py`](../../backend/src/fidelis/adapters/speech.py), [`speech/worker.py`](../../speech/worker.py) | A subprocess per request in `.venv-stt`. It verifies the model SHA-256s, decodes with PyAV at 16 kHz, rejects clips over 30 s, and runs `WhisperModel(tiny.en, cpu, int8, cpu_threads=4)` with `transcribe(language="en", beam_size=5, vad_filter=True)`. Returns JSON text. | VERIFIED |
| Frontend | [`frontend/src/main.tsx`](../../frontend/src/main.tsx) (202 LOC), `style.css` | A single React component: a hero heading ("Give an instruction. See the image change."), an instruction textarea, Review / Use microphone / upload audio, an action list, Run in Fiji, a macro preview, two static `<img>` figures (original / stage N), an event timeline, a stats table and download links. Polls the job every 350 ms. | VERIFIED |
| Pins and licences | `external/*.json`, `speech/requirements.lock.txt`, `third_party/` | Fiji file inventory, faster-whisper version and model revision, a hash-locked speech dependency tree, licence copies and package inventories. | VERIFIED |
| Tests | `tests/backend/*.py` (5 files, 23 tests) | API validation/origin (4), grammar rejection and strictness (13), health (1), real integration (2), runtime failure (3). | VERIFIED. Re-run below. |
| Scripts | `scripts/*.py` | `check_fiji.py` (smoke macro), `pin_fiji.py` (re-pin, requires an explicit flag), `setup_speech.py` (model download plus hash), `create_fixture.py`, `collect_python_licenses.py`. | VERIFIED |

## 3. Independent test re-run (VERIFIED)

Command: `FIDELIS_INTEGRATION=1 .venv/Scripts/python.exe -m pytest -v`, then `ruff check .` and `npm --prefix frontend run build`. Run on 2026-10-17, branch `docs/analysis-design` @ `08a1433`, Windows 11 x64.

Result:

- pytest: **23 passed, 1 warning** (a Starlette `httpx` TestClient deprecation) in 35.4 s;
- ruff: all checks passed;
- Vite build: succeeded.

Raw log: [`probes/P00-prototype-test-rerun.log`](../01-analysis/references/probes/P00-prototype-test-rerun.log). The previous session's claim of 23 passing tests is therefore **confirmed**. Note what the tests do and do not establish:

- The two integration tests exercise real Fiji and real faster-whisper. They establish pixel-level correctness of *Invert* (every output pixel equals 255 − input) and replay determinism, and qualitative properties of blur → threshold → measure on a 64×64 synthetic image.
- They do **not** compare against a reference Fiji execution with tolerances (A30). They do not test real microscopy data or speech from a human speaker.
- `test_timeout_terminates_a_real_worker_process` substitutes a sleeping Python process for Fiji, and `test_api_surfaces_worker_failure` substitutes the adapter. Both are correctly described as failure-path tests in `docs/verification.md`.

## 4. Documentation claims versus implementation

| Claim (source) | Assessment |
|---|---|
| "Supported-action macro recording and replay" (README) | **Accurate but narrow.** The "recording" is the prototype's own validated action list rendered as macro text. It does not use `ij.plugin.frame.Recorder`, as `docs/architecture.md` states correctly. |
| "ImageJ measurements" table (UI caption) and "ImageJ Measure" (verification.md) | **Partly misleading.** The table shown comes from `getStatistics(area, mean, min, max, std)`, which is appended for *every* stage by the wrapper macro (`fiji.py:build_macro`). The `measure` action's `run("Measure")` writes to an ImageJ Results table that is never exported. The numbers are genuine ImageJ statistics, but they are not the output of the Measure command or of its Set Measurements configuration. |
| "Real stage events… no simulated progress" (architecture.md) | **Accurate.** Events derive from `progress.txt` lines written by the macro after each stage. Granularity is per stage only; there is no intra-operation progress. |
| "Speech… transcript becomes a visible command for review; transcription alone never runs image processing" | **Accurate** (`transcribe` → `preview`; Run is a separate user action). |
| "Pinned external Fiji process adapter" | **Accurate.** Coverage gaps in the lock are noted in §6. |
| "Only Windows x64 is tested" | **Accurate.** |

## 5. Architectural assumptions embedded in the prototype (not validated)

| # | Assumption | Status | Where analysed |
|---|---|---|---|
| AS-1 | A per-workflow, short-lived, headless JVM is an adequate execution model. | INFERRED false for the product. It cannot hold an active image, ROI Manager, Results, display state or undo between user actions. JVM start-up is paid per run (`elapsed_seconds` recorded in manifests, not benchmarked). | A15, A21, A23 |
| AS-2 | Interaction is "compose a command list, then run it as a batch". | Conflicts with Fiji's immediate, stateful interaction model (A03, A04). | A18 |
| AS-3 | A web (React) UI is the presentation layer. | Unevaluated. It was inherited from the scaffold. | A21 |
| AS-4 | Global ImageJ preferences can be set freely per run. | The threshold macro sets `setOption("BlackBackground", true)`, and the measure macro sets `Set Measurements…`. Both are global ImageJ preferences. Persistence to `IJ_Prefs.txt` when exiting through `System.exit(0)` in headless mode is **UNKNOWN**. In a shared, persistent Fiji, such side effects would change the user's results elsewhere. | A11, A19 |
| AS-5 | Gaussian `sigma` is in pixels. | True for the emitted `sigma=…` without the `scaled` keyword (INFERRED from the macro text; confirm in A11/A17). It must be explicit in any NL/voice interface. | A17 |
| AS-6 | A regex grammar is an adequate NL layer. | Adequate for 4 commands; does not scale to the catalogue (A08). | A17 |
| AS-7 | 8-bit PNG input is representative. | False for microscopy, which is typically 12/16-bit, multichannel, Z/T, calibrated and in TIFF/OME-TIFF or proprietary formats. `run("8-bit")` is applied unconditionally. | A14, A29 |

## 6. Technical debt and defects (VERIFIED from source unless marked)

1. The lock covers root `.exe`/`.bat` files and the `jars plugins lib config java scripts macros luts update` folders (`fiji.py:runtime_files`). It does not cover the root `fiji` shell script, `db.xml.gz`, `images/` or `licenses/`. A low-risk gap, but the pin is not a complete inventory.
2. `scripts/check_fiji.py` launches Fiji without `--allow-multiple` or `--java-home`, which is inconsistent with the adapter.
3. Job state lives in memory only. History is lost on restart. One job runs at a time per process; there is no cancellation or queue (all documented).
4. Stale `click_to_batch.egg-info` remains in `backend/src` (ignored by Git).
5. The UI is a single 202-line component with hero/marketing composition and static before/after images. It has no canvas interaction, zoom, pixel readout, ROI or stack support. This is the pattern the brief rejects (§1.4).
6. `pyproject.toml` declares `requires-python >=3.11`, while the tested environment is 3.14.3. The speech environment is pinned separately to 3.11.

## 7. Reuse assessment

| Component | Recommendation | Reason |
|---|---|---|
| Runtime pin + `verify_runtime` + `pin_fiji.py` procedure | **Reuse** (concept and probably code) | Directly serves reproducibility (NFR-FID). Coverage gaps (§6.1) should be closed. |
| Speech worker isolation (separate venv/process, model hash check, duration caps, CPU int8) | **Reuse** as the baseline for D08 | Keeps native dependencies out of the main runtime. The transcript→review rule matches A19. |
| Typed action validation (`extra="forbid"`, ranges, whole-input rejection) | **Reuse the pattern**; regenerate the schema from the command catalogue (D06) | Matches the brief's "no unrestricted authority" principle |
| Macro text generation per action | **Reuse the idea** (actions map to recordable macro lines). Replace the hard-coded strings with catalogue-driven parameters. | Must align with Recorder syntax (A12) |
| Per-run headless execution | **Retain only as a batch/headless back-end** for non-interactive replays and reference runs (A30) | Unsuitable for interactive work (AS-1) |
| FastAPI service | **Undecided** (depends on ADR-001) | Only useful if the architecture keeps a Python orchestration layer |
| React workbench | **Do not reuse as the UI design.** Keep it in the repository as evidence. | Brief §1.4. Also lacks a real viewer. |
| Tests | **Keep** as regression tests for whatever survives. Failure-path patterns (timeouts, pin mismatch) are reusable. | — |

## 8. Unresolved problems carried into analysis

- Whether interactive state should live in a persistent Fiji JVM, and how a modern UI reaches it (A15, A21).
- Whether to observe original Fiji actions through `CommandListener` and the Recorder, rather than generating macros only from our own controls (A12, A18, A20).
- Product naming (DEC-001). Original-code licence and redistribution model (A24, DEC-005).

## Risks

- **R-F02-01:** Inheriting the prototype's architecture by inertia. Likelihood: medium. Impact: high. Mitigation: ADR-001 must compare alternatives explicitly (A21).
- **R-F02-02:** Global preference side effects corrupting a user's later analyses once the work moves to a persistent Fiji session. Likelihood: high if unaddressed. Impact: high (scientific). Mitigation: CR in A11/A19 to snapshot and restore prefs, or to pass every option explicitly.

## Change log

- 2026-10-17: Initial audit. Test re-run recorded.
