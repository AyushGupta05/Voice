"""P48 - Read-only inventory of the pinned Fiji installation's plugin metadata.
Usage: python -I plugin_index.py <FijiDir> <outdir>
Reads META-INF/json/org.scijava.plugin.Plugin (SciJava annotation index) and
plugins.config (ImageJ1 menu registration) from every jar in jars/ and plugins/.
Writes nothing into the Fiji dir."""
import sys, os, json, zipfile, collections, csv
fiji, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
dec = json.JSONDecoder()
def parse_concat(t):
    i, res = 0, []
    while i < len(t):
        while i < len(t) and t[i].isspace(): i += 1
        if i >= len(t): break
        o, i = dec.raw_decode(t, i); res.append(o)
    return res
rows, cfg = [], []
jars = []
for d in ("jars", "plugins"):
    for root, _, files in os.walk(os.path.join(fiji, d)):
        for f in files:
            if f.endswith(".jar"): jars.append(os.path.join(root, f))
ij1_classes = collections.Counter()
for jp in sorted(jars):
    rel = os.path.relpath(jp, fiji).replace("\\", "/")
    try:
        z = zipfile.ZipFile(jp)
    except Exception as e:
        print("skip", rel, e); continue
    names = set(z.namelist())
    if "META-INF/json/org.scijava.plugin.Plugin" in names:
        t = z.read("META-INF/json/org.scijava.plugin.Plugin").decode("utf-8", "replace")
        for o in parse_concat(t):
            v = o.get("values", {})
            rows.append([rel, o.get("class"), v.get("type", ""), v.get("name", ""), v.get("label", ""),
                         str(v.get("headless", "")), str(v.get("priority", "")),
                         ";".join(m.get("label", "") for m in v.get("menu", []) if isinstance(m, dict)) or v.get("menuPath", "")])
    if "plugins.config" in names:
        for line in z.read("plugins.config").decode("utf-8", "replace").splitlines():
            s = line.strip()
            if s and not s.startswith("#"):
                cfg.append([rel, s])
with open(os.path.join(out, "scijava-plugin-index.csv"), "w", newline="", encoding="utf-8") as fh:
    w = csv.writer(fh); w.writerow(["jar", "class", "type", "name", "label", "headless", "priority", "menu"]); w.writerows(rows)
with open(os.path.join(out, "plugins-config-lines.csv"), "w", newline="", encoding="utf-8") as fh:
    w = csv.writer(fh); w.writerow(["jar", "line"]); w.writerows(cfg)
c = collections.Counter(r[2] for r in rows)
print("jars scanned:", len(jars))
print("sciJava plugin entries:", len(rows))
for k, n in c.most_common(40): print(f"{n:6d} {k}")
print("jars with plugins.config:", len(set(r[0] for r in cfg)), "lines:", len(cfg))
hl = collections.Counter((r[2], r[5]) for r in rows if r[2].endswith("Command"))
print("Command headless attr:", dict(hl))
