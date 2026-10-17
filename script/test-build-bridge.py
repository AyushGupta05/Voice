#!/usr/bin/env python3
"""Disposable build fixture; never runs QuPath or modifies this checkout's jar."""
from pathlib import Path
import os
import hashlib
import json
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
            shutil.copyfile(Path(__file__).with_name('bridge-identity.py'), root / 'script/bridge-identity.py')
            (root / 'script/dependencies.json').write_text('{}')
            (root / '.local').mkdir()
            manifest=root / '.local/bridge-build.json'
            manifest.write_text('previous build identity')
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
assert pathlib.Path(sys.argv[-1]).read_text()=='fixture'
if os.environ.get('EDIT_ORIGINAL'): pathlib.Path(os.environ['EDIT_ORIGINAL']).write_text('edited after snapshot')
(target/'Current.class').write_text('new compiled class')
''')
            (jdk / 'jar').write_text('''#!/usr/bin/env python3
import pathlib, sys
source=pathlib.Path(sys.argv[sys.argv.index('-C')+1])
assert sorted(p.name for p in source.iterdir())==['Current.class','science','service']
assert (source/'science/luma/qupath/bridge-build.properties').read_text().startswith('sourceSha256=')
pathlib.Path(sys.argv[sys.argv.index('--file')+1]).write_bytes(b'new complete jar')
''')
            for path in jdk.iterdir():
                path.chmod(0o755)
            environment = {**os.environ, 'LUMA_JAVA_HOME': str(jdk.parent), 'LUMA_QUPATH_APP': str(root / 'fake app')}
            command = ['/bin/bash', str(root / 'script/build-bridge.sh')]
            failed = subprocess.run(command, env={**environment, 'FAIL_BUILD': '1'}, capture_output=True, text=True)
            self.assertNotEqual(failed.returncode, 0)
            self.assertEqual(output.read_bytes(), b'previous working jar')
            self.assertEqual(manifest.read_text(),'previous build identity')
            self.assertEqual(list((root / 'dist').iterdir()), [output], 'Failed staging files must be removed')
            original_inode = output.stat().st_ino
            completed = subprocess.run(command, env=environment, capture_output=True, text=True)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertEqual(output.read_bytes(), b'new complete jar')
            self.assertNotEqual(output.stat().st_ino, original_inode, 'Replace the archive, never truncate the old one')
            self.assertEqual(list((root / 'dist').iterdir()), [output])
            identity=json.loads(manifest.read_text())
            self.assertEqual(identity['jarSha256'],hashlib.sha256(output.read_bytes()).hexdigest())
            self.assertEqual(len(identity['sourceSha256']),64)
            # The compiler must consume the captured source, even if an editor
            # changes the working file while this build is running.
            original_source=sources/'java/science/luma/qupath/Fixture.java'
            edited=subprocess.run(command,env={**environment,'EDIT_ORIGINAL':str(original_source)},capture_output=True,text=True)
            self.assertEqual(edited.returncode,0,edited.stderr)
            self.assertEqual(original_source.read_text(),'edited after snapshot')
            self.assertEqual(json.loads(manifest.read_text())['sourceSha256'],identity['sourceSha256'])
            original_source.unlink()
            original_source.symlink_to(root/'external.java')
            (root/'external.java').write_text('not a captured regular source')
            before_jar=output.read_bytes();before_manifest=manifest.read_bytes()
            linked=subprocess.run(command,env=environment,capture_output=True,text=True)
            self.assertNotEqual(linked.returncode,0)
            self.assertEqual(output.read_bytes(),before_jar);self.assertEqual(manifest.read_bytes(),before_manifest)
            original_source.unlink();original_source.write_text('fixture')
            for name in ['dependencies.json','build-bridge.sh','bridge-identity.py']:
                with self.subTest(linked_input=name):
                    target=root/'script'/name;data=target.read_bytes();external=root/('external-'+name)
                    external.write_bytes(data);target.unlink();target.symlink_to(external)
                    refused=subprocess.run(command,env=environment,capture_output=True,text=True)
                    self.assertNotEqual(refused.returncode,0)
                    self.assertEqual(output.read_bytes(),before_jar);self.assertEqual(manifest.read_bytes(),before_manifest)
                    target.unlink();target.write_bytes(data)
            for target in [sources,sources.parent]:
                with self.subTest(linked_directory=target.name):
                    external=root/('external-'+target.name);target.rename(external);target.symlink_to(external,target_is_directory=True)
                    refused=subprocess.run(command,env=environment,capture_output=True,text=True)
                    self.assertNotEqual(refused.returncode,0)
                    self.assertEqual(output.read_bytes(),before_jar);self.assertEqual(manifest.read_bytes(),before_manifest)
                    target.unlink();external.rename(target)


if __name__ == '__main__':
    unittest.main()
