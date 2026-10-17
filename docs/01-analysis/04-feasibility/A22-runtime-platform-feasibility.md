---
id: A22
title: Runtime and Platform Feasibility
stage: 01-analysis
status: Draft
priority: P1
source_revisions: [Fiji archive 20261013-1517 (lock f6695397), Jaunch config in Fiji/config/jaunch]
related_requirements: []
related_decisions: [DEC-010]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# A22 — Runtime and Platform Feasibility

## Executive Summary

The development machine can run the recommended architecture (A21 O2) with the components already present:

- the pinned Fiji with bundled Zulu JDK 21.0.7;
- Swing with FlatLaf, and JavaFX 23 including its Windows natives;
- the existing Python 3.11 speech environment.

The machine does **not** have a Java build tool (Maven or Gradle) or a Rust toolchain. A Java plugin build therefore needs a Maven wrapper or a Maven install. A Tauri shell (relevant only for O4) would need Rust.

Runtime facts that the design must accommodate:

- Fiji starts in 4.5–5.3 s with a working set of about 450 MB (P85).
- The heap defaults to 75 % of RAM (Jaunch config).
- `System.exit` hangs in GUI mode (P85).
- Fiji applies pending updates on every non-dry-run launch, which interacts with the runtime pin.
- The Fiji updater checks the network unless `-Dimagej.updater.disableAutocheck=true` is set (A04, A25).
- Prefs persist in two places: IJ1 `IJ_Prefs.txt` on a normal quit, and ImageJ2/SciJava `java.util.prefs` in the Windows registry.

Only Windows 11 x64 has been tested. macOS and Linux remain **UNKNOWN**.

## Sources and Evidence

- Platform inspection, 2026-10-17 (CIM, `nvidia-smi`, PATH): lead notes, summarised in §1.
- `Fiji/config/jaunch/fiji.toml`, `jvm.toml` (read-only).
- Probes P01, P52, P60, P64, P84, P85, P87. Prefs inspection (lead probe notes in `probes/README-lead.md`).
- F02 (environment and prototype toolchain), `docs/environment.md` (prototype record P05).

## Verified Findings

### 1. Development and test machine (VERIFIED 2026-10-17)

| Item | Value |
|---|---|
| OS | Windows 11 Home 10.0.26200 |
| CPU | Intel Core Ultra 9 285H, 16 cores / 16 logical |
| RAM | 31.4 GB |
| GPU | NVIDIA GeForce RTX 5070 Laptop (8 GB, driver 616.56); Intel Arc 140T |
| Display | One screen, 1707×1067 logical (about 150 % scaling) |
| Disk | About 495 GB free on C:. The Fiji install is 845 MB. |
| Java | Bundled Zulu 21.0.7 (used by Fiji and the prototype); system Temurin 21.0.12 |
| Python | 3.14.3 (`.venv`, prototype API); 3.11.16 (`.venv-stt`, speech) |
| Node / npm | 24.14.0 / 11.9.0 |
| Build tools | `uv` present; **no** `mvn`, `gradle`, `rustc` or `cargo` on PATH |

### 2. Fiji runtime behaviour

| Topic | Finding | Evidence |
|---|---|---|
| Launcher | Jaunch (`fiji-windows-x64.exe` + `config/jaunch/*.toml`). Supports `--headless`, `--allow-multiple`, `--run <script>`, `--python` mode, `--dry-run`, `--java-home`. | **VERIFIED** `fiji.toml#L21-L74`; P40 |
| Heap | `cfg.max-heap = '75%'`, which is about 23 GB here (`-Xmx23g`) | **VERIFIED** `fiji.toml#L81`; P84 |
| Java version policy | `jvm.version-min = '8'`; recommended Java 21 (`-Dscijava.app.java-version-recommended=21`) | **VERIFIED** `fiji.toml#L111, L256-L257` |
| Pending updates | Applied at each non-dry-run JVM or Python launch (`apply-update` directive) | **VERIFIED** `fiji.toml#L70-L71`. The impact on the pin is INFERRED: an update staged by anyone will mutate the install at the next launch, and the lock check (which covers `update/`) would then fail. |
| Updater network check | Runs on the first `UIShownEvent` of a non-headless UI, unless `-Dimagej.updater.disableAutocheck=true` is set | **VERIFIED** A04, A25 (`DefaultUpdateService.java#L65, L113-L125`); P49 observed the check firing |
| Start-up | Headless trivial macro: 4.1–4.7 s start to exit (P01). GUI JVM to `IJ.getInstance()`: 4.5–5.3 s. Working set 437–468 MB; heap used about 63 MB. | **VERIFIED** P01, P85 |
| Shutdown | `System.exit(0)` hangs in GUI mode (the shutdown hook calls `EventQueue.invokeAndWait` after `Prefs.savePreferences()`). `Runtime.halt` works but skips hooks. | **VERIFIED** P85 (jstack) |
| Prefs persistence | IJ1 `%USERPROFILE%\.imagej\IJ_Prefs.txt` is written on the GUI quit path (G8 runs created it; headless runs never did). ImageJ2/SciJava options go to `HKCU\Software\JavaSoft\Prefs\net\imagej\…` and `…\org\scijava\…`. | **VERIFIED** (lead inspection; P52; G8 report) |
| HiDPI | The AWT/Swing default transform is identity and the normalising transform is 1.333 (P87). G4 found Java renders some windows at 100 % on the 150 % display (A04 §4). Fiji HiDPI support is an open upstream issue (`fiji/fiji#395`, A05). | **VERIFIED** P87, A04, A05 |
| JavaFX | Initialises in 290 ms with the `d3d` pipeline; WebView works (P87) | **VERIFIED** P87 |
| GPU for speech | The ctranslate2 wheel sees the RTX 5070 but lacks `cublas64_12.dll`, so CPU is the working baseline | **VERIFIED** P64 |

