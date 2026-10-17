---
id: D19
title: Dependency, Packaging and Deployment Design
stage: 02-design
status: Draft (provisional on DEC-010)
priority: P0
source_revisions: [third_party/fiji-installed-pom.xml (Fiji 2.18.1-SNAPSHOT), fiji/fiji@ddc9a45d (pom.xml), org.scijava:pom-scijava:45.1.0, org.scijava:pom-scijava-base:22.0.0, Fiji archive 20261013-1517 (lock f6695397), SYSTRAN/faster-whisper@v1.2.1 (65882eee), prototype snapshot 08a1433]
related_requirements: [FR-SYS-001, FR-SYS-002, FR-SYS-008, FR-SYS-009, FR-SYS-010, FR-SPEECH-001, FR-SPEECH-007, NFR-FID-004, NFR-MAINT-001, NFR-MAINT-004, NFR-PKG-001, NFR-PKG-002, NFR-PKG-003, NFR-PKG-004, NFR-PKG-005, NFR-PKG-006, NFR-PKG-007, NFR-PLAT-001, NFR-PLAT-003, NFR-PRIV-001, NFR-SEC-002]
related_decisions: [DEC-005, DEC-010, DEC-012, ADR-005, ADR-010]
last_reviewed: 2026-10-17
approval: Awaiting approval
---

# D19 — Dependency, Packaging and Deployment Design

## Executive Summary

This document defines how the product is built, packaged, distributed, launched and verified reproducibly under DEC-010 (option O2, provisional). The main decisions are below; all are **PROPOSED**.

- **Build.** Maven, with the SciJava parent `org.scijava:pom-scijava:45.1.0`, run through a checked-in Maven Wrapper, because the machine has no Maven or Gradle (A22). Compilation uses the bundled Zulu 21.0.7 at `--release 21`.
  - The parent version is the one the installed Fiji is built against. **VERIFIED:** `third_party/fiji-installed-pom.xml#L5-L9` and `fiji/fiji@ddc9a45d:pom.xml#L5-L9` both declare `pom-scijava` `45.1.0`, and `scijava.jvm.version` is `21` at `fiji-installed-pom.xml#L169`.
  - The pinned install is **not** exactly the pom-scijava 45.1.0 bill of materials. The BOM manages `scijava-common` 2.100.1, `scijava-search` 3.0.1 and `imagej-legacy` 2.0.3 (`pom-scijava-45.1.0.pom#L379, L431, L539`), but the install ships `scijava-common-2.101.0.jar`, `scijava-search-3.0.2.jar` and `imagej-legacy-3.0.0.jar` (**VERIFIED**, `Fiji/jars/` listing).
  - The build therefore overrides those three versions, and a build step checks every `provided` dependency against the Fiji lock.
- **Product artefacts.** Three SciJava plugin jars plus a Jaunch launch fragment, distributed through a Fiji update site (ADR-010; FR-SYS-009). Fiji itself is not redistributed.
- **Speech worker.** A Python 3.11 virtual environment built by `uv` from the existing hash-locked `speech/requirements.lock.txt` in a per-user directory (PROPOSED), in preference to an Appose-built environment.
  - Rationale: the uv route is already VERIFIED working (P60/P63), is hash-locked, keeps the IF-06 protocol under the project's control, and works offline from a wheelhouse.
  - **New finding:** `faster_whisper/__init__.py` imports `faster_whisper.audio`, which imports `av` at module load (`SYSTRAN/faster-whisper@v1.2.1:faster_whisper/__init__.py#L1`, `audio.py#L15`). Sending PCM instead of files therefore does **not** by itself allow PyAV and its codec DLLs to be dropped, contrary to the hope in ADR-005 and A24 §5.6.
- **Models.** Pinned by revision and SHA-256 (the existing `external/stt.lock.json` and `stt-model.sha256.json`), verified at install and at every worker start.
- **Verification.** A pristine reference install, rebuilt from the archive whose SHA-256 is in `external/fiji.lock.json`, lock-checked before and after each run (NFR-FID-004; D18 §1).

The licence of the project's own code and any bundled distribution remain blocked on **DEC-005** (Open). The whole structure is provisional on **DEC-010**.

## Purpose and Scope

**In scope:**
- build toolchain and dependency pinning;
- product, speech and model artefacts;
- update-site distribution;
- launch configuration and start-up order;
- the reference install;
- licence-notice assembly;
- release structure;
- the environment matrix;
- reuse of prototype tooling.

