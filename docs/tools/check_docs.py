"""Documentation consistency checker for the Fiji Modernisation Project docs (F04 §10, Phase 8).

Usage (from the repository root):
    .venv/Scripts/python.exe -I docs/tools/check_docs.py [--index]

Checks: front matter, ID definitions vs references (with range expansion), FR/NFR acceptance
criteria, design-doc requirement citations, relative links, decision statuses, empty headings and
placeholder text. With --index, (re)generates docs/INDEX.md from document front matter.
Exit code 1 if any ERROR is reported. Read-only except for --index.
"""

import csv
import re
import sys
from pathlib import Path

DOCS = Path(__file__).resolve().parents[1]
ROOT = DOCS.parent
ERR, WARN = [], []

ID_PATTERNS = {
    "FR": r"(?<![A-Za-z])FR-[A-Z]+-\d{3}",
    "NFR": r"NFR-[A-Z0-9]+-\d{3}",
    "CR": r"CR-A\d{2}-\d{2}",
    "DEC": r"DEC-\d{3}",
    "ADR": r"ADR-\d{3}",
    "PAR": r"PAR-\d{3}",
    "FX": r"FX-\d{3}",
    "CMP": r"CMP-\d{2}",
    "IF": r"IF-\d{2}",
    "UI": r"UI-\d{2}",
    "J": r"J-\d{2}",
    "RISK": r"(?<![A-Za-z])R-\d{3}",
    "DOC": r"\b(?:F0[1-4]|A[0-3]\d|D[0-2]\d)\b",
}
FM_KEYS = ["id", "title", "stage", "status", "priority", "source_revisions",
           "related_requirements", "related_decisions", "last_reviewed", "approval"]
ACCEPTED_ALLOWED = {"DEC-001", "DEC-002", "DEC-003", "DEC-004"}


def md_files():
    return sorted(p for p in DOCS.rglob("*.md") if "tools" not in p.parts)


def front_matter(text):
    m = re.match(r"^---\n(.*?)\n---\n", text.replace("\r\n", "\n"), re.S)
    if not m:
        return None
    fm = {}
    for line in m.group(1).splitlines():
        k, sep, v = line.partition(":")
        if sep:
            fm[k.strip()] = v.strip()
    return fm


def expand_ranges(text):
    """Expand 'FR-NL-001…013', 'FR-NL-001..013', 'FR-MEAS-001..005' into explicit IDs."""
    out = set()
    for m in re.finditer(r"(?<![A-Za-z])((?:N?FR)-[A-Z0-9]+-)(\d{3})\s*(?:…|\.\.)\s*(?:(?:N?FR)-[A-Z0-9]+-)?(\d{3})", text):
        prefix, a, b = m.group(1), int(m.group(2)), int(m.group(3))
        if a <= b <= a + 60:
            out.update(f"{prefix}{i:03d}" for i in range(a, b + 1))
    return out


def defined_ids():
    d = {k: set() for k in ID_PATTERNS}
    a26 = next(DOCS.rglob("A26-*.md"), None)
    a27 = next(DOCS.rglob("A27-*.md"), None)
    for path, key in ((a26, "FR"), (a27, "NFR")):
        if path:
            for m in re.finditer(r"^\|\s*(" + ID_PATTERNS[key] + r")\s*\|", path.read_text(encoding="utf-8"), re.M):
                d[key].add(m.group(1))
    for p in (DOCS / "01-analysis").rglob("A*.md"):
        for m in re.finditer(r"\*\*(" + ID_PATTERNS["CR"] + r")", p.read_text(encoding="utf-8")):
            d["CR"].add(m.group(1))
    dec = (DOCS / "DECISIONS.md").read_text(encoding="utf-8")
    d["DEC"].update(re.findall(r"^\|\s*(DEC-\d{3})\s*\|", dec, re.M))
    for p in (DOCS / "02-design").rglob("ADR-*.md"):
        d["ADR"].add(p.name[:7])
    for name, key, col in (("parity-matrix.csv", "PAR", "parity_id"), ("datasets.csv", "FX", "fixture_id"),
                           ("risks.csv", "RISK", "risk_id")):
        f = DOCS / "data" / name
        if f.exists():
            d[key].update(r[col] for r in csv.DictReader(f.open(encoding="utf-8")))
    d01 = next(DOCS.rglob("D01-*.md"), None)
    if d01:
        t = d01.read_text(encoding="utf-8")
        d["CMP"].update(re.findall(r"^\|\s*(CMP-\d{2})\s*\|", t, re.M))
        d["IF"].update(re.findall(r"^\|\s*(IF-\d{2})\s*\|", t, re.M))
    d12 = next(DOCS.rglob("D12-*.md"), None)
    if d12:
        d["UI"].update(re.findall(r"\b(UI-\d{2})\b", d12.read_text(encoding="utf-8")))
    d16 = next(DOCS.rglob("D16-*.md"), None)
    if d16:
        d["J"].update(re.findall(r"^#+\s*(J-\d{2})\b", d16.read_text(encoding="utf-8"), re.M))
        d["J"].update(re.findall(r"^\|\s*(J-\d{2})\s*\|", d16.read_text(encoding="utf-8"), re.M))
    for p in md_files():
        fm = front_matter(p.read_text(encoding="utf-8"))
        if fm and fm.get("id"):
            d["DOC"].add(fm["id"])
    return d


