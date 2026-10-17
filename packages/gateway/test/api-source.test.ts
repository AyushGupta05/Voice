import{test}from'node:test';
import assert from'node:assert/strict';
import{mkdtemp,mkdir,writeFile,symlink,rm}from'node:fs/promises';
import{tmpdir}from'node:os';
import{join}from'node:path';
import{execFile}from'node:child_process';
import{promisify}from'node:util';
import{createHash}from'node:crypto';
import{ApiSource}from'../src/api-source.js';
import{startServer}from'../src/server.js';
import{readFile}from'node:fs/promises';
import{PublicApiFixtureRuntime}from'../../../script/public-api-fixture.js';
import{Knowledge}from'../src/knowledge.js';
const run=promisify(execFile);
const gitFixtureEnv={...process.env};
for(const key of ['GIT_DIR','GIT_WORK_TREE','GIT_INDEX_FILE','GIT_OBJECT_DIRECTORY','GIT_ALTERNATE_OBJECT_DIRECTORIES'])delete gitFixtureEnv[key];
const fixtureGit=(args:string[])=>run('git',args,{env:gitFixtureEnv});
async function fixture(){
 const root=await mkdtemp(join(tmpdir(),'luma-api-source-'));const repo=join(root,'.local/upstream/qupath');
 await mkdir(join(repo,'core/src/main/java/qupath/lib/scripting'),{recursive:true});await mkdir(join(root,'script'));
 const source='core/src/main/java/qupath/lib/scripting/QP.java';
 const text=['package qupath.lib.scripting;','public final class QP {','  /** Executes the synthetic fixture operation. */','  public static double runAudienceAnalysis(double threshold) {','    return threshold;','  }','}'].join('\n')+'\n';
 await writeFile(join(repo,source),text);await writeFile(join(root,'outside.txt'),'OUTSIDE_DATA_MUST_NOT_BE_READ');
 await symlink(join(root,'outside.txt'),join(repo,'core/src/main/java/Outside.java'));
 await fixtureGit(['-C',repo,'init','-q']);await fixtureGit(['-C',repo,'add','.']);
 await fixtureGit(['-c','core.hooksPath=/dev/null','-c','commit.gpgsign=false','-c','user.name=Luma fixture','-c','user.email=fixture@example.invalid','-C',repo,'commit','-qm','Synthetic source fixture']);
 const commit=(await fixtureGit(['-C',repo,'rev-parse','HEAD'])).stdout.trim();
 const manifest=(pin=commit)=>writeFile(join(root,'script/dependencies.json'),JSON.stringify({qupath:{version:'fixture',sourceCommit:pin}}));await manifest();
 return{root,repo,source,text,commit,manifest,clean:()=>rm(root,{recursive:true,force:true})};
}
test('API lookup returns exact pinned source and ignores working-tree edits and symlinks',async()=>{
 const f=await fixture();try{
  await writeFile(join(f.repo,f.source),'CHANGED_WORKING_TREE_MUST_NOT_BE_READ');
  const api=new ApiSource();await api.load(f.root);const result=api.search('runAudienceAnalysis');
  assert.equal(result.available,true);assert.equal(result.fileCount,1);assert.equal(result.sourceCommit,f.commit);assert.equal(result.results.length,1);
  const hit=result.results[0];assert.equal(hit.source,f.source);assert.equal(hit.matchLine,4);assert.match(hit.text,/public static double runAudienceAnalysis/);
  assert.equal(hit.sourceSha256,createHash('sha256').update(f.text).digest('hex'));
  assert.equal(api.read(hit.source,4,1).text,'  public static double runAudienceAnalysis(double threshold) {');
  assert.equal(api.search('OUTSIDE_DATA_MUST_NOT_BE_READ').results.length,0);
  assert.throws(()=>api.read('core/src/main/java/Outside.java'),/Unknown API source/);
  assert.throws(()=>api.read('../../outside.txt'),/Unknown API source/);
  assert.throws(()=>api.read(f.source,0),/1-based/);assert.throws(()=>api.read(f.source,1,161),/1–160/);
  assert.throws(()=>api.read(f.source,999),/exceeds/);assert.throws(()=>api.search('x'),/2–200/);
 }finally{await f.clean();}
});
test('missing or invalid pinned source fails closed without arbitrary filesystem fallback',async()=>{
 const f=await fixture();try{
  const api=new ApiSource();await f.manifest('0'.repeat(40));await api.load(f.root);
  assert.equal(api.search('threshold').available,false);assert.throws(()=>api.read(f.source),/unavailable/);
  const tree=(await fixtureGit(['-C',f.repo,'rev-parse',`${f.commit}^{tree}`])).stdout.trim();await f.manifest(tree);await api.load(f.root);
  assert.equal(api.search('threshold').available,false,'A tree object must not be advertised as a source commit');
  await f.manifest();await api.load(f.root);assert.equal(api.search('threshold').available,true);
  await rm(join(f.root,'script/dependencies.json'));await api.load(f.root);
  const missing=api.search('threshold');assert.equal(missing.available,false);assert.equal(missing.sourceCommit,'');assert.deepEqual(missing.results,[]);
 }finally{await f.clean();}
});
test('replacement refs cannot change the configured source commit',async()=>{
 const f=await fixture();try{
  await writeFile(join(f.repo,f.source),'package qupath.lib.scripting; public class ReplacedSource {}');
  await fixtureGit(['-C',f.repo,'add','.']);await fixtureGit(['-c','core.hooksPath=/dev/null','-c','commit.gpgsign=false','-c','user.name=Luma fixture','-c','user.email=fixture@example.invalid','-C',f.repo,'commit','-qm','Replacement fixture']);
  const replacement=(await fixtureGit(['-C',f.repo,'rev-parse','HEAD'])).stdout.trim();await fixtureGit(['-C',f.repo,'replace',f.commit,replacement]);
  const api=new ApiSource();await api.load(f.root);assert.equal(api.read(f.source,1,100).text,f.text.replace(/\n$/,''));assert.deepEqual(api.search('ReplacedSource').results,[]);
 }finally{await f.clean();}
});
test('source reads bound excerpts while preserving exact content hashes',async()=>{
 const f=await fixture();try{
  const api=new ApiSource();await api.load(f.root);const result=api.read(f.source,1,160);
  assert.equal(result.startLine,1);assert.equal(result.endLine,7);assert.equal(result.totalLines,7);assert.equal(result.excerptTruncated,false);assert.equal(result.text,f.text.replace(/\n$/,''));
  assert.match(result.upstreamURL,new RegExp(`/blob/${f.commit}/`));
  assert.deepEqual(api.search('method_that_does_not_exist').results,[]);
 }finally{await f.clean();}
});
test('authenticated reference tools work through the actual gateway without a QuPath bridge',async()=>{
 const f=await fixture();const gateway=await startServer(f.root,0);try{
  const address=gateway.server.address();assert(address&&typeof address==='object');
  const token=(await readFile(join(f.root,'.local/state/gateway-token'),'utf8')).trim();
  const call=(name:string,args:Record<string,unknown>)=>fetch(`http://127.0.0.1:${address.port}/tools/call`,{method:'POST',headers:{authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify({name,arguments:args})});
  const found=await call('qupath_api_search',{query:'runAudienceAnalysis'});assert.equal(found.status,200);assert.equal((await found.json() as any).result.results[0].source,f.source);
  const read=await call('qupath_api_read',{source:f.source,startLine:4,lineCount:1});assert.equal(read.status,200);assert.match((await read.json() as any).result.text,/public static double/);
  const refused=await call('qupath_api_read',{source:'../../outside.txt'});assert.equal(refused.status,400);assert.match((await refused.json() as any).error,/Unknown API source/);
 }finally{await gateway.close();await f.clean();}
});
test('actual-model reference fixture exposes only sanitized public context and pinned source',async()=>{
 const f=await fixture();try{
  const knowledge=new Knowledge();await knowledge.load(f.root);let key='public-fixture';let actions=0;
  const context=()=>({contextKey:key,image:{name:'OS-2.ndpi',width:126976,height:73728},counts:{},viewer:{centerX:10,centerY:20,downsample:3},selection:[],selectionCount:0,project:{uri:'PRIVATE_PROJECT'},checkpoints:['PRIVATE_CHECKPOINT']});
  const runtime=new PublicApiFixtureRuntime({async execute(operation){if(operation==='context')return context();actions++;throw new Error('Unexpected action');}},knowledge,join(f.root,'fixture-state'),context());
  const observed=await runtime.call('qupath_context',{});assert(!JSON.stringify(observed).includes('PRIVATE_'));
  const found=await runtime.call('qupath_api_search',{query:'runAudienceAnalysis'}) as {results:Array<{source:string}>};
  assert.equal(found.results[0].source,f.source);assert.equal(actions,0);
  await assert.rejects(runtime.call('qupath_focus',{x:51100,y:27100,downsample:3}),/No application action/);
  await assert.rejects(runtime.call('qupath_run_script',{source:'print 1',reason:'not authorized'}),/No application action/);
  assert.equal(runtime.actions.size,0);assert.equal(actions,0);
  key='other-image';await assert.rejects(runtime.call('qupath_api_read',{source:f.source}),/bounded test contract/);
 }finally{await f.clean();}
});
