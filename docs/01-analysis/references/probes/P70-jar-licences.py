"""P70 - aggregate declared licences of every jar under Fiji/jars and Fiji/plugins.

Read-only probe. Uses `unzip -Z1` (list) and `unzip -p` (stream to stdout) via
subprocess; nothing is ever extracted to disk, and the Fiji directory is never
written to.

Sources examined per jar, in this order:
  pom      - every META-INF/maven/**/pom.xml: <licenses><license><name>
  manifest - META-INF/MANIFEST.MF: Bundle-License header
  licfile  - META-INF/LICENSE*, META-INF/LICENCE*, LICENSE*, COPYING* at jar root
             or META-INF (first 4 KB classified by keyword)
A licence family is then assigned by keyword matching (see FAMILY_RULES).

Usage (Git Bash):
  python -I P70-jar-licences.py <FijiDir> <out.csv> <summary.txt>
"""
from __future__ import annotations

import csv
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

FAMILY_RULES = [
    # order matters: first match wins for each declared name
    ("CC0/Public domain", r"public domain|cc0|unlicense"),
    ("LGPL", r"lgpl|lesser general public|library general public"),
    ("AGPL", r"agpl|affero"),
    ("GPL+CPE", r"classpath"),
    ("GPL-3", r"(gpl|general public license|gnu public license).{0,40}(v ?3|version 3|3\.0|\s3\b)|gpl-?3"),
    ("GPL-2", r"(gpl|general public license|gnu public license).{0,40}(v ?2|version 2|2\.0|\s2\b)|gpl-?2"),
    ("GPL (unversioned)", r"\bgpl\b|general public license|gnu public license"),
    ("CC-BY", r"creative commons attribution"),
    ("Apache-2.0", r"apache"),
    ("BSD-2", r"simplified bsd|bsd[- ]2|2-clause|freebsd"),
    ("BSD-3", r"new bsd|bsd[- ]3|3-clause|revised bsd|modified bsd|edl|eclipse distribution"),
    ("BSD (unversioned)", r"\bbsd\b|0bsd"),
    ("MIT", r"\bmit\b|expat|x11"),
    ("EPL", r"eclipse public|\bepl\b"),
    ("CDDL", r"cddl|common development and distribution"),
    ("MPL", r"mozilla|\bmpl\b"),
    ("ISC", r"\bisc\b"),
    ("zlib/libpng", r"zlib|libpng"),
    ("JSON", r"json license"),
    ("Bouncy Castle", r"bouncy castle"),
    ("CPL", r"common public license"),
    ("Other", r"."),
]


def family(name: str) -> str:
    low = name.lower()
    for fam, pat in FAMILY_RULES:
        if re.search(pat, low):
            return fam
    return "Other"


