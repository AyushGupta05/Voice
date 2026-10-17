"""Local paths; upstream applications stay outside this repository."""

import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def setting(name: str, default: str = "") -> str:
    if name in os.environ:
        return os.environ[name]
    config = ROOT / ".env"
    if config.exists():
        for line in config.read_text(encoding="utf-8-sig").splitlines():
            key, separator, value = line.partition("=")
            if separator and key.strip() == name:
                return value.strip().strip('"')
    return default


def fiji_executable() -> Path:
    value = setting("FIJI_EXECUTABLE")
    if not value or not Path(value).is_file():
        raise RuntimeError("Set FIJI_EXECUTABLE in .env to the pinned Fiji launcher")
    return Path(value).resolve()


def stt_python() -> Path:
    relative = ".venv-stt/Scripts/python.exe" if os.name == "nt" else ".venv-stt/bin/python"
    return Path(setting("STT_PYTHON") or str(ROOT / relative))
