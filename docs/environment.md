# Development environment

Inspected and exercised on 17 October 2026, Europe/London.

The existing repository was found at C:/Users/arnav/Documents/dev/click-to-batch, on main with a clean working tree before this task. The latest original commit was e48b5bc. The project mirror under .codex/.chatgpt-projects contained no source files, and none of its synced references were changed.

| Tool / runtime | Observed version |
| --- | --- |
| Git | 2.53.0.windows.2 |
| Main Python environment | 3.14.3 |
| Speech Python environment | 3.11.16, installed through uv |
| uv | 0.12.11 |
| Node | 24.14.0 |
| npm | 11.9.0 |
| System Java | Temurin 21.0.12 |
| Fiji worker Java | Bundled Zulu 21.0.7, explicitly selected by adapter |
| Fiji assembly JAR | 2.18.1-SNAPSHOT |
| ImageJ1 | 1.54p |
| faster-whisper | 1.2.1 |
| Speech model | tiny.en, CPU int8, revision in external/stt.lock.json |

The API uses the existing .venv with Pillow added for input validation and test comparisons. The old click-to-batch editable Python distribution was removed and replaced with fidelis. No global Python packages were installed. The speech environment and downloaded model are ignored by Git.

Frontend dependencies were retained from the scaffold; package identity and lock metadata were renamed to fidelis-frontend. VS Code backend tasks/debugger now use fidelis.main:app. Fidelis.code-workspace replaces click-to-batch.code-workspace.

No Maven compiler is needed for this integration because upstream Fiji binaries are reused. No GitHub remote rename, push or release was performed. No native microphone device, CUDA execution, fresh-machine installation, macOS or Linux configuration was tested.
