/** Actual companion WebSocket -> credit-checked model -> committed public QuPath API reference fixture.
 * A separate loopback server reuses production text routing with a public-context/reference-only Runtime.
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
import { assertPublicFixture } from './public-text-fixture.js';
import { PublicApiFixtureRuntime } from './public-api-fixture.js';
import { verifyDeclaration,declarationTypes } from './api-declaration-proof.js';
if(!process.argv.includes('--public-fixture'))throw new Error('Pass --public-fixture only for the real AWS-credit API-reference / public OS-2 test.');
const root=resolve(process.env.LUMA_ROOT??process.cwd());const state=resolve(root,'.local/state');
await loadTextConfig(state);
const adapter=new QuPathAdapter(root);const initial=await adapter.execute('context');assertPublicFixture(initial);
const knowledge=new Knowledge();await knowledge.load(root);
const expectedSearch=knowledge.api.search('loadPixelClassifier');const expectedHit=expectedSearch.results.find(hit=>hit.symbol==='qupath.lib.scripting.QP');assert(expectedHit);
const expected={source:expectedHit.source,sourceCommit:expectedSearch.sourceCommit,sourceSha256:expectedHit.sourceSha256,line:expectedHit.matchLine,signature:knowledge.api.read(expectedHit.source,expectedHit.matchLine,1).text};
declarationTypes(expected);
const runtime=new PublicApiFixtureRuntime(adapter,knowledge,resolve(root,'.local/api-model-smoke'),initial);
const arranged=await runtime.call('qupath_context',{}) as any;assert.equal(arranged.viewer.centerX,(initial as any).viewer.centerX);assert.equal(arranged.viewer.centerY,(initial as any).viewer.centerY);
const ledger=async()=>{
 try{return JSON.parse(await readFile(resolve(state,'credit-budget.json'),'utf8')).reservations as Array<{id:string}>;}
 catch(error){if((error as NodeJS.ErrnoException).code==='ENOENT')return [];throw error;}
};
const previous=new Set((await ledger()).map(row=>row.id));
const gateway=await startServer(root,0,{runtime});const address=gateway.server.address();assert(address&&typeof address==='object');
const token=(await readFile(resolve(state,'companion-token'),'utf8')).trim();
const ws=new WebSocket(`ws://127.0.0.1:${address.port}/events`,{headers:{Authorization:`Bearer ${token}`}});
const types:string[]=[];const executed:string[]=[];const sourceReads:unknown[]=[];const sourceSearches:unknown[]=[];let answer='';let answered=false;
let done!:()=>void;let fail!:(error:Error)=>void;
const completed=new Promise<void>((resolve,reject)=>{done=resolve;fail=reject;});
void completed.catch(()=>{}); // A connection failure can precede the later completion await.
ws.on('message',raw=>{const event=JSON.parse(raw.toString());types.push(event.type);
 if(event.type==='tool'){executed.push(event.name);if(event.name==='qupath_api_read')sourceReads.push(event.result);if(event.name==='qupath_api_search')sourceSearches.push(event.result);}
 if(event.type==='text_result'){answer=event.text;answered=true;}
 if(event.type==='task_state'&&event.state==='idle'&&answered)done();
 if(event.type==='text_error'||event.type==='error')fail(new Error(String(event.message)));
});
ws.on('error',fail);
const started=performance.now();
try{
 await once(ws,'open');
 ws.send(JSON.stringify({type:'text',text:'Use qupath_api_search to find loadPixelClassifier in qupath.lib.scripting.QP at the pinned source version, then use qupath_api_read to verify its public declaration. Return only a JSON object with string fields returnType and firstParameterType containing the simple Java type names derived from that declaration. No prose or markdown. You must inspect both search and read results, not guess. Do not execute this method, run code, propose any approval, or change the application. Only qupath_context, qupath_api_search and qupath_api_read are permitted in this fixture.'}));
 await Promise.race([completed,new Promise<never>((_resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('API-reference fixture timed out.')),120_000);timer.unref();})]);
 const final=await runtime.call('qupath_context',{}) as any;
 assert.deepEqual(final.viewer,arranged.viewer);assert.deepEqual(final.counts,{});assert.equal(runtime.actions.size,0);assert(executed.includes('qupath_api_search'));assert(executed.includes('qupath_api_read'));const declarationProof=verifyDeclaration(expected,sourceSearches,sourceReads,answer);
 const attempts=(await ledger()).filter(row=>!previous.has(row.id));const ids=new Set(attempts.map(row=>row.id));
 const receipts=(await readFile(resolve(state,'credit-usage.jsonl'),'utf8')).trim().split('\n').filter(Boolean).map(row=>JSON.parse(row)).filter(row=>ids.has(row.id));
 const evidence={status:'passed',observedAt:new Date().toISOString(),flow:'authenticated companion WebSocket -> actual AWS-credit OpenAI text -> restricted Runtime -> pinned QuPath API source',testServer:'isolated loopback; production conversation routing with restricted public fixture Runtime',model:'openai.gpt-oss-120b-1:0',operation:'actual model API search and declaration verification; no application action',viewerUnchanged:true,hierarchyUnchanged:true,approvalProposals:0,events:types,executedTools:executed,declarationProof,answer,projectAttemptReserveDeltaUSD:attempts.length,estimatedTokenCostUSD:receipts.length===attempts.length&&receipts.every(row=>row.outcome==='completed'&&typeof row.estimatedUSD==='number')?receipts.reduce((sum,row)=>sum+row.estimatedUSD,0):null,billingReconciled:false,elapsedMs:Math.round(performance.now()-started),nativeUI:'not exercised; separate foreground verification pending',voice:'not used; GPT-Live disabled',vision:'text-only; no image sent'};
 await mkdir(resolve(root,'.local/evidence'),{recursive:true});await writeFile(resolve(root,'.local/evidence/api-model.json'),JSON.stringify(evidence,null,2)+'\n');console.log(JSON.stringify(evidence,null,2));
}finally{ws.terminate();await gateway.close();}