def check():
    defs = defined_ids()
    files = md_files() + sorted((DOCS / "data").glob("*.csv"))
    referenced = {k: set() for k in ID_PATTERNS}
    for p in files:
        text = p.read_text(encoding="utf-8")
        rel = p.relative_to(ROOT).as_posix()
        if p.suffix == ".md" and p.name not in ("INDEX.md", "README.md") and not p.name.startswith("README-") \
                and p.parent.name not in ("screenshots", "probes") and p.name not in (
                "architecture.md", "environment.md", "setup.md", "upstreams.md", "verification.md"):
            fm = front_matter(text)
            if p.name in ("DECISIONS.md", "WORK-PROGRESS.md", "GLOSSARY.md", "BIBLIOGRAPHY.md"):
                pass
            elif fm is None:
                ERR.append(f"{rel}: missing front matter")
            else:
                missing = [k for k in FM_KEYS if k not in fm]
                if missing:
                    WARN.append(f"{rel}: front matter missing {missing}")
                st = (fm.get("approval", "") + fm.get("status", "")).lower()
                if re.search(r"\bapproved\b", st) and "not" not in st and "awaiting" not in st:
                    ERR.append(f"{rel}: marked approved")
        expanded = expand_ranges(text)
        for key, pat in ID_PATTERNS.items():
            if key == "DOC":
                continue
            found = set(re.findall(pat, text)) | {e for e in expanded if re.fullmatch(pat, e)}
            referenced[key] |= found
            for ident in sorted(found - defs[key]):
                if key in ("UI", "J") and not defs[key]:
                    continue
                if key == "CR" and p.name.startswith("A26"):
                    pass
                ERR.append(f"{rel}: dangling {ident}")
        if p.suffix == ".md":
            for m in re.finditer(r"\]\(([^)#\s]+)(?:#[^)]*)?\)", text):
                target = m.group(1)
                if re.match(r"[a-z]+:", target):
                    continue
                if not (p.parent / target).resolve().exists():
                    ERR.append(f"{rel}: broken link {target}")
            lines = text.splitlines()
            for i, line in enumerate(lines[:-1]):
                if re.match(r"^#{2,}\s", line):
                    nxt = next((l for l in lines[i + 1:] if l.strip()), "")
                    if re.match(r"^#{1,%d}\s" % len(line.split()[0]), nxt):
                        WARN.append(f"{rel}:{i+1}: empty heading '{line.strip()}'")
            unquoted = re.sub(r'"[^"\n]*"', "", text)  # ignore quoted upstream text
            if re.search(r"\b(TODO|TBD|lorem ipsum|FIXME)\b", unquoted) and "check_docs" not in p.name:
                WARN.append(f"{rel}: placeholder text found")
    # FR/NFR acceptance criteria present
    for key, name in (("FR", "A26-*.md"), ("NFR", "A27-*.md")):
        path = next(DOCS.rglob(name), None)
        if not path:
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            m = re.match(r"^\|\s*(" + ID_PATTERNS[key] + r")\s*\|(.*)$", line)
            if m:
                cells = [c.strip() for c in m.group(2).split("|")]
                ac_idx = 3 if key == "FR" else 3
                if len(cells) <= ac_idx or len(cells[ac_idx]) < 8:
                    ERR.append(f"{path.name}: {m.group(1)} lacks acceptance criteria")
    # design docs cite requirements
    for p in (DOCS / "02-design").rglob("D*.md"):
        t = p.read_text(encoding="utf-8")
        if not re.search(ID_PATTERNS["FR"] + "|" + ID_PATTERNS["NFR"], t):
            ERR.append(f"{p.relative_to(ROOT).as_posix()}: design doc cites no FR/NFR")
    # decision statuses
    dec = (DOCS / "DECISIONS.md").read_text(encoding="utf-8")
    for m in re.finditer(r"^\|\s*(DEC-\d{3})\s*\|[^\n]*\*\*Accepted\*\*", dec, re.M):
        if m.group(1) not in ACCEPTED_ALLOWED:
            ERR.append(f"DECISIONS.md: {m.group(1)} marked Accepted without owner approval record")
    for p in (DOCS / "02-design").rglob("ADR-*.md"):
        fm = front_matter(p.read_text(encoding="utf-8")) or {}
        if "proposed" not in fm.get("status", "").lower():
            ERR.append(f"{p.name}: ADR status is not Proposed")
    # requirement coverage by design
    design_text = "\n".join(p.read_text(encoding="utf-8") for p in (DOCS / "02-design").rglob("*.md"))
    design_refs = set(re.findall(ID_PATTERNS["FR"] + "|" + ID_PATTERNS["NFR"], design_text)) | expand_ranges(design_text)
    trace = DOCS / "data" / "traceability.csv"
    if trace.exists():
        design_refs |= {r["requirement_id"] for r in csv.DictReader(trace.open(encoding="utf-8"))}
    for ident in sorted((defs["FR"] | defs["NFR"]) - design_refs):
        WARN.append(f"requirement not referenced by any design doc or traceability row: {ident}")
    return defs, referenced


