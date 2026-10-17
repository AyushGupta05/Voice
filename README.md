# Click to Batch

Local Fiji/ImageJ workflow companion for the **Reinvent the Wheel** hackathon.

This is a working development scaffold: a FastAPI health endpoint, a React/TypeScript/Vite starter screen, local development tooling and a verified Fiji macro smoke check. Scientific image analysis, batch processing and voice input are future work.

## Quick start on Windows

Open this folder in VS Code. Choose **Terminal → Run Task → Dev: start both**. The frontend is at <http://127.0.0.1:5173>; API documentation is at <http://127.0.0.1:8000/docs>.

Alternatively, run these commands in two PowerShell terminals from the project root:

```powershell
# Terminal 1
.\.venv\Scripts\python.exe -m uvicorn click_to_batch.main:app --reload --host 127.0.0.1 --port 8000

# Terminal 2
npm.cmd --prefix frontend run dev
```

No virtual environment activation or PowerShell policy change is needed. Stop each server with Ctrl+C. Stop a running Backend task before using F5 to debug on the same port.

## Layout

```text
backend/src/click_to_batch/  Python API package
frontend/src/               React interface
tests/backend/              API contract tests
fixtures/                   Small synthetic test inputs
fiji/macros/                Fiji smoke check and future analysis macros
scripts/                   Local Fiji verification helper
docs/                       Architecture, setup and local inventory
.vscode/                    Interpreter, tasks, debugging and extensions
pyproject.toml              Python dependencies and tooling
requirements-dev.lock.txt   Resolved Python development dependencies
click-to-batch.code-workspace
```

## Recreate dependencies

Use Python 3.11+ and Node.js 22.12+ (this environment was verified with Python 3.14.3 and Node 24.14.0). The existing `.venv` and frontend dependencies are already set up on this machine.

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.lock.txt
.\.venv\Scripts\python.exe -m pip install --no-deps -e ".[dev]"
npm.cmd --prefix frontend ci
```

The Python lock is a snapshot tested on this Windows/Python environment; re-resolve and test for another platform or Python version. After deliberately changing Python dependencies, install `-e ".[dev]"`, rerun checks and refresh the lock with `python -m pip freeze --exclude-editable` using the project interpreter.

## Verify

```powershell
.\.venv\Scripts\python.exe -m pytest
.\.venv\Scripts\python.exe -m ruff check .
npm.cmd --prefix frontend run build
.\.venv\Scripts\python.exe scripts/check_fiji.py
```

Fiji is installed at `C:\Users\arnav\Documents\dev\Fiji`. The Fiji check reads `FIJI_EXECUTABLE` from the ignored local `.env` file and runs `fiji/macros/smoke-test.ijm` in headless mode. You can also run the **Check Fiji** VS Code task. The app's health endpoint and interface do not yet run scientific Fiji analysis.

See [architecture and next steps](docs/architecture.md) and [local environment](docs/environment.md). The repository is local with no remote configured. Claude Code is installed but currently signed out. Run `claude auth login` in a VS Code terminal, then `claude` from this folder after signing in.
