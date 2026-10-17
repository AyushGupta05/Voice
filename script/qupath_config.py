"""Local launcher additions, shared by packaging and preservation verification."""
import hashlib


def separate_directories(source, launcher):
    original, companion = source.resolve(), launcher.resolve()
    if original == companion or original in companion.parents or companion in original.parents:
        raise ValueError('Original QuPath and the Luma launcher must be separate directories.')


def validate_destination(source, launcher):
    separate_directories(source, launcher)
    for relative in ['', 'Contents', 'Contents/MacOS', 'Contents/app', 'Contents/Resources', 'Contents/_CodeSignature']:
        path = launcher / relative
        if path.is_symlink() or (path.exists() and not path.is_dir()):
            raise ValueError('Refusing a linked or non-directory launcher write destination.')
    for relative in ['Contents/Info.plist', 'Contents/MacOS/QuPath-0.7.0-arm64',
                     'Contents/Resources/QuPath-0.7.0-arm64.icns',
                     'Contents/app/QuPath-0.7.0-arm64.cfg', 'Contents/_CodeSignature/CodeResources']:
        path = launcher / relative
        if path.is_symlink() or (path.exists() and (not path.is_file() or path.stat().st_nlink != 1)):
            raise ValueError('Refusing a symlinked, hard-linked or non-file launcher write target.')
    # Linked classpath resources are replaced as links, never traversed. Refuse
    # a real directory at those destinations to avoid writing a link inside it.
    for path in (source / 'Contents/app').iterdir():
        if path.name == 'QuPath-0.7.0-arm64.cfg':
            continue
        target = launcher / 'Contents/app' / path.name
        if target.is_dir() and not target.is_symlink():
            raise ValueError('Refusing an existing directory at a linked classpath destination.')
    runtime = launcher / 'Contents/runtime'
    if runtime.exists() and not runtime.is_symlink():
        raise ValueError('Refusing an existing non-link runtime destination.')


def preference_node(root):
    # MacOSXPreferencesFactory ignores java.util.prefs.userRoot. QuPath's own
    # supported override isolates its settings without altering the stock node.
    project = hashlib.sha256(str(root.resolve()).encode('utf-8')).hexdigest()[:16]
    return f'science.luma/{project}/qupath/0.7'


def launcher_config(original, root):
    config = original.replace('[JavaOptions]', 'app.classpath=$APPDIR/luma-qupath-bridge.jar\n\n[JavaOptions]')
    return config + '\njava-options=-Dqupath.prefs.name=' + preference_node(root) + '\n'
