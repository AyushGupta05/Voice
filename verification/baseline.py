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

Checking instead of (re)generating, e.g. on another machine or platform (macOS runs the same pinned components,
see reference_runner.py), never overwrites a stored baseline:
    python -I verification/baseline.py --check --fixture FX-003 [--modes gui]
        runs into verification/runs/check/<key>/<mode> and compares every step with the stored per-step dumps (E1, E3-E6)
        and with the committed summary.json; exit 1 on any difference.
    python -I verification/baseline.py --check --fill-missing --fixture FX-030 --fixture FX-031 --modes gui
        additionally stores the per-step dumps of a mode that has none (FX-030/031 keep only summary.json in Git)
        when, and only when, the run's summary equals the committed one; differences are reported, nothing stored.
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
import compare  # noqa: E402
import j12  # noqa: E402
import reference_runner as rr  # noqa: E402

ROOT = rr.ROOT
BASELINES = ROOT / "verification" / "baselines"
RUNS = ROOT / "verification" / "runs"
MANIFEST = ROOT / "fixtures" / "manifest.json"

# fixture id -> (file the macro opens, file whose SHA-256 is pinned in the manifest)
FIXTURE_FILES = {
    "FX-001": ("fixtures/two-squares.png", "fixtures/two-squares.png"),
    "FX-003": ("fixtures/cache/FX-003/blobs.gif", "fixtures/cache/FX-003/blobs.gif"),
    # E9 reporting only (licence CC BY-NC-SA; derived numbers only are stored)
    "FX-030": ("fixtures/cache/FX-030/extracted/human_ht29_colon_cancer_1_images/AS_09125_050118150001_A03f00d0.tif",
               "fixtures/cache/FX-030/BBBC001_v1_images_tif.zip"),
    "FX-031": ("fixtures/cache/FX-031/extracted/synthetic_000_images/1GRAY.tif",
               "fixtures/cache/FX-031/BBBC004_v1_000_images.zip"),
}


def fixture_path(fixture_id: str) -> Path:
    opened, pinned_file = FIXTURE_FILES[fixture_id]
    path = ROOT / opened
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    entry = next(f for f in manifest["fixtures"] if f["id"] == fixture_id)
    pinned = entry["files"][0]["sha256"]
    actual = rr.sha256(ROOT / pinned_file)
    if pinned and pinned != actual:
        raise RuntimeError(f"{fixture_id}: SHA-256 mismatch ({actual} != {pinned})")
    return path


def lock16() -> str:
    return hashlib.sha256(rr.LOCK_PATH.read_bytes()).hexdigest()[:16]


def run_mode(fixture_id: str, mode: str, timeout: float, threshold: str = j12.THRESHOLD,
             key: str | None = None) -> dict:
    meta, out_dir, macro = run_reference(fixture_id, mode, timeout, threshold, key)
    store(meta, out_dir, macro, key or fixture_id, mode)
    return meta


def run_reference(fixture_id: str, mode: str, timeout: float, threshold: str = j12.THRESHOLD,
                  key: str | None = None, runs: Path = RUNS) -> tuple[dict, Path, str]:
    """Run the reference macro into <runs>/<key>/<mode> (git-ignored); stores no baseline."""
    path = fixture_path(fixture_id)
    key = key or fixture_id
    run_dir = runs / key / mode
    if run_dir.exists():
        shutil.rmtree(run_dir)
    out_dir = run_dir / "out"
    out_dir.mkdir(parents=True)
    with_add = mode == "gui"  # `add` fails headless (A08 op 11)
    macro, chosen = j12.build_macro(path, out_dir, with_add=with_add,
                                    exit_with="halt" if mode == "gui" else "script", threshold=threshold)
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
        "threshold": threshold,
    }
    saved = list((out_dir / "saved").glob("*.tif"))
    if saved:
        meta["saved_tiff_sha256"] = rr.sha256(saved[0])
        meta["saved_tiff_bytes"] = saved[0].stat().st_size
    (out_dir / "run.json").write_text(json.dumps(meta, indent=1), encoding="utf-8")
    return meta, out_dir, macro


