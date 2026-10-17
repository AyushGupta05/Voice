"""Speech is a bounded subprocess with a separate Python environment and model."""

import json
import os
import subprocess
from pathlib import Path

from fidelis.config import ROOT, stt_python


def transcribe(path: Path) -> dict:
    interpreter = stt_python()
    if not interpreter.is_file():
        raise RuntimeError("Speech environment missing; follow docs/setup.md")
    result = subprocess.run(
        [str(interpreter), str(ROOT / "speech/worker.py"), str(path)],
        cwd=ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=180,
        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
    )
    if result.returncode:
        raise RuntimeError("Speech recognition failed: " + result.stderr[-1500:])
    return json.loads(result.stdout)
