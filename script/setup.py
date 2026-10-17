#!/usr/bin/env python3
"""Install verified development dependencies locally; never creates paid resources."""
import hashlib
import json
import pathlib
import platform
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOCAL = ROOT / '.local'
MANIFEST = json.loads((ROOT / 'script/dependencies.json').read_text())

def run(*args):
    subprocess.run(args, cwd=str(ROOT), check=True)

def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()

def download(key, filename):
    target = LOCAL / 'downloads' / filename
    expected = MANIFEST[key]['sha256']
    if target.exists() and digest(target) == expected:
        print('Verified existing ' + filename, flush=True)
        return target
    partial = target.with_suffix(target.suffix + '.part')
    print('Downloading ' + MANIFEST[key]['url'], flush=True)
    with urllib.request.urlopen(MANIFEST[key]['url'], timeout=90) as response, partial.open('wb') as destination:
        shutil.copyfileobj(response, destination)
    if digest(partial) != expected:
        raise RuntimeError('Hash mismatch for ' + filename + '; file was not installed.')
    partial.replace(target)
    return target

def clone(repository, directory, revision):
    if directory.exists():
        print('Preserving existing checkout ' + str(directory.relative_to(ROOT)), flush=True)
        return
    run('git', 'clone', '--no-checkout', '--filter=blob:none', repository, str(directory))
    run('git', '-C', str(directory), 'checkout', '--detach', revision)

def main():
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        raise RuntimeError('This bootstrap currently targets Apple Silicon macOS. See docs/PENNY-SETUP.md for portability notes.')
    for command in ['node', 'npm', 'git', 'swift', 'pkgutil', 'codesign']:
        if not shutil.which(command):
            raise RuntimeError('Missing prerequisite: ' + command)
    if int(subprocess.check_output(['node', '--version'], text=True).strip().split('.')[0][1:]) < 22:
        raise RuntimeError('Node.js 22 or later is required.')
    (LOCAL / 'downloads').mkdir(parents=True, exist_ok=True)
    (LOCAL / 'upstream').mkdir(parents=True, exist_ok=True)
    package = download('qupath', 'QuPath-v0.7.0-Mac-arm64.pkg')
    if not (LOCAL / 'qupath-package').exists():
        run('pkgutil', '--expand-full', str(package), str(LOCAL / 'qupath-package'))
    jdk = download('jdk', 'jdk25.tar.gz')
    jdk_root = LOCAL / 'jdk'
    if not list(jdk_root.glob('*/Contents/Home/bin/javac')):
        jdk_root.mkdir(exist_ok=True)
        with tarfile.open(jdk) as archive:
            for member in archive.getmembers():
                target = (jdk_root / member.name).resolve()
                if jdk_root.resolve() not in target.parents:
                    raise RuntimeError('Archive entry escapes local JDK directory.')
            archive.extractall(jdk_root)
    clone('https://github.com/qupath/qupath.git', LOCAL / 'upstream/qupath', MANIFEST['qupath']['sourceCommit'])
    clone(MANIFEST['qupathDocs']['repository'], LOCAL / 'upstream/qupath-docs', MANIFEST['qupathDocs']['commit'])
    run('npm', 'ci')
    run('./script/build-bridge.sh')
    run('./script/package-qupath.sh')
    print('Local setup complete. Start QuPath with ./script/run-qupath.sh and Luma with ./script/build_and_run.sh', flush=True)

if __name__ == '__main__':
    main()