def store(meta: dict, out_dir: Path, macro: str, key: str, mode: str) -> Path:
    """Copy a run's dumps into verification/baselines/<key>/<lock16>/<mode> (replacing that mode's folder)."""
    dest = BASELINES / key / lock16() / mode
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
    return dest


def column(table: dict | None, name: str) -> list[str] | None:
    if not table or "columns" not in table:
        return None
    return next((c["values"] for c in table["columns"] if c["name"] == name), None)


def write_summary(fixture_id: str, key: str | None = None) -> Path:
    """Compact, reviewable summary of the stored modes for one fixture (hashes and numbers only)."""
    base = BASELINES / (key or fixture_id) / lock16()
    summary = summarise(fixture_id, key, {mode: base / mode for mode in ("gui", "headless")})
    path = base / "summary.json"
    path.write_text(json.dumps(summary, indent=1), encoding="utf-8")
    return path


def summarise(fixture_id: str, key: str | None, folders: dict[str, Path]) -> dict:
    """The summary.json content for the dump folders of each mode (folders without a run.json are skipped)."""
    summary: dict = {"fixture": fixture_id, "key": key or fixture_id, "lock16": lock16(), "modes": {}}
    for mode, folder in folders.items():
        if not (folder / "run.json").exists():
            continue
        run = json.loads((folder / "run.json").read_text())
        steps = []
        for tag in ["s00_globals"] + [st["tag"] for st in run["steps"]]:
            dump = json.loads((folder / f"{tag}.json").read_text())
            steps.append({
                "tag": tag,
                "images": [{"title": i["title"], "type": i["type"], "size": [i["width"], i["height"]],
                            "allPlanesSha256": i["allPlanesSha256"], "invertedLut": i["invertedLut"],
                            "threshold": i["threshold"]} for i in dump["images"]],
                "resultsRows": (dump["results"] or {}).get("rows"),
                "roiManagerCount": None if dump["roiManager"] is None else len(dump["roiManager"]),
                "globals": dump["globals"],
            })
        measure = json.loads((folder / "s08_measure.json").read_text())
        particles = json.loads((folder / "s09_analyze_particles.json").read_text())
        summary_table = particles.get("summary")
        summary["modes"][mode] = {
            "reference_mode_rationale": ("authoritative: GUI because Open.../Threshold.../Watershed are not "
                                         "headless_probe=pass and Analyze Particles... add fails headless")
            if mode == "gui" else "cross-check: headless-safe prefix; Analyze Particles without `add`",
            "run": {k: run[k] for k in ("exit_code", "seconds", "timed_out", "complete", "fixture_sha256",
                                         "with_add", "saved_tiff_sha256", "saved_tiff_bytes") if k in run},
            "measure_row": {c["name"]: c["values"][0] for c in measure["results"]["columns"]
                            if c.get("values")},
            "particle_count_results_rows": (particles["results"] or {}).get("rows"),
            "particle_summary": None if not summary_table or "columns" not in summary_table else {
                c["name"]: c["values"][0] for c in summary_table["columns"] if c.get("values")},
            "steps": steps,
        }
    return summary


def _differences(a, b, where: str = "") -> list[str]:
    """Paths where two JSON values differ (run timing excluded: it is not a result)."""
    if isinstance(a, dict) and isinstance(b, dict):
        out = []
        for k in sorted(set(a) | set(b)):
            if k == "seconds":
                continue
            if k not in a or k not in b:
                out.append(f"{where}/{k}: only in {'stored' if k in a else 'run'}")
            else:
                out += _differences(a[k], b[k], f"{where}/{k}")
        return out
    if isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            return [f"{where}: {len(a)} vs {len(b)} entries"]
        return [d for i, (x, y) in enumerate(zip(a, b)) for d in _differences(x, y, f"{where}[{i}]")]
    return [] if a == b else [f"{where}: stored {a!r} != run {b!r}"]


