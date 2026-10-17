# Penny × Fidelis

This repository joins two workstreams that grew separately and are now integrated:

- **Fidelis** (`fidelis-app/`, Arnav's engine and Alex's workspace UI): the real Fiji running in one persistent JVM behind a modern workspace, with every action going through one engine (typed commands, history, undo, exact macro generation and replay).
- **Penny** (`packages/gateway`, `apps/companion`, `adapters/fiji`, Ayush's voice): hands-free voice (AWS Transcribe → OpenAI gpt-oss on Bedrock → Polly), action-first tools for biological image analysis, a floating voice bar and an on-screen cursor.

In the integrated system Penny's voice drives the Fidelis engine, and the Fidelis workspace shows the voice interaction. See `docs/INTEGRATION.md` for the architecture and how to run both.

---

# Fidelis

**Fidelis is a faithful modernisation of Fiji/ImageJ.** It aims to give scientists a modern, coherent desktop workspace, with typed and spoken commands, that runs **the real Fiji**: the same scientific algorithms, macro language and plugins. It is not an AI image-analysis application that approximates Fiji. Every result is meant to come from original Fiji code, and every step can be expressed as a standard ImageJ macro.

> **Status (17 October 2026):** the **Fidelis workstation** (`fidelis-app/`) runs the real Fiji in one persistent process with a modern single-window workspace, typed and spoken commands, structured history and macro export. The J-12 cell-counting journey is verified end to end against the pinned Fiji's own output (211 automated tests on the integration branch `development/fidelis-workstation`). The analysis and design documentation in [`docs/`](docs/INDEX.md) is the basis; the earlier Python/React prototype is kept below as evidence.

## The Fidelis workstation (current development)

One real scientific engine, several ways to interact with it:

- **Engine.** Fiji runs unchanged in one non-headless JVM whose main window is hidden. `sc.fidelis.engine.DefaultEngine` executes every typed action as the real `IJ.run` with complete option strings, judges success by postconditions (pixel hashes, Results rows, files written), captures Fiji's error dialogs, rolls back from snapshots, and observes what the native tools do. Result-affecting globals (`BlackBackground`, measurements, precision, conversions) are shown and never changed silently.
- **Workspace.** A FlatLaf Swing window with Fiji's own menu bar and tools, tabbed documents that host the **real IJ1 image canvases** (native ROI tools, zoom, stacks and channels), parameter forms generated from the command catalogue with the exact macro line, Threshold and Brightness/Contrast panels, ROI list, Results at full precision, before/after comparison, history with undo/redo and the live generated macro.
- **Typed and spoken instructions.** A local speech worker (faster-whisper, hash-pinned `tiny.en`, audio never leaves the machine) and a deterministic natural-language layer resolve instructions to catalogued Fiji commands with explicit parameters; a proposal is shown and confirmed before anything runs. No code is generated from language.
- **Reproducibility.** The structured history generates a standard ImageJ macro; the verification suite replays it in the pinned Fiji (separate process) and compares pixels, Results doubles and ROI vertices exactly.

Run it (Windows; the pinned Fiji installed outside the repository, see [docs/setup.md](docs/setup.md)):

```powershell
python -I fidelis-app/tools/build.py          # javac/jar with Fiji's bundled JDK; no Maven needed
python -I fidelis-app/tools/run.py --isolated-home
python -I fidelis-app/tools/test.py           # JUnit 5 inside a live Fiji JVM (~5 min)
```

Demo script: [docs/03-development/DEMO-RUNBOOK.md](docs/03-development/DEMO-RUNBOOK.md). Development record: [docs/03-development/ACTIVE-DEVELOPMENT.md](docs/03-development/ACTIVE-DEVELOPMENT.md). Verification evidence: [docs/03-development/VERIFICATION-RESULTS.md](docs/03-development/VERIFICATION-RESULTS.md).

## The earlier prototype (kept as evidence)

A small, tested end-to-end integration with a real, pinned Fiji installation:

- **Real Fiji execution.** A Python (FastAPI) service generates ImageJ macro text and runs it in an external Fiji process (headless, one process per workflow). The Fiji installation is checked against a SHA-256 file inventory before each run.
- **Four operations:** Invert, Gaussian Blur (sigma), fixed threshold to mask, and whole-image measurements. Input is limited to single-frame 8-bit grayscale PNG images.
- **Typed and spoken commands.** A strict English command grammar is used, for example "blur sigma 2 then threshold at 128". Speech is transcribed **locally** by faster-whisper (tiny.en, CPU) in an isolated Python process. A transcript is always shown for review and never runs processing by itself.
- **Review and record.** You see the resolved actions and the macro text before running them. Per-stage output images are shown after the run. The action list can be downloaded as an ImageJ macro and replayed.
- **Tests.** 23 automated tests pass, including two real Fiji and speech integration tests (re-verified on 17 October 2026).
- **Interface.** A minimal React workbench used to exercise the integration. It is not the intended product UI.

**Limitations.** One headless Fiji process per workflow (about 4–5 s start-up each). No persistent images, ROIs, undo, stacks, microscopy formats or plugin dialogs. Windows x64 only. The "recording" is the prototype's own action list, not the ImageJ Recorder.

## What is planned (proposal, not implemented)

The Stage 00–02 analysis and design documentation in [`docs/`](docs/INDEX.md) proposes the following:

- **Engine.** Fiji runs unchanged in **one persistent, non-headless process with its main window hidden**. A UI-independent engine service runs inside it.
- **Workspace.** A **modern single-window workspace** (Swing + FlatLaf) that keeps Fiji's menus, tools, shortcuts and interaction semantics. It has a real image viewer, ROI editing, Results, history and macro panels. Plugins that need their own windows open as tracked Fiji windows.
- **Execution.** Every input (mouse, menu, command search, typed text, speech, macros) becomes the same typed action with an explicit target image, executed through Fiji's own commands with complete parameters.
- **Reproducibility.** A structured history generates standard ImageJ macros that replay in classic Fiji. Results are verified numerically against a reference Fiji run.
- **Natural language and voice.** Instructions resolve to real Fiji commands with explicit parameters. A preview and confirmation are required before data changes. A deterministic validator decides what runs. Any language model is optional, local by default, and never receives image pixels.

Planned capabilities and their priority tiers are tracked as requirements (116 functional, 46 non-functional) and a Fiji parity matrix. None of these is claimed as working.

## Documentation

| What | Where |
|---|---|
| **Analysis and design proposal** (foundation F01–F04, analysis A01–A32, design D01–D20, ADRs, catalogues, traceability). All proposals await approval. | [docs/INDEX.md](docs/INDEX.md) · decisions: [docs/DECISIONS.md](docs/DECISIONS.md) · handoff: [D20](docs/02-design/06-handoff/D20-implementation-readiness-handoff.md) |
| Prototype setup and dependency recreation | [docs/setup.md](docs/setup.md) |
| Prototype architecture and boundaries | [docs/architecture.md](docs/architecture.md) |
| Upstream research and licence notes (prototype era) | [docs/upstreams.md](docs/upstreams.md) |
| Prototype verification record | [docs/verification.md](docs/verification.md) |
| Environment inventory | [docs/environment.md](docs/environment.md) |
| Third-party notices | [third_party/NOTICE.md](third_party/NOTICE.md) |

## Development setup (prototype)

Tested on Windows 11 x64. Requirements:
- the pinned **Fiji** archive (Windows x64, dated 20261013-1517), extracted **outside** this repository (exact URL and SHA-256 in [docs/setup.md](docs/setup.md));
- Python 3.14 (API) and Python 3.11 with `uv` (speech worker);
- Node 24.

```powershell
python -m venv .venv
./.venv/Scripts/python.exe -m pip install -r requirements-dev.lock.txt
./.venv/Scripts/python.exe -m pip install --no-deps -e ".[dev]"
npm.cmd --prefix frontend ci
copy .env.example .env    # then set FIJI_EXECUTABLE to your fiji-windows-x64.exe

# Optional speech worker (downloads a pinned ~75 MB model once)
uv venv --python 3.11 .venv-stt
uv pip sync --python .venv-stt/Scripts/python.exe --require-hashes speech/requirements.lock.txt
./.venv-stt/Scripts/python.exe scripts/setup_speech.py
```

Run the API and the workbench, then open http://127.0.0.1:5173:

```powershell
./.venv/Scripts/python.exe -m uvicorn fidelis.main:app --host 127.0.0.1 --port 8000
npm.cmd --prefix frontend run dev
```

Verify:

```powershell
./.venv/Scripts/python.exe -m pytest -m "not integration"
./.venv/Scripts/python.exe -m ruff check .
npm.cmd --prefix frontend run build
$env:FIDELIS_INTEGRATION = "1"; ./.venv/Scripts/python.exe -m pytest   # real Fiji + speech
```

## Repository layout

```text
fidelis-app/           The Fidelis workstation: src/main/java/sc/fidelis/{engine,ui,nl,speech}, tests, tools/ (build, run, test)
verification/          Reference runner, J-12 baselines from the pinned Fiji, comparators, replay and GUI smoke
fixtures/              Fixture manifest (ImageJ samples, BBBC images; downloads are git-ignored)
backend/src/fidelis/   Prototype API, typed command grammar, Fiji and speech adapters
frontend/              Prototype React / TypeScript / Vite workbench
speech/                Isolated speech worker and hash-locked requirements
external/              Fiji runtime and speech-model pins (downloads are git-ignored)
fiji/macros/           Integration smoke macro
fixtures/              Synthetic test image and generated speech clip
scripts/               Setup, pinning and fixture tools
tests/backend/         Validation, failure-path and real integration tests
third_party/           Preserved upstream licence texts and package inventories
docs/                  Documentation: Stage 00-02 proposal (docs/INDEX.md) and prototype-era records
```

The prototype code and the proposed architecture are separate. The prototype is kept as working evidence and a demonstration. It is not the basis of the proposed workspace.

## Licence

Original Fidelis code and documentation are licensed under the **Apache License 2.0** ([LICENSE](LICENSE)). The scope is defined in [NOTICE](NOTICE). Apache 2.0 **does not** apply to Fiji, ImageJ or any other upstream software. That software is not included in this repository, is used as an external installation, and keeps its own licences: the Fiji bundle is GPL-3.0-or-later, and its components have their own licences. Upstream licence texts in `third_party/` remain under their own terms. Redistribution of a bundled Fiji, JDK or speech runtime is an open decision (DEC-005) and is not covered here.

## Credits

Fidelis builds on, and depends on, the work of these communities:
- [Fiji](https://imagej.net/software/fiji/) / [ImageJ](https://imagej.net), including ImageJ 1.x (Wayne Rasband, NIH), ImageJ2, SciJava, ImgLib2, SCIFIO and [Bio-Formats](https://www.openmicroscopy.org/bio-formats/) (OME).
- [faster-whisper](https://github.com/SYSTRAN/faster-whisper) (SYSTRAN) and OpenAI [Whisper](https://github.com/openai/whisper).

Key references: Schindelin et al. (2012), *Fiji: an open-source platform for biological-image analysis*, [doi:10.1038/nmeth.2019](https://doi.org/10.1038/nmeth.2019). Rueden et al. (2017), *ImageJ2: ImageJ for the next generation of scientific image data*, [doi:10.1186/s12859-017-1934-z](https://doi.org/10.1186/s12859-017-1934-z).

Fidelis is an independent project and is not endorsed by the Fiji or ImageJ developers.

## Repositories

This repository is the team's **hackathon submission**: Penny's voice integrated with the Fidelis workstation. It is named `Voice` after the team's shared hackathon repository.

| Repository | Role |
|---|---|
| [`Sleep-On-It/Voice`](https://github.com/Sleep-On-It/Voice) | **This repository.** Penny × Fidelis: the integrated voice companion and workstation (primary since 17 October 2026). |
| [`reverez/Voice`](https://github.com/reverez/Voice) | The fork where the Fidelis workstreams were developed before the integration. |
| [`Sleep-On-It/Voice`](https://github.com/Sleep-On-It/Voice) | Shared hackathon parent (initial commit with the Apache-2.0 licence only) |
| [`Sleep-On-It/Fidelis`](https://github.com/Sleep-On-It/Fidelis) | The original Fidelis repository, formerly `reverez/click-to-batch`. Its branches and history are preserved there. |
| [`AyushGupta05/Voice`](https://github.com/AyushGupta05/Voice) | Ayush's fork where Penny (formerly Luma) was built; its work is merged here and it is kept in sync as a secondary remote. |

The Fidelis history is preserved here through an explicit, history-preserving merge (no rewriting). The merge commit's first parent is the Voice initial commit; its second parent carries the complete Fidelis history from `Sleep-On-It/Fidelis`.

## Hackathon

Fidelis started at **Reinvent the Wheel: A Tools for Science Hack** (London, 16–18 October 2026), by the **Sleep-On-It** team. The earlier working name was Click-to-Batch; Mikra was also considered. The original repository (`reverez/click-to-batch`, now `Sleep-On-It/Fidelis`) keeps its full history. See [Repositories](#repositories).

---

# Penny (voice)


**Make scientific software conversational.**

Penny is a voice-native companion for scientific software, inspired by [Clicky](https://github.com/farzaa/clicky). You keep using the real application; you talk, Penny acts in it, points at what it did with its own cursor, and answers in one spoken sentence with the application's real numbers. **Fiji (ImageJ)** is the demo application; QuPath is the second supported profile. The same bar, voice stack, instant routes and desktop control serve both; only the application adapter differs.

Built for [Reinvent the Wheel: Tools for Science](https://luma.com/vdq0qw9u), 16–18 October 2026.

![Actual QuPath viewer with threshold-positive cells on the public OS-2 sample](docs/images/ki67-positive-view.png)

## Fiji (current demo application)

- `LUMA_APP=fiji` (written to `.local/state/app` by `./script/run-fiji.sh`) switches the agent to the Fiji profile: 68 tools over a 57-operation bridge plugin, including analysis recipes and Accessibility desktop tools.
- Verified by voice-style turns on the Blobs sample: "segment the cells" → 67 cells outlined, "how many and the mean intensity" → 67 / 190 (from the original pixels), "make it stricter / looser" → instant re-segmentation with the new count (~40 ms, no model round), "go back", "save this as a macro" → `.ijm` written. See [DEMO-FIJI.md](docs/DEMO-FIJI.md), [FIJI-SETUP.md](docs/FIJI-SETUP.md) and the [bridge contract](docs/FIJI-PROTOCOL.md).
- Speak naturally: the wake word is optional by default. `LUMA_WAKE_WORD=on` requires "Penny, …" in a noisy room; follow-ups within 30 s and short controls such as "stop", "undo", "stricter" and "brighter" work without repeating the name.
- The real-gateway correction workflow passed on an isolated installed Fiji: 12 objects → split 13 → remove under 20 µm² 10 → replay three fields with 10 objects each. Counts use image-owned detections; selection/corner queries preserve the segmentation, and undo restores workflow parameters. See [voice workflow evidence](docs/qa/PENNY-VOICE-WORKFLOW.md).

## Fidelis profile (integration in progress)

- `LUMA_APP=fidelis` (written to `.local/state/app` by `./script/run-fidelis.sh`, which builds and launches the Fidelis workstation with its Penny bridge on port 49558) drives Fidelis through 33 tools over the 30 operations of the [bridge contract](docs/FIDELIS-PROTOCOL.md) (every Fiji menu command and plugin through the catalogue, original dialogs included). Every change goes through the Fidelis engine and its History; explicit plans wait for Confirm or the researcher's spoken "yes".
- Protocol-tested against a fake bridge only; the Java bridge is being written in parallel. See [the integration notes](docs/INTEGRATION.md).

## QuPath profile

- A compact bar at the top of the screen shows short status text. Expand it for conversation, measured results and action history. The companion follows the active QuPath image and selection.
- **33 implemented MCP tools** expose context, objects, measurements, navigation, display, highlights, analysis, recovery, live menus, JavaFX dialogs and reviewed Groovy execution.
- Actions execute immediately (`LUMA_APPROVALS=auto`, the default). Every data change is journaled and hierarchy changes get a checkpoint first, so "undo that" works. `LUMA_APPROVALS=review` restores human approval cards bound to the exact arguments and application state; the model never gets an approval tool.
- MCP discovery reflects the authenticated running gateway; a built update needs a normal reload before its new tools are available.
- Authored task guidance and the locally downloaded official QuPath documentation provide searchable application knowledge.
- A real public-slide fixture detected **899 cells**, classified **49** above a chosen DAB measurement threshold, compared results and restored the empty object hierarchy. An actual OpenAI agent also completed that fixed sequence in 21.5 seconds with five test-only simulated approvals; ordinary production actions still require human decisions. [Scientific agent evidence](docs/SCIENTIFIC-AGENT-TEST.md)

Those counts verify software operation for the fixture. They do not validate segmentation or biological interpretation. See [data provenance](docs/DATASETS.md).

**Voice is live and feels live.** Click the mic, talk, and QuPath moves. Amazon Transcribe hears you, the credit-checked OpenAI agent on Bedrock acts through the 33 tools, and a natural Polly voice answers. Simple view commands ("zoom in a little") run with no model round: spoken reply 0.4 s after you stop talking. Everything else gets an immediate spoken acknowledgment ("Zooming in.", "Ready for your approval.") while the action runs, then one short sentence with real numbers. Penny's own cursor flies to what it's talking about and outlines the matched cells or controls, Clicky-style. The full demo flow (region → detect 294 cells → classify 19 positive → "show me the positive cells") was driven by voice with human approvals on 17 October. Not GPT-Live; AWS credits only, with every request reserved against a $200 cap. [AWS speech](docs/AWS-SPEECH.md) · [Demo guide](docs/DEMO.md) · [Funding decision](docs/FUNDING.md)

## Start locally

Apple Silicon macOS, Xcode command-line tools, Node.js 22+, Git and Python 3.9+ are required.

```sh
python3 script/setup.py
./script/run-qupath.sh
./script/build_and_run.sh --verify
```

The bootstrap verifies pinned official downloads and keeps the JDK, QuPath, upstream source and documentation in `.local`. It installs nothing globally. The local QuPath launcher needs the full JDK because its stock runtime omits the extension's HTTP module.

QuPath's downloaded application and source remain unmodified. Penny uses a separate development launcher, Java extension, gateway and floating companion. Run `npm run verify:qupath` to compare the entire stock bundle with the verified package and check the launcher's specific additions. See [application preservation](docs/PENNY-ARCHITECTURE.md#preserving-qupath).

Open an image in QuPath and select an object or region. Use Penny's quick actions, or connect an MCP client using the [setup guide](docs/PENNY-SETUP.md). Risky actions appear in the companion for review.

## Understand and extend it

| Start here | Purpose |
|---|---|
| [Current readiness and safe next steps](docs/READINESS.md) | Verified results, loaded-build checks and remaining activation work |
| [Foreground demo](docs/LIVE-DEMO.md) | Compact bar, safe reload and real image-opening verification |
| [Product brief](docs/PRODUCT.md) | Problem, accepted direction and scope |
| [Setup](docs/PENNY-SETUP.md) | Install, launch, MCP configuration and troubleshooting |
| [Tool reference](docs/TOOL-REFERENCE.md) | Current schemas, parameters and approval requirements |
| [Task coverage](docs/SOFTWARE-COVERAGE.md) | Import, analysis and navigation routes with proof boundaries |
| [Capability matrix](docs/CAPABILITIES.md) | Implemented, verified and blocked behavior |
| [Architecture](docs/PENNY-ARCHITECTURE.md) | Process ownership and trust boundaries |
| [AWS text adapter](docs/AWS-TEXT.md) | Credit/access checks, conservative reserves and explicit model test |
| [API lookup](docs/API-LOOKUP.md) | Read-only pinned source retrieval for unfamiliar scripts |
| [AWS speech voice](docs/AWS-SPEECH.md) | Live route, guards, enable and verify commands |
| [Voice integration](docs/VOICE.md) | Implemented protocols, backend interface and remaining activation work |
| [Protocol](docs/PROTOCOL.md) | Requests, tool execution and approval semantics |
| [Contributor guide](docs/CONTRIBUTING.md) | Add adapters/tools and verify changes |
| [QuPath agent skill](skills/qupath/SKILL.md) | Scientific operating guidance and task references |
| [Demo guide](docs/DEMO.md) | Run the demonstration honestly |
| [Editable pitch deck](docs/pitch/README.md) | Seven slides with actual application evidence |
| [Review and verification](docs/REVIEW.md) | Commands, accepted fixes and proof boundaries |
| [Build journal](docs/BUILD-JOURNAL.md) | Decisions, evidence and outstanding work |

## Repository map

- `apps/companion/`: SwiftUI/AppKit floating interface, pointer and audio components.
- `packages/gateway/`: TypeScript tool validation, MCP, approvals, local events and knowledge.
- `adapters/qupath/`: Java 25 extension using the actual QuPath APIs.
- `skills/qupath/`: source-backed application guidance.
- `script/`: reproducible setup, build, launch, smoke and guarded fixture checks.
- `docs/`: architecture, evidence, contributor and pitch material.

`npm run doctor` checks local setup and connections without changing the application. `npm run check` runs focused tests, type checking and tool-reference consistency. `npm run smoke` exercises a real MCP client through the running gateway and QuPath without changing data. See the contributor guide before running real mutation fixtures.

Original project code retains the existing Apache-2.0 license. QuPath and the JDK retain their upstream licenses. This repository does not relicense upstream QuPath source. Public sample imagery has separate provenance in the dataset guide.
