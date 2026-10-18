"""E7 macro replay (docs/FIDELIS-VERIFICATION.md): run an exported macro in the pinned reference Fiji (GUI mode)
and dump the final state with the same dumper as the baselines.

    python -I verification/replay.py --macro exported.ijm --run-dir DIR [--timeout 240]

The exported macro is used verbatim (the caller rewrites file paths); this script only prepends the dump
function, appends `dump("final")` and a done marker, and terminates the JVM. Results: DIR/out/final.json and
DIR/out/raw/final/. Exit code 0 when the run completed. Nothing here depends on the product.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import j12  # noqa: E402
import reference_runner as rr  # noqa: E402


def wrap(export: str, out_dir: Path) -> str:
    out = str(out_dir).replace("\\", "/")
    head = [
        "function dump(tag) {",
        '  r = eval("js", "var DUMP_OUT=\'' + out + '\'; var DUMP_TAG=\'" + tag + "\'; var DUMP_RAW=true;\\n"'
        f" + File.openAsString({j12.q(j12.DUMPER)}));",
        f'  File.append(tag + ": " + r, {j12.q(out_dir / "dumper.log")});',
        "}",
        "// ---- exported macro (verbatim) ----",
    ]
    tail = [
        "// ---- end of exported macro ----",
        'dump("final");',
        f'File.saveString("{{}}", {j12.q(out_dir / "done.json")});',
        'eval("script", "java.lang.Runtime.getRuntime().halt(0);");',
    ]
    return "\n".join(head) + "\n" + export.rstrip() + "\n" + "\n".join(tail) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--macro", required=True)
    ap.add_argument("--run-dir", required=True)
    ap.add_argument("--timeout", type=float, default=120)
    args = ap.parse_args()
    run_dir = Path(args.run_dir).resolve()
    out_dir = run_dir / "out"
    out_dir.mkdir(parents=True, exist_ok=True)
    macro = wrap(Path(args.macro).read_text(encoding="utf-8"), out_dir)
    result = rr.run_macro(macro, run_dir, mode="gui", timeout=args.timeout, done_name="out/done.json")
    final = out_dir / "final.json"
    ok = result.ok and final.exists()
    print(json.dumps({"ok": ok, "lock_hash": result.lock_hash, "seconds": round(result.seconds, 1),
                      "timed_out": result.timed_out, "notes": result.notes, "final": str(final)}))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
