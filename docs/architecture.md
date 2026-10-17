# Fidelis integration architecture

## Decision

Keep upstream Fiji intact in an external portable installation. Launch a dedicated, headless Fiji process for each supported workflow. Keep speech in another Python environment and process. Reuse the existing React + FastAPI repository. No Fiji source fork or full-codebase copy is needed for this milestone.

The existing installation already passed a macro smoke check. Its command-line interface offers a narrower integration surface than embedding a JVM in the API server. PyImageJ remains a candidate when persistent datasets, SciJava service discovery or ImageJ2 operations become necessary.

## Current data flow

    Typed text ────────────────────────────┐
    Browser microphone/audio upload       │
          ↓                               │
    Isolated faster-whisper CPU worker    │
          ↓ transcript                    │
    Explicit command grammar ←────────────┘
          ↓ validated action list + macro preview
    User reviews and selects Run
          ↓
    Single Fiji worker → generated execution.ijm → external Fiji + bundled Java
          ↓                  ↓                       ↓
    Real stage events    PNG previews          ImageJ measurements
          └──────────────────┴───────────────────────┘
                    React timeline + image comparison
                    Run manifest + downloadable macro + replay

## Boundaries and contracts

- React uses same-origin /api requests through the Vite proxy. It polls job state every 350 ms while running. The timeline displays events emitted after Fiji writes each stage; no simulated progress percentages.
- FastAPI validates typed actions and numeric ranges. Speech text is parsed by full-match grammar; unsupported clauses are rejected as a whole. No LLM or arbitrary shell/macro interpreter is exposed through HTTP.
- The supported actions are invert; blur with sigma 0.1–10; threshold with integer level 0–255; measure. A workflow contains 1–12 actions.
- Image inputs are restricted to single-frame, grayscale 8-bit PNGs, at most 4 million pixels and 10 MiB. Real microscopy formats, stacks, color and calibration need explicit future adapters.
- Each run writes into outputs/<UUID>. Inputs are never overwritten. A stage preview and whole-image statistics are emitted after input loading and each action.
- Fiji launches with --allow-multiple, --headless and an explicit pinned bundled Java home. The generated worker macro explicitly exits its own JVM after saving outputs, because Gaussian Blur can leave Java threads running.
- A 120-second execution deadline terminates the dedicated process tree on Windows. Return code, completion marker and expected stage artifacts are checked. Failures become visible job events and logs.
- One Fiji workflow runs at a time per API process. A second submission receives 409. Use one API worker. Multi-process scheduling and durable queueing are not implemented.
- Each speech request uses a separate Python 3.11 environment, CPU int8 inference and a checksum-verified tiny.en model. Maximum accepted audio duration is 30 seconds; upload limit is 10 MiB; subprocess timeout is 180 seconds. Model downloads occur during setup, not normal transcription.
- The browser records up to 25 seconds and releases microphone tracks when recording ends. Browser capture requires user permission and has not been tested with the user's microphone.
- The service binds to loopback; it rejects unrelated browser origins and unexpected Host values. It is a single-user development service, without authentication or production hosting.

## Recording semantics

The recording is Fidelis's validated action list, exported as ordinary ImageJ macro commands. Replay regenerates the same workflow against the original image in a new output directory. The exported recording is intended for an already-open 8-bit image in Fiji.

This does not hook Fiji's native Recorder, observe desktop clicks, import arbitrary .ijm files, or claim all plugins work headlessly. The execution wrapper contains paths, stage output and worker termination; the downloadable recording omits those environment-specific details.

A run manifest stores actions, source image ID, status, timestamped events, measurements, input SHA-256 and runtime-lock SHA-256. Job state is currently in memory; manifests and artifacts persist on disk, but history is not reloaded after restarting the API. The workbench caps each session at 100 jobs. Disk retention is manual.

## Upstream integrity

external/fiji.lock.json contains the dated upstream archive URL, official archive checksum and 1,379 file hashes covering launchers, configuration, Java, JARs, plugins, macros, scripts and LUTs. Pending update files also cause a mismatch. The adapter checks the installation before each run.

external/stt.lock.json pins faster-whisper 1.2.1 and the model repository revision. speech/requirements.lock.txt pins the resolved dependency tree and wheel hashes. external/stt-model.sha256.json verifies downloaded model assets at setup and each transcription.

## Next milestones and acceptance criteria

1. **Workflow expansion:** pick one representative microscopy task and reference dataset; add plugin-specific output checks, calibrated units and Bio-Formats import before advertising support.
2. **Recording:** design a native Fiji Recorder bridge or a broader action registry. Demonstrate capture → editable macro → replay and clearly separate imported trusted code from speech-generated actions.
3. **Batch processing:** persistent job queue, cancellation, per-file progress, restart recovery and provenance. Compare batch results with direct Fiji runs on identical inputs.
4. **Speech quality:** test recorded human speech, accents, noise, silence and ambiguous commands. Evaluate larger Whisper models and confidence/confirmation behavior. General natural-language planning needs separate validation; it is not silently substituted for the current grammar.
5. **UI design session:** settle composition after workflow evidence. Plan an image-centered workspace with clear input/output identity, selectable stages, parameter inspection, responsive layouts, keyboard navigation, accessible contrast, reduced motion and visible failure/cancellation states. Rich interactions must correspond to actual work.
6. **Packaging:** choose the distribution model, original-code license and upstream source/notice bundle, then test installation on a clean Windows machine. Evaluate a desktop shell only after the local API is stable.

## Known limits

Only Windows x64 is tested. The four commands are ImageJ1 functionality executed through Fiji; no claim is made that the complete Fiji plugin catalog is integrated. Headless compatibility varies per plugin. This milestone does not replace Fiji's menus or renderer.