### 3. Process topology implied by A21 O2

| Process | Runtime | Start-up | Lifetime | Notes |
|---|---|---|---|---|
| Fiji JVM (engine + workspace UI) | Zulu 21 via Jaunch, non-headless | 4.5–5.3 s to IJ1 ready, plus UI construction | User session | Must avoid `System.exit` hangs (CR-A21-06). Disable the updater autocheck in product-managed launches. |
| Speech worker | Python 3.11 + faster-whisper (CPU int8) | Model load about 0.2 s once the process is up. Process start about 0.7 s (P60, P63). | Persistent while the voice feature is enabled | Separate process (CR-A16-02). It could be launched through Appose (bundled) or a plain subprocess; decided in D03. |
| Optional local LLM | Ollama (already installed on this machine; not a product dependency) | First call 50 s for model load; median 1.2 s afterwards (P65) | External service | Optional (CR-A17-09) |

## Constraints and Limitations

- **Single platform tested.** macOS main-thread/AppKit constraints for non-headless JVMs and PyImageJ are documented (A15 §10). Linux display requirements (X11/Wayland/Xvfb) are documented but not probed. Cross-platform claims are therefore out of scope until probed.
- **Build toolchain absent.** Building SciJava plugins needs Maven (pom-scijava parent) or an equivalent. Adding a Maven wrapper to the repository is an implementation-stage action, not taken here.
- **Pin integrity versus Fiji's self-update.** Running the product's plugin from an update site means the user's Fiji is, by design, *not* the pinned reference. Product and test environments must be separated: a pinned reference install for verification (A30), and users' own installs for use. Compatibility with unpinned user installs is a separate, ongoing obligation (D19).

## Requirement implications

- **CR-A22-01** — Product-managed Fiji launches shall pass `-Dimagej.updater.disableAutocheck=true`, and shall offer updates only through an explicit user action. *Rationale:* A25 egress; pin integrity.
- **CR-A22-02** — Verification runs shall use the pinned reference install with a lock check before and after each run. Runs shall fail if staged updates exist in `update/`. *Rationale:* `apply-update` on launch; F03.
- **CR-A22-03** — The product shall declare its supported Fiji/ImageJ versions (initially those in the pinned runtime). At start-up it shall detect and warn when the host Fiji's core component versions differ. *Rationale:* update-site distribution into unpinned installs.
- **CR-A22-04** — The product shall support Windows 11 x64 for HACK and PARITY scopes. Other platforms shall be listed as untested until verification probes pass. *Rationale:* only Windows was probed.
- **CR-A22-05** — The UI shall render correctly at 100–200 % OS display scaling on Windows. *Rationale:* the development display is at 150 %; HiDPI is a known Fiji problem (A05).
- **CR-A22-06** — Test harnesses shall isolate or restore ImageJ prefs (`IJ_Prefs.txt` and the `JavaSoft\Prefs` registry keys) so that tests do not alter a user's settings. *Rationale:* P52, G8 (prefs written by GUI runs).

## Risks

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R-A22-01 | Users' Fiji installs differ from the pinned versions and break the product's assumptions | High | Medium | CR-A22-03; depend only on stable core APIs; compatibility test matrix (D19) |
| R-A22-02 | Start-up time (about 5 s plus UI) feels slow compared with a web app | Medium | Low | Honest start-up state (CR-A20-06); the UI frame can appear early |
| R-A22-03 | The 75 % heap default competes with the speech worker and LLM memory | Low | Medium | Document the memory budget (A23); configurable heap |
| R-A22-04 | HiDPI rendering defects in legacy AWT windows | High | Low | Out of scope to fix upstream; note in UI; prefer modern components |

## References

- `Fiji/config/jaunch/fiji.toml` L21–L128, L254–L257 (pinned install, read-only).
- Probes P01, P40, P52, P60, P63, P64, P65, P84, P85, P87.
- A04, A05, A15, A16, A21, A25.

## Change log

- 2026-10-17: Initial draft by the lead.
