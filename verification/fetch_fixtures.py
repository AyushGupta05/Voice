"""Fetch verification fixtures into the git-ignored cache with SHA-256 verification.

Usage (repo root, repo venv python):
    python -I verification/fetch_fixtures.py --list
    python -I verification/fetch_fixtures.py --only FX-003 [--only FX-040 ...] [--pin]
    python -I verification/fetch_fixtures.py            # every fixture allowed by the policy

Rules (docs A29, D18 section 5):
  * Files go to fixtures/cache/<id>/<name>; zip members are extracted to fixtures/cache/<id>/extracted/.
  * A file whose digest is not pinned in fixtures/manifest.json is refused unless --pin is given
    (no trust on first use in CI). --pin records size and digest into the manifest.
  * A pinned digest that does not match is an error and the file is deleted.
  * Licence-restricted fixtures (CC BY-NC-SA) stay in the git-ignored cache and are never committed.
  * Fixtures whose files total more than --max-mb (default 50) are skipped.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "fixtures" / "manifest.json"
CACHE = ROOT / "fixtures" / "cache"
USER_AGENT = "fidelis-verification/1 (+https://github.com/reverez/Voice)"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def load_manifest() -> dict:
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


def save_manifest(doc: dict) -> None:
    MANIFEST.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")


def cache_dir(fixture_id: str) -> Path:
    return CACHE / fixture_id


def resolve(fixture_id: str, name: str) -> Path:
    """Path of a cached fixture file (used by the Python harness and mirrored in Java Fixtures)."""
    doc = load_manifest()
    for fx in doc["fixtures"]:
        if fx["id"] == fixture_id:
            if fx.get("local_path"):
                return ROOT / fx["local_path"]
            return cache_dir(fixture_id) / name
    raise KeyError(fixture_id)


def download(url: str, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(target.suffix + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=120) as response, tmp.open("wb") as out:
        while True:
            block = response.read(1 << 20)
            if not block:
                break
            out.write(block)
    tmp.replace(target)


def safe_extract(archive: Path, destination: Path) -> list[str]:
    destination.mkdir(parents=True, exist_ok=True)
    root = destination.resolve()
    names = []
    with zipfile.ZipFile(archive) as zf:
        for info in zf.infolist():
            out = (destination / info.filename).resolve()
            if root != out and root not in out.parents:
                raise RuntimeError(f"unsafe zip member {info.filename!r} in {archive.name}")
            if info.is_dir():
                continue
            out.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(info) as src, out.open("wb") as dst:
                dst.write(src.read())
            names.append(info.filename)
    return names


def fetch_fixture(fx: dict, pin: bool, max_bytes: int) -> str:
    """Return a one-line status; raise on a digest mismatch."""
    if fx.get("local_path"):
        path = ROOT / fx["local_path"]
        digest = sha256_file(path)
        entry = fx["files"][0]
        if entry["sha256"] is None:
            if not pin:
                return f"{fx['id']}: repo file digest not pinned (run with --pin)"
            entry["sha256"], entry["size_bytes"] = digest, path.stat().st_size
            fx["sha256"], fx["size_bytes"] = digest, path.stat().st_size
        elif entry["sha256"] != digest:
            raise RuntimeError(f"{fx['id']}: repo file changed ({digest} != {entry['sha256']})")
        return f"{fx['id']}: repo file ok ({digest[:12]})"
    declared = sum(f["size_bytes"] or 0 for f in fx["files"])
    if declared > max_bytes:
        return f"{fx['id']}: skipped, {declared} B exceeds the cache limit; metadata only"
    messages = []
    for entry in fx["files"]:
        target = cache_dir(fx["id"]) / entry["name"]
        if not target.exists():
            download(entry["url"], target)
            messages.append(f"{entry['name']} downloaded")
        digest, size = sha256_file(target), target.stat().st_size
        if entry["sha256"] is None:
            if not pin:
                raise RuntimeError(
                    f"{fx['id']}/{entry['name']}: digest not pinned; inspect then rerun with --pin"
                )
            entry["sha256"], entry["size_bytes"] = digest, size
            messages.append(f"pinned {digest[:12]}")
        elif entry["sha256"] != digest:
            target.unlink()
            raise RuntimeError(
                f"{fx['id']}/{entry['name']}: SHA-256 mismatch ({digest} != {entry['sha256']}); deleted"
            )
        else:
            messages.append(f"verified {digest[:12]}")
        if entry.get("extract"):
            members = safe_extract(target, cache_dir(fx["id"]) / "extracted")
            messages.append(f"extracted {len(members)} files")
    fx["size_bytes"] = sum(f["size_bytes"] or 0 for f in fx["files"]) or None
    first = fx["files"][0]["sha256"]
    fx["sha256"] = first
    return f"{fx['id']}: " + "; ".join(messages)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument("--list", action="store_true", help="list fixtures and cache state")
    parser.add_argument("--only", action="append", default=[], help="fixture id (repeatable)")
    parser.add_argument("--pin", action="store_true", help="record unpinned digests in the manifest")
    parser.add_argument("--max-mb", type=float, default=50.0)
    args = parser.parse_args()
    doc = load_manifest()
    fixtures = doc["fixtures"]
    if args.list:
        for fx in fixtures:
            present = (
                (ROOT / fx["local_path"]).exists()
                if fx.get("local_path")
                else all((cache_dir(fx["id"]) / f["name"]).exists() for f in fx["files"])
            )
            pinned = all(f["sha256"] for f in fx["files"])
            print(
                f"{fx['id']:7} {'cached ' if present else 'missing'} {'pinned  ' if pinned else 'unpinned'} "
                f"{fx['size_bytes'] or '?':>10} B  {fx['name']}  [{fx['licence'][:40]}]"
            )
        return 0
    selected = [f for f in fixtures if not args.only or f["id"] in args.only]
    unknown = set(args.only) - {f["id"] for f in fixtures}
    if unknown:
        print(f"unknown fixture ids: {sorted(unknown)}", file=sys.stderr)
        return 2
    failures = 0
    for fx in selected:
        try:
            print(fetch_fixture(fx, args.pin, int(args.max_mb * 1_000_000)))
        except Exception as exc:  # report every fixture, fail at the end
            failures += 1
            print(f"{fx['id']}: FAILED {exc}", file=sys.stderr)
    if args.pin:
        save_manifest(doc)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
