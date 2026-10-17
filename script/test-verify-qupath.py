#!/usr/bin/env python3
"""Synthetic filesystem contracts; no QuPath installation or research data needed."""
import importlib.util
import os
from pathlib import Path
import shutil
import plistlib
import tempfile
import subprocess
import unittest
from unittest.mock import patch
from qupath_config import validate_destination

spec = importlib.util.spec_from_file_location('verify_qupath', Path(__file__).with_name('verify-qupath.py'))
verification = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verification)


class PreservationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='luma-preservation-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.reference = self.root / 'reference'
        self.reference.mkdir()
        (self.reference / 'empty').mkdir()
        (self.reference / 'application.jar').write_bytes(b'original bytes')
        (self.reference / 'runtime-link').symlink_to('runtime', target_is_directory=True)
        self.installed = self.root / 'installed'
        shutil.copytree(self.reference, self.installed, symlinks=True)

    def test_exact_copy_and_unfollowed_symlinks_pass(self):
        self.assertEqual(verification.compare_trees(self.reference, self.installed), {'entries': 3, 'regularFiles': 1})

    def test_modified_added_missing_or_retargeted_entries_fail(self):
        for mutation in ['modified', 'added', 'missing', 'link', 'executable', 'empty-directory']:
            with self.subTest(mutation=mutation):
                shutil.rmtree(self.installed)
                shutil.copytree(self.reference, self.installed, symlinks=True)
                path = self.installed / 'application.jar'
                if mutation == 'modified':
                    path.write_bytes(b'changed bytes')
                elif mutation == 'added':
                    (self.installed / 'injected.jar').write_bytes(b'new')
                elif mutation == 'missing':
                    path.unlink()
                elif mutation == 'link':
                    link = self.installed / 'runtime-link'
                    link.unlink()
                    link.symlink_to('../elsewhere')
                elif mutation == 'executable':
                    path.chmod(0o755)
                else:
                    (self.installed / 'empty').rmdir()
                with self.assertRaisesRegex(ValueError, 'differs'):
                    verification.compare_trees(self.reference, self.installed)

    def test_bundle_root_link_and_overlapping_destinations_fail(self):
        link = self.root / 'alias'
        link.symlink_to(self.reference, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'real bundle'):
            verification.inventory(link)
        for target in [self.reference, link, self.reference / 'nested', self.root]:
            with self.subTest(target=target), self.assertRaisesRegex(ValueError, 'separate'):
                verification.separate_directories(self.reference, target)
        verification.separate_directories(self.reference, self.installed)

    def test_failed_verification_does_not_repair_changed_bytes(self):
        path = self.installed / 'application.jar'
        path.write_bytes(b'user modified')
        with self.assertRaises(ValueError):
            verification.compare_trees(self.reference, self.installed)
        self.assertEqual(path.read_bytes(), b'user modified')
        self.assertEqual((self.reference / 'application.jar').read_bytes(), b'original bytes')

    def test_launcher_refuses_extra_jars_and_retargeted_original(self):
        original = self.root / 'stock.app'
        launcher = self.root / 'dist/QuPathLuma.app'
        name = verification.EXECUTABLE
        for app in [original, launcher]:
            for directory in ['app', 'MacOS', 'Resources']:
                (app / 'Contents' / directory).mkdir(parents=True)
            (app / 'Contents/MacOS' / name).write_bytes(b'executable fixture')
            (app / 'Contents/Resources' / f'{name}.icns').write_bytes(b'icon fixture')
        source = original / 'Contents/app/qupath.jar'
        source.write_bytes(b'original jar fixture')
        bridge = self.root / 'dist/luma-qupath-bridge.jar'
        bridge.write_bytes(b'bridge fixture')
        target = launcher / 'Contents/app/qupath.jar'
        target.symlink_to(source)
        (launcher / 'Contents/app/luma-qupath-bridge.jar').symlink_to(bridge)
        docs = original / 'Contents/app/docs'
        docs.mkdir()
        (docs / 'qupath-javadoc.jar').write_bytes(b'documentation fixture')
        docs_link = launcher / 'Contents/app/docs'
        docs_link.symlink_to(docs, target_is_directory=True)
        stock_config = 'app.classpath=$APPDIR/qupath.jar\napp.classpath=$APPDIR/docs/qupath-javadoc.jar\n[JavaOptions]\n'
        (original / 'Contents/app' / f'{name}.cfg').write_text(stock_config)
        (launcher / 'Contents/app' / f'{name}.cfg').write_text(verification.launcher_config(stock_config, self.root))
        (original / 'Contents/Info.plist').write_bytes(plistlib.dumps({'CFBundleName': 'QuPath'}))
        (launcher / 'Contents/Info.plist').write_bytes(plistlib.dumps(
            {'CFBundleName': 'QuPath + Luma', 'CFBundleIdentifier': 'science.luma.qupath'}))
        jdk = self.root / '.local/jdk/full-jdk'
        (jdk / 'Contents/Home/bin').mkdir(parents=True)
        (jdk / 'Contents/Home/bin/javac').write_bytes(b'javac fixture')
        (launcher / 'Contents/runtime').symlink_to(jdk, target_is_directory=True)
        # File routing is portable. Actual Apple signature/code normalization is
        # verified separately by the installed-artifact command on this Mac.
        with patch.object(verification, 'unsigned_executable_digest', return_value='fixture'), \
                patch.object(verification, 'run', return_value=''):
            result = verification.verify_launcher(self.root, original, launcher)
            self.assertEqual(result['originalJars'], 1)
            self.assertEqual(result['documentationJars'], 1)
            self.assertEqual(result['stockClasspathEntries'], 2)
            docs_link.unlink()
            with self.assertRaisesRegex(ValueError, 'classpath'):
                verification.verify_launcher(self.root, original, launcher)
            docs_link.symlink_to(docs, target_is_directory=True)
            (docs / 'qupath-javadoc.jar').unlink()
            with self.assertRaisesRegex(ValueError, 'original classpath entry'):
                verification.verify_launcher(self.root, original, launcher)
            (docs / 'qupath-javadoc.jar').write_bytes(b'documentation fixture')
            injection = launcher / 'Contents/app/unexpected.jar'
            injection.write_bytes(b'unexpected')
            with self.assertRaisesRegex(ValueError, 'classpath'):
                verification.verify_launcher(self.root, original, launcher)
            injection.unlink()
            target.unlink()
            target.symlink_to(bridge)
            with self.assertRaisesRegex(ValueError, 'link exactly'):
                verification.verify_launcher(self.root, original, launcher)

    def test_hidden_git_changes_fail_without_resetting_flags_or_files(self):
        directory = self.root / 'upstream'
        directory.mkdir()
        def git(*arguments):
            return subprocess.run(['git', '-C', str(directory), *arguments], check=True,
                                  capture_output=True, text=True).stdout.strip()
        git('init', '--quiet')
        path = directory / 'source.java'
        path.write_text('original')
        git('add', 'source.java')
        git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '--quiet', '-m', 'fixture')
        pin = git('rev-parse', 'HEAD')
        verification.verify_source(directory, pin)
        for flag in ['assume-unchanged', 'skip-worktree']:
            with self.subTest(flag=flag):
                git('update-index', '--' + flag, 'source.java')
                path.write_text('hidden modification')
                self.assertEqual(git('status', '--porcelain'), '')
                before = git('ls-files', '-v')
                with self.assertRaisesRegex(ValueError, 'index'):
                    verification.verify_source(directory, pin)
                self.assertEqual(path.read_text(), 'hidden modification')
                self.assertEqual(git('ls-files', '-v'), before)
                git('update-index', '--no-' + flag, 'source.java')
                path.write_text('original')

    def test_packager_refuses_interior_links_without_touching_stock(self):
        original = self.root / 'stock.app'
        source = original / 'Contents/app/QuPath-0.7.0-arm64.cfg'
        source.parent.mkdir(parents=True)
        source.write_bytes(b'original configuration')
        launcher = self.root / 'prepared.app'
        destination = launcher / 'Contents/app'
        destination.mkdir(parents=True)
        target = destination / source.name
        for link_kind in ['symlink', 'hardlink', 'directory']:
            with self.subTest(link_kind=link_kind):
                if link_kind == 'symlink':
                    target.symlink_to(source)
                elif link_kind == 'hardlink':
                    os.link(source, target)
                else:
                    destination.rmdir()
                    destination.symlink_to(source.parent, target_is_directory=True)
                with self.assertRaisesRegex(ValueError, 'linked'):
                    validate_destination(original, launcher)
                # Run the real shell entrypoint: preflight must stop before any
                # copy/link/write or platform-specific process inventory.
                command = ['/bin/bash', str(Path(__file__).with_name('package-qupath.sh')), '--output', str(launcher)]
                result = subprocess.run(command, env={**os.environ, 'LUMA_QUPATH_APP': str(original),
                    'LUMA_JAVA_HOME': str(self.root / 'unused jdk')}, capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Refusing', result.stderr)
                self.assertEqual(source.read_bytes(), b'original configuration')
                if link_kind == 'directory':
                    destination.unlink()
                    destination.mkdir()
                else:
                    target.unlink()


if __name__ == '__main__':
    unittest.main()
