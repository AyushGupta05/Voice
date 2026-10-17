"""P72 - header-only metadata probe of BBBC001/BBBC004 archives and selected OME sample files.

Like P71, only HEAD plus at most two HTTP Range requests (<= 64 KiB each) are sent per
file; bytes are parsed in memory and discarded. Nothing is written to disk except the CSV.
Handles: ZIP (walks local headers past directory entries, partial inflate of the first
file), classic TIFF and BigTIFF IFD0, OME-XML in ImageDescription (counts <Image> elements,
reads first <Pixels> attributes), and counts SubIFDs (tag 330) as a pyramid indicator.

Usage:  python -I P72-bbbc-ome-headers.py <out.csv>
"""
from __future__ import annotations

import csv
import re
import struct
import sys
import urllib.request
import zlib

UA = {"User-Agent": "FijiModernisation-P72-header-probe/1.0"}
R = 65535
OME = "https://downloads.openmicroscopy.org/images/OME-TIFF/2016-06/"
TARGETS = [
    ("BBBC001 images (TIFF)", "https://data.broadinstitute.org/bbbc/BBBC001/BBBC001_v1_images_tif.zip"),
    ("BBBC001 counts", "https://data.broadinstitute.org/bbbc/BBBC001/BBBC001_v1_counts.txt"),
    ("BBBC004 images, overlap 0.00", "https://data.broadinstitute.org/bbbc/BBBC004/BBBC004_v1_000_images.zip"),
    ("BBBC004 images, overlap 0.60", "https://data.broadinstitute.org/bbbc/BBBC004/BBBC004_v1_060_images.zip"),
    ("BBBC004 foreground, overlap 0.00", "https://data.broadinstitute.org/bbbc/BBBC004/BBBC004_v1_000_foreground.zip"),
    ("OME artificial single-channel", OME + "bioformats-artificial/single-channel.ome.tif"),
    ("OME artificial multi-channel-4D-series", OME + "bioformats-artificial/multi-channel-4D-series.ome.tif"),
    ("OME artificial multi-channel-4D-series BigTIFF", OME + "bioformats-artificial/multi-channel-4D-series.ome.btf"),
    ("OME tubhiswt-4D C0 TP0", OME + "tubhiswt-4D/tubhiswt_C0_TP0.ome.tif"),
    ("OME plate-companion well-A2", OME + "plate-companion/well-A2.ome.tiff"),
    ("OME companion multifile-Z1", OME + "companion/multifile-Z1.ome.tiff"),
    ("OME pyramid Z-stack retina_large", OME + "sub-resolutions/Z-stack/retina_large.ome.tiff"),
    ("OME pyramid brightfield Leica-1", OME + "sub-resolutions/Brightfield/Leica-1/Leica-1.ome.tiff"),
    ("OME MitoCheck 00001_01", OME + "MitoCheck/00001_01.ome.tiff"),
]


def head(url):
    req = urllib.request.Request(url, method="HEAD", headers=UA)
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.headers.get("Content-Length"), r.geturl()


