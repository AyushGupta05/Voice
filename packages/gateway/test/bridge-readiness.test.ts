import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,readFile,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {fileURLToPath} from 'node:url';
import {symlink} from 'node:fs/promises';
import {bridgeReadiness,requiredBridgeOperations} from '../src/bridge-readiness.js';

async function fixture(){
  const root=await mkdtemp(join(tmpdir(),'luma-build-identity-'));await mkdir(join(root,'.local'));await mkdir(join(root,'dist'));
  const jar=Buffer.from('completed bridge fixture');const sourceSha256='a'.repeat(64);
  await writeFile(join(root,'dist/luma-qupath-bridge.jar'),jar);
  await writeFile(join(root,'.local/bridge-build.json'),JSON.stringify({version:1,protocolVersion:1,sourceSha256,jarSha256:createHash('sha256').update(jar).digest('hex')}));
  const context={adapter:{adapterVersion:'0.2.0',protocolVersion:1,sourceSha256,operations:[...requiredBridgeOperations]}};
  return{root,context,clean:()=>rm(root,{recursive:true,force:true})};
}
test('bridge readiness distinguishes the loaded build from a later artifact without altering either',async()=>{
  const f=await fixture();try{
    assert.equal((await bridgeReadiness(f.root,f.context)).status,'matches_build');
    assert.equal((await bridgeReadiness(f.root,{})).status,'restart_required','Legacy bridge must not be certified current');
    assert.equal((await bridgeReadiness(f.root,{adapter:{...f.context.adapter,sourceSha256:'b'.repeat(64)}})).status,'restart_required');
    const missing=await bridgeReadiness(f.root,{adapter:{...f.context.adapter,operations:f.context.adapter.operations.filter(op=>op!=='import_annotations')}});
    assert.equal(missing.status,'restart_required');assert.deepEqual(missing.missingOperations,['import_annotations']);
    assert.equal((await bridgeReadiness(f.root,undefined)).status,'unavailable');
    assert.equal((await readFile(join(f.root,'dist/luma-qupath-bridge.jar'))).toString(),'completed bridge fixture');
  }finally{await f.clean();}
});
test('corrupt, missing or inconsistent build identities fail without repair or restart',async()=>{
  const f=await fixture();try{
    for(const adapter of [{...f.context.adapter,protocolVersion:2},{...f.context.adapter,sourceSha256:'unknown'},
      {...f.context.adapter,operations:['context','context']},{...f.context.adapter,operations:['../file']}])
      assert.equal((await bridgeReadiness(f.root,{adapter})).status,'build_unverified');
    await writeFile(join(f.root,'dist/luma-qupath-bridge.jar'),'changed artifact');
    assert.equal((await bridgeReadiness(f.root,f.context)).status,'build_unverified');
    assert.equal((await readFile(join(f.root,'dist/luma-qupath-bridge.jar'))).toString(),'changed artifact');
    await writeFile(join(f.root,'.local/bridge-build.json'),'not JSON');
    assert.equal((await bridgeReadiness(f.root,f.context)).status,'build_unverified');
    await rm(join(f.root,'.local/bridge-build.json'));
    assert.equal((await bridgeReadiness(f.root,f.context)).status,'build_unverified');
  }finally{await f.clean();}
});
test('declared Java bridge operations cover exactly the current gateway adapter contract',async()=>{
  const source=await readFile(new URL('../../../adapters/qupath/src/main/java/science/luma/qupath/BridgeIdentity.java',import.meta.url),'utf8');
  const declared=source.match(/OPERATIONS=List\.of\(([\s\S]*?)\);/);assert(declared);
  const operations=[...declared[1].matchAll(/"([a-z_]+)"/g)].map(match=>match[1]).sort();
  assert.deepEqual(operations,requiredBridgeOperations);
});
test('nonregular manifest/artifact inputs refuse promptly without blocking a context request',{skip:process.platform==='win32'},async()=>{
  const f=await fixture();const execute=promisify(execFile);
  try{
    for(const target of [join(f.root,'.local/bridge-build.json'),join(f.root,'dist/luma-qupath-bridge.jar')]) {
      const original=await readFile(target);await rm(target);
      for(const kind of ['fifo','device']){
        if(kind==='fifo')await execute('mkfifo',[target]);else await symlink('/dev/zero',target);
        const module=fileURLToPath(new URL('../src/bridge-readiness.ts',import.meta.url));
        const code=`const {bridgeReadiness}=await import(${JSON.stringify(module)}); console.log(JSON.stringify(await bridgeReadiness(${JSON.stringify(f.root)},${JSON.stringify(f.context)})));`;
        const result=await execute(process.execPath,['--import','tsx','--input-type=module','-e',code],{timeout:2000,maxBuffer:4096});
        assert.equal(JSON.parse(result.stdout).status,'build_unverified');await rm(target);
      }
      await writeFile(target,original);
    }
  }finally{await f.clean();}
});
