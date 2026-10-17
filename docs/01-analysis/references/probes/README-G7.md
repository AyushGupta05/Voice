# Probe log — agent G7 (P70–P79)

All probes are read-only. None starts Fiji, writes into `C:\Users\arnav\Documents\dev\Fiji`, or stores downloaded image bytes. Network probes send `HEAD` plus at most two HTTP `Range` requests of 64 KiB or less per file, parse the header bytes in memory, and keep only the parsed metadata in CSV form. Interpreter: `C:\Users\arnav\Documents\dev\click-to-batch\.venv\Scripts\python.exe` (CPython 3.14, run with `-I`). The shell is Git Bash with `unzip` 6.0 at `/usr/bin/unzip`.

| Probe | Purpose | Exact command (cwd = `docs/01-analysis/references/probes`) | Date | Outputs | Pin check |
|---|---|---|---|---|---|
| P70 | Aggregate declared licences of every jar under `Fiji/jars` and `Fiji/plugins` (recursive, 662 jars). Sources: `META-INF/maven/**/pom.xml` `<licenses>`, `META-INF/MANIFEST.MF` `Bundle-License`, and `LICENSE*`/`COPYING*` at the jar root or in `META-INF`. Uses `unzip -Z1` and `unzip -p` to stdout only. | `../../../../.venv/Scripts/python.exe -I P70-jar-licences.py /c/Users/arnav/Documents/dev/Fiji P70-jar-licences.csv P70-jar-licences-summary.txt` | 2026-10-17 | `P70-jar-licences.py`, `P70-jar-licences.csv`, `P70-jar-licences-summary.txt` | `f66953972bb5ed85b8ab0273e4294af5b03136c28c9ef617e24556bca523bafa` (match) |
| P71 | Header-only metadata (dimensions, bit depth, samples per pixel, ImageJ `ImageDescription` hyperstack keys, calibration) for the 30 `File > Open Samples` entries in `ij-1.54p.jar!/IJ_Props.txt` (`open01`–`open30`) and two `Samples_-2.0.3.jar` links. | `../../../../.venv/Scripts/python.exe -I P71-sample-image-headers.py P71-sample-image-headers.csv` | 2026-10-17 | `P71-sample-image-headers.py`, `P71-sample-image-headers.csv` | match (as above) |
| P72 | Header-only metadata for BBBC001 and BBBC004 archives, the BBBC001 counts file (303 bytes, read in full), and selected OME-TIFF samples: artificial, multi-file, plate companion, pyramidal (SubIFD) and BigTIFF. | `../../../../.venv/Scripts/python.exe -I P72-bbbc-ome-headers.py P72-bbbc-ome-headers.csv` | 2026-10-17 | `P72-bbbc-ome-headers.py`, `P72-bbbc-ome-headers.csv` | match (as above) |
| P73 | Native-library and licence-string inspection of the prototype speech venv (`.venv-stt`). It extracts the FFmpeg `configure` string and the `av_*_license()` string from `av.libs/avutil-60-*.dll` and `avcodec-62-*.dll`, lists the DLLs bundled with `ctranslate2` and `av`, and reads `METADATA` licence fields. This was an ad hoc shell inspection, recorded here verbatim. | `cd .venv-stt/Lib/site-packages/av.libs && grep -a -o -E '(LGPL\|GPL) version [0-9.]+ or later' avcodec-62-*.dll avutil-60-*.dll` and `python -I -c "b=open(glob('avutil-60-*.dll')[0],'rb').read(); i=b.find(b'--prefix='); print(b[i:i+1500].split(b'\x00')[0])"`, then `ls ctranslate2/*.dll av.libs` and `grep -i '^License' <pkg>.dist-info/METADATA` | 2026-10-17 | Results are quoted in A24 §5.6. No file was stored. | match (as above) |

Pin command (run 2026-10-17 after the probes; Fiji was not started by any G7 probe):

```
cd C:\Users\arnav\Documents\dev\click-to-batch && ./.venv/Scripts/python.exe -c "from fidelis.adapters.fiji import verify_runtime; from fidelis.config import fiji_executable; print(verify_runtime(fiji_executable()))"
f66953972bb5ed85b8ab0273e4294af5b03136c28c9ef617e24556bca523bafa
```

Known limitations of P70: the licence family comes from keyword matching on declared names, and dual or multi-licensed jars count in every family they declare. A pom that inherits `<licenses>` from a parent (for example `org.bytedeco:ffmpeg`) appears under "NONE DECLARED". The classifier does not open nested jars or native libraries.