def unzip_list(jar: Path) -> list[str]:
    r = subprocess.run(["unzip", "-Z1", str(jar)], capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    return r.stdout.splitlines()


def unzip_read(jar: Path, member: str) -> str:
    r = subprocess.run(["unzip", "-p", str(jar), member], capture_output=True)
    return r.stdout.decode("utf-8", errors="replace")


LIC_BLOCK = re.compile(r"<licenses>(.*?)</licenses>", re.S)
LIC_NAME = re.compile(r"<license>.*?<name>(.*?)</name>", re.S)
GAV_G = re.compile(r"<groupId>(.*?)</groupId>")
GAV_A = re.compile(r"<artifactId>(.*?)</artifactId>")
GAV_V = re.compile(r"<version>(.*?)</version>")


def pom_gav_and_licences(text: str, member: str) -> tuple[str, list[str]]:
    # strip <parent>, <dependencies>, <build>, <profiles> blocks before reading own GAV
    body = re.sub(r"<parent>.*?</parent>", "", text, flags=re.S)
    parent = re.search(r"<parent>(.*?)</parent>", text, re.S)
    for tag in ("dependencies", "dependencyManagement", "build", "profiles",
                "reporting", "developers", "contributors", "licenses", "scm",
                "properties", "repositories", "pluginRepositories", "mailingLists",
                "issueManagement", "ciManagement", "distributionManagement"):
        body = re.sub(rf"<{tag}>.*?</{tag}>", "", body, flags=re.S)
    parts = member.split("/")  # META-INF/maven/<g>/<a>/pom.xml
    g = GAV_G.search(body)
    a = GAV_A.search(body)
    v = GAV_V.search(body)
    if not g and parent:
        g = GAV_G.search(parent.group(1))
    if not v and parent:
        v = GAV_V.search(parent.group(1))
    gid = g.group(1).strip() if g else (parts[2] if len(parts) > 4 else "?")
    aid = a.group(1).strip() if a else (parts[3] if len(parts) > 4 else "?")
    ver = v.group(1).strip() if v else "?"
    lics: list[str] = []
    blk = LIC_BLOCK.search(text)
    if blk:
        lics = [re.sub(r"\s+", " ", n).strip() for n in LIC_NAME.findall(blk.group(1))]
    return f"{gid}:{aid}:{ver}", lics


def classify_text(text: str) -> str:
    head = text[:4000].lower()
    checks = [
        ("Apache-2.0", "apache license"),
        ("GPL-3", "gnu general public license\n                       version 3"),
        ("GPL-3", "version 3, 29 june 2007"),
        ("LGPL", "lesser general public license"),
        ("GPL-2", "version 2, june 1991"),
        ("EPL", "eclipse public license"),
        ("MPL", "mozilla public license"),
        ("CDDL", "common development and distribution"),
        ("BSD-2", "redistributions in binary form"),
        ("MIT", "permission is hereby granted, free of charge"),
        ("CC0/Public domain", "public domain"),
    ]
    for fam, needle in checks:
        if needle in head:
            if fam == "BSD-2" and ("neither the name" in head or "endorse or promote" in head):
                return "BSD-3"
            return fam
    return "Other"


def main() -> int:
    fiji = Path(sys.argv[1])
    out_csv = Path(sys.argv[2])
    out_sum = Path(sys.argv[3])
    jars = sorted(list((fiji / "jars").rglob("*.jar")) + list((fiji / "plugins").rglob("*.jar")))
    rows = []
    fam_by_jar: Counter = Counter()
    fam_by_decl: Counter = Counter()
    no_info = []
    for jar in jars:
        rel = jar.relative_to(fiji).as_posix()
        members = unzip_list(jar)
        poms = [m for m in members if m.startswith("META-INF/maven/") and m.endswith("/pom.xml")]
        licfiles = [m for m in members
                    if re.match(r"(META-INF/)?(LICEN[CS]E|COPYING)[^/]*$", m, re.I)
                    and not m.endswith("/")]
        jar_fams: set[str] = set()
        gavs, decls, sources = [], [], []
        for p in poms:
            gav, lics = pom_gav_and_licences(unzip_read(jar, p), p)
            gavs.append(gav)
            if lics:
                decls.extend(lics)
                sources.append(f"pom:{p}")
                for l in lics:
                    jar_fams.add(family(l))
        if "META-INF/MANIFEST.MF" in members:
            mf = unzip_read(jar, "META-INF/MANIFEST.MF").replace("\r\n", "\n")
            m = re.search(r"^Bundle-License:\s*(.*(?:\n [^\n]*)*)", mf, re.M)
            if m:
                val = re.sub(r"\n ", "", m.group(1)).strip()
                decls.append(val)
                sources.append("manifest:Bundle-License")
                jar_fams.add(family(val))
        declared_fams = set(jar_fams)
        for lf in licfiles:
            fam = classify_text(unzip_read(jar, lf))
            decls.append(f"[file {lf} -> {fam}]")
            sources.append(f"licfile:{lf}")
            # an unclassified LICENSE text only counts when nothing else was declared
            if fam != "Other" or not declared_fams:
                jar_fams.add(fam if fam != "Other" else "Unclassified LICENSE file")
        if not jar_fams:
            jar_fams = {"NONE DECLARED"}
            no_info.append(rel)
        # de-duplicate declared names
        seen, ddecl = set(), []
        for d in decls:
            if d not in seen:
                seen.add(d)
                ddecl.append(d)
        for d in set(ddecl):
            if not d.startswith("[file"):
                fam_by_decl[family(d)] += 1
        fams = sorted(jar_fams)
        for f in fams:
            fam_by_jar[f] += 1
        rows.append({
            "jar": rel,
            "groupId:artifactId:version": ";".join(dict.fromkeys(gavs)) or "(no pom)",
            "declared licence(s)": ";".join(ddecl) or "(none)",
            "licence family": ";".join(fams),
            "source": ";".join(dict.fromkeys(sources)) or "(none)",
        })
    out_csv.parent.mkdir(parents=True, exist_ok=True)
    with out_csv.open("w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    combo = Counter(r["licence family"] for r in rows)
    with out_sum.open("w", encoding="utf-8") as fh:
        fh.write(f"jars scanned: {len(rows)}\n")
        fh.write(f"jars with no declared licence (no pom <licenses>, no Bundle-License, no LICENSE file): {len(no_info)}\n\n")
        fh.write("Jars per licence family (a jar with several families counts in each):\n")
        for k, v in fam_by_jar.most_common():
            fh.write(f"  {k}: {v}\n")
        fh.write("\nExact family combination per jar:\n")
        for k, v in combo.most_common():
            fh.write(f"  {k}: {v}\n")
        fh.write("\nJars with no declared licence:\n")
        for j in no_info:
            fh.write(f"  {j}\n")
        others = sorted({d for r in rows for d in r['declared licence(s)'].split(';')
                         if d and not d.startswith('[file') and family(d) == 'Other'})
        fh.write("\nDeclared names classified 'Other':\n")
        for o in others:
            fh.write(f"  {o}\n")
    print(f"{len(rows)} jars; no-info {len(no_info)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
