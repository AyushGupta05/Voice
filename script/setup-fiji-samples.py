#!/usr/bin/env python3
"""Download the small, redistributable image sets the Fiji demo and the verified biology recipes use.

Targets (all under .local/data/fiji, excluded from Git; archives are kept in .local/downloads/fiji-samples):
  imagej/      the ImageJ sample images that File > Open Samples fetches from https://imagej.net/images/
               (blobs.gif, FluorescentCells.tif, mitosis.tif, confocal-series.tif, t1-head.tif)
  bbbc039/     a subset of the Broad Bioimage Benchmark Collection BBBC039v1 nuclei set (CC0): the first
               SUBSET_SIZE test-split fields from metadata/test.txt, with their ground-truth masks and the metadata
  trackmate/   FakeTracks.tif, the TrackMate "getting started" tutorial time-lapse from samples.fiji.sc

Every download is verified against the SHA-256 recorded in script/dependencies.json under "fiji-samples";
run with --update-pins to (re)record pins after reviewing an upstream change. Idempotent: a second run
verifies and extracts nothing new. Never creates paid resources; plain HTTPS downloads only.
"""
import argparse
import hashlib
import json
import pathlib
import shutil
import sys
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
MANIFEST_PATH = ROOT / 'script/dependencies.json'
DATA = ROOT / '.local/data/fiji'
DOWNLOADS = ROOT / '.local/downloads/fiji-samples'
SUBSET_SIZE = 10

# url, archive/file name under DOWNLOADS, licence note. Extraction rules are in extract_all().
SOURCES = {
    'imagej/blobs.gif': ('https://imagej.net/images/blobs.gif', 'ImageJ sample image (NIH; distributed with the public-domain ImageJ)'),
    'imagej/FluorescentCells.zip': ('https://imagej.net/images/FluorescentCells.zip', 'ImageJ sample image (NIH; distributed with the public-domain ImageJ)'),
    'imagej/mitosis.tif': ('https://imagej.net/images/mitosis.tif', 'ImageJ sample image (NIH; distributed with the public-domain ImageJ)'),
    'imagej/confocal-series.zip': ('https://imagej.net/images/confocal-series.zip', 'ImageJ sample image (NIH; distributed with the public-domain ImageJ)'),
    'imagej/t1-head.zip': ('https://imagej.net/images/t1-head.zip', 'ImageJ sample image (NIH; distributed with the public-domain ImageJ)'),
    'bbbc039/images.zip': ('https://data.broadinstitute.org/bbbc/BBBC039/images.zip', 'BBBC039v1, Caicedo et al. 2018, Broad Bioimage Benchmark Collection (Ljosa et al., Nature Methods 2012); CC0'),
    'bbbc039/masks.zip': ('https://data.broadinstitute.org/bbbc/BBBC039/masks.zip', 'BBBC039v1 ground truth; CC0'),
    'bbbc039/metadata.zip': ('https://data.broadinstitute.org/bbbc/BBBC039/metadata.zip', 'BBBC039v1 metadata (train/validation/test split); CC0'),
    'trackmate/FakeTracks.tif': ('https://samples.fiji.sc/FakeTracks.tif', 'Fiji/TrackMate tutorial sample (synthetic spots), samples.fiji.sc'),
}


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def load_manifest():
    return json.loads(MANIFEST_PATH.read_text())


def save_manifest(manifest):
    MANIFEST_PATH.write_text(json.dumps(manifest, indent=2) + '\n')


