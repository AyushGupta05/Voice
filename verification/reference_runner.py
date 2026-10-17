"""Reference runner: executes an independent ImageJ macro in the pinned Fiji (docs A30 section 2, D18 3.1).

The pinned runtime is verified against external/fiji.lock.json before and after every run
(`verify_runtime` is copied from backend/src/fidelis/adapters/fiji.py; the prototype package is not
imported, so the oracle shares no code with it). One JVM per run, isolated user.home and working
directory, updater autocheck disabled, hard timeout, and only the PID this runner started is killed.

Windows (the pinned archive's platform): the lock is checked file by file and Fiji's own launcher runs the macro.
macOS (Apple silicon): the lock pins the Windows archive, so the platform-neutral inventory (jars, plugins, macros,
scripts, luts, configuration: every lock entry outside the win64 folders and the .exe launchers) must equal it file
by file, and the host platform's JDK and native jars must be the same component versions as the pinned win64 ones.
The macro then runs in the JVM Fiji's launcher would start (its `--dry-run` command line), with one change: Penny's
Fiji bridge plugin (plugins/luma-fiji-bridge*.jar, installed into the same Fiji for the stand-alone Penny profile and
not part of the pin) is left off the class path, so the reference run never loads it or its control server.

Python API:
    result = run_macro(macro_text, run_dir, mode="headless"|"gui", arg="", timeout=180)
CLI (self-test):
    python -I verification/reference_runner.py --verify          # lock check only
    python -I verification/reference_runner.py --macro f.ijm --mode gui --run-dir DIR
"""

from __future__ import annotations

import argparse
import csv
import glob
import hashlib
import json
import os
import platform
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MACOS = sys.platform == "darwin"


def _default_fiji_dir() -> Path:
    if MACOS:
        # the checkout's Fiji (script/setup-fiji.py), or, in a git worktree under .local/worktrees, the enclosing one's
        for base in [ROOT, *ROOT.parents]:
            candidate = base / ".local" / "fiji" / "Fiji.app"
            if (candidate / "jars").is_dir():
                return candidate
        return ROOT / ".local" / "fiji" / "Fiji.app"
    return Path(r"C:\Users\arnav\Documents\dev\Fiji")


FIJI_DIR = Path(os.environ.get("FIDELIS_FIJI_DIR", str(_default_fiji_dir())))
LOCK_PATH = ROOT / "external" / "fiji.lock.json"
COMMANDS_CSV = ROOT / "docs" / "data" / "commands.csv"
DEFAULT_TIMEOUT = 180

# Plugins installed into the same Fiji that are not part of the pin and never reach a reference run's class path.
EXCLUDED_PLUGINS = ("luma-fiji-bridge",)
# Files a plugin writes into its own folder at run time (data, not code); reported, never loaded as commands.
RUNTIME_DATA = ("plugins/colourdeconvolution.txt",)
MAC_PLATFORM = "macos-arm64" if platform.machine() == "arm64" else "macos64"
MAC_LAUNCHER = f"Fiji.app/Contents/MacOS/fiji-{'macos-arm64' if MAC_PLATFORM == 'macos-arm64' else 'macos-x64'}"


# --- runtime pin (copied from backend/src/fidelis/adapters/fiji.py) --------------------------------


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        if hasattr(hashlib, "file_digest"):  # Python 3.11+
            return hashlib.file_digest(stream, "sha256").hexdigest()
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
        return digest.hexdigest()


def runtime_files(home: Path) -> list[Path]:
    files = [p for p in home.iterdir() if p.is_file() and p.suffix in {".exe", ".bat"}]
    for directory in (
        "jars", "plugins", "lib", "config", "java", "scripts", "macros", "luts", "update",
    ):
        files.extend(p for p in (home / directory).rglob("*") if p.is_file())
    return sorted(files)


def verify_runtime(executable: Path) -> str:
    """Return the lock hash (SHA-256 of fiji.lock.json) if the installation equals the lock."""
    if MACOS:
        return verify_runtime_macos(executable)
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


