#!/usr/bin/env python3
"""Disposable process fixture. No native app, UI or research data is stopped."""
import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

HELPER = Path(__file__).with_name('companion-process.py')
spec = importlib.util.spec_from_file_location('companion_process', HELPER)
processes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(processes)


@unittest.skipUnless(sys.platform == 'darwin', 'macOS executable-path semantics')
class ProcessScopeTests(unittest.TestCase):
    def test_exact_path_stops_only_the_disposable_target(self):
        with tempfile.TemporaryDirectory(prefix='luma-process-') as directory:
            fixture_binary = Path(directory) / 'fixture-process'
            subprocess.run(['/usr/bin/clang', '-x', 'c', '-', '-o', str(fixture_binary)],
                           input='#include <unistd.h>\nint main(void) { sleep(30); return 0; }\n',
                           text=True, capture_output=True, check=True)
            paths = []
            children = []
            try:
                for project in ['project one', 'project two']:
                    path = Path(directory) / project / 'LumaCompanion'
                    path.parent.mkdir()
                    shutil.copyfile(fixture_binary, path)
                    path.chmod(0o755)
                    paths.append(path.resolve())
                    children.append(subprocess.Popen([str(path), '30']))
                deadline = time.monotonic() + 2
                while not all(child.pid in processes.matching_pids(path) for child, path in zip(children, paths)):
                    self.assertLess(time.monotonic(), deadline, 'Disposable processes did not become observable')
                    time.sleep(0.02)
                self.assertEqual(processes.matching_pids(paths[0]), [children[0].pid])
                result = subprocess.run([sys.executable, str(HELPER), 'stop', str(paths[0])], capture_output=True, text=True, timeout=8)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(children[0].wait(timeout=2), -15)
                self.assertIsNone(children[1].poll(), 'Same basename in another project must keep running')
                self.assertEqual(processes.matching_pids(paths[1]), [children[1].pid])
                again = subprocess.run([sys.executable, str(HELPER), 'stop', str(paths[0])], capture_output=True, text=True, timeout=8)
                self.assertEqual(again.returncode, 0, again.stderr)
                self.assertIsNone(children[1].poll())
            finally:
                for child in children:
                    if child.poll() is None:
                        child.terminate()
                    child.wait(timeout=3)


if __name__ == '__main__':
    unittest.main()