def fetch(key, url, pins, update_pins):
    target = DOWNLOADS / key
    target.parent.mkdir(parents=True, exist_ok=True)
    expected = pins.get(key, {}).get('sha256')
    if target.exists():
        actual = digest(target)
        if expected is None or actual == expected:
            print('Verified existing ' + key + ' (' + str(target.stat().st_size // 1024) + ' KB)', flush=True)
            return target, actual
        print('Existing ' + key + ' does not match its pin; downloading again', flush=True)
    partial = target.with_suffix(target.suffix + '.part')
    print('Downloading ' + url, flush=True)
    request = urllib.request.Request(url, headers={'User-Agent': 'luma-setup-fiji-samples/1'})
    with urllib.request.urlopen(request, timeout=120) as response, partial.open('wb') as destination:
        shutil.copyfileobj(response, destination, 4 * 1024 * 1024)
    actual = digest(partial)
    if expected is not None and actual != expected and not update_pins:
        partial.unlink()
        raise RuntimeError('SHA-256 mismatch for ' + key + ' (expected ' + expected + ', got ' + actual + '); the file was discarded. '
                           'If upstream changed it, review the new file and rerun with --update-pins.')
    partial.replace(target)
    print('Downloaded ' + key + ' (' + str(target.stat().st_size // 1024) + ' KB)', flush=True)
    return target, actual


def safe_extract(archive, members, destination):
    """Extract the named members flat into destination (no paths from the archive are trusted)."""
    written = []
    with zipfile.ZipFile(archive) as zipped:
        for member in members:
            name = pathlib.PurePosixPath(member).name
            if not name or name.startswith('.'):
                continue
            target = destination / name
            if target.exists():
                continue
            with zipped.open(member) as source, target.open('wb') as sink:
                shutil.copyfileobj(source, sink)
            written.append(target)
    return written


def zip_members(archive, suffixes):
    with zipfile.ZipFile(archive) as zipped:
        return [m.filename for m in zipped.infolist()
                if not m.is_dir() and m.filename.lower().endswith(suffixes) and '__MACOSX' not in m.filename
                and not pathlib.PurePosixPath(m.filename).name.startswith('.')]


def extract_all(downloaded):
    # ImageJ samples: each zip holds one TIFF.
    imagej = DATA / 'imagej'
    imagej.mkdir(parents=True, exist_ok=True)
    for key in ('imagej/blobs.gif', 'imagej/mitosis.tif'):
        target = imagej / pathlib.Path(key).name
        if not target.exists():
            shutil.copyfile(downloaded[key], target)
    for key in ('imagej/FluorescentCells.zip', 'imagej/confocal-series.zip', 'imagej/t1-head.zip'):
        safe_extract(downloaded[key], zip_members(downloaded[key], ('.tif', '.tiff')), imagej)
    # BBBC039: metadata first, then the first SUBSET_SIZE test-split images and their masks.
    bbbc = DATA / 'bbbc039'
    (bbbc / 'metadata').mkdir(parents=True, exist_ok=True)
    (bbbc / 'images').mkdir(exist_ok=True)
    (bbbc / 'masks').mkdir(exist_ok=True)
    safe_extract(downloaded['bbbc039/metadata.zip'], zip_members(downloaded['bbbc039/metadata.zip'], ('.txt', '.csv')), bbbc / 'metadata')
    test_list = bbbc / 'metadata' / 'test.txt'
    if not test_list.exists():
        raise RuntimeError('BBBC039 metadata.zip did not contain test.txt; cannot pick the subset.')
    wanted = [line.strip() for line in test_list.read_text().splitlines() if line.strip()][:SUBSET_SIZE]
    stems = [pathlib.PurePosixPath(name).stem for name in wanted]
    images = [m for m in zip_members(downloaded['bbbc039/images.zip'], ('.tif', '.tiff')) if pathlib.PurePosixPath(m).stem in stems]
    masks = [m for m in zip_members(downloaded['bbbc039/masks.zip'], ('.png',)) if pathlib.PurePosixPath(m).stem in stems]
    if len(images) != len(stems):
        raise RuntimeError('Expected ' + str(len(stems)) + ' BBBC039 images for the subset, found ' + str(len(images)))
    safe_extract(downloaded['bbbc039/images.zip'], images, bbbc / 'images')
    safe_extract(downloaded['bbbc039/masks.zip'], masks, bbbc / 'masks')
    (bbbc / 'SUBSET.txt').write_text('\n'.join(wanted) + '\n')
    # TrackMate sample.
    trackmate = DATA / 'trackmate'
    trackmate.mkdir(parents=True, exist_ok=True)
    target = trackmate / 'FakeTracks.tif'
    if not target.exists():
        shutil.copyfile(downloaded['trackmate/FakeTracks.tif'], target)


def describe():
    total = 0
    for path in sorted(DATA.rglob('*')):
        if path.is_file():
            total += path.stat().st_size
    files = sum(1 for p in DATA.rglob('*') if p.is_file())
    archives = sum(p.stat().st_size for p in DOWNLOADS.rglob('*') if p.is_file())
    print('Fiji sample data: ' + str(files) + ' files, ' + str(total // (1024 * 1024)) + ' MB in ' + str(DATA.relative_to(ROOT))
          + '; archives ' + str(archives // (1024 * 1024)) + ' MB in ' + str(DOWNLOADS.relative_to(ROOT)), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--update-pins', action='store_true', help='record new SHA-256 pins for the downloads')
    args = parser.parse_args()
    manifest = load_manifest()
    section = manifest.setdefault('fiji-samples', {'root': '.local/data/fiji', 'subset': SUBSET_SIZE, 'files': {}})
    pins = section.setdefault('files', {})
    downloaded = {}
    changed = False
    for key, (url, licence) in SOURCES.items():
        path, sha = fetch(key, url, pins, args.update_pins)
        downloaded[key] = path
        record = {'url': url, 'sha256': sha, 'bytes': path.stat().st_size, 'licence': licence}
        if pins.get(key) != record:
            if pins.get(key, {}).get('sha256') not in (None, sha) and not args.update_pins:
                raise RuntimeError('Pin changed for ' + key + ' without --update-pins')
            pins[key] = record
            changed = True
    section['subset'] = SUBSET_SIZE
    extract_all(downloaded)
    if changed:
        save_manifest(manifest)
        print('Recorded pins in script/dependencies.json', flush=True)
    describe()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:  # report plainly; nothing partial is left behind
        print('setup-fiji-samples failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
