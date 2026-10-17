/** Explicitly authorized synthetic-only actual QuPath import fixture. No provider or normal-app approvals. */
import {randomUUID,randomBytes,createHash} from 'node:crypto';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import assert from 'node:assert/strict';
import {Runtime} from '../packages/gateway/src/runtime.js';
import {QuPathAdapter} from '../packages/gateway/src/bridge.js';
import {Knowledge} from '../packages/gateway/src/knowledge.js';
import {startServer} from '../packages/gateway/src/server.js';
import {MCPGateway} from '../packages/gateway/src/mcp-gateway.js';
import {createMCPServer} from '../packages/gateway/src/mcp.js';
import {Client} from '@modelcontextprotocol/sdk/client/index.js';
import {InMemoryTransport} from '@modelcontextprotocol/sdk/inMemory.js';
if(!process.argv.includes('--authorized-synthetic-imports'))throw new Error('Explicit isolated synthetic-import authorization is required.');
const exec=promisify(execFile),root=process.cwd(),nonce=randomUUID();
const fixture=join(root,'.local/synthetic-import-fixtures',nonce);await mkdir(join(fixture,'.local/state'),{recursive:true,mode:0o700});
await writeFile(join(fixture,'nonce'),nonce+'\n',{mode:0o600,flag:'wx'});
await writeFile(join(fixture,'.local/state/bridge-token'),randomBytes(32).toString('hex')+'\n',{mode:0o600,flag:'wx'});
const app=join(fixture,'QuPathImportFixture.app'),executable=join(app,'Contents/MacOS/QuPath-0.7.0-arm64');
const evidence:Record<string,unknown>={nonce,startedAt:new Date().toISOString(),status:'failed',providerRequests:0,normalAppRestarted:false,normalApprovalsUsed:false};
let ready:any;let launched=false;let expectedContext:string|undefined;let closeGateway:(()=>Promise<void>)|undefined;let client:Client|undefined;let closeMCP:(()=>Promise<void>)|undefined;
try{
 await exec('python3',[resolve('script/prepare-import-fixture.py'),fixture],{timeout:60000,maxBuffer:128*1024});
 await exec('/usr/bin/open',['-n',app,'--env','LUMA_BRIDGE_TOKEN=','--stdout',join(fixture,'app.log'),'--stderr',join(fixture,'error.log'),'--args','--quiet'],{timeout:10000});launched=true;
 const deadline=Date.now()+45000;
 while(Date.now()<deadline){try{ready=JSON.parse(await readFile(join(fixture,'ready.json'),'utf8'));break;}catch{}await new Promise(r=>setTimeout(r,200));}
 if(!ready)throw new Error('Disposable QuPath did not become ready in45seconds. A locked/unavailable desktop may prevent normal JavaFX startup; no alternate display or unlock is attempted.');
 assert.equal(ready.nonce,nonce);assert(Number.isInteger(ready.port)&&ready.port>0&&ready.port!==49556&&ready.port!==49555);
 const pids=await exec('python3',[resolve('script/companion-process.py'),'running',executable]);assert(pids.stdout.trim().split(/\s+/).includes(String(ready.pid)));
 const runtime=new Runtime(new QuPathAdapter(fixture,ready.port),new Knowledge(),join(fixture,'.local/state'));
 const server=await startServer(fixture,0,{runtime});closeGateway=server.close;
 const address=server.server.address();assert(address&&typeof address==='object');
 const mcp=createMCPServer(fixture,new MCPGateway(fixture,address.port));closeMCP=()=>mcp.close();
 const [a,b]=InMemoryTransport.createLinkedPair();await mcp.connect(b);client=new Client({name:'authorized-synthetic-import',version:'1'});await client.connect(a);
 const call=async(name:string,args:Record<string,unknown>={})=>{
  const result=await client!.callTool({name,arguments:args});if(result.isError)throw new Error(JSON.stringify(result.content));
  return JSON.parse((result.content as Array<{type:string;text:string}>).find(item=>item.type==='text')!.text);
 };
 const context=await call('qupath_context');assert.equal(context.image?.name,ready.imageName);assert.equal(context.image.width,512);assert.equal(context.image.height,512);assert.equal(context.project??null,null);assert.deepEqual(context.counts,{});assert.equal(context.selectionCount,0);assert.equal(context.adapterReadiness.status,'matches_build');
 expectedContext=context.contextKey;assert.equal(typeof expectedContext,'string');
 const baseline=await call('qupath_checkpoint',{label:'Authorized synthetic import baseline'});
 const path=join(fixture,'annotations.geojson'),bytes=await readFile(path),sha256=createHash('sha256').update(bytes).digest('hex');
 const preview=await call('qupath_inspect_import',{path});assert.equal(preview.sha256,sha256);assert.equal(preview.annotations,2);
 const args={path,sha256,coordinateSpace:'image_pixels'};
 // Changed bytes must fail before a proposal. Restore only this fixture-owned file afterward.
 await writeFile(path,Buffer.concat([bytes,Buffer.from('\n')]));await assert.rejects(call('qupath_import_annotations',args),/changed since inspection/);await writeFile(path,bytes);assert.equal(runtime.actions.size,0);
 const human=(await readFile(join(fixture,'.local/state/companion-token'),'utf8')).trim();
 const approve=async(name:string,arguments_:Record<string,unknown>)=>{
  assert(name==='qupath_import_annotations'||name==='qupath_restore');
  if(name==='qupath_import_annotations')assert.deepEqual(arguments_,args);else assert.deepEqual(arguments_,{checkpointId:baseline.id});
  const before=await call('qupath_context');assert.equal(before.image?.name,ready.imageName);assert.equal(before.project??null,null);assert.equal(before.contextKey,expectedContext,'Independent fixture state change; preserve for inspection.');
  const proposal=await call(name,arguments_);assert.equal(proposal.status,'approval_required');
  const action=runtime.actions.get(proposal.actionId)!;assert.equal(action.context.image?.name,ready.imageName);assert.equal(action.context.project??null,null);assert.equal(action.context.contextKey,expectedContext);assert.deepEqual(action.arguments,arguments_);
  const response=await fetch(`http://127.0.0.1:${address.port}/approvals/${proposal.actionId}`,{method:'POST',headers:{Authorization:`Bearer ${human}`,'Content-Type':'application/json'},body:'{"approved":true}',signal:AbortSignal.timeout(15000)});
  assert(response.ok);const value=await response.json();assert.equal(value.action.status,'completed',value.action.error);
  if(name==='qupath_import_annotations'){assert.equal(typeof value.action.result.contextKey,'string');expectedContext=value.action.result.contextKey;}
  else expectedContext=undefined; // Restore has no returned context key; adopt only a verified empty result below.
  return value.action.result;
 };
 const imported=await approve('qupath_import_annotations',args);assert.equal(imported.result.imported,2);
 const objects=await call('qupath_objects',{kind:'annotation'});assert.equal(objects.total,2);assert.deepEqual(objects.objects.map((o:any)=>o.name).sort(),['Synthetic A','Synthetic B']);
 const expected=[{id:'11111111-1111-4111-8111-111111111111',name:'Synthetic A',classification:'Fixture A',x:20,y:30,width:80,height:60,score:1},{id:'22222222-2222-4222-8222-222222222222',name:'Synthetic B',classification:'Fixture B',x:180,y:150,width:100,height:90,score:3}];
 const measured=await call('qupath_measurements',{kind:'annotation'});assert.equal(measured.total,2);assert.equal(measured.truncated,false);
 for(const item of expected){
  const object=objects.objects.find((o:any)=>o.id===item.id);assert(object,'Imported UUID must be preserved');assert.equal(object.name,item.name);assert.equal(object.classification,item.classification);assert.equal(object.kind,'annotation');
  for(const key of ['x','y','width','height'] as const)assert.equal(object.roi[key],item[key]);assert.equal(object.roi.z,0);assert.equal(object.roi.t,0);
  const row=measured.rows.find((r:any)=>r.object.id===item.id);assert(row);assert.equal(row.measurements['Synthetic score'],item.score);
 }
 const summary=await call('qupath_measurement_summary',{kind:'annotation',measurements:['Synthetic score'],histogramBins:2});assert.equal(summary.total,2);assert.equal(summary.measurements['Synthetic score'].mean,2);
 await assert.rejects(call('qupath_import_annotations',args),/ID already exists/);assert.equal(runtime.actions.size,1);
 const compared=await call('qupath_compare',{checkpointId:baseline.id});assert.equal(compared.delta.annotations,2);
 await approve('qupath_restore',{checkpointId:baseline.id});const restored=await call('qupath_context');assert.equal(restored.image?.name,ready.imageName);assert.equal(restored.project??null,null);assert.equal(restored.image.type,'BRIGHTFIELD_OTHER');assert.deepEqual(restored.counts,{});assert.equal(restored.selectionCount,0);assert.equal(restored.analysisRunning,false);assert.equal(typeof restored.contextKey,'string');expectedContext=restored.contextKey;assert.deepEqual(await readFile(path),bytes);
 Object.assign(evidence,{status:'passed',actualQuPathGUI:true,mcpTools:(await client.listTools()).tools.length,annotationsImported:2,identitiesClassesGeometryAndScoresPreserved:true,measurementMean:2,changedFileRejected:true,duplicateImportRejected:true,baselineRestored:true,sourceFileUnchanged:true,simulatedFixtureDecisions:2,bridgeSourceMatchesBuild:true});
}catch(error){evidence.reason=error instanceof Error?error.message:String(error);process.exitCode=1;}
finally{
 for(const clean of [()=>client?.close(),closeMCP,closeGateway]){try{await clean?.();}catch{evidence.cleanupError=true;process.exitCode=1;}}
 if(launched){
  let safeToStop=false;
  if(ready){try{
   const token=(await readFile(join(fixture,'.local/state/bridge-token'),'utf8')).trim();
   const response=await fetch(`http://127.0.0.1:${ready.port}/execute`,{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:JSON.stringify({operation:'context',arguments:{}}),signal:AbortSignal.timeout(5000)});
   const current=await response.json();safeToStop=response.ok&&current.result?.image?.name===`Luma synthetic import ${nonce}`&&(current.result?.project??null)===null&&typeof expectedContext==='string'&&current.result.contextKey===expectedContext&&current.result.analysisRunning===false;
  }catch{}}
  else{try{safeToStop=/No Screen|Unable to open DISPLAY/.test(await readFile(join(fixture,'error.log'),'utf8'));}catch{}}
  if(safeToStop){try{await exec('python3',[resolve('script/companion-process.py'),'stop',executable],{timeout:10000});evidence.disposableProcessStopped=true;}catch{evidence.disposableProcessStopped=false;process.exitCode=1;}}
  else{evidence.disposableProcessStopped=false;evidence.cleanupNeedsInspection=true;process.exitCode=1;}
 }
 evidence.completedAt=new Date().toISOString();await writeFile(join(fixture,'evidence.json'),JSON.stringify(evidence,null,2)+'\n');console.log(JSON.stringify({fixture,evidence},null,2));
}
