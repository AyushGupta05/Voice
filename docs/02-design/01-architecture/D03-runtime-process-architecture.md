---
id: D03
title: Runtime and Process Architecture
stage: 02-design
status: Draft (provisional on DEC-010)
priority: P0
source_revisions: [Fiji archive 20261013-1517 (lock f6695397); Fiji/config/jaunch/fiji.toml]
related_requirements: [FR-SYS-001, FR-SYS-002, FR-SYS-005, FR-SYS-006, FR-SYS-007, FR-SYS-008, FR-SYS-011, FR-SPEECH-001, NFR-REL-001, NFR-REL-002, NFR-REL-003, NFR-PLAT-003, NFR-SEC-002]
related_decisions: [DEC-010, ADR-001, ADR-002, ADR-005]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# D03 — Runtime and Process Architecture

## Executive Summary

The product runs as **one Fiji JVM**, launched by Fiji's Jaunch launcher on the bundled Zulu 21 JDK. It is non-headless, and its main window stays hidden (ADR-002). It hosts the engine service, the workspace UI and the interpreter. It owns **one child process**, the speech worker. This document specifies:

- the launch configuration and start-up order;
- the thread model inside the JVM;
- the speech worker lifecycle;
- health checks and watchdogs;
- the bounded quit sequence, which works around the GUI-mode `System.exit` hang (P85);
- crash recovery through a session journal.

## 1. Processes and ownership

| Process | Started by | Parent/ownership | Ends when | Requirements |
|---|---|---|---|---|
| Fiji JVM (product) | User, via the Fiji launcher with the product launch mode (§2) | Top-level | User quits (§6), or crash | FR-SYS-001, NFR-PLAT-003 |
| Speech worker (Python 3.11) | CMP-43, lazily on first voice use (configurable: at start-up) | Child of the JVM. Exits on stdin EOF, so it does not outlive a dead parent. | Voice disabled; quit; parent exit; failure | FR-SPEECH-001, NFR-REL-002 |
| Reference Fiji (verification only) | Test harness (D18) | Harness | Each test run (timeout enforced) | NFR-FID-004, NFR-REL-003 |

No other process is spawned, and no network listener is opened (NFR-SEC-002). The optional local LLM (D08) is a user-managed external service, contacted over a loopback client connection only when the user enables it.

## 2. Launch configuration

| Setting | Value | Reason | Evidence |
|---|---|---|---|
| Launcher | `fiji-windows-x64.exe` (Jaunch) | Supported Fiji entry point | A22 |
| Java | Bundled `java/win64/zulu21…` (default Jaunch discovery; `--java-home` in managed or test launches) | NFR-PLAT-003 | A22 |
| Headless | **No** | ADR-002 | A15 |
| `-Dimagej.updater.disableAutocheck=true` | Always | FR-SYS-008, NFR-PRIV-001 | `DefaultUpdateService.java#L65` (A04, A25) |
| UI selection | Product UI selected as the SciJava `UserInterface` for this launch (`-Dscijava.ui=<product-ui-name>`) | Shows the workspace instead of the legacy UI | P49 showed `-Dscijava.ui=swing` selecting a non-legacy UI (VERIFIED for `swing`; INFERRED for a product UI) |
| How the flags are supplied | **PROPOSED:** a product Jaunch fragment `config/jaunch/extra-<product>.toml` defines a launch option (e.g. `--workspace`) that adds the two `-D` flags. A desktop shortcut runs `fiji-windows-x64.exe --workspace`. Classic Fiji launches are unchanged. | Keeps the user's classic Fiji intact; works with update-site installation | `fiji.toml#L14` includes `extra*.toml` (VERIFIED). Whether an update site can deliver a Jaunch fragment, and its exact syntax, is **UNKNOWN**; validate in milestone 1. Fallback: a product launcher script with explicit arguments. |
| `--allow-multiple` | Not used for product launches. Used for test and reference runs. | Avoid accidentally attaching to a classic Fiji instance; isolate tests | A22 |
| Heap | Fiji default (75 % of RAM), overridable | A23 | `fiji.toml#L81` |

## 3. Start-up sequence

