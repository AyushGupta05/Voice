#!/usr/bin/env python3
"""Disposable build fixture; never runs QuPath or modifies this checkout's jar."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest


class BridgeBuildTests(unittest.TestCase):
    def test_failed_build_preserves_jar_and_success_is_clean_replacement(self):
        with tempfile.TemporaryDirectory(prefix='luma-bridge-build-test-') as directory:
            root = Path(directory)
            (root / 'script').mkdir()
            shutil.copyfile(Path(__file__).with_name('build-bridge.sh'), root / 'script/build-bridge.sh')
            (root / 'dist').mkdir()
            output = root / 'dist/luma-qupath-bridge.jar'
            output.write_bytes(b'previous working jar')
            sources = root / 'adapters/qupath/src/main'
            (sources / 'java/science/luma/qupath').mkdir(parents=True)
            (sources / 'java/science/luma/qupath/Fixture.java').write_text('fixture')
            (sources / 'resources').mkdir()
            (sources / 'resources/service').write_text('fixture extension')
            jdk = root / 'fake jdk/bin'
            jdk.mkdir(parents=True)
            (jdk / 'javac').write_text('''#!/usr/bin/env python3
import os, pathlib, sys
target=pathlib.Path(sys.argv[sys.argv.index('-d')+1]); target.mkdir()
if os.environ.get('FAIL_BUILD'): sys.exit(1)
(target/'Current.class').write_text('new compiled class')
''')
            (jdk / 'jar').write_text('''#!/usr/bin/env python3
import pathlib, sys
source=pathlib.Path(sys.argv[sys.argv.index('-C')+1])
assert sorted(p.name for p in source.iterdir())==['Current.class','service']
pathlib.Path(sys.argv[sys.argv.index('--file')+1]).write_bytes(b'new complete jar')
''')
            for path in jdk.iterdir():
                path.chmod(0o755)
            environment = {**os.environ, 'LUMA_JAVA_HOME': str(jdk.parent), 'LUMA_QUPATH_APP': str(root / 'fake app')}
            command = ['/bin/bash', str(root / 'script/build-bridge.sh')]
            failed = subprocess.run(command, env={**environment, 'FAIL_BUILD': '1'}, capture_output=True, text=True)
            self.assertNotEqual(failed.returncode, 0)
            self.assertEqual(output.read_bytes(), b'previous working jar')
            self.assertEqual(list((root / 'dist').iterdir()), [output], 'Failed staging files must be removed')
            original_inode = output.stat().st_ino
            completed = subprocess.run(command, env=environment, capture_output=True, text=True)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertEqual(output.read_bytes(), b'new complete jar')
            self.assertNotEqual(output.stat().st_ino, original_inode, 'Replace the archive, never truncate the old one')
            self.assertEqual(list((root / 'dist').iterdir()), [output])


if __name__ == '__main__':
    unittest.main()