**Out of scope:** runtime process behaviour (D03), test mechanics (D18), legal conclusions (A24; counsel).

## 1. Deployable units

| Unit | Contents | Built by | Installed to | Distributed via | Requirements |
|---|---|---|---|---|---|
| U1 Product jars | `…-engine` (CMP-20…31), `…-workspace` (CMP-01…12), `…-interaction` (CMP-40…43) | Maven (§2) | `<Fiji>/jars/` | Fiji update site (§5) | FR-SYS-009, NFR-MAINT-001 |
| U2 Launch fragment | `config/jaunch/extra-<product>.toml` adding the `--workspace` launch option (D03 DEC-028) | Hand-maintained, versioned | `<Fiji>/config/jaunch/` | Update site, **if** the updater can deliver it (V-07, UNKNOWN). The fallback is a launcher script in U3. | FR-SYS-008, FR-SYS-001 |
| U3 Speech worker bundle | `worker.py` (CMP-44), the lock file, an installer script, licence notices | uv (§3) | Per-user data directory, e.g. `%LOCALAPPDATA%\<product>\speech\` (never inside the Fiji folder) | Release archive, or built on first voice use | FR-SPEECH-001, FR-SPEECH-007, NFR-REL-002 |
| U4 Model files | `Systran/faster-whisper-tiny.en` at revision `0d3d19a3…`: `config.json`, `model.bin` (75,537,502 B), `tokenizer.json`, `vocabulary.txt`, `README.md` | Fetched by the pinned-revision downloader (§4) | `%LOCALAPPDATA%\<product>\models\tiny.en\` | Hugging Face at a pinned revision; offline copy in the release archive (subject to DEC-005) | FR-SPEECH-001 |
| U5 Test kit (never released) | Dumper bridge, fault-injection plugins, API test driver (D18 §4) | Maven, `test` profile | Product test install P only | Never | NFR-FID-004 |
| U6 Reference install (verification only) | The pinned Fiji archive, unmodified | Downloaded and verified (§6) | Separate folder | Never redistributed by the project | FR-SYS-008, NFR-FID-004 |

The product writes nothing into the Fiji installation except U1 and U2, installed through the updater. All runtime data (journal, logs, layout, speech environment, models) lives in the per-user data directory. This keeps the lock check of non-product files valid (FR-SYS-009 acceptance; FR-SYS-008).

## 2. Build toolchain (Java)

| Item | Design | Evidence |
|---|---|---|
| Build tool | Maven through the **Maven Wrapper** (`mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`) checked into the repository. The wrapper pins `distributionUrl` to an exact Apache Maven version ≥ 3.6.3, with a SHA-256 for the distribution. Whether the wrapper version chosen supports the checksum property must be confirmed at implementation (UNKNOWN). | No `mvn` or `gradle` on PATH (A22 §1). The minimum version is `scijava.mvn.version` 3.6.3 (`pom-scijava-base-22.0.0.pom#L194`), enforced by `requireMavenVersion` (`#L876-L878`). |
| JDK for building | The bundled `Fiji/java/win64/zulu21.42.19-ca-jdk21.0.7-win_x64` (a full JDK: `javac`, `jar`, `javap` present; A22, AGENT-BRIEF) via `JAVA_HOME`. No separate JDK is required (NFR-PLAT-003). | A22 §1 |
| Parent POM | `org.scijava:pom-scijava:45.1.0` | `third_party/fiji-installed-pom.xml#L5-L9`; `fiji/fiji@ddc9a45d:pom.xml#L5-L9` (VERIFIED) |
| Java level | `<scijava.jvm.version>21</scijava.jvm.version>`, matching Fiji. The base default is 11 (`pom-scijava-base-22.0.0.pom#L171`). The enforcer's `enforceBytecodeVersion` (`#L919-L920`) then rejects dependencies newer than 21. | `fiji-installed-pom.xml#L169` |
| Fiji dependencies | Scope `provided`. Never shaded or packaged (FR-SYS-009; no redistribution of Fiji files). | ADR-010 |
| Version overrides | `scijava-common.version=2.101.0`, `scijava-search.version=3.0.2`, `imagej-legacy.version=3.0.0`. The BOM already matches `imagej1.version` 1.54p (`#L517`), `ij1-patcher` 2.0.0 (`#L522`), `imagej2` 2.18.0 (`#L526`), `imagej-ui-swing` 1.2.2 (`#L611`), `imagej-updater` 2.0.3 (`#L615`) and `flatlaf` 3.6.1 (`#L1740`). `appose` 0.12.0 is set by the Fiji pom (`fiji-installed-pom.xml#L183`). | BOM lines (VERIFIED, `repo1.maven.org` copy fetched 2026-10-17) versus `Fiji/jars/` filenames (VERIFIED) |
| Pin consistency check | A build step lists the resolved `provided` artefacts (`dependency:list`) and compares each `artifactId-version.jar` with the file names in `external/fiji.lock.json`. Any artefact whose version is absent from the lock fails the build. This prevents compiling against an API the pinned runtime lacks. | NFR-FID-004; R-A22-01 |
| Plugin discovery | SciJava annotation processing generates `META-INF/json/org.scijava.plugin.Plugin` (the mechanism the pinned jars use; P48). The UI registers as a `UserInterface` plugin (P49). | A13, P48, P49 |
| Static gates | (1) a `jdeps` import scan of U1 against the P70 GPL/AGPL jar list (`loci.plugins.*`, `fiji.util.*`, `com.itextpdf.*`) (NFR-PKG-003); (2) no class in the `ij.*`, `net.imagej.*` or `org.scijava.*` packages in U1 (NFR-MAINT-001); (3) no `Roi.containsPoint` call (FR-ROI-007); (4) the enforcer rules inherited from pom-scijava-base. | A24 CR-A24-03; A10 |
| Repositories | `maven.scijava.org` (the SciJava public group) and Maven Central. The first online build populates `~/.m2`. Subsequent builds can run offline (`-o`). | INFERRED (standard SciJava practice) |
| Hackathon spike (DEC-012) | Needs no build: a Groovy script run with `fiji-windows-x64.exe --run` (A31 §2) | A31 |

