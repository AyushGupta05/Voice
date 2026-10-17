"""Run a synthetic ImageJ macro to verify the configured Fiji installation."""

import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def configured_executable() -> Path:
    config = ROOT / ".env"
    if not config.exists():
        raise RuntimeError("Missing .env; copy .env.example and set FIJI_EXECUTABLE")
    for line in config.read_text(encoding="utf-8-sig").splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == "FIJI_EXECUTABLE":
            executable = Path(value.strip().strip('"'))
            if executable.is_file():
                return executable
            raise RuntimeError(f"Fiji executable not found: {executable}")
    raise RuntimeError("FIJI_EXECUTABLE is missing from .env")


def main() -> None:
    executable = configured_executable()
    macro = ROOT / "fiji" / "macros" / "smoke-test.ijm"
    with tempfile.TemporaryDirectory(prefix="click-to-batch-fiji-") as temporary:
        marker = Path(temporary) / "result.txt"
        result = subprocess.run(
            [str(executable), "--headless", "-macro", str(macro), str(marker)],
            cwd=executable.parent,
            capture_output=True,
            text=True,
            timeout=120,
            check=False,
        )
        if result.returncode != 0 or not marker.exists() or marker.read_text() != "ok":
            raise RuntimeError(
                f"Fiji check failed (exit {result.returncode}).\n"
                f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
            )
    print(f"Fiji smoke check passed: {executable}")


if __name__ == "__main__":
    main()
