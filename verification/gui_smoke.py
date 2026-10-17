"""GUI smoke harness (verification brief T5; docs D18 section 8).

Launches the application through fidelis-app/tools/run.py with an isolated user.home and asks it to write
a screenshot, then checks that the PNG exists and is not blank (non-trivial size, dimensions, colour
variance). A screenshot is smoke evidence that the window rendered; it is never scientific evidence of
equivalence (CR-A30-08).

    python -I verification/gui_smoke.py [--out shot.png] [--timeout 120] [--force]
    python -I verification/gui_smoke.py --check-png existing.png      # only analyse a PNG

Exit codes: 0 pass, 1 fail, 77 skipped (the app does not implement `--screenshot` yet).
The skip is tolerant on purpose: A2 adds the flag, and until its source mentions it the harness will not
launch an application that would simply stay open.
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "fidelis-app"
RUN_PY = APP / "tools" / "run.py"
SKIP = 77

MIN_BYTES = 2_000
MIN_WIDTH, MIN_HEIGHT = 400, 300
MIN_STDDEV = 5.0  # of the 8-bit luminance
MIN_DISTINCT_COLOURS = 24


def supports_screenshot() -> bool:
    """True when the application sources mention the --screenshot flag."""
    for path in (APP / "src" / "main").rglob("*.java"):
        try:
            if '"--screenshot"' in path.read_text(encoding="utf-8", errors="replace"):
                return True
        except OSError:
            continue
    return False


def analyse_png(path: Path) -> dict:
    """Return metrics and a list of `problems` (empty means the screenshot is non-trivial)."""
    problems: list[str] = []
    info: dict = {"path": str(path)}
    if not path.is_file():
        return {**info, "problems": ["screenshot file was not written"]}
    size = path.stat().st_size
    info["bytes"] = size
    if size < MIN_BYTES:
        problems.append(f"file too small ({size} B < {MIN_BYTES})")
    try:
        from PIL import Image

        with Image.open(path) as image:
            image.load()
            info["size"] = list(image.size)
            if image.size[0] < MIN_WIDTH or image.size[1] < MIN_HEIGHT:
                problems.append(f"too small {image.size} (min {MIN_WIDTH}x{MIN_HEIGHT})")
            rgb = image.convert("RGB")
            # Sample on a grid so a 4K capture is cheap; blank detection does not need every pixel.
            step = max(1, min(rgb.size) // 200)
            pixels = [rgb.getpixel((x, y)) for y in range(0, rgb.size[1], step) for x in range(0, rgb.size[0], step)]
        luma = [0.299 * r + 0.587 * g + 0.114 * b for r, g, b in pixels]
        info["luminance_stddev"] = round(statistics.pstdev(luma), 3)
        info["distinct_colours_sampled"] = len(set(pixels))
        if info["luminance_stddev"] < MIN_STDDEV:
            problems.append(f"blank or near-uniform (luminance stddev {info['luminance_stddev']} < {MIN_STDDEV})")
        if info["distinct_colours_sampled"] < MIN_DISTINCT_COLOURS:
            problems.append(f"too few colours ({info['distinct_colours_sampled']} < {MIN_DISTINCT_COLOURS})")
    except Exception as exc:  # an unreadable PNG is a failure
        problems.append(f"cannot decode PNG: {exc}")
    info["problems"] = problems
    return info


def kill_tree(pid: int) -> None:
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True, check=False)
    else:
        os.kill(pid, 9)


def launch(out: Path, timeout: float) -> tuple[int | None, bool, float]:
    out.parent.mkdir(parents=True, exist_ok=True)
    out.unlink(missing_ok=True)
    cmd = [sys.executable, "-I", str(RUN_PY), "--isolated-home", "--", "--screenshot", str(out)]
    start = time.monotonic()
    process = subprocess.Popen(cmd, cwd=ROOT)  # only this process tree is ever killed
    try:
        code = process.wait(timeout=timeout)
        return code, False, time.monotonic() - start
    except subprocess.TimeoutExpired:
        kill_tree(process.pid)
        process.wait(timeout=15)
        return None, True, time.monotonic() - start


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=str(ROOT / "verification" / "runs" / "gui-smoke.png"))
    ap.add_argument("--timeout", type=float, default=120.0)
    ap.add_argument("--force", action="store_true", help="launch even if the sources do not mention --screenshot")
    ap.add_argument("--check-png", help="only analyse this PNG")
    args = ap.parse_args()
    out = Path(args.out)
    if args.check_png:
        report = analyse_png(Path(args.check_png))
        print(json.dumps(report, indent=1))
        return 1 if report["problems"] else 0
    if not args.force and not supports_screenshot():
        print("SKIP: the application does not implement --screenshot yet (no '\"--screenshot\"' in "
              "fidelis-app/src/main); the UI agent adds it. Use --force to launch anyway.")
        return SKIP
    code, timed_out, seconds = launch(out, args.timeout)
    report = analyse_png(out)
    report.update({"exit_code": code, "timed_out": timed_out, "seconds": round(seconds, 1)})
    if timed_out:
        report["problems"].append(f"application did not exit within {args.timeout:.0f} s")
    elif code not in (0, None):
        report["problems"].append(f"application exited with code {code}")
    print(json.dumps(report, indent=1))
    print("PASS" if not report["problems"] else "FAIL: " + "; ".join(report["problems"]))
    return 1 if report["problems"] else 0


if __name__ == "__main__":
    sys.exit(main())
