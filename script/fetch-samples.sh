#!/usr/bin/env bash
# Fetch the openly available microscopy test samples listed in docs/DATASETS.md
# ("Test samples") into .local/data/samples/ (excluded from Git).
# Every download lands in its own fresh temporary directory, is checked against the
# recorded sha256 and only then moved or extracted. Existing targets are skipped.
# Usage: script/fetch-samples.sh [--force]   (LUMA_SAMPLES_DIR overrides the destination)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${LUMA_SAMPLES_DIR:-$ROOT/.local/data/samples}"
UA="LumaSampleFetch/1.0 (https://github.com/AyushGupta05/Voice)"
FORCE=0
[[ "${1:-}" == "--force" ]] && FORCE=1

mkdir -p "$DEST"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT
failures=0

# download <url> <sha256> -> prints path of verified file in a fresh directory
download() {
  local url="$1" sha="$2" dir file actual
  dir="$(mktemp -d "$TMP_ROOT/dl.XXXXXX")"
  file="$dir/download"
  if ! curl -fL --silent --show-error --retry 2 -A "$UA" -o "$file" "$url"; then
    echo "FAIL download $url" >&2; return 1
  fi
  actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  if [[ "$actual" != "$sha" ]]; then
    echo "FAIL sha256 $url: expected $sha, got $actual" >&2; return 1
  fi
  printf '%s\n' "$file"
}

# plain <name> <filename> <url> <sha256>
plain() {
  local name="$1" fname="$2" url="$3" sha="$4" target f
  target="$DEST/$name/$fname"
  if [[ -f "$target" && $FORCE -eq 0 ]]; then echo "skip $name (exists)"; return 0; fi
  f="$(download "$url" "$sha")" || { failures=$((failures + 1)); return 0; }
  mkdir -p "$DEST/$name" && mv "$f" "$target"
  echo "ok   $name -> ${target#"$ROOT/"}"
}

# zipped <name> <subdir|.> <check-file> <url> <sha256> <member-glob>
# Extracts only the matching members, flattened, into DEST/name/subdir.
zipped() {
  local name="$1" sub="$2" check="$3" url="$4" sha="$5" glob="$6" outdir f
  outdir="$DEST/$name${sub:+/$sub}"
  if [[ -f "$outdir/$check" && $FORCE -eq 0 ]]; then echo "skip $name/$sub (exists)"; return 0; fi
  f="$(download "$url" "$sha")" || { failures=$((failures + 1)); return 0; }
  mkdir -p "$outdir"
  unzip -q -o -j "$f" "$glob" -d "$outdir"
  echo "ok   $name -> ${outdir#"$ROOT/"}"
}

IJ=https://imagej.net/images
WM=https://upload.wikimedia.org/wikipedia/commons
BBBC=https://data.broadinstitute.org/bbbc/BBBC008

plain  faketracks FakeTracks.tif https://samples.fiji.sc/FakeTracks.tif \
  dbe7fbe73a5a6e141ffd1182354d7dd0a9c138984be96b8b88decb4b54e9c941
plain  mitosis mitosis.tif "$IJ/mitosis.tif" \
  0729e84de2153093f214a0ffcc1f7b43b6c12c820ab1bb7ce9eaeeeed74c666b
plain  blobs blobs.gif "$IJ/blobs.gif" \
  507d168e317a6b80cd4a2e94f5ff483769779553b7d02b98cb94f790333d5271
zipped hela-cells "" hela-cells.tif "$IJ/hela-cells.zip" \
  9d121c922be1dedad61c7a9902be7384a5c1e1d27ff21bd4a74f554b5574e181 'hela-cells.tif'
zipped fluorescent-cells "" FluorescentCells.tif "$IJ/FluorescentCells.zip" \
  38507fab9787016fad6204239fb0e56437e7513f57d362cec644cd363faadda7 'FluorescentCells.tif'
zipped confocal-series "" confocal-series.tif "$IJ/confocal-series.zip" \
  e1114e37bb442cfd05a5b701cf557b1fd12991cf9c9cd40390a4c0259a092df9 'confocal-series.tif'
zipped bbbc008 images AS_09125_050116000001_L15f00d0_slice6_channel3.tif "$BBBC/BBBC008_v1_images.zip" \
  ff49adf65f57078c9d62e6fd2ffede7290f749fd63cfb543864da49d5bacd91d 'human_ht29_colon_cancer_2_images/*.tif'
zipped bbbc008 foreground AS_09125_050116000001_L15f00d0_slice6_channel3.tif "$BBBC/BBBC008_v1_foreground.zip" \
  a40a7d416f8d6ebec39c4d0f68432423984fbd5b4ba20ac48be9bbe48f9da886 'human_ht29_colon_cancer_2_foreground/*.tif'
plain  he-breast-idc Histopathology_of_invasive_ductal_carcinoma_intermediate_magnification.jpg \
  "$WM/e/eb/Histopathology_of_invasive_ductal_carcinoma%2C_intermediate_magnification.jpg" \
  a8232659dfff67d97da047bba487a65d949711b81cd0227d43d74299eb0762e1
plain  ki67-cervical-scc Invasive_cervical_squamous_cell_carcinoma_HE_and_Ki-67.jpg \
  "$WM/4/4c/Invasive_cervical_squamous_cell_carcinoma_on_H%26E_histopathology_and_Ki-67_immunohistochemistry.jpg" \
  015fb1e3d92e61965503313d119895afd7388b2fca6a2e275968dc8e02a14da5

if [[ $failures -gt 0 ]]; then echo "$failures sample(s) failed" >&2; exit 1; fi
echo "samples in ${DEST#"$ROOT/"} (provenance, licences and dimensions: docs/DATASETS.md)"
