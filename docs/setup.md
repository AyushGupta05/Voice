# Setup and reproducibility

Tested on Windows x64. Keep the external Fiji installation outside the Git checkout.

## Existing local setup

- Repository: C:/Users/arnav/Documents/dev/click-to-batch
- Fiji: C:/Users/arnav/Documents/dev/Fiji
- API environment: .venv, Python 3.14.3
- Speech environment: .venv-stt, Python 3.11.16
- Frontend: Node 24.14.0, npm 11.9.0

The ignored .env already points FIJI_EXECUTABLE at the Fiji launcher. An optional STT_PYTHON overrides the speech interpreter; leave it blank to use the repository's speech environment.

## Recreate Python and frontend dependencies

Use the same tested Python minor versions to reproduce this Windows environment. The core lock is a resolved pip snapshot; the speech lock additionally includes artifact hashes.

    python -m venv .venv
    ./.venv/Scripts/python.exe -m pip install -r requirements-dev.lock.txt
    ./.venv/Scripts/python.exe -m pip install --no-deps -e ".[dev]"
    npm.cmd --prefix frontend ci

For speech, with uv available:

    uv venv --python 3.11 .venv-stt
    uv pip sync --python .venv-stt/Scripts/python.exe --require-hashes speech/requirements.lock.txt
    ./.venv-stt/Scripts/python.exe scripts/setup_speech.py

The model is about 75 MB. The setup downloads only the pinned Hugging Face revision, then validates local file hashes. Normal inference is local and does not use a hosted speech API. CPU inference is the tested baseline; CUDA is not required or configured.

The installed speech tree was checked against the lock with a hash-enforced sync. Do not move an existing virtual environment between directories; recreate it when moving the checkout.

## Fiji installation pin

The existing installation is a byte-pinned snapshot with ImageJ 1.54p, Fiji 2.18.1-SNAPSHOT and bundled Zulu Java 21.0.7. Its dated official download is:

https://downloads.imagej.net/fiji/archive/latest/20261013-1517/fiji-latest-win64-jdk.zip

Expected SHA-256:

    9201287a86967d1dc93a5a8426ef8c5048c6186c50642c75164fb2c813794c54

The official .sha256 file matches the archive digest recorded by the previous setup. The archive was not downloaded and extracted again during this integration milestone.

On another Windows machine, download that exact archive, verify its SHA-256 with Get-FileHash, extract it to a new directory, and retain all upstream license files. Copy .env.example to .env and set FIJI_EXECUTABLE to the extracted fiji-windows-x64.exe. Do not point to a moving latest download or update the pinned installation in place.

Verify the installation against the committed file inventory:

    ./.venv/Scripts/python.exe -c "from fidelis.adapters.fiji import verify_runtime; from fidelis.config import fiji_executable; print(verify_runtime(fiji_executable()))"

Then run the integration tests described in the README. Fresh-machine extraction has not yet been tested; the current installed runtime has been verified against the full runtime-file manifest.

## Intentional updates

Treat an upstream update as a separate change. Install into a separate directory, inspect repository/license/dependency changes, update the source archive URL and checksum, regenerate the file inventory, and run numerical and speech integration tests before adopting it.

scripts/pin_fiji.py is a maintenance helper for the current dated archive. It requires --accept-current-installation and overwrites the lock. Its provenance constants must be updated when adopting a different archive. Never repin merely to silence an unexplained mismatch.

## Start / stop

Use the two commands in README.md or the VS Code tasks. Both services bind to 127.0.0.1. Stop with Ctrl+C in their terminals. Avoid reloading or shutting down the API while a workflow is running; user cancellation and persistent restart recovery are pending.

Do not use multiple Uvicorn workers with the current in-memory scheduler. Production frontend serving is not packaged; npm build verifies the frontend bundle, while npm run dev supplies the /api proxy.
