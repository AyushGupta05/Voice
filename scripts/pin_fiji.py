"""Explicit maintenance operation: review an installation, then record its hashes."""

import argparse
import json

from fidelis.adapters.fiji import runtime_files, sha256
from fidelis.config import ROOT, fiji_executable

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--accept-current-installation", action="store_true", required=True)
parser.parse_args()
executable = fiji_executable()
home = executable.parent
java_homes = [p.parent for p in (home / "java").rglob("release")]
if len(java_homes) != 1:
    raise RuntimeError("Expected exactly one bundled Java runtime; review the installation")
lock = {
    "upstream": "https://github.com/fiji/fiji",
    "distribution": "Fiji Windows x64 archive 20261013-1517, installed 2026-10-17",
    "source_url": (
        "https://downloads.imagej.net/fiji/archive/latest/20261013-1517/fiji-latest-win64-jdk.zip"
    ),
    "archive_sha256": ("9201287a86967d1dc93a5a8426ef8c5048c6186c50642c75164fb2c813794c54"),
    "pin_kind": "Installed file SHA-256 inventory; snapshot components, not a release-version pin",
    "launcher": executable.name,
    "java_home": java_homes[0].relative_to(home).as_posix(),
    "files": {p.relative_to(home).as_posix(): sha256(p) for p in runtime_files(home)},
}
(ROOT / "external/fiji.lock.json").write_text(json.dumps(lock, indent=2) + "\n")
print(f"Pinned {len(lock['files'])} runtime files")
