#!/usr/bin/env python3
"""Install the pinned official Fiji build into .local/fiji unchanged; idempotent, never creates paid resources."""
import hashlib
import json
import pathlib
import platform
import os
import shutil
import stat
import subprocess
import sys
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOCAL = ROOT / '.local'
MANIFEST = json.loads((ROOT / 'script/dependencies.json').read_text())['fiji']
FIJI_DIR = LOCAL / 'fiji'
APP = FIJI_DIR / 'Fiji.app'


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def download():
    target = LOCAL / 'downloads' / pathlib.Path(MANIFEST['url']).name.replace('fiji-latest', 'fiji-' + MANIFEST['build'])
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and digest(target) == MANIFEST['sha256']:
        print('Verified existing ' + target.name, flush=True)
        return target
    partial = target.with_suffix(target.suffix + '.part')
    print('Downloading ' + MANIFEST['url'], flush=True)
    with urllib.request.urlopen(MANIFEST['url'], timeout=120) as response, partial.open('wb') as destination:
        shutil.copyfileobj(response, destination, 4 * 1024 * 1024)
    if digest(partial) != MANIFEST['sha256']:
        partial.unlink()
        raise RuntimeError('SHA-256 mismatch for ' + target.name + '; the archive was discarded and nothing was installed.')
    partial.replace(target)
    return target


def is_link(member):
    return stat.S_ISLNK(member.external_attr >> 16)


def restore_symlinks(archive, app_root, prefix):
    """ZipFile writes symlink entries as text files holding the target; recreate the links (idempotent, safe while Fiji runs)."""
    repaired = 0
    with zipfile.ZipFile(archive) as zipped:
        for member in zipped.infolist():
            if not is_link(member) or not member.filename.startswith(prefix):
                continue
            path = app_root / member.filename[len(prefix):]
            target = zipped.read(member).decode('utf-8')
            if path.is_symlink() and os.readlink(path) == target:
                continue
            if path.is_symlink() or path.exists():
                path.unlink()
            path.parent.mkdir(parents=True, exist_ok=True)
            os.symlink(target, path)
            repaired += 1
    return repaired


def archive_prefix(archive):
    with zipfile.ZipFile(archive) as zipped:
        roots = {name.split('/', 1)[0] for name in zipped.namelist() if '/' in name}
    if len(roots) != 1:
        raise RuntimeError('Expected exactly one top-level directory in the Fiji archive, found ' + str(sorted(roots)))
    return roots.pop() + '/'


def extract(archive):
    marker = APP / '.luma-source-sha256'
    if marker.exists() and marker.read_text().strip() == MANIFEST['sha256'] and (APP / 'jars').is_dir():
        repaired = restore_symlinks(archive, APP, archive_prefix(archive))
        print('Preserving existing ' + str(APP.relative_to(ROOT)) + (' (restored ' + str(repaired) + ' symlinks)' if repaired else ''), flush=True)
        return
    if APP.exists():
        raise RuntimeError(str(APP.relative_to(ROOT)) + ' exists but was not installed from the pinned archive; move it away before rerunning.')
    staging = FIJI_DIR / '.extracting'
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    with zipfile.ZipFile(archive) as zipped:
        for member in zipped.infolist():
            resolved = (staging / member.filename).resolve()
            if staging.resolve() not in resolved.parents and resolved != staging.resolve():
                raise RuntimeError('Archive entry escapes the Fiji directory: ' + member.filename)
        # ZipFile drops POSIX permissions and symlinks; the launcher and bundled JDK need both back.
        for member in zipped.infolist():
            if is_link(member):
                continue
            zipped.extract(member, staging)
            mode = (member.external_attr >> 16) & 0o7777
            if mode and not member.is_dir():
                (staging / member.filename).chmod(mode)
    restore_symlinks(archive, staging, '')
    extracted = [entry for entry in staging.iterdir() if entry.name == 'Fiji.app' or entry.name.startswith('Fiji')]
    if len(extracted) != 1:
        raise RuntimeError('Expected exactly one Fiji.app in the archive, found ' + str([e.name for e in staging.iterdir()]))
    extracted[0].rename(APP)
    marker.write_text(MANIFEST['sha256'] + '\n')
    shutil.rmtree(staging)
    subprocess.run(['xattr', '-dr', 'com.apple.quarantine', str(APP)], check=False, capture_output=True)


def describe():
    launchers = sorted(p.relative_to(APP).as_posix() for p in (APP / 'Contents/MacOS').glob('*')) if (APP / 'Contents/MacOS').is_dir() else []
    top = sorted(p.name for p in APP.iterdir())
    jars = sorted(p.name for p in (APP / 'jars').glob('ij-1*.jar')) + sorted(p.name for p in (APP / 'jars').glob('imagej-2*.jar'))
    print('Fiji.app launchers: ' + ', '.join(launchers), flush=True)
    print('Fiji.app entries: ' + ', '.join(top), flush=True)
    print('Core jars: ' + ', '.join(jars), flush=True)


def main():
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        raise RuntimeError('The pinned Fiji archive is the official macOS arm64 build; other platforms need another dependencies.json entry.')
    if not list((LOCAL / 'jdk').glob('*/Contents/Home/bin/javac')):
        raise RuntimeError('Run python3 script/setup.py first: the Fiji bridge compiles with the local JDK in .local/jdk.')
    extract(download())
    describe()
    print('Fiji is installed unmodified in ' + str(APP.relative_to(ROOT)) + '. Build the bridge with ./script/build-fiji-bridge.sh and start Fiji with ./script/run-fiji.sh', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, zipfile.BadZipFile) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