def write_index():
    rows = []
    for p in md_files():
        fm = front_matter(p.read_text(encoding="utf-8"))
        if not fm or not re.match(r"^(F|A|D)\d{2}$|^ADR-\d{3}$", fm.get("id", "")):
            continue
        rows.append((fm["id"], fm.get("title", ""), fm.get("stage", ""), fm.get("status", ""),
                     fm.get("approval", ""), fm.get("last_reviewed", ""), fm.get("related_decisions", ""),
                     fm.get("related_requirements", ""), fm.get("source_revisions", ""), p.relative_to(DOCS).as_posix()))
    order = {"F": 0, "A": 1, "D": 2}
    rows.sort(key=lambda r: (order.get(r[0][0], 3), r[0]))
    out = ["# Documentation Index", "",
           "Generated by `docs/tools/check_docs.py --index` from each document's front matter. Do not edit the tables by hand; edit the front matter instead.", "",
           "Status vocabulary: F04 §5. **No document is approved.** Approval is the project owner's decision.", "",
           "| ID | Title | Stage | Status | Approval | Reviewed | Decisions | Requirements | Sources |", "|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        reqs = r[7] if len(r[7]) < 90 else r[7][:87] + "…]"
        src = r[8] if len(r[8]) < 70 else r[8][:67] + "…]"
        out.append(f"| [{r[0]}]({r[9]}) | {r[1]} | {r[2]} | {r[3]} | {r[4]} | {r[5]} | {r[6]} | {reqs} | {src} |")
    out += ["", "## Prototype-era records (preserved unedited; DEC-006)", "",
            "| ID | Document | Status |", "|---|---|---|",
            "| P01 | [architecture.md](architecture.md) | Prototype record; superseded for product architecture by A21/D01 |",
            "| P02 | [upstreams.md](upstreams.md) | Prototype record; superseded by F03 + `data/upstream-manifest.yaml` |",
            "| P03 | [verification.md](verification.md) | Prototype record; re-verified in F02 §3 |",
            "| P04 | [setup.md](setup.md) | Prototype setup (still valid for T0 demo) |",
            "| P05 | [environment.md](environment.md) | Prototype environment record; updated facts in A22 |",
            "", "## Registers, data and tooling", "",
            "| Artefact | Content | Owner doc |", "|---|---|---|",
            "| [DECISIONS.md](DECISIONS.md) | Decision register | F04 §7 |",
            "| [WORK-PROGRESS.md](WORK-PROGRESS.md) | Recovery checkpoint | — |",
            "| [00-foundation/GLOSSARY.md](00-foundation/GLOSSARY.md) | Glossary | F04 |",
            "| [01-analysis/references/BIBLIOGRAPHY.md](01-analysis/references/BIBLIOGRAPHY.md) | Sources with versions and access dates | F04 §4 |",
            "| [01-analysis/references/probes/](01-analysis/references/probes/) | Probe scripts and raw outputs (README-*.md per agent) | F04 §9 |",
            "| [01-analysis/references/screenshots/](01-analysis/references/screenshots/) | Real screenshots of the pinned Fiji | A04 |"]
    for f in sorted((DOCS / "data").glob("*")):
        owner = {"upstream-manifest.yaml": "F03", "commands.csv": "A08", "commands-core-parameters.csv": "A08",
                 "datasets.csv": "A29", "parity-matrix.csv": "A28", "risks.csv": "A31",
                 "traceability.csv": "D20", "verification-coverage.csv": "D18"}.get(f.name, "—")
        out.append(f"| [data/{f.name}](data/{f.name}) | Machine-readable catalogue | {owner} |")
    out.append("| [tools/check_docs.py](tools/check_docs.py) | Consistency checker and index generator | F04 §10 |")
    (DOCS / "INDEX.md").write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"INDEX.md written: {len(rows)} documents")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    if "--index" in sys.argv:
        write_index()
    defs, refs = check()
    print("Defined:", {k: len(v) for k, v in defs.items()})
    for w in WARN:
        print("WARN ", w)
    for e in ERR:
        print("ERROR", e)
    print(f"{len(ERR)} errors, {len(WARN)} warnings")
    sys.exit(1 if ERR else 0)
