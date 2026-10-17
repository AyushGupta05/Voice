# Local environment

Inspected on 17 October 2026 (Europe/London).

Project: `C:\Users\arnav\Documents\dev\click-to-batch`

## Existing installations reused

| Tool | Version | Location |
| --- | --- | --- |
| Git | 2.53.0.windows.2 | `C:\Program Files\Git\cmd\git.exe` |
| Python | 3.14.3 | `C:\Users\arnav\AppData\Local\Python\pythoncore-3.14-64\python.exe` |
| Node.js | 24.14.0 | `C:\Program Files\nodejs\node.exe` |
| npm | 11.9.0 | `C:\Program Files\nodejs\npm.cmd` |
| Claude Code | 2.1.276 | `C:\Users\arnav\.local\bin\claude.exe` |
| VS Code | 1.142.0 | `C:\Users\arnav\AppData\Local\Programs\Microsoft VS Code\Code.exe` |

The Python launcher also reported an Astral-managed CPython 3.14.3 runtime. The project uses the PythonCore runtime above. The Python install manager automatically reported updating itself to 26.3 during its inventory command; no Python runtime reinstall was performed.

## Project setup

- Isolated Python environment: `.venv/`; no activation required.
- Editable backend package with FastAPI, Uvicorn, pytest, HTTPX and Ruff.
- Frontend dependencies installed locally in `frontend/node_modules/`; exact direct versions in `package.json` and the resolved tree in `package-lock.json`.
- VS Code Python, Python Debugger and Ruff extensions installed; Python's environment and Pylance dependencies were installed automatically. EditorConfig was already present.
- Local Git repository uses branch `main`, with an initial scaffold commit. No remote or account-linked repository was created.
- Claude Code's CLI version was checked. `claude auth status` reports `loggedIn=false`; sign in with `claude auth login` from a VS Code terminal when ready. Subscription capacity and model access were not tested or changed.

## Fiji / ImageJ

Installed the official **Fiji Latest** Windows x64 build at `C:\Users\arnav\Documents\dev\Fiji`. Its launcher is `C:\Users\arnav\Documents\dev\Fiji\fiji-windows-x64.exe`. The distribution includes a Java 21 runtime, so no separate system Java install was needed. The official ZIP was fetched from the Fiji UK mirror on 17 October 2026, validated with ZIP CRC checks, and extracted only after confirming that its destination did not exist. Archive SHA-256: `9201287A86967D1DC93A5A8426EF8C5048C6186C50642C75164FB2C813794C54`. The temporary download was removed after the installation passed its check.

The ignored project `.env` sets `FIJI_EXECUTABLE` to this launcher. `scripts/check_fiji.py` ran `fiji/macros/smoke-test.ijm` headlessly, created a synthetic image, checked a pixel and confirmed the expected output. Use the **Check Fiji** VS Code task to repeat the check. This verifies basic macro execution; plugin-specific scientific workflows still need their own tests.

Fiji's official download page recommends a user-writable location for Windows and describes the distribution as portable: <https://imagej.net/software/fiji/downloads>.

## Development use

Open the project folder or `click-to-batch.code-workspace` in VS Code. Use **Terminal → Run Task → Dev: start both**, then visit <http://127.0.0.1:5173>. Use **Debug FastAPI** with F5 when the Backend task is stopped.

Both development servers bind to loopback. The frontend's `/api` proxy targets port 8000. If a port is occupied, stop the conflicting development process or deliberately update the server and proxy configuration together.

Use `npm.cmd` in Windows PowerShell to avoid execution-policy issues with `npm.ps1`; no system execution-policy change is needed. If VS Code asks about workspace trust, review the newly created project and choose the setting yourself.

## Verification completed

- Backend API contract test: 1 passed with no warnings.
- Ruff lint and Python package compatibility checks: passed.
- TypeScript check and Vite production build: passed.
- Live HTTP checks: backend health, health through the Vite proxy, frontend HTML and OpenAPI schema all passed.
- Verification servers were stopped afterward; use the VS Code tasks to start development.
- Git ignore rules exclude the virtual environment, dependencies, build output, local environment files, datasets and results.
- Headless Fiji synthetic macro check: passed.

The scaffold's HTTP behavior, build and Fiji macro launch were checked. Full analysis integration and browser workflow testing remain future work.
