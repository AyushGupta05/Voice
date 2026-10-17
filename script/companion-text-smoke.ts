/** Actual companion WebSocket -> credit-checked model -> public QuPath view fixture.
 * A separate loopback server reuses production text routing with a restricted fixture Runtime.
 * It does not click the native UI or activate voice.
 */
import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { once } from 'node:events';
import WebSocket from 'ws';
import { QuPathAdapter } from '../packages/gateway/src/bridge.js';
import { Knowledge } from '../packages/gateway/src/knowledge.js';
import { loadTextConfig } from '../packages/gateway/src/bedrock-client.js';
import { startServer } from '../packages/gateway/src/server.js';
import { PublicFixtureRuntime, assertPublicFixture } from './public-text-fixture.js';
if(!process.argv.includes('--public-fixture'))throw new Error('Pass --public-fixture only for the real AWS-credit companion-text / public OS-2 test.');
const root=resolve(process.env.LUMA_ROOT??process.cwd());const state=resolve(root,'.local/state');
await loadTextConfig(state);
const adapter=new QuPathAdapter(root);const initial=await adapter.execute('context');assertPublicFixture(initial);
await adapter.execute('focus',{x:48000,y:24000,downsample:4},{expectedContext:initial.contextKey});
const runtime=new PublicFixtureRuntime(adapter,new Knowledge(),resolve(root,'.local/companion-text-smoke'),initial);
const arranged=await runtime.call('qupath_context',{}) as any;assert.equal(arranged.viewer.centerX,48000);assert.equal(arranged.viewer.centerY,24000);
const ledger=async()=>{
 try{return JSON.parse(await readFile(resolve(state,'credit-budget.json'),'utf8')).reservations as Array<{id:string}>;}
 catch(error){if((error as NodeJS.ErrnoException).code==='ENOENT')return [];throw error;}
};
const previous=new Set((await ledger()).map(row=>row.id));
const gateway=await startServer(root,0,{runtime});const address=gateway.server.address();assert(address&&typeof address==='object');
const token=(await readFile(resolve(state,'companion-token'),'utf8')).trim();
const ws=new WebSocket(`ws://127.0.0.1:${address.port}/events`,{headers:{Authorization:`Bearer ${token}`}});
const types:string[]=[];const executed:string[]=[];let answer='';let answered=false;
let done!:()=>void;let fail!:(error:Error)=>void;
const completed=new Promise<void>((resolve,reject)=>{done=resolve;fail=reject;});
ws.on('message',raw=>{const event=JSON.parse(raw.toString());types.push(event.type);
 if(event.type==='tool')executed.push(event.name);
 if(event.type==='text_result'){answer=event.text;answered=true;}
 if(event.type==='task_state'&&event.state==='idle'&&answered)done();
 if(event.type==='text_error'||event.type==='error')fail(new Error(String(event.message)));
});
ws.on('error',fail);
const started=performance.now();
try{
 await once(ws,'open');
 ws.send(JSON.stringify({type:'text',text:'Read actual QuPath context. Focus the real viewer at full-resolution image pixel coordinates x=51100, y=27100, downsample=3. Confirm the actual returned center. Only use qupath_context and qupath_focus. Do not change selections, scientific data, files or settings. Keep the result short.'}));
 await Promise.race([completed,new Promise<never>((_resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('Companion-text fixture timed out.')),120_000);timer.unref();})]);
 const final=await runtime.call('qupath_context',{}) as any;
 assert.equal(final.viewer.centerX,51100);assert.equal(final.viewer.centerY,27100);assert.equal(final.viewer.downsample,3);assert.deepEqual(final.counts,{});assert.equal(runtime.actions.size,0);assert(executed.includes('qupath_focus'));assert(answer);
 const attempts=(await ledger()).filter(row=>!previous.has(row.id));const ids=new Set(attempts.map(row=>row.id));
 const receipts=(await readFile(resolve(state,'credit-usage.jsonl'),'utf8')).trim().split('\n').filter(Boolean).map(row=>JSON.parse(row)).filter(row=>ids.has(row.id));
 const evidence={status:'passed',observedAt:new Date().toISOString(),flow:'authenticated companion WebSocket -> actual AWS-credit OpenAI text -> shared Runtime -> real QuPath',testServer:'isolated loopback; production conversation routing with restricted public fixture Runtime',model:'openai.gpt-oss-120b-1:0',operation:'exact display-only focus',center:[final.viewer.centerX,final.viewer.centerY],hierarchyUnchanged:true,approvalProposals:0,events:types,executedTools:executed,answer,projectAttemptReserveDeltaUSD:attempts.length,estimatedTokenCostUSD:receipts.length===attempts.length&&receipts.every(row=>row.outcome==='completed'&&typeof row.estimatedUSD==='number')?receipts.reduce((sum,row)=>sum+row.estimatedUSD,0):null,billingReconciled:false,elapsedMs:Math.round(performance.now()-started),nativeUI:'compiled separately; no foreground click/visual verification in this fixture',voice:'not used; GPT-Live disabled',vision:'text-only; no image sent'};
 await mkdir(resolve(root,'.local/evidence'),{recursive:true});await writeFile(resolve(root,'.local/evidence/companion-text.json'),JSON.stringify(evidence,null,2)+'\n');console.log(JSON.stringify(evidence,null,2));
}finally{ws.terminate();await gateway.close();}
