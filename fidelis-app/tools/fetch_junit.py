"""Fetch the pinned JUnit Platform console launcher (test runner) into fidelis-app/lib.

The jar is git-ignored; this script makes it reproducible by pinning version and SHA-256.
"""

from __future__ import annotations

import hashlib
import sys
import urllib.request
from pathlib import Path

VERSION = "1.11.4"
SHA256 = "b016ef6b1c3454d6d7c2c88ce081dabf289699686af6622d6e4e2e1b54b4a2fc"
URL = (f"https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/"
       f"{VERSION}/junit-platform-console-standalone-{VERSION}.jar")
DEST = Path(__file__).resolve().parents[1] / "lib" / f"junit-platform-console-standalone-{VERSION}.jar"


def main() -> None:
    DEST.parent.mkdir(parents=True, exist_ok=True)
    if not DEST.exists():
        print(f"[fetch] {URL}")
        urllib.request.urlretrieve(URL, DEST)
    digest = hashlib.sha256(DEST.read_bytes()).hexdigest()
    if digest != SHA256:
        DEST.unlink()
        sys.exit(f"[fetch] SHA-256 mismatch for {DEST.name}: {digest}")
    print(f"[fetch] OK {DEST.name} sha256={digest}")


if __name__ == "__main__":
    main()
