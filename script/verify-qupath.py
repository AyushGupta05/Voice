#!/usr/bin/env python3
"""Verify the stock QuPath bundle and separate Luma launcher; never repair either."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import plistlib
import shutil
import stat
import subprocess
import tempfile
from qupath_config import launcher_config, preference_node, separate_directories


APP_RELATIVE = Path('QuPath-0.7.0-arm64-app.pkg/Payload/QuPath-0.7.0-arm64.app')
EXECUTABLE = 'QuPath-0.7.0-arm64'


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def inventory(root):
    """Hash regular files and record links as links, without following their targets."""
    if root.is_symlink() or not root.is_dir():
        raise ValueError('Expected a real bundle directory.')
    result = {}
    def walk(directory):
        for path in sorted(directory.iterdir()):
            relative = path.relative_to(root).as_posix()
            mode = path.lstat().st_mode
            if stat.S_ISLNK(mode):
                result[relative] = {'kind': 'symlink', 'target': os.readlink(path)}
            elif stat.S_ISDIR(mode):
                result[relative] = {'kind': 'directory'}
                walk(path)
            elif stat.S_ISREG(mode):
                result[relative] = {'kind': 'file', 'sha256': digest(path), 'executable': bool(mode & 0o111)}
            else:
                raise ValueError('Unexpected special file in application bundle.')
    walk(root)
    return result


def compare_trees(reference, installed):
    expected, actual = inventory(reference), inventory(installed)
    changed = sorted(key for key in expected.keys() | actual.keys() if expected.get(key) != actual.get(key))
    if changed:
        raise ValueError(f'Stock QuPath differs from the verified package at {len(changed)} paths. '
                         'Preserve the installation and inspect it; no repair was attempted.')
    return {'entries': len(actual), 'regularFiles': sum(item['kind'] == 'file' for item in actual.values())}


def unsigned_executable_digest(path):
    # Signing the separate bundle changes the copied Mach-O signature. Normalize
    # only a temporary copy; never strip signatures from either installed app.
    with tempfile.TemporaryDirectory(prefix='luma-executable-integrity-') as directory:
        copy = Path(directory) / EXECUTABLE
        shutil.copyfile(path, copy)
        run('/usr/bin/codesign', '--remove-signature', str(copy))
        return digest(copy)


def verify_launcher(root, original, launcher):
    separate_directories(original, launcher)
    if launcher.is_symlink() or not launcher.is_dir():
        raise ValueError('Build the separate launcher with script/package-qupath.sh.')
    app = launcher / 'Contents/app'
    source_app = original / 'Contents/app'
    jars = sorted(source_app.glob('*.jar'))
    entries = sorted(path for path in source_app.iterdir() if path.name != f'{EXECUTABLE}.cfg')
    expected_names = {path.name for path in source_app.iterdir()} | {'luma-qupath-bridge.jar'}
    if {path.name for path in app.iterdir()} != expected_names:
        raise ValueError('Unexpected or missing launcher classpath files. Rebuild deliberately after inspection.')
    for source in entries + [root / 'dist/luma-qupath-bridge.jar']:
        target = app / source.name
        if not source.exists() or not target.is_symlink() or target.resolve() != source.resolve():
            raise ValueError('Launcher must link exactly the original classpath resources and separate Luma bridge.')
    executable = Path('Contents/MacOS') / EXECUTABLE
    if (launcher / executable).is_symlink():
        raise ValueError('Launcher must have its own copied executable.')
    executable_digest = unsigned_executable_digest(original / executable)
    if unsigned_executable_digest(launcher / executable) != executable_digest:
        raise ValueError('Launcher executable code differs from original QuPath after signature normalization.')
    icon = Path('Contents/Resources') / f'{EXECUTABLE}.icns'
    if (launcher / icon).is_symlink() or digest(launcher / icon) != digest(original / icon):
        raise ValueError('Launcher icon differs from original QuPath.')
    config_name = f'{EXECUTABLE}.cfg'
    config = (source_app / config_name).read_text()
    classpath = [line.removeprefix('app.classpath=$APPDIR/') for line in config.splitlines()
                 if line.startswith('app.classpath=$APPDIR/')]
    for value in classpath:
        relative = PurePosixPath(value)
        if relative.is_absolute() or '..' in relative.parts or not (app / value).is_file():
            raise ValueError('An original classpath entry is missing or invalid, including nested documentation jars.')
    expected_config = launcher_config(config, root)
    if (app / config_name).is_symlink() or (app / config_name).read_text() != expected_config:
        raise ValueError('Launcher configuration differs from the bridge/isolated-preferences additions.')
    expected_info = plistlib.loads((original / 'Contents/Info.plist').read_bytes())
    expected_info.update(CFBundleIdentifier='science.luma.qupath', CFBundleName='QuPath + Luma')
    if plistlib.loads((launcher / 'Contents/Info.plist').read_bytes()) != expected_info:
        raise ValueError('Launcher metadata differs from the documented bundle name/identifier additions.')
    runtime = launcher / 'Contents/runtime'
    if not runtime.is_symlink() or not runtime.resolve().is_relative_to((root / '.local/jdk').resolve()):
        raise ValueError('Launcher must use the separate local full JDK, leaving the stock runtime untouched.')
    if not (runtime / 'Contents/Home/bin/javac').is_file():
        raise ValueError('The linked full JDK is unavailable.')
    # External resource links make this a development launcher, not a sealed
    # distributable bundle. Verify the signed copied executable, not a false
    # claim that macOS accepts external links as a strictly sealed app resource.
    run('/usr/bin/codesign', '--verify', '--ignore-resources', '--strict', str(launcher / executable))
    return {'originalJars': len(jars), 'bridgeJarsAdded': 1, 'isolatedPreferences': True,
            'stockClasspathEntries': len(classpath), 'documentationJars': len(list((source_app / 'docs').glob('*.jar'))),
            'preferenceNode': preference_node(root),
            'unsignedExecutableSha256': executable_digest, 'executableSignatureValid': True,
            'distributionReady': False}


def run(*arguments):
    return subprocess.run(arguments, check=True, capture_output=True, text=True, timeout=180).stdout.strip()


def verify_source(directory, pin):
    environment = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
    environment.update(GIT_OPTIONAL_LOCKS='0', GIT_NO_REPLACE_OBJECTS='1', GIT_NO_LAZY_FETCH='1')
    command = ['git', '-C', str(directory)]
    def git(*arguments):
        return subprocess.run(command + list(arguments), env=environment, check=True,
                              capture_output=True, text=True, timeout=15).stdout.strip()
    entries = git('ls-files', '-v', '-z').split('\0')
    if any(entry and entry[:1] != 'H' for entry in entries):
        raise ValueError('Upstream index has skip-worktree, assume-unchanged or other exceptional flags. '
                         'Its contents cannot be certified clean; no index flags were changed.')
    if git('rev-parse', 'HEAD') != pin or git('status', '--porcelain', '--untracked-files=all'):
        raise ValueError('Upstream source/reference checkout differs from the pin or contains changes. '
                         'No checkout, reset or cleanup was attempted.')


def verify(root, launcher=None):
    manifest = json.loads((root / 'script/dependencies.json').read_text())
    archive = root / '.local/downloads/QuPath-v0.7.0-Mac-arm64.pkg'
    if digest(archive) != manifest['qupath']['sha256']:
        raise ValueError('QuPath download hash differs from the pinned manifest. Nothing was extracted or changed.')
    original = root / '.local/qupath-package' / APP_RELATIVE
    launcher = launcher or root / 'dist/QuPathLuma.app'
    separate_directories(original, launcher)
    # Only a fresh temporary directory is written. Both existing app bundles remain untouched.
    with tempfile.TemporaryDirectory(prefix='luma-qupath-integrity-') as directory:
        extracted = Path(directory) / 'expanded'
        run('/usr/sbin/pkgutil', '--expand-full', str(archive), str(extracted))
        original_result = compare_trees(extracted / APP_RELATIVE, original)
    run('/usr/bin/codesign', '--verify', '--deep', '--strict', str(original))
    launcher_result = verify_launcher(root, original, launcher)
    sources = {}
    for name, pin in [('qupath', manifest['qupath']['sourceCommit']), ('qupath-docs', manifest['qupathDocs']['commit'])]:
        verify_source(root / '.local/upstream' / name, pin)
        sources[name] = pin
    return {'verified': True, 'packageSha256': manifest['qupath']['sha256'],
            'launcher': launcher.relative_to(root).as_posix() if launcher.is_relative_to(root) else 'custom outside checkout',
            'stockBundle': {**original_result, 'signatureValid': True, 'matchesFreshExtraction': True},
            'separateLauncher': launcher_result, 'cleanPinnedSources': sources,
            'boundary': 'Read-only installed-file verification; temporary package expansion only. '
                        'No application restart, research data access, provider call or repair. '
                        'Verifies default bootstrap paths; custom app/JDK overrides need their own baseline.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--launcher', type=Path, help='Optional prepared launcher path, relative to --root unless absolute')
    args = parser.parse_args()
    try:
        root = args.root.resolve()
        launcher = (root / args.launcher).absolute() if args.launcher else None
        print(json.dumps(verify(root, launcher), indent=2))
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(json.dumps({'verified': False, 'error': str(error), 'repaired': False}, indent=2))
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