# --- macOS: the same pinned archive, other platform folders --------------------------------------

_PLATFORM_DIRS = ("java", "jars", "lib")
# "ffmpeg-6.1.1-1.5.10-windows-x86_64.jar" and "...-macosx-arm64.jar" -> "ffmpeg-6.1.1-1.5.10"
_PLATFORM_JAR = re.compile(r"^(.*?)-(?:natives-)?(?:windows|win|macosx|mac|osx)(?:[-_].*)?\.jar$")
_ZULU = re.compile(r"^(zulu[0-9.]+-ca-jdk[0-9.]+)-")


def _platform_specific(rel: str, plat: str) -> bool:
    parts = rel.split("/")
    if len(parts) >= 2 and parts[0] in _PLATFORM_DIRS and parts[1] == plat:
        return True
    return False


def _windows_only(rel: str) -> bool:
    if _platform_specific(rel, "win64") or rel.endswith(".exe"):
        return True
    return rel.startswith("config/jaunch/jaunch-windows")


def _host_platform(rel: str) -> bool:
    parts = rel.split("/")
    if parts[0] == "Fiji.app":  # the macOS launcher bundle (outside runtime_files' folders)
        return True
    return len(parts) >= 2 and parts[0] in _PLATFORM_DIRS and parts[1] in (MAC_PLATFORM, "macosx")


def _component_versions(names: list[str]) -> list[str]:
    out = []
    for name in names:
        m = _PLATFORM_JAR.match(Path(name).name)
        if m:
            out.append(m.group(1))
    return sorted(out)


def mac_runtime_report(home: Path) -> dict:
    """Compare a macOS install with the (Windows) lock; raises when anything that could change a result differs."""
    lock = json.loads(LOCK_PATH.read_text())
    pinned = {k: v for k, v in lock["files"].items() if not _windows_only(k)}
    actual = {p.relative_to(home).as_posix(): p for p in runtime_files(home)}
    missing = sorted(set(pinned) - set(actual))
    changed = sorted(k for k in pinned if k in actual and sha256(actual[k]) != pinned[k])
    extra = sorted(k for k in actual if k not in lock["files"] and not _host_platform(k))
    excluded = [k for k in extra if k.startswith("plugins/") and Path(k).name.startswith(EXCLUDED_PLUGINS)]
    data = [k for k in extra if k in RUNTIME_DATA]
    unexpected = [k for k in extra if k not in excluded and k not in data]
    win_jars = _component_versions([k for k in lock["files"] if k.startswith("jars/win64/")])
    mac_jars = _component_versions([k for k in actual if k.startswith((f"jars/{MAC_PLATFORM}/", "jars/macosx/"))])
    win_jdk = _ZULU.match(Path(lock["java_home"]).name)
    mac_jdks = sorted({m.group(1) for m in (_ZULU.match(Path(d).name) for d in glob.glob(str(home / "java" / MAC_PLATFORM / "zulu*"))) if m})
    problems = []
    if missing or changed or unexpected:
        problems.append(f"platform-neutral files differ from {LOCK_PATH.name}: missing={missing[:5]} "
                        f"changed={changed[:5]} extra={unexpected[:5]}")
    if win_jars != mac_jars:
        problems.append(f"native/JavaFX jars are not the pinned versions: win64={win_jars} {MAC_PLATFORM}={mac_jars}")
    if not win_jdk or [win_jdk.group(1)] != mac_jdks:
        problems.append(f"JDK is not the pinned build: lock={lock['java_home']} {MAC_PLATFORM}={mac_jdks}")
    if not (home / MAC_LAUNCHER).is_file():
        problems.append(f"launcher {MAC_LAUNCHER} not found under {home}")
    if problems:
        raise RuntimeError("; ".join(problems))
    return {"neutral_files": len(pinned), "platform_jars": mac_jars, "jdk": mac_jdks[0],
            "excluded_from_classpath": excluded, "runtime_data": data}


