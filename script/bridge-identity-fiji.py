#!/usr/bin/env python3
"""Fiji variant of bridge-identity.py: snapshot adapters/fiji inputs and stamp the build; no app operations."""
import hashlib
import json
from pathlib import Path
import sys


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def prepare(root, build):
    base = root / 'adapters/fiji/src/main'
    def validate(path, directory=False):
        relative = path.relative_to(root)
        current = root
        for part in relative.parts:
            current = current / part
            if current.is_symlink():
                raise ValueError('Bridge fingerprint inputs and their directories must not be links.')
        if not (path.is_dir() if directory else path.is_file()):
            raise ValueError('Bridge fingerprint input is not a regular file/directory.')
    validate(base, directory=True)
    paths = []
    for path in sorted(base.rglob('*')):
        if path.is_symlink():
            raise ValueError('Bridge source/resource links need a regular-file snapshot before building.')
        if path.is_file():
            paths.append(path)
    paths += [root / 'script/build-fiji-bridge.sh', root / 'script/bridge-identity-fiji.py', root / 'script/dependencies.json']
    for path in paths:
        validate(path)
    result = hashlib.sha256()
    for path in paths:
        relative = path.relative_to(root).as_posix().encode('utf-8')
        data = path.read_bytes()
        result.update(len(relative).to_bytes(8, 'big') + relative + len(data).to_bytes(8, 'big') + data)
        if path.is_relative_to(base):
            target = build / 'source' / path.relative_to(base)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    source = {'version': 1, 'protocolVersion': 1, 'application': 'fiji', 'sourceSha256': result.hexdigest(), 'sourceFiles': len(paths)}
    (build / 'bridge-source.json').write_text(json.dumps(source) + '\n')


def stamp(build):
    source = json.loads((build / 'bridge-source.json').read_text())
    resource = build / 'classes/science/luma/fiji/bridge-build.properties'
    resource.parent.mkdir(parents=True, exist_ok=True)
    resource.write_text('sourceSha256=' + source['sourceSha256'] + '\n')


def finish(build):
    source = json.loads((build / 'bridge-source.json').read_text())
    source['jarSha256'] = digest(build / 'bridge.jar')
    (build / 'bridge-build.json').write_text(json.dumps(source) + '\n')


if __name__ == '__main__':
    if len(sys.argv) == 4 and sys.argv[1] == 'prepare':
        prepare(Path(sys.argv[2]).resolve(), Path(sys.argv[3]).resolve())
    elif len(sys.argv) == 3 and sys.argv[1] == 'finish':
        finish(Path(sys.argv[2]).resolve())
    elif len(sys.argv) == 3 and sys.argv[1] == 'stamp':
        stamp(Path(sys.argv[2]).resolve())
    else:
        raise SystemExit('Usage: bridge-identity-fiji.py prepare ROOT BUILD | stamp BUILD | finish BUILD')