Proposed module layout (one Maven reactor, version `x.y.z` in semantic versioning):

```
pom.xml (parent: pom-scijava 45.1.0; modules below)
  product-engine/        CMP-20…31, CMP-50 (in-JVM watchdog/journal)
  product-workspace/     CMP-01…12 (Swing + FlatLaf)
  product-interaction/   CMP-40…43 (NL, plan validator, LLM client, speech client)
  product-testkit/       U5 (test scope; excluded from release)
```

The three runtime jars depend only on Fiji APIs (provided) and on each other. Any non-Fiji runtime library would have to be added to the update site and the inventory (§8). The design adds none.

## 3. Speech worker bundle (U3)

| Option | Description | Pros | Cons | Evidence |
|---|---|---|---|---|
| **A. uv venv from the hash-locked requirements (PROPOSED)** | `uv python install 3.11.16` (pinned CPython); `uv venv --python 3.11.16 <data>/speech/venv`; `uv pip sync --require-hashes speech/requirements.lock.txt`. Offline variant: `uv pip sync --no-index --find-links <wheelhouse> --require-hashes …` from a wheelhouse in the release archive. | Already working in the prototype: Python 3.11.16, 24 pinned packages generated by `uv pip compile --generate-hashes` (`speech/requirements.lock.txt` header). Hash-locked end to end. Independent of Fiji's update cadence. Keeps IF-06 (JSON lines) fully under project control. Works offline after install (NFR-PRIV-001). | Python runtime of about 248 MB (current `.venv-stt`). Ships or installs the native DLLs that A24 flags (PyAV codecs, `cudnn64_9.dll`, `libiomp5md.dll`). | uv 0.12.11 present (A22); P60, P63; F02 §7 |
| B. Appose-built environment | Use the Appose library in Fiji (`appose-0.12.0.jar`, `scripting-appose-python-0.1.0.jar`, VERIFIED present) to build and launch the Python worker | Present in the pinned Fiji; a SciJava-ecosystem convention; build on first use | How Appose resolves environments, whether it supports hash-locked installs and offline operation, and what its worker protocol is have **not** been verified in this project (UNKNOWN). It would replace IF-06 with Appose's protocol, and environment builds would contact package channels at first use (NFR-PRIV-001 concern). | A21 §15; jar listing |

**Decision (PROPOSED): option A.** Appose is re-evaluated at PARITY once a probe establishes its lock and offline behaviour.

Bundle layout:

