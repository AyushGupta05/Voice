#!/usr/bin/env python3
"""Portable startup-guard fixture; never opens a native app or real process inventory."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class QuPathLaunchTests(unittest.TestCase):
    def test_running_instance_and_inventory_error_leave_everything_untouched(self):
        for inventory_status in [0, 2]:
            with self.subTest(inventory_status=inventory_status), tempfile.TemporaryDirectory(prefix='luma-launch-test-') as directory:
                root = Path(directory)
                (root / 'script').mkdir()
                shutil.copyfile(Path(__file__).with_name('run-qupath.sh'), root / 'script/run-qupath.sh')
                (root / 'script/companion-process.py').write_text(
                    'import pathlib,sys\n'
                    'assert sys.argv[1]=="running"\n'
                    'assert pathlib.Path(sys.argv[2]).resolve()==(pathlib.Path(__file__).parent.parent/"dist/QuPathLuma.app/Contents/MacOS/QuPath-0.7.0-arm64").resolve()\n'
                    f'raise SystemExit({inventory_status})\n')
                (root / '.local/state').mkdir(parents=True)
                token = root / '.local/state/bridge-token'
                token.write_text('existing token fixture')
                (root / 'dist').mkdir()
                jar = root / 'dist/luma-qupath-bridge.jar'
                jar.write_bytes(b'existing loaded bridge fixture')
                inode = jar.stat().st_ino
                before = sorted(path.relative_to(root).as_posix() for path in root.rglob('*'))
                result = subprocess.run(['/bin/bash', str(root / 'script/run-qupath.sh')], capture_output=True, text=True)
                self.assertEqual(result.returncode, inventory_status, result.stderr)
                self.assertEqual(sorted(path.relative_to(root).as_posix() for path in root.rglob('*')), before)
                self.assertEqual(token.read_text(), 'existing token fixture')
                self.assertEqual(jar.read_bytes(), b'existing loaded bridge fixture')
                self.assertEqual(jar.stat().st_ino, inode)
                self.assertIn('already running' if inventory_status == 0 else 'nothing was launched', result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