def verify_runtime_macos(executable: Path) -> str:
    mac_runtime_report(fiji_home(executable))
    return sha256(LOCK_PATH)


def fiji_home(executable: Path) -> Path:
    """The Fiji folder of a launcher (on macOS the launcher sits in Fiji.app/Contents/MacOS inside it)."""
    return executable.parents[3] if MACOS else executable.parent


def fiji_executable() -> Path:
    if MACOS:
        return FIJI_DIR / MAC_LAUNCHER
    return FIJI_DIR / json.loads(LOCK_PATH.read_text())["launcher"]


def java_home() -> Path:
    if MACOS:
        homes = sorted(glob.glob(str(FIJI_DIR / "java" / MAC_PLATFORM / "zulu*")))
        if not homes:
            raise RuntimeError(f"no bundled JDK under {FIJI_DIR / 'java' / MAC_PLATFORM}")
        return Path(homes[-1])
    return FIJI_DIR / json.loads(LOCK_PATH.read_text())["java_home"]


def launch_command(mode: str, home: Path, macro: Path, arg: str = "") -> list[str]:
    """The command that runs `macro` in a fresh pinned Fiji JVM (see the module docstring for macOS)."""
    executable = fiji_executable()
    jaunch_args = ["--allow-multiple"] + (["--headless"] if mode == "headless" else [])
    if not MACOS:
        cmd = [str(executable), f"--java-home={java_home()}", "-Dimagej.updater.disableAutocheck=true",
               f"-Duser.home={home}"] + jaunch_args + ["-macro", str(macro)]
        return cmd + ([arg] if arg else [])
    # Fiji's launcher prints the JVM command it would run; placeholders keep our own paths out of its
    # space-separated output, which is split on spaces (the Fiji folder itself must not contain one).
    if " " in str(FIJI_DIR):
        raise RuntimeError(f"the Fiji folder must not contain spaces on macOS: {FIJI_DIR}")
    dry = subprocess.run([str(executable), "--dry-run", "-Dimagej.updater.disableAutocheck=true",
                          "-Duser.home=__FIDELIS_HOME__"] + jaunch_args + ["-macro", "__FIDELIS_MACRO__"] + (["__FIDELIS_ARG__"] if arg else []),
                         capture_output=True, text=True, timeout=60, check=False)
    line = next((ln for ln in (dry.stdout + dry.stderr).splitlines() if ln.startswith("[DRY-RUN]")), None)
    if line is None:
        raise RuntimeError(f"{executable.name} --dry-run printed no command: {dry.stdout[-500:]} {dry.stderr[-500:]}")
    cmd = line[len("[DRY-RUN]"):].split()
    out = []
    for token in cmd:
        if token.startswith("-Djava.class.path="):
            jars = token[len("-Djava.class.path="):].split(os.pathsep)
            kept = [j for j in jars if not Path(j).name.startswith(EXCLUDED_PLUGINS)]
            token = "-Djava.class.path=" + os.pathsep.join(kept)
        out.append({"-Duser.home=__FIDELIS_HOME__": f"-Duser.home={home}", "__FIDELIS_MACRO__": str(macro), "__FIDELIS_ARG__": arg}.get(token, token))
    if not out or not Path(out[0]).name == "java":
        raise RuntimeError(f"unexpected --dry-run command: {line[:300]}")
    return out


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
    cmd = launch_command(mode, home, macro, arg)
    start = time.monotonic()
    timed_out = False
    notes: list[str] = []
    if MACOS:
        report = mac_runtime_report(fiji_home(executable))
        notes.append(f"macOS {MAC_PLATFORM}: {report['neutral_files']} platform-neutral files equal the lock; "
                     f"platform jars and JDK {report['jdk']} are the pinned versions; excluded from the class path: "
                     f"{report['excluded_from_classpath']}")
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
