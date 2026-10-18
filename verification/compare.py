"""Comparators E1, E3, E4, E5, E6 over dumper output directories (docs/FIDELIS-VERIFICATION.md).

    python -I verification/compare.py REF_DIR CAND_DIR [--report report.json] [--steps s01_open ...]

Both directories hold per-step JSON written by verification/dumper.js (or an equivalent dump of the product).
Criteria are exact: pixel hashes, metadata strings, ROI vertices (float text round-trips exactly), mask hashes,
Results cells as doubles (compared as Python floats parsed from Java's Double.toString, i.e. bit-exact; NaN
equals NaN). Where one side has no data for a dimension (headless has no ROI Manager or Summary window) the
dimension is reported as `n/a`, never as a pass. Visual similarity is never evidence (CR-A30-08).
"""

from __future__ import annotations

import argparse
import array
import json
import math
import sys
from pathlib import Path

DIMENSIONS = ("E1", "E3", "E4", "E5", "E6")


def load_dir(directory: Path) -> dict[str, dict]:
    return {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in sorted(directory.glob("s*.json"))}


def _eq_num(a: str, b: str) -> bool:
    fa, fb = float(a), float(b)
    return (math.isnan(fa) and math.isnan(fb)) or fa == fb


def _raw_diff(ref_dir: Path, cand_dir: Path, tag: str, image: dict, plane: int, bits: int) -> dict | None:
    name = f"{image['index']}_{plane}.raw"
    ra, ca = ref_dir / "raw" / tag / name, cand_dir / "raw" / tag / name
    if not (ra.exists() and ca.exists()):
        return None
    data_a, data_b = ra.read_bytes(), ca.read_bytes()
    width = image["width"]
    if len(data_a) != len(data_b):
        return {"length_ref": len(data_a), "length_cand": len(data_b)}
    code, size = {8: ("B", 1), 16: ("H", 2), 32: ("f", 4), 24: ("I", 4)}[bits]
    a, b = array.array(code), array.array(code)
    a.frombytes(data_a)
    b.frombytes(data_b)
    if sys.byteorder == "big":
        a.byteswap()
        b.byteswap()
    differing, max_abs, first = 0, 0.0, None
    for i, (x, y) in enumerate(zip(a, b)):
        if x != y and not (code == "f" and math.isnan(x) and math.isnan(y)):
            differing += 1
            if bits != 24:
                max_abs = max(max_abs, abs(float(x) - float(y)))
            if first is None:
                first = {"x": i % width, "y": i // width, "ref": x, "cand": y}
    return {"differing_pixels": differing, "max_abs_diff": max_abs, "first": first}


def e1(tag: str, ref: dict, cand: dict, ref_dir: Path, cand_dir: Path) -> dict:
    out = {"status": "pass", "details": []}
    if len(ref["images"]) != len(cand["images"]):
        return {"status": "fail", "details": [f"image count {len(ref['images'])} != {len(cand['images'])}"]}
    for ia, ib in zip(ref["images"], cand["images"]):
        if "dumpError" in ia or "dumpError" in ib:
            out["status"] = "fail"
            out["details"].append(f"dump error: {ia.get('dumpError')} / {ib.get('dumpError')}")
            continue
        if ia["planeSha256"] != ib["planeSha256"] or ia["allPlanesSha256"] != ib["allPlanesSha256"]:
            out["status"] = "fail"
            diffs = []
            for p, (ha, hb) in enumerate(zip(ia["planeSha256"], ib["planeSha256"]), 1):
                if ha != hb:
                    diffs.append({"plane": p, "raw": _raw_diff(ref_dir, cand_dir, tag, ia, p, ia["bitDepth"])})
            out["details"].append({"image": ia["title"], "planes": diffs})
    return out


E3_FIELDS = ("title", "type", "bitDepth", "width", "height", "channels", "slices", "frames", "stackSize",
             "invertedLut", "lutSha256", "displayRange", "threshold", "calibration", "infoSha256",
             "overlayCount")


def e3(ref: dict, cand: dict, ignore: tuple[str, ...] = ()) -> dict:
    out = {"status": "pass", "details": []}
    if len(ref["images"]) != len(cand["images"]):
        return {"status": "fail", "details": ["image count differs"]}
    for ia, ib in zip(ref["images"], cand["images"]):
        for field in E3_FIELDS:
            if field in ignore:
                continue
            if ia.get(field) != ib.get(field):
                out["status"] = "fail"
                out["details"].append({"image": ia["title"], "field": field, "ref": ia.get(field),
                                       "cand": ib.get(field)})
    if ref.get("currentImageTitle") != cand.get("currentImageTitle") and "title" not in ignore:
        out["status"] = "fail"
        out["details"].append({"field": "currentImageTitle", "ref": ref.get("currentImageTitle"),
                               "cand": cand.get("currentImageTitle")})
    return out


def _roi_equal(a: dict | None, b: dict | None) -> list[str]:
    if a is None and b is None:
        return []
    if a is None or b is None:
        return ["one side has no ROI"]
    return [k for k in ("type", "name", "bounds", "position", "strokeWidth", "xs", "ys", "maskBounds", "maskSha256")
            if a.get(k) != b.get(k)]


def e4(ref: dict, cand: dict) -> dict:
    out = {"status": "pass", "details": []}
    compared = False
    for ia, ib in zip(ref["images"], cand["images"]):
        compared = True
        diff = _roi_equal(ia.get("roi"), ib.get("roi"))
        if diff:
            out["status"] = "fail"
            out["details"].append({"image": ia["title"], "image_roi_fields": diff})
    ra, rb = ref.get("roiManager"), cand.get("roiManager")
    if ra is None or rb is None:
        if ra is not rb:
            out["details"].append("ROI Manager available on one side only (n/a)")
            if out["status"] == "pass":
                out["status"] = "n/a" if not compared else "pass-partial"
    else:
        if len(ra) != len(rb):
            out["status"] = "fail"
            out["details"].append(f"ROI Manager count {len(ra)} != {len(rb)}")
        else:
            for i, (x, y) in enumerate(zip(ra, rb)):
                diff = _roi_equal(x, y)
                if diff:
                    out["status"] = "fail"
                    out["details"].append({"roi_index": i, "fields": diff})
    return out


def _table_diff(a: dict | None, b: dict | None) -> list:
    if a is None and b is None:
        return []
    if a is None or b is None or "dumpError" in (a or {}) or "dumpError" in (b or {}):
        return ["table missing or unreadable on one side"]
    diffs = []
    if a["rows"] != b["rows"]:
        diffs.append(f"rows {a['rows']} != {b['rows']}")
    if [c["name"] for c in a["columns"]] != [c["name"] for c in b["columns"]]:
        diffs.append("column names/order differ")
        return diffs
    for ca, cb in zip(a["columns"], b["columns"]):
        if "labels" in ca:
            if ca["labels"] != cb["labels"]:
                diffs.append(f"{ca['name']}: labels differ")
            continue
        bad = [i for i, (x, y) in enumerate(zip(ca["values"], cb["values"])) if not _eq_num(x, y)]
        # exact double equality; the hex strings make -0.0/NaN payload differences visible
        bad += [i for i, (x, y) in enumerate(zip(ca["hex"], cb["hex"])) if x != y and i not in bad]
        if bad:
            diffs.append({"column": ca["name"], "rows": sorted(bad)[:10],
                          "ref": [ca["values"][i] for i in sorted(bad)[:3]],
                          "cand": [cb["values"][i] for i in sorted(bad)[:3]]})
    return diffs


def e5(ref: dict, cand: dict) -> dict:
    diffs = _table_diff(ref.get("results"), cand.get("results"))
    summary_ref, summary_cand = ref.get("summary"), cand.get("summary")
    if summary_ref is None and summary_cand is None:
        summary_note = None
    elif summary_ref is None or summary_cand is None:
        summary_note = "Summary table available on one side only (n/a)"
    else:
        summary_note = None
        diffs += [{"summary": d} for d in _table_diff(summary_ref, summary_cand)]
    out = {"status": "fail" if diffs else "pass", "details": list(diffs)}
    if summary_note:
        out["details"].append(summary_note)
        if not diffs:
            out["status"] = "pass-partial"
    return out


def e6(ref: dict, cand: dict) -> dict:
    diffs = {k: [ref["globals"].get(k), cand["globals"].get(k)]
             for k in set(ref["globals"]) | set(cand["globals"]) if ref["globals"].get(k) != cand["globals"].get(k)}
    return {"status": "fail" if diffs else "pass", "details": diffs}


def compare(ref_dir: Path, cand_dir: Path, steps: list[str] | None = None, ignore: tuple[str, ...] = ()) -> dict:
    ref, cand = load_dir(ref_dir), load_dir(cand_dir)
    tags = steps or [t for t in ref if t in cand]
    report = {"ref": str(ref_dir), "cand": str(cand_dir), "steps": {}, "missing_in_cand":
              [t for t in (steps or ref) if t not in cand], "summary": {}}
    for tag in tags:
        if tag not in ref or tag not in cand:
            continue
        a, b = ref[tag], cand[tag]
        report["steps"][tag] = {"E1": e1(tag, a, b, ref_dir, cand_dir), "E3": e3(a, b, ignore),
                                "E4": e4(a, b), "E5": e5(a, b), "E6": e6(a, b)}
    for dim in DIMENSIONS:
        statuses = [s[dim]["status"] for s in report["steps"].values()]
        report["summary"][dim] = {"pass": statuses.count("pass"), "fail": statuses.count("fail"),
                                  "partial_or_na": len(statuses) - statuses.count("pass") - statuses.count("fail")}
    report["ok"] = (all(report["summary"][d]["fail"] == 0 for d in DIMENSIONS) and not report["missing_in_cand"]
                    and bool(report["steps"]))
    return report


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("ref")
    ap.add_argument("cand")
    ap.add_argument("--report")
    ap.add_argument("--steps", nargs="*")
    ap.add_argument("--ignore", nargs="*", default=[], help="E3 fields to ignore (explicit, per fixture)")
    args = ap.parse_args()
    report = compare(Path(args.ref), Path(args.cand), args.steps, tuple(args.ignore))
    text = json.dumps(report, indent=1)
    if args.report:
        Path(args.report).write_text(text, encoding="utf-8")
    print(json.dumps({"ok": report["ok"], "summary": report["summary"],
                      "missing_in_cand": report["missing_in_cand"]}, indent=1))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