```mermaid
sequenceDiagram
  participant L as Jaunch launcher
  participant J as JVM main (Fiji)
  participant C as SciJava Context + LegacyService
  participant E as Engine service (CMP-20..31)
  participant U as Workspace UI (CMP-01)
  participant S as Speech worker (CMP-44)
  L->>J: start JVM (bundled JDK, -D flags); Fiji/Jaunch splash shown
  J->>C: create Context (LegacyInjector.preinit before ij.*) [FR-SYS-002]
  C->>C: LegacyService installs hooks, hidden ij.ImageJ frame
  C->>E: service initialise (SciJava Service lifecycle; lightweight)
  C->>U: UIService.showUI(product UI), immediately
  U->>U: show start-up screen (UI-19): INITIALISING [FR-SYS-006]
  E->>E: self-test: !isHeadless, LegacyService.isActive, Fiji frame hidden [FR-SYS-001]
  E->>E: register CommandListener, module-event subscriptions, IJ1 listeners, chained LegacyHooks [ADR-011]
  E->>E: apply window policy, auto-run sources check [FR-SYS-011]
  E-->>U: EngineStateChanged(BUILDING_CATALOGUE)
  E->>E: build runtime catalogue asynchronously (Menus + ModuleService + ScriptService + schemas) [FR-CMD-002]
  E-->>U: EngineStateChanged(READY) or FAILED(reason)
  U->>U: enable actions; check session journal for unclean shutdown [FR-SYS-007]
  opt voice enabled at start-up
    U->>S: spawn worker; handshake (model hash check) [FR-SPEECH-001]
  end
```

Start-up order (Phase 8 correction): (1) the JVM starts, covered by the Fiji/Jaunch splash screen (**VERIFIED**: `Fiji/config/jaunch/fiji.toml#L254-L255` sets `-Dscijava.app.splash-image` unless `--no-splash`); (2) the Context is created and the legacy layer initialises; (3) `UIService.showUI(product UI)` is called immediately and UI-19 shows INITIALISING; (4) the engine completes its self-test and its listener and hook registration, then builds the catalogue asynchronously, emitting `BUILDING_CATALOGUE`; (5) `READY`. The product UI is a SciJava `UserInterface` created by the Context, so it cannot show anything earlier; no product Swing code runs before the Context (FR-SYS-002).

Engine states visible in the UI: `INITIALISING → BUILDING_CATALOGUE → READY`, or `FAILED(reason)`, and `QUITTING` during §6. The JVM start phase is covered by the splash screen, not by UI-19. Actions submitted before `READY` are rejected with `ENGINE_NOT_READY` (FR-SYS-006).

Timing basis: the GUI JVM is ready in 4.5–5.3 s (P85). Catalogue build time is **UNKNOWN**; measure it in milestone 1 and cache it by the lock hash if it is slow.

## 4. Thread model inside the JVM

| Thread | Role | Rules | Evidence |
|---|---|---|---|
| AWT EDT | All Swing UI work; delivery of IJ1 `ImageListener` and SciJava events | No engine calls that block. Listeners only enqueue refreshes (NFR-PERF-005). | P80 |
| **Engine worker** (one) | Executes every state-mutating action (R1–R4) in order | Never the EDT. Renamed `Run$_…` by `IJ.run` (permanent, P80). The engine therefore treats it as a dedicated, never-reused thread name. | ADR-004 |
| SciJava module threads | R2 module execution | The engine worker waits on the `Future` with a timeout and a dialog watch | A15 §6 |
| Engine watchdog | Detects a stalled worker (no progress, no pending dialog, no completion within the per-family limit) | Raises a `TIMEOUT` advisory. Never kills the worker silently. | D17 |
| Speech client I/O | Reads and writes worker stdin/stdout | Bounded queues; timeouts | ADR-005 |
| LLM client | HTTP to a local LLM (optional) | Off the EDT and off the engine worker | D08 |
| Journal writer | Appends history records to the session journal (§7) | fsync per record (INFERRED cost acceptable; measure) | FR-SYS-007 |

## 5. Health checks

| Check | Mechanism | Period | Action on failure |
|---|---|---|---|
| Engine worker liveness | Heartbeat counter updated between actions; watchdog compares against the running action's family limit | 1 s | Status shows "operation running for N s". Over the limit, offer Cancel (cooperative, D17) or engine restart. |
| EDT responsiveness | Timestamped `invokeLater` probe | 1 s | Log stalls > 100 ms (NFR-PERF-006 instrumentation) |
| Pending modal dialog | AWT window listener plus `Window.getWindows()` scan while an action runs | 250 ms during actions | `WAITING_FOR_DIALOG` state (event code `DIALOG_PENDING`); bring the dialog forward (FR-PROC-007) |
| Speech worker | `ping` message on IF-06 | On demand, and 30 s while enabled | Restart once; then mark voice unavailable (NFR-REL-002) |
| Runtime pin (reference install only) | Lock check before and after each run | Per test run | Fail the run (NFR-FID-004) |

## 6. Quit sequence (bounded; FR-SYS-005)

