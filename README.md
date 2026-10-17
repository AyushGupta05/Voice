# Fidelis

A local companion that brings real Fiji / ImageJ operations into a modern, observable interface. Formerly Click to Batch; this milestone extends the existing repository instead of replacing Fiji.

**Implemented:** a React workbench, a FastAPI job service, a pinned external Fiji process adapter, an isolated faster-whisper CPU worker, command review, per-action image previews, measurements, supported-action macro recording and replay.

**Scope:** invert, Gaussian blur, fixed threshold and whole-image measurements on single-frame 8-bit grayscale PNGs. Speech understands a small explicit English command grammar. This is a working integration milestone, not a complete Fiji replacement.

## Run on this machine

Open [Fidelis.code-workspace](Fidelis.code-workspace) in VS Code and run the **Dev: start both** task, or use two terminals from this directory:

    ./.venv/Scripts/python.exe -m uvicorn fidelis.main:app --host 127.0.0.1 --port 8000

    npm.cmd --prefix frontend run dev

Open http://127.0.0.1:5173. Use the synthetic image, review “invert the image then measure the image”, then click Run in Fiji. Click a timeline stage to inspect its output; download the macro or replay the recording.

To test speech without a microphone, upload fixtures/invert-command.wav through the audio control. Its generated speech says “Invert the image.” The transcript becomes a visible command for review; transcription alone never runs image processing.

## Documentation

- [Setup and dependency recreation](docs/setup.md)
- [Architecture, boundaries and next milestones](docs/architecture.md)
- [Upstream research and license decisions](docs/upstreams.md)
- [Verification results and limitations](docs/verification.md)
- [Environment inventory](docs/environment.md)
- [Third-party notices](third_party/NOTICE.md)

## Repository layout

    backend/src/fidelis/       API, typed command grammar and upstream adapters
    frontend/                 React / TypeScript / Vite workbench
    speech/                   Isolated speech worker and hash-locked requirements
    external/                 Runtime/model pins; downloaded model data is ignored
    fiji/macros/              Original integration smoke macro
    fixtures/                 Original synthetic image and generated speech fixture
    scripts/                  Setup, pinning and fixture tools
    tests/backend/            Validation, failure-path and real integration tests
    third_party/              Preserved upstream licenses and package inventories
    outputs/                  Ignored per-run macros, previews, measurements and manifests
    data/inputs/              Ignored imported grayscale images

The API package, UI and workspace are named Fidelis. The existing checkout directory remains C:/Users/arnav/Documents/dev/click-to-batch to preserve local tooling paths. Its existing GitHub remote remains https://github.com/reverez/click-to-batch. No GitHub rename, push or publication was performed.

## Verify

    ./.venv/Scripts/python.exe -m pytest -m "not integration"
    ./.venv/Scripts/python.exe -m ruff check .
    npm.cmd --prefix frontend run build

Opt into real Fiji and speech tests in PowerShell:

    $env:FIDELIS_INTEGRATION = "1"
    ./.venv/Scripts/python.exe -m pytest --junitxml=outputs/all-tests.xml

See docs/verification.md for what was actually checked and what remains pending.
