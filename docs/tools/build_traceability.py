"""Build docs/data/traceability.csv (D20) from the authoritative sources.

Sources: A26/A27 requirement tables (ID, priority, sources), D01 CMP table (key requirements),
design documents D01-D20 and ADRs (citations), parity-matrix.csv (requirements column),
verification-coverage.csv (coverage rows and journeys).
The output is derived data: regenerate it whenever any source changes. Do not edit it by hand.

Usage: .venv/Scripts/python.exe -I docs/tools/build_traceability.py
"""

import csv
import re
import sys
from pathlib import Path

DOCS = Path(__file__).resolve().parents[1]
FR = r"(?<![A-Za-z])N?FR-[A-Z0-9]+-\d{3}"


def expand(text):
    ids = set(re.findall(FR, text))
    for m in re.finditer(
        r"(?<![A-Za-z])((?:N?FR)-[A-Z0-9]+-)(\d{3})\s*(?:…|\.\.)\s*(?:(?:N?FR)-[A-Z0-9]+-)?(\d{3})",
        text,
    ):
        a, b = int(m.group(2)), int(m.group(3))
        if a <= b <= a + 60:
            ids.update(f"{m.group(1)}{i:03d}" for i in range(a, b + 1))
    return ids


def requirements():
    reqs = {}
    for name in ("A26-*.md", "A27-*.md"):
        path = next(DOCS.rglob(name))
        for line in path.read_text(encoding="utf-8").splitlines():
            m = re.match(r"^\|\s*(N?FR-[A-Z0-9]+-\d{3})\s*\|(.*)$", line)
            if not m:
                continue
            cells = [c.strip() for c in m.group(2).split("|")]
            # FR columns: Requirement | Rationale | Pri | Acceptance | Sources | Deps
            # NFR columns: Requirement | Rationale | Pri | Acceptance | Sources
            reqs[m.group(1)] = {
                "statement": cells[0],
                "priority": cells[2],
                "sources": cells[4] if len(cells) > 4 else "",
            }
    return reqs


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    reqs = requirements()
    design_docs = {}
    for p in sorted((DOCS / "02-design").rglob("*.md")):
        did = p.name.split("-")[0] if p.name.startswith("D") else p.name[:7]
        for rid in expand(p.read_text(encoding="utf-8")):
            design_docs.setdefault(rid, set()).add(did)
    cmps = {}
    d01 = next(DOCS.rglob("D01-*.md")).read_text(encoding="utf-8")
    for line in d01.splitlines():
        m = re.match(r"^\|\s*(CMP-\d{2})\s*\|(.*)$", line)
        if m:
            for rid in expand(m.group(2)):
                cmps.setdefault(rid, set()).add(m.group(1))
    parity = {}
    for r in csv.DictReader((DOCS / "data" / "parity-matrix.csv").open(encoding="utf-8")):
        for rid in expand(r["requirements"]):
            parity.setdefault(rid, set()).add(r["parity_id"])
    cov, journeys = {}, {}
    for r in csv.DictReader((DOCS / "data" / "verification-coverage.csv").open(encoding="utf-8")):
        cov.setdefault(r["requirement_id"], set()).add(r["coverage_id"])
        for j in re.findall(r"J-\d{2}", r.get("journey", "")):
            journeys.setdefault(r["requirement_id"], set()).add(j)
    out = DOCS / "data" / "traceability.csv"
    gaps = []
    with out.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(
            [
                "requirement_id",
                "priority",
                "source_crs_or_docs",
                "design_docs",
                "components",
                "parity_ids",
                "verification_ids",
                "journeys",
                "trace_status",
            ]
        )
        for rid in sorted(reqs, key=lambda x: (x.startswith("NFR"), x)):
            r = reqs[rid]
            dd = sorted(design_docs.get(rid, set()))
            vv = sorted(cov.get(rid, set()))
            status = (
                "complete"
                if dd and (vv or not re.search(r"\b(MVP|HACK)\b", r["priority"]))
                else "gap"
            )
            if status == "gap":
                gaps.append(rid)
            w.writerow(
                [
                    rid,
                    r["priority"],
                    "; ".join(
                        re.findall(r"CR-A\d{2}-\d{2}|D\d{2}[^;,)]*\(gap\)|D\d{2}", r["sources"])
                    )
                    or r["sources"],
                    ";".join(dd),
                    ";".join(sorted(cmps.get(rid, set()))),
                    ";".join(sorted(parity.get(rid, set()))),
                    ";".join(vv),
                    ";".join(sorted(journeys.get(rid, set()))),
                    status,
                ]
            )
    print(f"{len(reqs)} requirements -> {out.name}; gaps: {gaps}")


if __name__ == "__main__":
    main()