```
<data>/speech/
  venv/                      (uv-built, Python 3.11.16)
  worker.py                  (CMP-44; evolved from speech/worker.py)
  requirements.lock.txt      (copy of the hash lock used)
  bundle-manifest.json       (python version, lock sha256, uv version, build date, DLL scan result)
  licenses/                  (notices of every installed distribution, §8)
```

Native-library constraints (NFR-PKG-005, PARITY):
- **PyAV cannot simply be removed.** `faster_whisper/__init__.py#L1` imports `faster_whisper.audio`, which does `import av` at `#L15`, and `requirements.txt` lists `av>=11` (VERIFIED, faster-whisper@v1.2.1). Even with PCM input (ADR-005), the package import needs `av`. Options for a redistributable bundle:
  - (i) a PyAV wheel built against an LGPL-only FFmpeg without x264/x265;
  - (ii) whisper.cpp, the ADR-005 alternative;
  - (iii) the user builds the venv from PyPI on their own machine, so the project does not redistribute the DLLs.

  Whether (iii) changes the project's obligations is a DEC-005 / A24 Q-05/Q-09 question. **PROPOSED MVP choice:** (iii), building on the user's machine. The bundle is not redistributed until DEC-005 is decided.
- `ctranslate2/cudnn64_9.dll` (NVIDIA) and `libiomp5md.dll` (Intel) are present in the wheel (VERIFIED (ad hoc inspection; no stored output), `.venv-stt` listing; P73). The CPU-only requirement (FR-SPEECH-007) does not need cuDNN. Whether the CPU path still needs `libiomp5md.dll` is UNKNOWN. A redistributable bundle needs an allow-list DLL scan (NFR-PKG-005).

## 4. Model artefacts (U4)

| Aspect | Design | Evidence |
|---|---|---|
| Identity | `Systran/faster-whisper-tiny.en`, revision `0d3d19a32d3338f10357c0889762bd8d64bbdeba`, MIT | `external/stt.lock.json` (VERIFIED) |
| Hashes | SHA-256 for the five files, in `external/stt-model.sha256.json` (for example `model.bin` `1a5afae0…936b2a`) | VERIFIED |
| Fetch | `scripts/setup_speech.py` pattern: `snapshot_download(repo_id, revision, allow_patterns=[…5 files])`, then the hashes are verified. This is an explicit install action, never implicit at run time. | `scripts/setup_speech.py` |
| Load | `WhisperModel(…, local_files_only=True)`. Hashes are re-verified at every worker start, and a mismatch is refused (FR-SPEECH-001). | `speech/worker.py` |
| VAD | Silero VAD v6 ONNX ships inside the faster-whisper wheel (`faster_whisper/assets/silero_vad_v6.onnx`), so it is covered by the wheel hash in the lock | A24 §5.6 |
| Larger models | Selected only through the evaluation sets (NFR-USE-003). Each needs its own lock entry and hashes. | ADR-005 |
| LLM weights | Never shipped. The optional local LLM is user-managed (D01 §2; CR-A24-08). | — |

## 5. Update-site distribution (FR-SYS-009, FR-SYS-010: PARITY)

| Aspect | Design |
|---|---|
| Site | One product update site over HTTPS. Hosting is UNKNOWN (an ImageJ personal update site or a project server); to be decided with DEC-005. |
| Content | U1 jars into `jars/`, and U2 (if deliverable; V-07). No Fiji file is uploaded or overridden, so the lock check of non-product files passes after installation (FR-SYS-009 acceptance). |
| Integrity | The Fiji updater's own checksums (SHA-1 in `db.xml.gz`, A25 T-09). The project additionally publishes `SHA256SUMS` for every uploaded file in the release notes, and the start-up self-check recomputes the SHA-256 of its own jars. |
| Installation | Only through explicit user action: `Help › Update...` → *Manage update sites* → add the site (FR-SYS-008; A25 T-09/T-25). The product never adds sites or triggers updates itself. |
| Version compatibility (FR-SYS-010) | At build time the product records its **supported component versions**: the jar versions in `external/fiji.lock.json` for `ij`, `imagej`, `imagej-legacy`, `ij1-patcher`, `scijava-common`, `imagej-ui-swing`, `flatlaf`, `formats-api`/`formats-bsd`/`formats-gpl`, and the Fiji launcher. At start-up it reads the loaded versions (jar names on the class path, or the Maven POM properties inside the jars; mechanism to be fixed in D03) and **warns** in UI-19 on any difference, listing the components. It does not block. |
| Speech and models | Not on the update site: they are not Java artefacts, and the updater manages only files inside Fiji. They are installed by a separate, explicit "Install voice support" action (U3/U4). |
| Rollback | The updater's own uninstall of the site's files. The per-user data directory is untouched. |

