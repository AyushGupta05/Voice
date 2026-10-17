"""Execute generated ImageJ macros in a separate, pinned Fiji installation."""

import hashlib
import json
import os
import subprocess
import time
from pathlib import Path

from fidelis.commands import Workflow, action_macro
from fidelis.config import ROOT, fiji_executable


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def runtime_files(home: Path) -> list[Path]:
    files = [p for p in home.iterdir() if p.is_file() and p.suffix in {".exe", ".bat"}]
    for directory in (
        "jars",
        "plugins",
        "lib",
        "config",
        "java",
        "scripts",
        "macros",
        "luts",
        "update",
    ):
        files.extend(p for p in (home / directory).rglob("*") if p.is_file())
    return sorted(files)


def verify_runtime(executable: Path) -> str:
    lock_path = ROOT / "external/fiji.lock.json"
    lock = json.loads(lock_path.read_text())
    home = executable.parent
    actual = {p.relative_to(home).as_posix(): sha256(p) for p in runtime_files(home)}
    if executable.name != lock["launcher"] or actual != lock["files"]:
        raise RuntimeError(
            "Fiji differs from external/fiji.lock.json; review the runtime before repinning"
        )
    return sha256(lock_path)


def quote_path(path: Path) -> str:
    return json.dumps(path.resolve().as_posix(), ensure_ascii=False)


def build_macro(workflow: Workflow, source: Path, directory: Path) -> str:
    progress = quote_path(directory / "progress.txt")
    lines = ["setBatchMode(true);", f"open({quote_path(source)});", 'run("8-bit");']
    for index in range(len(workflow.actions) + 1):
        if index:
            lines.append(action_macro(workflow.actions[index - 1]))
        lines += [
            f'saveAs("PNG", {quote_path(directory / f"stage-{index}.png")});',
            "getStatistics(area, mean, min, max, std);",
            f'File.append("{index},"+area+","+d2s(mean,8)+","+min+","+max+","+d2s(std,8)+"\\n", '
            f"{quote_path(directory / 'measurements.csv')});",
            f'File.append("{index}\\n", {progress});',
        ]
    lines += [
        f'File.saveString("ok", {quote_path(directory / "complete.txt")});',
        "close();",
        'eval("script", "System.exit(0);");',
    ]
    return "\n".join(lines) + "\n"


def terminate_tree(process: subprocess.Popen) -> None:
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True, check=False
        )
    else:
        import signal

        os.killpg(process.pid, signal.SIGKILL)


def execute(workflow: Workflow, source: Path, directory: Path, emit, timeout=120) -> dict:
    executable = fiji_executable()
    emit("verifying", "Checking pinned Fiji installation")
    runtime_hash = verify_runtime(executable)
    runtime_lock = json.loads((ROOT / "external/fiji.lock.json").read_text())
    java_home = executable.parent / runtime_lock["java_home"]
    macro = directory / "execution.ijm"
    macro.write_text(build_macro(workflow, source, directory), encoding="utf-8")
    (directory / "measurements.csv").write_text("stage,area,mean,min,max,std\n")
    emit("starting", "Starting Fiji / ImageJ")
    with (directory / "fiji.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            [
                str(executable),
                f"--java-home={java_home}",
                "--allow-multiple",
                "--headless",
                "-macro",
                str(macro),
            ],
            cwd=executable.parent,
            stdout=log,
            stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
            start_new_session=os.name != "nt",
        )
        start = time.monotonic()
        seen = set()

        def read_progress():
            progress = directory / "progress.txt"
            if progress.exists():
                # Only newline-terminated records are committed by the macro.
                for line in progress.read_text().split("\n")[:-1]:
                    if line.isdigit() and line not in seen:
                        index = int(line)
                        if index <= len(workflow.actions):
                            seen.add(line)
                            label = (
                                "Input loaded"
                                if not index
                                else workflow.actions[index - 1].operation
                            )
                            emit("stage", label, index)

        try:
            while process.poll() is None:
                read_progress()
                if time.monotonic() - start > timeout:
                    raise TimeoutError(f"Fiji exceeded {timeout}s")
                time.sleep(0.15)
            read_progress()
        finally:
            if process.poll() is None:
                terminate_tree(process)
                process.wait(timeout=10)
    marker = directory / "complete.txt"
    if process.returncode != 0 or not marker.exists() or marker.read_text() != "ok":
        raise RuntimeError(f"Fiji failed (exit {process.returncode}); inspect fiji.log")
    expected = range(len(workflow.actions) + 1)
    if any(not (directory / f"stage-{i}.png").is_file() for i in expected):
        raise RuntimeError("Fiji did not produce every expected preview")
    return {
        "runtime_lock_sha256": runtime_hash,
        "input_sha256": sha256(source),
        "elapsed_seconds": round(time.monotonic() - start, 3),
    }
