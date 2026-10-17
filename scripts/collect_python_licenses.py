"""Preserve installed package notices without vendoring their implementation."""

import argparse
import hashlib
import importlib.metadata as metadata
import json
import shutil
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("environment", choices=["core", "speech"])
args = parser.parse_args()
root = Path(__file__).resolve().parents[1] / "third_party"
destination = root / "licenses" / args.environment
inventory = []
for distribution in sorted(metadata.distributions(), key=lambda d: d.metadata["Name"].lower()):
    name, version = distribution.metadata["Name"], distribution.version
    if name == "fidelis":
        continue
    entry = {
        "name": name,
        "version": version,
        "license_expression": distribution.metadata.get("License-Expression"),
        "project_urls": distribution.metadata.get_all("Project-URL", []),
        "notices": [],
    }
    for relative in distribution.files or []:
        parts = relative.parts
        if not any(p.endswith(".dist-info") for p in parts):
            continue
        if not any(token in relative.name.lower() for token in ("license", "copying", "notice")):
            continue
        source = distribution.locate_file(relative)
        target = destination / f"{name}-{version}" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        entry["notices"].append(
            {
                "path": target.relative_to(root).as_posix(),
                "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
            }
        )
    inventory.append(entry)
(root / f"{args.environment}-packages.json").write_text(json.dumps(inventory, indent=2) + "\n")
print(f"Preserved available notices for {len(inventory)} {args.environment} packages")
