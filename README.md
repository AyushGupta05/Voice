# Fidelis

**Fidelis is a faithful modernisation of Fiji/ImageJ.** It aims to give scientists a modern, coherent desktop workspace, with typed and spoken commands, that runs **the real Fiji**: the same scientific algorithms, macro language and plugins. It is not an AI image-analysis application that approximates Fiji. Every result is meant to come from original Fiji code, and every step can be expressed as a standard ImageJ macro.

> **Status (17 October 2026):** an early **integration prototype** plus a detailed **analysis and design proposal**. The modern Fiji workspace described below is **designed, not built**. The proposed architecture (decision DEC-010) is awaiting approval.

## What works today: the prototype

A small, tested end-to-end integration with a real, pinned Fiji installation:

- **Real Fiji execution.** A Python (FastAPI) service generates ImageJ macro text and runs it in an external Fiji process (headless, one process per workflow). The Fiji installation is checked against a SHA-256 file inventory before each run.
- **Four operations:** Invert, Gaussian Blur (sigma), fixed threshold to mask, and whole-image measurements. Input is limited to single-frame 8-bit grayscale PNG images.
- **Typed and spoken commands.** A strict English command grammar is used, for example "blur sigma 2 then threshold at 128". Speech is transcribed **locally** by faster-whisper (tiny.en, CPU) in an isolated Python process. A transcript is always shown for review and never runs processing by itself.
- **Review and record.** You see the resolved actions and the macro text before running them. Per-stage output images are shown after the run. The action list can be downloaded as an ImageJ macro and replayed.
- **Tests.** 23 automated tests pass, including two real Fiji and speech integration tests (re-verified on 17 October 2026).
- **Interface.** A minimal React workbench used to exercise the integration. It is not the intended product UI.

**Limitations.** One headless Fiji process per workflow (about 4–5 s start-up each). No persistent images, ROIs, undo, stacks, microscopy formats or plugin dialogs. Windows x64 only. The "recording" is the prototype's own action list, not the ImageJ Recorder.

## What is planned (proposal, not implemented)

The Stage 00–02 analysis and design documentation (pull request `docs: Stage 00–02 analysis and design proposal`, branch `docs/analysis-design`) proposes the following:

- **Engine.** Fiji runs unchanged in **one persistent, non-headless process with its main window hidden**. A UI-independent engine service runs inside it.
- **Workspace.** A **modern single-window workspace** (Swing + FlatLaf) that keeps Fiji's menus, tools, shortcuts and interaction semantics. It has a real image viewer, ROI editing, Results, history and macro panels. Plugins that need their own windows open as tracked Fiji windows.
- **Execution.** Every input (mouse, menu, command search, typed text, speech, macros) becomes the same typed action with an explicit target image, executed through Fiji's own commands with complete parameters.
- **Reproducibility.** A structured history generates standard ImageJ macros that replay in classic Fiji. Results are verified numerically against a reference Fiji run.
- **Natural language and voice.** Instructions resolve to real Fiji commands with explicit parameters. A preview and confirmation are required before data changes. A deterministic validator decides what runs. Any language model is optional, local by default, and never receives image pixels.

Planned capabilities and their priority tiers are tracked as requirements (116 functional, 46 non-functional) and a Fiji parity matrix. None of these is claimed as working.

## Documentation

| What | Where |
|---|---|
| **Analysis and design proposal** (foundation F01–F04, analysis A01–A32, design D01–D20, ADRs, catalogues, traceability) | Branch `docs/analysis-design` → `docs/INDEX.md` (pending review; see the open draft PR) |
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
backend/src/fidelis/   Prototype API, typed command grammar, Fiji and speech adapters
frontend/              Prototype React / TypeScript / Vite workbench
speech/                Isolated speech worker and hash-locked requirements
external/              Fiji runtime and speech-model pins (downloads are git-ignored)
fiji/macros/           Integration smoke macro
fixtures/              Synthetic test image and generated speech clip
scripts/               Setup, pinning and fixture tools
tests/backend/         Validation, failure-path and real integration tests
third_party/           Preserved upstream licence texts and package inventories
docs/                  Documentation (prototype records; the Stage 00-02 proposal is on its branch)
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

## Hackathon

Fidelis started at **Reinvent the Wheel: A Tools for Science Hack** (London, 16–18 October 2026), by the **Sleep-On-It** team. The earlier working name was Click-to-Batch; Mikra was also considered. The repository was formerly `reverez/click-to-batch` and was transferred to `Sleep-On-It/Fidelis` with its full history.
