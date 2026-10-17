"""P71 - header-only metadata probe of the ImageJ 'File > Open Samples' images.

For each sample listed in jars/ij-1.54p.jar!/IJ_Props.txt (open01..open30) and the
four Fiji Samples_ links, this probe:
  1. sends HEAD to obtain Content-Length (the full file is NOT downloaded);
  2. sends one HTTP Range request for the first 64 KiB only;
  3. parses the header bytes in memory (ZIP local header -> partial inflate ->
     TIFF IFD0 and ImageJ ImageDescription; GIF logical screen; JPEG SOFn;
     DICOM rows/columns/bits-allocated) and discards the bytes.
No image file is stored on disk. Output is a CSV of parsed metadata.

Usage:  python -I P71-sample-image-headers.py <out.csv>
"""
from __future__ import annotations

import csv
import re
import struct
import sys
import urllib.request
import zlib

BASE = "https://imagej.net/ij/images/"
SAMPLES = [
    ("open01", "AuPbSn 40", "AuPbSn40.jpg"),
    ("open02", "Bat Cochlea Volume", "bat-cochlea-volume.zip"),
    ("open03", "Bat Cochlea Renderings", "bat-cochlea-renderings.zip"),
    ("open04", "Blobs", "blobs.gif"),
    ("open05", "Boats", "boats.gif"),
    ("open06", "Cardio (RGB DICOM)", "cardio.dcm.zip"),
    ("open07", "Cell Colony", "Cell_Colony.jpg"),
    ("open08", "Clown", "clown.jpg"),
    ("open09", "Confocal Series", "confocal-series.zip"),
    ("open10", "CT (16-bit DICOM)", "ct.dcm.zip"),
    ("open11", "Dot Blot", "Dot_Blot.jpg"),
    ("open12", "Embryos", "embryos.jpg"),
    ("open13", "Fluorescent Cells", "FluorescentCells.zip"),
    ("open14", "Fly Brain", "flybrain.zip"),
    ("open15", "Gel", "gel.gif"),
    ("open16", "HeLa Cells (48-bit RGB)", "hela-cells.zip"),
    ("open17", "Image with Overlay", "ImageWithOverlay.zip"),
    ("open18", "Leaf", "leaf.jpg"),
    ("open19", "Line Graph", "LineGraph.jpg"),
    ("open20", "Mitosis (5D stack)", "Spindly-GFP.zip"),
    ("open21", "MRI Stack", "mri-stack.zip"),
    ("open22", "M51 Galaxy (16-bits)", "m51.zip"),
    ("open23", "Neuron (5 channels)", "Rat_Hippocampal_Neuron.zip"),
    ("open24", "Nile Bend", "NileBend.jpg"),
    ("open25", "Organ of Corti (4D stack)", "organ-of-corti.zip"),
    ("open26", "Particles", "particles.gif"),
    ("open27", "T1 Head (16-bits)", "t1-head.zip"),
    ("open28", "T1 Head Renderings", "t1-rendering.zip"),
    ("open29", "TEM Filter", "TEM_filter_sample.jpg"),
    ("open30", "Tree Rings", "Tree_Rings.jpg"),
    ("fiji1", "Malaria Sporozoites", "http://samples.fiji.sc/_malaria_parasites.tif"),
    ("fiji2", "First-instar brain", "http://samples.fiji.sc/first-instar-brain.zip"),
]
RANGE = 65535
UA = {"User-Agent": "FijiModernisation-P71-header-probe/1.0"}


def fetch(url: str):
    size = None
    try:
        req = urllib.request.Request(url, method="HEAD", headers=UA)
        with urllib.request.urlopen(req, timeout=30) as r:
            size = r.headers.get("Content-Length")
            final = r.geturl()
    except Exception as e:  # noqa: BLE001
        return None, None, f"HEAD failed: {e}"
    req = urllib.request.Request(final, headers={**UA, "Range": f"bytes=0-{RANGE}"})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = r.read(RANGE + 1)
        status = r.status
    return size, data[: RANGE + 1], f"range status {status}; url {final}"