def rng(url, start, length=R):
    req = urllib.request.Request(url, headers={**UA, "Range": f"bytes={start}-{start + length - 1}"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read(length)


def parse_tiff(buf: bytes, fetch_more=None) -> dict:
    out = {}
    e = "<" if buf[:2] == b"II" else ">"
    magic = struct.unpack(e + "H", buf[2:4])[0]
    big = magic == 43
    out["tiff"] = "BigTIFF" if big else "TIFF"
    ifd = struct.unpack(e + ("Q" if big else "I"), buf[8:16] if big else buf[4:8])[0]
    base = 0
    if ifd + 4096 > len(buf):
        if fetch_more is None:
            out["err"] = f"IFD0 at {ifd} beyond range"
            return out
        buf = fetch_more(ifd)
        base = ifd
    p = ifd - base
    n = struct.unpack(e + ("Q" if big else "H"), buf[p:p + (8 if big else 2)])[0]
    esz = 20 if big else 12
    p += 8 if big else 2
    tags = {}
    for i in range(min(n, 200)):
        o = p + esz * i
        if big:
            tag, typ, cnt = struct.unpack(e + "HHQ", buf[o:o + 12])
            val = buf[o + 12:o + 20]
        else:
            tag, typ, cnt = struct.unpack(e + "HHI", buf[o:o + 8])
            val = buf[o + 8:o + 12]
        tags[tag] = (typ, cnt, val)

    def small(tag):
        if tag not in tags:
            return None
        typ, cnt, val = tags[tag]
        if typ == 3:
            return struct.unpack(e + "H", val[:2])[0]
        if typ == 4:
            return struct.unpack(e + "I", val[:4])[0]
        if typ == 16:
            return struct.unpack(e + "Q", val[:8])[0]
        return None

    out["width"] = small(256)
    out["height"] = small(257)
    out["bits"] = small(258)
    out["spp"] = small(277) or 1
    out["compression"] = small(259)
    out["sampleformat"] = small(339) or 1
    if 330 in tags:
        out["subifds"] = tags[330][1]
    if 270 in tags:
        typ, cnt, val = tags[270]
        off = struct.unpack(e + ("Q" if big else "I"), val)[0]
        if base <= off < base + len(buf):
            desc = buf[off - base: off - base + cnt]
        elif fetch_more is not None:
            desc = fetch_more(off)[:cnt]
        else:
            desc = b""
        d = desc.decode("utf-8", "replace")
        out["desc_len"] = cnt
        if "<OME" in d:
            out["ome_images_in_first_64k"] = len(re.findall(r"<Image[ >]", d))
            m = re.search(r"<Pixels[^>]*>", d)
            if m:
                attrs = dict(re.findall(r'(\w+)="([^"]*)"', m.group(0)))
                out["ome_pixels"] = ";".join(f"{k}={attrs[k]}" for k in
                                              ("DimensionOrder", "Type", "SizeX", "SizeY", "SizeZ",
                                               "SizeC", "SizeT", "PhysicalSizeX", "PhysicalSizeXUnit",
                                               "PhysicalSizeZ") if k in attrs)
            out["ome_has_plate"] = "<Plate" in d
            out["ome_binaryonly"] = "BinaryOnly" in d
        else:
            out["desc_head"] = d[:200].replace("\n", " | ")
    return out


def parse_zip(buf: bytes) -> dict:
    p = 0
    names = []
    while buf[p:p + 4] == b"PK\x03\x04":
        flag, method = struct.unpack("<HH", buf[p + 6:p + 10])
        csize, usize, fnlen, exlen = struct.unpack("<IIHH", buf[p + 18:p + 30])
        name = buf[p + 30:p + 30 + fnlen].decode("latin1")
        start = p + 30 + fnlen + exlen
        names.append(name)
        if name.endswith("/") or (csize == 0 and method == 0 and not flag & 8):
            p = start + csize
            continue
        body = buf[start:]
        inner = zlib.decompressobj(-15).decompress(body) if method == 8 else body
        info = {"zip_first_file": name, "zip_entries_seen": ";".join(names)}
        if inner[:2] in (b"II", b"MM"):
            info.update(parse_tiff(inner))
        else:
            info["inner_magic"] = inner[:16].hex()
        return info
    return {"err": "no file entry found in range", "zip_entries_seen": ";".join(names)}


def main() -> int:
    rows = []
    for label, url in TARGETS:
        row = {"label": label, "url": url}
        try:
            size, final = head(url)
            row["content_length"] = size
            buf = rng(final, 0)
            if url.endswith(".zip"):
                row.update(parse_zip(buf))
            elif url.endswith(".txt"):
                row["text"] = buf[:400].decode("utf-8", "replace").replace("\n", " | ")
            else:
                row.update(parse_tiff(buf, fetch_more=lambda off, f=final: rng(f, off)))
        except Exception as ex:  # noqa: BLE001
            row["err"] = repr(ex)
        rows.append(row)
        print(row)
    keys = []
    for r in rows:
        for k in r:
            if k not in keys:
                keys.append(k)
    with open(sys.argv[1], "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=keys)
        w.writeheader()
        w.writerows(rows)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