def check(fixture_id: str, mode: str, timeout: float, threshold: str = j12.THRESHOLD, key: str | None = None,
          fill_missing: bool = False) -> list[str]:
    """Run the reference without touching the stored baseline and list every difference from it."""
    key = key or fixture_id
    stored = BASELINES / key / lock16() / mode
    stored_summary = BASELINES / key / lock16() / "summary.json"
    if not (stored / "run.json").exists() and not stored_summary.exists():
        return [f"{key} {mode}: nothing stored under lock {lock16()} to check against"]
    # a check never replaces verification/runs/<key>/<mode>: that is the run the stored baseline came from, which
    # test_verification.py reads the saved Tiff of
    meta, out_dir, macro = run_reference(fixture_id, mode, timeout, threshold, key, runs=RUNS / "check")
    problems = []
    if not meta["complete"] or meta["missing_steps"] or meta["timed_out"]:
        problems.append(f"run incomplete: missing={meta['missing_steps']} timed_out={meta['timed_out']}")
    if (stored / "run.json").exists():
        report = compare.compare(stored, out_dir)
        if not report["ok"]:
            failed = {t: {d: r["status"] for d, r in dims.items() if r["status"] == "fail"}
                      for t, dims in report["steps"].items()}
            problems.append(f"per-step dumps differ: { {t: f for t, f in failed.items() if f} } "
                            f"missing={report['missing_in_cand']}")
        meta_stored = json.loads((stored / "run.json").read_text())
        problems += [f"run.json{d}" for d in _differences(
            {k: meta_stored.get(k) for k in ("fixture_sha256", "dumped_steps", "saved_tiff_sha256", "saved_tiff_bytes")},
            {k: meta.get(k) for k in ("fixture_sha256", "dumped_steps", "saved_tiff_sha256", "saved_tiff_bytes")})]
    if stored_summary.exists():
        want = json.loads(stored_summary.read_text()).get("modes", {}).get(mode)
        got = summarise(fixture_id, key, {mode: out_dir})["modes"].get(mode)
        if want is None:
            problems.append(f"summary.json has no {mode} entry")
        else:
            problems += [f"summary.json/{mode}{d}" for d in _differences(want, got)]
    if not problems and fill_missing and not (stored / "run.json").exists():
        dest = store(meta, out_dir, macro, key, mode)
        print(f"{key} {mode}: per-step dumps stored in {dest} (summary.json unchanged)")
    return problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixture", action="append", default=[])
    ap.add_argument("--modes", nargs="+", default=["gui", "headless"])
    ap.add_argument("--timeout", type=float, default=240)
    ap.add_argument("--threshold", default=j12.THRESHOLD, help='setAutoThreshold method string (default "Otsu dark")')
    ap.add_argument("--key", help="baseline folder name when a variant is stored (default: the fixture id)")
    ap.add_argument("--check", action="store_true", help="compare a fresh run with the stored baseline; store nothing")
    ap.add_argument("--fill-missing", action="store_true",
                    help="with --check: store per-step dumps of a mode that has none when the summary matches")
    args = ap.parse_args()
    status = 0
    if args.check:
        for fixture_id in args.fixture or ["FX-003", "FX-001"]:
            for mode in args.modes:
                t = time.monotonic()
                problems = check(fixture_id, mode, args.timeout, args.threshold, args.key, args.fill_missing)
                print(f"{args.key or fixture_id} {mode}: {'MATCHES the stored baseline' if not problems else 'DIFFERS'} "
                      f"in {time.monotonic() - t:.1f}s")
                for line in problems[:40]:
                    print("   ", line)
                status |= 1 if problems else 0
        return status
    for fixture_id in args.fixture or ["FX-003", "FX-001"]:
        for mode in args.modes:
            t = time.monotonic()
            meta = run_mode(fixture_id, mode, args.timeout, args.threshold, args.key)
            ok = meta["complete"] and not meta["missing_steps"] and not meta["timed_out"]
            print(f"{fixture_id} {mode}: {'OK' if ok else 'FAILED'} in {time.monotonic() - t:.1f}s "
                  f"missing={meta['missing_steps']} notes={meta['notes']}")
            status |= 0 if ok else 1
        write_summary(fixture_id, args.key)
    return status


if __name__ == "__main__":
    sys.exit(main())