def tiff(buf: bytes) -> dict:
    out: dict = {}
    if buf[:2] not in (b"II", b"MM"):
        return {"err": "not TIFF"}
    e = "<" if buf[:2] == b"II" else ">"
    ifd = struct.unpack(e + "I", buf[4:8])[0]
    n = struct.unpack(e + "H", buf[ifd:ifd + 2])[0]
    tags = {}
    for i in range(n):
        o = ifd + 2 + 12 * i
        tag, typ, cnt = struct.unpack(e + "HHI", buf[o:o + 8])
        val = buf[o + 8:o + 12]
        tags[tag] = (typ, cnt, val)

    def num(tag):
        if tag not in tags:
            return None
        typ, cnt, val = tags[tag]
        if typ == 3:
            if cnt <= 2:
                return struct.unpack(e + "H", val[:2])[0]
            off = struct.unpack(e + "I", val)[0]
            return struct.unpack(e + "H", buf[off:off + 2])[0] if off + 2 <= len(buf) else None
        if typ == 4:
            return struct.unpack(e + "I", val)[0]
        return None

    def rational(tag):
        if tag not in tags:
            return None
        off = struct.unpack(e + "I", tags[tag][2])[0]
        if off + 8 > len(buf):
            return None
        a, b = struct.unpack(e + "II", buf[off:off + 8])
        return a / b if b else None

    out["width"] = num(256)
    out["height"] = num(257)
    out["bits"] = num(258)
    out["spp"] = num(277) or 1
    out["sampleformat"] = num(339) or 1
    out["xres"] = rational(282)
    out["resunit"] = num(296)
    if 270 in tags:
        typ, cnt, val = tags[270]
        off = struct.unpack(e + "I", val)[0] if cnt > 4 else None
        desc = buf[off:off + cnt] if off is not None else val[:cnt]
        out["ij_desc"] = desc.decode("latin1", "replace").strip("\x00").replace("\n", " | ")
    return out


def gif(buf: bytes) -> dict:
    w, h = struct.unpack("<HH", buf[6:10])
    flags = buf[10]
    return {"width": w, "height": h, "bits": 8, "spp": 1,
            "note": f"GIF global colour table={bool(flags & 0x80)} size={2 ** ((flags & 7) + 1)}"}


def jpeg(buf: bytes) -> dict:
    i = 2
    while i < len(buf) - 9:
        if buf[i] != 0xFF:
            i += 1
            continue
        marker = buf[i + 1]
        if marker in (0xC0, 0xC1, 0xC2):
            p, h, w, nc = struct.unpack(">BHHB", buf[i + 4:i + 10])
            return {"width": w, "height": h, "bits": p, "spp": nc}
        seglen = struct.unpack(">H", buf[i + 2:i + 4])[0]
        i += 2 + seglen
    return {"err": "SOF not found in range"}


def dicom(buf: bytes) -> dict:
    out = {}
    for name, tag in (("height", b"\x28\x00\x10\x00"), ("width", b"\x28\x00\x11\x00"),
                      ("bits", b"\x28\x00\x00\x01"), ("spp", b"\x28\x00\x02\x00"),
                      ("frames", b"\x28\x00\x08\x00")):
        j = buf.find(tag)
        if j < 0:
            continue
        vr = buf[j + 4:j + 6]
        if vr in (b"US",):
            out[name] = struct.unpack("<H", buf[j + 8:j + 10])[0]
        elif vr == b"IS":
            ln = struct.unpack("<H", buf[j + 6:j + 8])[0]
            out[name] = buf[j + 8:j + 8 + ln].decode("latin1").strip()
        else:  # implicit VR little endian
            out[name] = struct.unpack("<H", buf[j + 8:j + 10])[0]
    return out


def zipped(buf: bytes) -> dict:
    if buf[:4] != b"PK\x03\x04":
        return {"err": "not ZIP"}
    method, = struct.unpack("<H", buf[8:10])
    csize, usize, fnlen, exlen = struct.unpack("<IIHH", buf[18:30])
    name = buf[30:30 + fnlen].decode("latin1")
    start = 30 + fnlen + exlen
    body = buf[start:]
    if method == 8:
        d = zlib.decompressobj(-15)
        inner = d.decompress(body)
    elif method == 0:
        inner = body
    else:
        return {"inner": name, "err": f"zip method {method}"}
    info = {"inner": name, "inner_uncompressed_bytes": usize}
    low = name.lower()
    if low.endswith((".tif", ".tiff")):
        info.update(tiff(inner))
    elif low.endswith(".dcm") or inner[128:132] == b"DICM":
        info.update(dicom(inner))
    else:
        info["err"] = "inner format not parsed"
    return info


def main() -> int:
    rows = []
    for key, label, fname in SAMPLES:
        url = fname if fname.startswith("http") else BASE + fname
        size, data, note = fetch(url)
        meta: dict = {}
        if data:
            low = fname.lower()
            try:
                if low.endswith(".zip"):
                    meta = zipped(data)
                elif low.endswith(".gif"):
                    meta = gif(data)
                elif low.endswith(".jpg"):
                    meta = jpeg(data)
                elif low.endswith((".tif", ".tiff")):
                    meta = tiff(data)
            except Exception as ex:  # noqa: BLE001
                meta = {"err": repr(ex)}
        desc = meta.pop("ij_desc", "")
        rows.append({
            "menu_key": key, "label": label, "url": url, "content_length": size,
            **{k: meta.get(k) for k in ("inner", "inner_uncompressed_bytes", "width", "height",
                                         "bits", "spp", "sampleformat", "xres", "resunit",
                                         "frames", "note", "err")},
            "imagej_description": desc, "fetch": note,
        })
        print(key, label, size, meta.get("width"), meta.get("height"), meta.get("bits"), desc[:120])
    with open(sys.argv[1], "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
