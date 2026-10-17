"""Generate reference baselines for journey J-12 (docs A30 section 2, D18 3.5; verification brief T3).

    python -I verification/baseline.py --fixture FX-003 --fixture FX-001 [--modes gui headless]

For each fixture and mode the pinned Fiji runs the independent macro from verification/j12.py with the
dumper after every step. Results are stored under
    verification/baselines/<fixture>/<lock16>/<mode>/   (per-step JSON + reference.ijm + saved Tiff)
    verification/baselines/<fixture>/<lock16>/summary.json
Raw planes (raw/) are git-ignored. The key is the first 16 hex digits of the SHA-256 of
external/fiji.lock.json (CR-A30-04): changing the pin invalidates every baseline.

Reference mode (D18 3.1): GUI for the full chain, because Open..., Threshold..., Watershed and
Analyze Particles... are not all `pass` in commands.csv (DEC-043). A headless run of the headless-safe
prefix is generated too and compared with the GUI run (E1/E5 across modes).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import j12  # noqa: E402
import reference_runner as rr  # noqa: E402

ROOT = rr.ROOT
BASELINES = ROOT / "verification" / "baselines"
RUNS = ROOT / "verification" / "runs"
MANIFEST = ROOT / "fixtures" / "manifest.json"

FIXTURE_FILES = {"FX-001": "fixtures/two-squares.png", "FX-003": "fixtures/cache/FX-003/blobs.gif"}


def fixture_path(fixture_id: str) -> Path:
    path = ROOT / FIXTURE_FILES[fixture_id]
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    entry = next(f for f in manifest["fixtures"] if f["id"] == fixture_id)
    pinned = entry["files"][0]["sha256"]
    actual = rr.sha256(path)
    if pinned and pinned != actual:
        raise RuntimeError(f"{fixture_id}: SHA-256 mismatch ({actual} != {pinned})")
    return path


def lock16() -> str:
    return hashlib.sha256(rr.LOCK_PATH.read_bytes()).hexdigest()[:16]


def run_mode(fixture_id: str, mode: str, timeout: float) -> dict:
    path = fixture_path(fixture_id)
    run_dir = RUNS / fixture_id / mode
    if run_dir.exists():
        shutil.rmtree(run_dir)
    out_dir = run_dir / "out"
    out_dir.mkdir(parents=True)
    with_add = mode == "gui"  # `add` fails headless (A08 op 11)
    macro, chosen = j12.build_macro(path, out_dir, with_add=with_add,
                                    exit_with="halt" if mode == "gui" else "script")
    result = rr.run_macro(macro, run_dir, mode=mode, timeout=timeout, done_name="out/done.json")
    complete = (out_dir / "done.json").exists()
    dumped = sorted(p.stem for p in out_dir.glob("s*.json"))
    expected = ["s00_globals"] + [s.tag for s in chosen]
    meta = {
        "fixture": fixture_id,
        "fixture_sha256": rr.sha256(path),
        "mode": mode,
        "lock_sha256": result.lock_hash,
        "exit_code": result.exit_code,
        "seconds": round(result.seconds, 2),
        "timed_out": result.timed_out,
        "complete": complete,
        "dumped_steps": dumped,
        "missing_steps": [t for t in expected if t not in dumped],
        "notes": result.notes,
        "steps": [s.__dict__ for s in chosen],
        "with_add": with_add,
    }
    saved = list((out_dir / "saved").glob("*.tif"))
    if saved:
        meta["saved_tiff_sha256"] = rr.sha256(saved[0])
        meta["saved_tiff_bytes"] = saved[0].stat().st_size
    dest = BASELINES / fixture_id / lock16() / mode
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    for item in out_dir.iterdir():
        if item.name == "saved":
            continue
        target = dest / item.name
        shutil.copytree(item, target) if item.is_dir() else shutil.copy2(item, target)
    (dest / "reference.ijm").write_text(macro.replace(str(out_dir).replace("\\", "/"), "<OUT>"),
                                        encoding="utf-8")
    (dest / "run.json").write_text(json.dumps(meta, indent=1), encoding="utf-8")
    return meta


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixture", action="append", default=[])
    ap.add_argument("--modes", nargs="+", default=["gui", "headless"])
    ap.add_argument("--timeout", type=float, default=240)
    args = ap.parse_args()
    status = 0
    for fixture_id in args.fixture or ["FX-003", "FX-001"]:
        for mode in args.modes:
            t = time.monotonic()
            meta = run_mode(fixture_id, mode, args.timeout)
            ok = meta["complete"] and not meta["missing_steps"] and not meta["timed_out"]
            print(f"{fixture_id} {mode}: {'OK' if ok else 'FAILED'} in {time.monotonic() - t:.1f}s "
                  f"missing={meta['missing_steps']} notes={meta['notes']}")
            status |= 0 if ok else 1
    return status


if __name__ == "__main__":
    sys.exit(main())