## 6. Reference install for verification (FR-SYS-008, NFR-FID-004)

| Step | Action | Evidence |
|---|---|---|
| 1 | Download `https://downloads.imagej.net/fiji/archive/latest/20261013-1517/fiji-latest-win64-jdk.zip` | `external/fiji.lock.json` `source_url` (VERIFIED in `scripts/pin_fiji.py`) |
| 2 | Verify the archive SHA-256 equals `9201287a86967d1dc93a5a8426ef8c5048c6186c50642c75164fb2c813794c54` | `pin_fiji.py` `archive_sha256` |
| 3 | Extract into a dedicated folder (install R). Never launch the updater. Never place product jars there. | D18 §1 |
| 4 | Run `verify_runtime` on the folder. The hash must equal the lock hash (`f6695397…bafa`). `update/` must be empty. | NFR-FID-004; `fiji.py#L36-L45` |
| 5 | Copy R to P (product test install). Install U1/U2 through a local update site, or by copying the jars into `jars/` as a test-only emulation if the updater cannot use a local site (UNKNOWN; VD19-1). Lock-check the non-product files. | FR-SYS-009 |
| 6 | Keep a private, access-controlled copy of the archive for CI, because the upstream "archive/latest" path may change (R-A29-01 analogue). Whether a CI cache counts as redistribution is a DEC-005 question. | A24 §6.1 D |

## 7. Launch configuration and start-up order

| Item | Product launch | Test or reference launch | Evidence / requirement |
|---|---|---|---|
| Launcher | `fiji-windows-x64.exe --workspace` (U2 fragment), or the launcher script fallback | `fiji-windows-x64.exe` with explicit flags | D03 §2 |
| Java | Bundled Zulu 21 (Jaunch default discovery); `--java-home=<bundled>` in managed launches | `--java-home=<R or P>/java/win64/zulu21.42.19-ca-jdk21.0.7-win_x64` | NFR-PLAT-003 |
| Headless | No | Per test (D18 §3.1) | FR-SYS-001 |
| Updater autocheck | `-Dimagej.updater.disableAutocheck=true` | Same | FR-SYS-008, NFR-PRIV-001, NFR-MAINT-004 |
| UI selection | `-Dscijava.ui=<product UI name>` | Workspace tests only | P49; D03 §2 |
| Multiple instances | Not used | `--allow-multiple` | A22; D03 §2 |
| Prefs | User's own | Isolated (`-Duser.home`, registry export and restore) | NFR-MAINT-004; D18 §3.1 |
| **Start-up order** | Jaunch starts the JVM with Fiji's standard main class. The SciJava `Context` is created, and `LegacyInjector.preinit()` patches IJ1 **before any `ij.*` class loads**. Only then are product services and the UI plugin instantiated by the Context. **Rules:** (1) the product provides no custom main class or Java agent; (2) no product class reachable during JVM bootstrap touches `ij.*` in a static initialiser; (3) the engine self-test asserts `LegacyService.isActive()` and refuses `READY` otherwise. | — | FR-SYS-002; P42 attempt 1 failed with "No _hooks field found in ij.IJ" when `ij.*` loaded first (`probes/P42-attempt1-patch-order-failure.txt`) |
| Speech worker | Spawned by CMP-43 with the U3 interpreter (`<data>/speech/venv/Scripts/python.exe -I worker.py`). No network port is opened (NFR-SEC-002). | Same, or IF-06 injection | ADR-005 |

## 8. Licence-notice assembly (NFR-PKG-001…007; A24; DEC-005 open)

