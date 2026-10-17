"""Reference runner: executes an independent ImageJ macro in the pinned Fiji (docs A30 section 2, D18 3.1).

The pinned runtime is verified against external/fiji.lock.json before and after every run
(`verify_runtime` is copied from backend/src/fidelis/adapters/fiji.py; the prototype package is not
imported, so the oracle shares no code with it). One JVM per run, isolated user.home and working
directory, updater autocheck disabled, hard timeout, and only the PID this runner started is killed.

Python API:
    result = run_macro(macro_text, run_dir, mode="headless"|"gui", arg="", timeout=180)
CLI (self-test):
    python -I verification/reference_runner.py --verify          # lock check only
    python -I verification/reference_runner.py --macro f.ijm --mode gui --run-dir DIR
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIJI_DIR = Path(os.environ.get("FIDELIS_FIJI_DIR", r"C:\Users\arnav\Documents\dev\Fiji"))
LOCK_PATH = ROOT / "external" / "fiji.lock.json"
COMMANDS_CSV = ROOT / "docs" / "data" / "commands.csv"
DEFAULT_TIMEOUT = 180


# --- runtime pin (copied from backend/src/fidelis/adapters/fiji.py) --------------------------------


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def runtime_files(home: Path) -> list[Path]:
    files = [p for p in home.iterdir() if p.is_file() and p.suffix in {".exe", ".bat"}]
    for directory in (
        "jars", "plugins", "lib", "config", "java", "scripts", "macros", "luts", "update",
    ):
        files.extend(p for p in (home / directory).rglob("*") if p.is_file())
    return sorted(files)


def verify_runtime(executable: Path) -> str:
    """Return the lock hash (SHA-256 of fiji.lock.json) if the installation equals the lock."""
    lock = json.loads(LOCK_PATH.read_text())
    home = executable.parent
    actual = {p.relative_to(home).as_posix(): sha256(p) for p in runtime_files(home)}
    if executable.name != lock["launcher"] or actual != lock["files"]:
        missing = sorted(set(lock["files"]) - set(actual))[:5]
        extra = sorted(set(actual) - set(lock["files"]))[:5]
        changed = sorted(k for k in actual if k in lock["files"] and actual[k] != lock["files"][k])[:5]
        raise RuntimeError(
            f"Fiji differs from {LOCK_PATH.name}: missing={missing} extra={extra} changed={changed}"
        )
    return sha256(LOCK_PATH)


def fiji_executable() -> Path:
    return FIJI_DIR / json.loads(LOCK_PATH.read_text())["launcher"]


def java_home() -> Path:
    return FIJI_DIR / json.loads(LOCK_PATH.read_text())["java_home"]


# --- reference mode per command (D18 3.1: headless_probe in commands.csv) ------------------------


def headless_probe(command_id: str) -> str:
    with COMMANDS_CSV.open(encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
            if row["command_id"] == command_id:
                return row["headless_probe"]
    return "untested"


def reference_mode(command_ids: list[str]) -> str:
    """Headless only if every command's headless_probe is `pass`; otherwise GUI (DEC-043)."""
    return "headless" if all(headless_probe(c) == "pass" for c in command_ids) else "gui"


# --- execution ----------------------------------------------------------------------------------


def kill_tree(pid: int) -> None:
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True, check=False)
    else:
        os.kill(pid, 9)


@dataclass
class RunResult:
    mode: str
    lock_hash: str
    exit_code: int | None
    seconds: float
    timed_out: bool
    done_marker: bool
    run_dir: Path
    log: str = ""
    notes: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return self.done_marker and not self.timed_out


def run_macro(
    macro_text: str,
    run_dir: Path,
    mode: str = "headless",
    arg: str = "",
    timeout: float = DEFAULT_TIMEOUT,
    macro_name: str = "reference.ijm",
    done_name: str = "done.json",
) -> RunResult:
    """Run `macro_text` in a fresh pinned-Fiji JVM. The macro must write `done_name` last.

    The process is stopped as soon as the done marker exists (a headless Gaussian Blur macro can leave
    the JVM alive, A08), or when the timeout expires (reported as a harness failure, never a pass).
    """
    if mode not in ("headless", "gui"):
        raise ValueError(mode)
    executable = fiji_executable()
    before = verify_runtime(executable)
    if (FIJI_DIR / "update").exists() and any((FIJI_DIR / "update").iterdir()):
        raise RuntimeError("Fiji/update is not empty: Jaunch would apply it at launch")
    run_dir = run_dir.resolve()
    home = run_dir / "home"
    home.mkdir(parents=True, exist_ok=True)
    macro = run_dir / macro_name
    macro.write_text(macro_text, encoding="utf-8")
    done = run_dir / done_name
    done.unlink(missing_ok=True)
    cmd = [
        str(executable),
        f"--java-home={java_home()}",
        "-Dimagej.updater.disableAutocheck=true",
        f"-Duser.home={home}",
        "--allow-multiple",
    ]
    if mode == "headless":
        cmd.append("--headless")
    cmd += ["-macro", str(macro)]
    if arg:
        cmd.append(arg)
    start = time.monotonic()
    timed_out = False
    notes: list[str] = []
    with (run_dir / "fiji-stdio.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            cmd,
            cwd=run_dir,
            stdout=log,
            stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" and mode == "headless" else 0,
        )
        try:
            while True:
                if done.exists():
                    # give the macro's own exit a moment, then stop the tree we started
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        notes.append("process still alive 5 s after done marker; killed")
                    break
                if process.poll() is not None:
                    break
                if time.monotonic() - start > timeout:
                    timed_out = True
                    break
                time.sleep(0.2)
        finally:
            exit_code = process.poll()
            if exit_code is None:
                kill_tree(process.pid)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    notes.append("process did not exit after taskkill")
    seconds = time.monotonic() - start
    after = verify_runtime(executable)
    if after != before:
        raise RuntimeError("Fiji lock hash changed during the run")
    if (FIJI_DIR / "IJ_Prefs.txt").exists():
        notes.append("WARNING: IJ_Prefs.txt exists in the Fiji root (not covered by the lock)")
    text = (run_dir / "fiji-stdio.log").read_text(encoding="utf-8", errors="replace")
    return RunResult(mode, after, exit_code, seconds, timed_out, done.exists(), run_dir, text, notes)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--verify", action="store_true")
    ap.add_argument("--macro")
    ap.add_argument("--mode", default="headless")
    ap.add_argument("--run-dir")
    ap.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT)
    args = ap.parse_args()
    if args.verify:
        t = time.monotonic()
        print("lock hash", verify_runtime(fiji_executable()), f"({time.monotonic() - t:.1f}s)")
        return 0
    if args.macro:
        result = run_macro(
            Path(args.macro).read_text(), Path(args.run_dir), args.mode, timeout=args.timeout
        )
        print(json.dumps({k: str(v) for k, v in result.__dict__.items() if k != "log"}, indent=2))
        return 0 if result.ok else 1
    ap.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