1. The user chooses Quit. The engine enters `QUITTING`, stops accepting new actions and waits for the running action, or offers Cancel.
2. For each document with unsaved changes, the user chooses save or discard. Inputs are never overwritten silently (FR-IMG-009).
3. All documents are closed through the engine. Discarded images get `imp.changes=false` first, so that no hidden "Save changes?" prompt can appear. Product-hidden windows are closed explicitly.
4. ImageJ prefs are saved explicitly (`Prefs.savePreferences()` in IJ1; the SciJava `PrefService` writes through). Test runs rely on prefs isolation (NFR-MAINT-004, D18 §3.1), not on skipping this step.
5. The session journal is flushed, closed and marked clean (§7).
6. The speech worker is sent `shutdown`. The engine waits 2 s, then closes stdin (the worker exits on EOF), waits 2 s, and finally kills the process.
7. `Runtime.halt(0)` is called. `context.dispose()` is **not** called while IJ1 images or windows remain: it disposes `LegacyUI`, which enters the IJ1 quit path (`ij.ImageJ.run`, P85 `P85-run2-jstack.txt`), and that path calls `WindowManager.closeAllWindows()` (which can prompt) and then `Prefs.savePreferences()` (`ij/ImageJ.java#L857-L864`). A bounded `context.dispose()` attempt before the halt is optional, and only if V-12 (D04 §10) shows that it completes without IJ1 quit prompts.

Unexpected exit (for example a plugin's `System.exit`): the product JVM shutdown hook (DEC-036) flushes the journal and then halts after a time bound (D17 §3.11).

**Validation needed:** confirm that steps 3–7 leave IJ1 and SciJava prefs consistent, and that no orphan processes remain (NFR-REL-003); V-12 for the optional dispose.

## 7. Session journal and crash recovery (FR-SYS-007, FR-HIST-001)

- Each completed action's `HistoryRecord` (IF-05) is appended to a journal file in the product's per-user data directory. Inputs are identified by path and SHA-256 (FR-HIST-010). Absolute paths in logs follow NFR-PRIV-002; the journal itself must store paths to replay, and is kept user-private. Speech transcripts are not written to the journal unless the user opted in (FR-SPEECH-002): a spoken action's record carries a `transcriptRef` ID, and the text stays in memory (D06 §8).
- On start-up, a journal without a clean-close marker indicates a crash or kill. The UI offers **"Rebuild session by replay"**, which re-opens the inputs (with hash checks) and replays the records in a fresh engine. It lists what cannot be restored: unsaved observed changes without parameters, and edits inside plugin windows (A19 §6).
- Engine hang recovery: from the watchdog the user may choose "Restart engine". Because the engine and UI share one JVM, a restart means: flush the journal, then spawn a new product launch (same arguments) and halt the current JVM. The new process offers replay. **PROPOSED**; acceptable for PARITY tier.
- Because the UI shares the JVM (O2), it cannot survive engine loss. In-session recovery is limited to the `TIMEOUT` advisory, Cancel and restart-with-replay (above). Any other loss is recovered at the next start from the journal (J-13).

## 8. Failure matrix

| Failure | Detection | Effect | Recovery | Requirement |
|---|---|---|---|---|
| Legacy layer inactive at start-up (patch-order issue) | Self-test | Start-up fails with an explanation | User relaunches; developer issue | FR-SYS-002 |
| Command throws inside a plugin | Window tracker ("Exception" `TextWindow` opened by `Executer` in M2, `Executer.java#L113-L117`; "Macro Error" window; DEC-035) plus log scan plus postconditions (CMP-26) | Action `FAILED` | Snapshot rollback (ADR-008) | FR-PROC-005, NFR-REL-001 |
| Modal dialog blocks the worker | Dialog watch | `WAITING_FOR_DIALOG` (event `DIALOG_PENDING`) | User answers in the legacy dialog | FR-PROC-007 |
| Worker hang (no dialog) | Watchdog | `TIMEOUT` advisory | Cancel (Escape/abort); else restart with replay | FR-PROC-013, FR-SYS-007 |
| Plugin calls `System.exit` | Product shutdown hook (DEC-036) | Journal flushed by the hook; bounded halt; session lost | Replay recovery at next start | FR-SYS-007 |
| JVM crash | Next start: unclean journal | Session lost | Replay recovery | FR-SYS-007 |
| Speech worker crash or missing environment | Ping or protocol error | Voice unavailable | One automatic restart; UI message | NFR-REL-002 |
| Quit hangs | Optional dispose attempt times out (V-12) | — | `Runtime.halt` after the steps that persist state | FR-SYS-005 |

## Decisions proposed

- DEC-028 (new): product launch via a Jaunch `extra-*.toml` launch option that adds the UI-selection and autocheck flags. The fallback is a launcher script. Validation is required.

## Change log

- 2026-10-17: Initial version by the lead.
- 2026-10-17: Phase 8 review fixes (findings #3, #7, #11, #12, #17, #26).