| Obligation | Mechanism | Tier |
|---|---|---|
| Machine-readable inventory of every bundled or downloaded third-party component: name, version, SHA-256, licences, notice path, source (NFR-PKG-001) | A build step writes `docs/data/third-party-inventory.csv` (PROPOSED name) from: U1's resolved non-`provided` dependencies (expected empty); the uv lock (24 packages, with hashes); U4 model files; U2. It reuses `scripts/collect_python_licenses.py` (copies installed notices and records the `License-Expression`) and the existing `third_party/speech-packages.json` format. A CI check compares the inventory with the build output. | HACK |
| Product jars carry the project licence and NOTICE | `META-INF/LICENSE`, `META-INF/NOTICE`. **Blocked:** licence not chosen (DEC-005). | Release |
| No GPL/AGPL imports in project code (NFR-PKG-003) | The `jdeps` gate in §2. GPL plugins (Bio-Formats importer, TrackMate, Cell Counter) are invoked only through command strings (D16 J-01, J-10). Q-01 on late-bound dispatch remains open (A24). | MVP |
| Speech bundle notices (NFR-PKG-004) | Copy every distribution's licence and notice files into `licenses/`. Close the A24 §5.7 gaps (ctranslate2, flatbuffers, onnxruntime, tokenizers, tqdm). CI fails on a missing notice. | PARITY |
| No GPL or proprietary native libraries in a redistributable speech bundle (NFR-PKG-005) | An allow-list scan of `*.dll` in the venv. While §3 option (iii) (build on the user's machine) is used, the project does not ship the bundle. | PARITY |
| Fiji and JDK notices untouched (NFR-PKG-006) | The update-site model never modifies `Fiji/licenses/`, JDK `legal/`, `DISCLAIMER` or `readme.txt`. A hash comparison of those paths before and after installing U1/U2 runs in CI. This requires extending the lock to cover `licenses/` (F02 §6.1). | PARITY |
| Fixtures never in artefacts (NFR-PKG-007) | The release packaging check rejects any file whose hash matches a CACHE or CONSENT fixture in `tests/fixtures.lock.json` (D18 §5) | MVP |
| Undeclared-licence components (NFR-PKG-002) | Only relevant if Fiji is bundled. Not done under ADR-010. | LONG |
| LLM terms (CR-A24-08) | Not shipped. Model identity and terms URL are recorded at use (FR-NL-011). | — |

## 9. Release structure

```
release-<x.y.z>/
  update-site/                 (files uploaded to the product update site: U1 jars, U2 fragment)
  speech/
    install-voice.ps1          (uv-based installer, option A; online or --wheelhouse)
    requirements.lock.txt
    wheelhouse/                (optional offline wheels; only after DEC-005 allows redistribution)
    worker.py
  models/
    stt.lock.json, stt-model.sha256.json   (model files fetched by the installer, not included until DEC-005)
  inventory/
    third-party-inventory.csv  (NFR-PKG-001)
    licenses/                  (notices for everything shipped)
  verification/
    report.md                  (D18 §10 run for this release: lock hash, coverage status per tier)
  SHA256SUMS
  RELEASE-NOTES.md             (supported platform: Windows 11 x64; others untested, NFR-PLAT-001; supported Fiji component versions, FR-SYS-010)
```

Release gates (PROPOSED):
1. Every `verification-coverage.csv` row for the claimed tier is `passing`, with baselines keyed by the current lock hash (NFR-FID-004).
2. The inventory is complete (NFR-PKG-001).
3. The `jdeps` gate passes (NFR-PKG-003).
4. The fixture-leak check passes (NFR-PKG-007).
5. DEC-005 is decided before any public release.

## 10. Environment matrix

| Environment | OS / hardware | Fiji | Java | Python | Purpose | Status |
|---|---|---|---|---|---|---|
| E-DEV | Windows 11 Home 10.0.26200; Core Ultra 9 285H; 31.4 GB RAM; RTX 5070 Laptop (unused for speech); 150 % display | Pinned 20261013-1517 (lock f6695397) | Zulu 21.0.7 (bundled); Temurin 21.0.12 present but unused | 3.11.16 (speech), 3.14.3 (prototype) | Development | VERIFIED (A22 §1) |
| E-BUILD | Same as E-DEV, or the CI runner | Not required. The `provided` jars resolve from Maven repositories. | Zulu 21.0.7 | — | Build U1 and U5 | PROPOSED |
| E-REF (install R) | CI runner, Windows 11 x64, interactive desktop session | Pinned, pristine | Bundled | — | Reference executions (D18) | PROPOSED |
| E-TEST (install P) | Same runner | Pinned plus U1/U2 | Bundled | 3.11.16 venv (U3) | Product tests | PROPOSED |
| E-USER | Windows 11 x64 | The user's Fiji (unpinned; FR-SYS-010 warns on version differences) | Fiji's bundled | U3 installed per user | Use | PROPOSED |
| E-USER-OFFLINE | As E-USER, with no network after installation | As above | As above | U3 from the wheelhouse | Offline labs (NFR-PRIV-001) | PROPOSED; wheelhouse blocked on DEC-005 |
| macOS / Linux | — | — | — | — | Untested (NFR-PLAT-001; A15 U-A15-4) | Out of scope |
| Optional LLM | User-installed Ollama on loopback | — | — | — | FR-NL-009/010 | External, not a product dependency |

## 11. Reuse of prototype tooling

| Asset | Reused as | How (implementation stage; no change in this stage) |
|---|---|---|
| `scripts/pin_fiji.py` (`--accept-current-installation`) | The only way to repin the reference runtime | Unchanged semantics. Extend `runtime_files` coverage first (root `fiji`, `db.xml.gz`, `licenses/`; F02 §6.1). That deliberately changes the lock hash, so all baselines are regenerated (D18 §3.5). |
| `external/fiji.lock.json` + `verify_runtime` (`backend/src/fidelis/adapters/fiji.py#L19-L45`) | Pin check for installs R and P; baseline key; the source of supported versions (FR-SYS-010) and of the build's pin-consistency check (§2) | Called from the D18 runner and the Maven check (by reading the JSON; no Python needed in the Maven step) |
| `external/stt.lock.json`, `stt-model.sha256.json`, `scripts/setup_speech.py` | U4 identity, hashes and fetch procedure | Install target moves to the per-user data directory |
| `speech/requirements.in` / `requirements.lock.txt` (uv, `--generate-hashes`) | The U3 lock | Regenerated only through `uv pip compile --generate-hashes`; the diff is reviewed |
| `speech/worker.py` | Starting point for CMP-44 | Becomes persistent with the IF-06 protocol (ADR-005). It keeps the hash verification, the 30 s cap, CPU int8 and `local_files_only`. |
| `scripts/collect_python_licenses.py`, `third_party/NOTICE.md`, `third_party/licenses/` | Notice assembly (§8) | Extended to write the inventory CSV |
| `tests/backend/test_runtime_failures.py` | Failure-path test patterns (D18 §9) | Re-targeted |
| `scripts/check_fiji.py` | Superseded | It omits `--allow-multiple` and `--java-home` (F02 §6.2) |

## 12. Items blocked on DEC-005 and DEC-010

| Item | Blocked on | Why |
|---|---|---|
| The project code licence and `META-INF/LICENSE` of U1 | DEC-005 | Open (A24 §10) |
| Publishing a public update site | DEC-005 (licence), DEC-010 (O2) | — |
| Shipping a speech wheelhouse or a prebuilt venv (redistributing the PyAV/FFmpeg, cuDNN and Intel DLLs) | DEC-005; A24 Q-05/Q-09 | P73 findings; PyAV cannot be dropped (§3) |
| Shipping model files in the release archive (MIT, but under the redistribution model) | DEC-005 | — |
| A private CI copy of the Fiji archive | DEC-005 (Q-05) | — |
| Treating OME CC BY 4.0 fixtures as REPO class | DEC-005 (attribution handling) | D18 §5 |
| The Jaunch fragment and `--workspace` mode (U2) | DEC-010, DEC-028, V-07 | The O2 launch model |
| The Java module layout, Swing/FlatLaf workspace and SciJava `UserInterface` registration | DEC-010 | Under O4 the UI would be a separate artefact behind the same engine API |
| Building any Java at all before the formal milestones | DEC-012 | Documentation-only stage |

## Requirement gaps

| ID | Gap | Evidence | Suggested resolution |
|---|---|---|---|
| G-D19-01 | No requirement defines the supported-version declaration format or source for FR-SYS-010. This design uses the jar versions in the lock. | FR-SYS-010 | Add an acceptance detail: the supported set equals the lock's core jars |
| G-D19-02 | No requirement says where runtime data (journal, logs, speech venv, models) is stored. Writing inside the Fiji folder would break FR-SYS-009's lock check. | FR-SYS-008/009 | Add: product runtime data shall live outside the Fiji installation |
| G-D19-03 | No requirement covers the build-time check that compiled APIs exist in the pinned runtime (the BOM/lock mismatch found here). | §2 (VERIFIED mismatch) | Add an NFR-MAINT item: the build fails if a provided dependency version is absent from the lock |
| G-D19-04 | NFR-PKG-005 assumes the speech runtime can avoid GPL encoder DLLs. `faster-whisper` 1.2.1 imports `av` unconditionally. | `faster_whisper/__init__.py#L1`, `audio.py#L15` | Revisit CR-A24-05 / ADR-005 wording. Add the whisper.cpp or custom-FFmpeg path as an explicit option. |
| G-D19-05 | No requirement covers an offline installation path for voice (NFR-PRIV-001 covers runtime only) | NFR-PRIV-001 | Add: voice support installable without network from a release wheelhouse (blocked on DEC-005) |

## Decisions proposed

| Candidate | Proposal | Rationale |
|---|---|---|
| DEC-038 (was DEC-cand-D19-1) | Maven Wrapper with parent `pom-scijava` 45.1.0, `scijava.jvm.version=21`, build JDK = bundled Zulu 21.0.7 | Matches the installed Fiji's own build (VERIFIED). No new JDK (NFR-PLAT-003). |
| DEC-038 (was DEC-cand-D19-2; merged into DEC-038 with DEC-cand-D19-1, see `DECISIONS.md`) | Override `scijava-common` 2.101.0, `scijava-search` 3.0.2 and `imagej-legacy` 3.0.0, and fail the build on any provided version absent from the lock | The pinned install deviates from the BOM (VERIFIED). Compiling against the BOM versions would target APIs the runtime may not have. |
| DEC-039 (was DEC-cand-D19-3) | Speech worker as a uv-built Python 3.11.16 venv from the hash-locked requirements, in the per-user data directory; Appose deferred | Verified, hash-locked, offline-capable, protocol under project control |
| DEC-046 (was DEC-cand-D19-4) | The product writes only U1/U2 into Fiji. Everything else goes in the per-user data directory. | Keeps FR-SYS-008/009 lock checks meaningful |
| DEC-047 (was DEC-cand-D19-5) | The reference install is rebuilt from the archive by SHA-256 and is never updated in place | NFR-FID-004; Jaunch `apply-update` (A22) |

## References

- `third_party/fiji-installed-pom.xml#L5-L9, L169, L183`; `fiji/fiji@ddc9a45d6042ed664d33ad95847096318c365aa4:pom.xml#L5-L9`.
- Maven Central, `org/scijava/pom-scijava/45.1.0/pom-scijava-45.1.0.pom` (lines 6–10, 379, 431, 517, 522, 526, 539, 611, 615, 1740) and `org/scijava/pom-scijava-base/22.0.0/pom-scijava-base-22.0.0.pom` (lines 171, 194, 876–878, 919–920): https://repo1.maven.org/maven2/org/scijava/ (accessed 2026-10-17).
- `Fiji/jars/` listing (pinned install, read-only): `scijava-common-2.101.0.jar`, `scijava-search-3.0.2.jar`, `imagej-legacy-3.0.0.jar`, `appose-0.12.0.jar`, `scripting-appose-python-0.1.0.jar`, `flatlaf-3.6.1.jar`.
- `SYSTRAN/faster-whisper@v1.2.1` (65882eee): `faster_whisper/__init__.py#L1`, `faster_whisper/audio.py#L15`, `requirements.txt`.
- Repository: `external/fiji.lock.json`, `external/stt.lock.json`, `external/stt-model.sha256.json`, `scripts/pin_fiji.py`, `scripts/setup_speech.py`, `scripts/collect_python_licenses.py`, `speech/requirements.lock.txt`, `speech/worker.py`, `backend/src/fidelis/adapters/fiji.py#L19-L45`, `third_party/NOTICE.md`.
- A21, A22, A24 (§5.6, §5.7, §6, §10, Q-01…Q-10), A25 (T-09, T-17, T-25), F02 §6–§7; D01, D03, D18; ADR-005, ADR-010; probes P42, P48, P49, P60, P63, P70, P73.

## Change log

- 2026-10-17: Initial version (G10).
- 2026-10-17: Phase 8 review fixes (findings #27, #29).

## Phase 8 requirement mapping (lead)

Requirements added from this document's gaps: NFR-MAINT-007 (runtime data only in the per-user directory; nothing written inside Fiji except update-site jars; DEC-046) and NFR-PKG-008 (the build fails when compile versions differ from the pinned runtime; DEC-038). The PyAV correction to ADR-005 also applies: faster-whisper imports `av` at module load.
